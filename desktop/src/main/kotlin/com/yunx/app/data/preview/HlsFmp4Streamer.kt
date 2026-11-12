package com.yunx.app.data.preview

import com.yunx.app.data.download.HlsDownloader
import com.yunx.app.data.download.HlsRequestPolicy
import com.yunx.app.data.network.HttpClients
import com.yunx.app.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream

/**
 * HLS 在线预览的「边下边播」重封装器。
 *
 * JavaFX 17/Windows 的 jfxmedia 不支持 m3u8（实测），所以由本类把 m3u8 拉下来后：
 * 1. 逐个下载 TS 分片，用 [TsSegmentParser] 解出视频 AU / 音频帧；
 * 2. 用 [Fmp4Builder] 重新封装成 fragmented MP4，**边转边写**进调用方给的输出流；
 * 3. 时间轴按源 PTS 归一（首个分片从 0 开始，33bit 回绕自动展开，`#EXT-X-DISCONTINUITY` 处顺延）。
 *
 * 若播放列表本身已经是 fMP4（带 `#EXT-X-MAP`），走**直通模式**：先写 init 段，再原样拼接分片。
 *
 * 安全边界与 HLS 下载一致：只接受 HTTPS 源，重定向手动跟随并逐跳应用
 * [HlsRequestPolicy.headersFor]（跨域剥 Cookie/Referer 等敏感头）。
 */
internal class HlsFmp4Streamer private constructor(
    private val credentialOrigin: HttpUrl,
    private val headers: Map<String, String>,
    private val segments: List<HttpUrl>,
    /** 该下标的分片前面有 `#EXT-X-DISCONTINUITY` */
    private val discontinuity: Set<Int>,
    /** 直通模式：EXT-X-MAP 的 init 段内容；非 null 时 [builder] 为 null */
    private val passthroughInit: ByteArray?,
    /** TS 模式下准备阶段已解析的前几个分片（下标与 [segments] 对齐） */
    private val prefetched: List<TsSegmentParser.Result>,
    private val builder: Fmp4Builder?
) {
    private val client: OkHttpClient = HttpClients.apiClient().newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // ---- 时间轴状态（每个请求独立，不跨会话共享）----

    private var sequenceNumber = 1
    private var videoWrap = 0L
    private var videoLastRaw = -1L
    private var videoOrigin = -1L
    private var videoShift = 0L
    private var videoEnd = 0L
    private var lastVideoDuration = DEFAULT_FRAME_DURATION
    private var audioEnd = 0L

    /**
     * 把整段重封装结果写进 [out]（阻塞）。
     */
    fun streamTo(out: OutputStream) {
        val consumed = writePrologue(out)
        writeRemaining(out, consumed)
    }

    /**
     * 写初始化段 + 准备阶段已预取的分片，返回下一个待写分片下标。
     *
     * 预览起播靠这两步分开：先把「够播几秒」的部分同步落盘，再把剩余分片交给后台线程续写，
     * 于是播放器在读文件的同时文件还在变长（jfxmedia 对 HTTP 的 fMP4 支持有缺陷，
     * 只有 file: + 增长文件这条路实测可用，见 §9.2）。
     */
    fun writePrologue(out: OutputStream): Int {
        val init = passthroughInit
        if (init != null) {
            out.write(init)
            out.flush()
            return 0
        }
        val fmp4 = builder ?: return segments.size
        out.write(fmp4.initSegment())
        out.flush()
        var index = 0
        while (index < prefetched.size) {
            writeFragment(out, prefetched[index], index in discontinuity)
            index++
        }
        out.flush()
        return index
    }

    /** 继续写 [fromIndex] 之后的分片（阻塞，直到写完或上游失败） */
    fun writeRemaining(out: OutputStream, fromIndex: Int) {
        if (passthroughInit != null) {
            for (index in fromIndex until segments.size) {
                val bytes = fetchBytes(segments[index]) ?: break
                out.write(bytes)
                out.flush()
            }
            return
        }
        if (builder == null) return
        for (index in fromIndex until segments.size) {
            val parsed = fetchAndParse(index) ?: continue
            writeFragment(out, parsed, index in discontinuity)
        }
        out.flush()
    }

    // ---- TS 模式 ----

    private fun fetchAndParse(index: Int): TsSegmentParser.Result? {
        val bytes = fetchBytes(segments[index]) ?: return null
        return runCatching { TsSegmentParser(bytes).parse() }.getOrElse {
            Log.w(TAG, "分片解析失败 #$index: ${it.message}")
            null
        }
    }

    private fun writeFragment(
        out: OutputStream,
        parsed: TsSegmentParser.Result,
        isDiscontinuity: Boolean
    ) {
        val fmp4 = builder ?: return
        val videos = parsed.videos
        val videoSamples = ArrayList<Fmp4Builder.Sample>(videos.size)
        var videoDecodeTime = videoEnd
        if (videos.isNotEmpty()) {
            val firstDts = videos.first().dts
            videoDecodeTime = videoBase(firstDts, isDiscontinuity)
            var segmentSpan = 0L
            for (index in videos.indices) {
                val au = videos[index]
                val dts = delta(au.dts, firstDts)
                val duration = if (index + 1 < videos.size) {
                    delta(videos[index + 1].dts, firstDts) - dts
                } else {
                    lastVideoDuration.toLong()
                }
                val safeDuration = duration.coerceIn(1L, MAX_FRAME_DURATION.toLong()).toInt()
                videoSamples += Fmp4Builder.Sample(
                    data = au.avccPayload,
                    duration = safeDuration,
                    flags = if (au.keyframe) SAMPLE_FLAG_KEY else SAMPLE_FLAG_NON_KEY,
                    compositionOffset = (delta(au.pts, firstDts) - dts).toInt()
                )
                segmentSpan = dts + safeDuration
            }
            videoEnd = videoDecodeTime + segmentSpan
            if (videoSamples.size >= 2) {
                lastVideoDuration = videoSamples[videoSamples.size - 2].duration
            }
        }

        val audioSamples = ArrayList<Fmp4Builder.Sample>(parsed.audios.size)
        if (fmp4.hasAudio) {
            parsed.audios.forEach { au ->
                audioSamples += Fmp4Builder.Sample(
                    data = au.rawAac,
                    duration = AAC_SAMPLES_PER_FRAME,
                    flags = SAMPLE_FLAG_KEY, // 音频样本全部是同步样本
                    compositionOffset = 0
                )
            }
        }
        val audioDecodeTime = audioEnd
        audioEnd += audioSamples.size * AAC_SAMPLES_PER_FRAME.toLong()

        if (videoSamples.isEmpty() && audioSamples.isEmpty()) return
        out.write(
            fmp4.fragment(
                sequenceNumber = sequenceNumber++,
                videoDecodeTime = videoDecodeTime,
                videoSamples = videoSamples,
                audioDecodeTime = audioDecodeTime,
                audioSamples = audioSamples
            )
        )
        out.flush()
    }

    /**
     * 视频轨解码时间的归一化：首分片从 0 起，33bit 回绕展开，
     * `#EXT-X-DISCONTINUITY` 或时间戳倒退时把后续内容顺延到已写时间轴之后。
     */
    private fun videoBase(firstDts: Long, isDiscontinuity: Boolean): Long {
        if (videoLastRaw >= 0) {
            if (firstDts < videoLastRaw - WRAP_HALF) videoWrap += WRAP_PERIOD
            else if (firstDts > videoLastRaw + WRAP_HALF) videoWrap -= WRAP_PERIOD
        }
        videoLastRaw = firstDts
        val normalized = firstDts + videoWrap
        if (videoOrigin < 0) videoOrigin = normalized
        var base = normalized - videoOrigin - videoShift
        if (isDiscontinuity || base < videoEnd) {
            videoShift += base - videoEnd
            base = videoEnd
        }
        return base
    }

    // ---- 上游请求 ----

    private fun fetchBytes(url: HttpUrl): ByteArray? =
        fetchSegmentBytes(client, url, credentialOrigin, headers)

    companion object {
        private const val TAG = "YunX-HlsFmp4"
        private const val MAX_REDIRECTS = 5
        private const val MAX_SEGMENT_BYTES = 512L * 1024 * 1024
        private const val MAX_PROBE_SEGMENTS = 3
        private const val AAC_SAMPLES_PER_FRAME = 1024
        private const val DEFAULT_FRAME_DURATION = 3000 // 30fps，兜底用
        private const val MAX_FRAME_DURATION = 90_000
        private const val WRAP_PERIOD = 1L shl 33
        private const val WRAP_HALF = 1L shl 32
        private const val SAMPLE_FLAG_KEY = 0x02000000
        private const val SAMPLE_FLAG_NON_KEY = 0x01010000

        /** 差值；跨越 33bit 回绕时归一（TS 时间戳是模 2^33） */
        private fun delta(value: Long, base: Long): Long {
            val diff = value - base
            return when {
                diff < -WRAP_HALF -> diff + WRAP_PERIOD
                diff > WRAP_HALF -> diff - WRAP_PERIOD
                else -> diff
            }
        }

        /**
         * 拉播放列表并探测编码参数；返回 null 表示「本流不支持预览」，调用方降级。
         * 阻塞执行（内部 [runBlocking]），只应在代理线程调用。
         */
        fun prepare(startUrl: HttpUrl, headers: Map<String, String>): HlsFmp4Streamer? {
            if (!startUrl.isHttps) return null
            return runCatching { runBlocking { prepareBlocking(startUrl, headers) } }
                .onFailure { Log.e(TAG, "HLS 预览准备异常: ${it::class.simpleName} ${it.message}") }
                .getOrNull()
        }

        private suspend fun prepareBlocking(startUrl: HttpUrl, headers: Map<String, String>): HlsFmp4Streamer? {
            val master = HlsDownloader.fetchText(startUrl, startUrl, headers) ?: run {
                Log.w(TAG, "播放列表拉取失败: ${startUrl.host}")
                return null
            }
            val mediaUrl = HlsDownloader.resolveMediaPlaylist(master.finalUrl, master.text) ?: return null
            val media = if (mediaUrl == master.finalUrl) master
            else HlsDownloader.fetchText(mediaUrl, startUrl, headers) ?: run {
                Log.w(TAG, "媒体播放列表拉取失败")
                return null
            }

            val text = media.text
            if (text.contains("#EXT-X-KEY")) {
                Log.w(TAG, "HLS 流已加密，暂不支持预览")
                return null
            }
            if (text.contains("#EXT-X-BYTERANGE")) {
                Log.w(TAG, "HLS BYTERANGE 分片暂不支持预览")
                return null
            }
            val base = media.finalUrl
            val rawSegments = runCatching { HlsDownloader.parseSegments(text) }.getOrNull() ?: return null
            if (rawSegments.isEmpty()) return null
            val segmentUrls = rawSegments.map { HlsRequestPolicy.resolve(base, it) ?: return null }
            val discontinuity = discontinuityIndices(text)

            // 已是 fMP4（EXT-X-MAP 给了 init 段）→ 直通拼接
            val mapUri = HlsDownloader.parseMapUri(text)
            if (mapUri != null) {
                val initUrl = HlsRequestPolicy.resolve(base, mapUri) ?: return null
                val init = fetchInitSegment(initUrl, startUrl, headers) ?: return null
                Log.i(TAG, "HLS 为 fMP4，直通输出 segments=${segmentUrls.size}")
                return HlsFmp4Streamer(
                    credentialOrigin = startUrl,
                    headers = headers,
                    segments = segmentUrls,
                    discontinuity = discontinuity,
                    passthroughInit = init,
                    prefetched = emptyList(),
                    builder = null
                )
            }

            // TS 模式：预取前几个分片凑 SPS/PPS 与采样率
            val client = HttpClients.apiClient().newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val prefetched = mutableListOf<TsSegmentParser.Result>()
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            var width = 0
            var height = 0
            var sampleRate = 0
            var channels = 0
            for (index in 0 until minOf(MAX_PROBE_SEGMENTS, segmentUrls.size)) {
                val bytes = fetchSegmentBytes(client, segmentUrls[index], startUrl, headers) ?: run {
                    Log.w(TAG, "预取分片失败 #$index")
                    return null
                }
                val parsed = runCatching { TsSegmentParser(bytes).parse() }.getOrElse {
                    Log.w(TAG, "预取分片解析失败 #$index: ${it.message}")
                    return null
                }
                prefetched += parsed
                if (sps == null) sps = parsed.sps
                if (pps == null) pps = parsed.pps
                if (width <= 0 && parsed.width > 0) {
                    width = parsed.width
                    height = parsed.height
                }
                if (sampleRate <= 0 && parsed.sampleRate > 0) {
                    sampleRate = parsed.sampleRate
                    channels = parsed.channels
                }
                if (sps != null && pps != null && sampleRate > 0) break
            }

            if (sps == null || pps == null || width <= 0 || height <= 0) {
                Log.w(TAG, "HLS 未取到 H.264 编码配置，无法预览")
                return null
            }
            val audioSupported = sampleRate > 0 && Fmp4Builder.sampleRateIndex(sampleRate) >= 0
            if (!audioSupported) Log.w(TAG, "HLS 音频不可重封装（采样率 $sampleRate），按无声预览")
            // 时长取自 EXTINF 之和：jfxmedia 走 HTTP 时必须有 moov 时长才肯出 READY
            val durationMs = playlistDurationMs(text)
            val builder = Fmp4Builder(
                width = width,
                height = height,
                sps = sps,
                pps = pps,
                sampleRate = sampleRate,
                channels = channels,
                hasVideo = true,
                hasAudio = audioSupported,
                durationMs = durationMs
            )
            Log.i(
                TAG,
                "HLS TS→fMP4 就绪 ${width}x$height ${sampleRate}Hz " +
                    "segments=${segmentUrls.size} duration=${durationMs}ms"
            )
            return HlsFmp4Streamer(
                credentialOrigin = startUrl,
                headers = headers,
                segments = segmentUrls,
                discontinuity = discontinuity,
                passthroughInit = null,
                prefetched = prefetched,
                builder = builder
            )
        }

        /** EXT-X-MAP 的 init 段（体积很小） */
        private fun fetchInitSegment(
            url: HttpUrl,
            credentialOrigin: HttpUrl,
            headers: Map<String, String>
        ): ByteArray? {
            val client = HttpClients.apiClient().newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            return fetchSegmentBytes(client, url, credentialOrigin, headers)
                ?.takeIf { it.size >= 8 }
        }

        /** 下载一个分片到内存；手动跟随重定向，逐跳剥跨域敏感头 */
        private fun fetchSegmentBytes(
            client: OkHttpClient,
            url: HttpUrl,
            credentialOrigin: HttpUrl,
            headers: Map<String, String>
        ): ByteArray? {
            var current = url
            repeat(MAX_REDIRECTS + 1) { redirectCount ->
                val requestBuilder = Request.Builder().url(current)
                HlsRequestPolicy.headersFor(current, credentialOrigin, headers)
                    .forEach { (name, value) -> requestBuilder.header(name, value) }
                val response = runCatching { client.newCall(requestBuilder.get().build()).execute() }
                    .getOrNull() ?: return null
                response.use { resp ->
                    if (resp.code in 300..399) {
                        val location = resp.header("Location") ?: return null
                        val next = HlsRequestPolicy.resolve(current, location) ?: return null
                        if (redirectCount >= MAX_REDIRECTS) return null
                        current = next
                        return@use
                    }
                    if (!resp.isSuccessful) return null
                    val body = resp.body ?: return null
                    if (body.contentLength() > MAX_SEGMENT_BYTES) return null
                    return body.bytes()
                }
            }
            return null
        }

        /**
         * EXTINF 之和（毫秒）。直播列表的 EXTINF 只是已发布部分，但预览契约里
         * 「能算多长算多长」比写 0 更友好；解析不出返回 0。
         */
        private fun playlistDurationMs(text: String): Long {
            var total = 0.0
            for (line in text.lineSequence()) {
                if (!line.startsWith("#EXTINF:")) continue
                val value = line.removePrefix("#EXTINF:").substringBefore(',').trim()
                total += value.toDoubleOrNull() ?: 0.0
            }
            return (total * 1000).toLong()
        }

        /** 解析「哪些分片前面有 #EXT-X-DISCONTINUITY」 */
        private fun discontinuityIndices(text: String): Set<Int> {
            val result = mutableSetOf<Int>()
            var pending = false
            var index = 0
            for (line in text.lineSequence()) {
                val value = line.trim()
                if (value.isEmpty()) continue
                if (value.startsWith("#EXT-X-DISCONTINUITY")) {
                    pending = true
                    continue
                }
                if (value.startsWith("#")) continue
                if (pending) result += index
                pending = false
                index++
            }
            return result
        }
    }
}
