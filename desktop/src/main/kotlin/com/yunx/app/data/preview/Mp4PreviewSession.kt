package com.yunx.app.data.preview

import com.yunx.app.data.download.HlsRequestPolicy
import com.yunx.app.data.network.HttpClients
import com.yunx.app.util.Log
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

private const val TAG = "YunX-Preview"

/** faststart 文件起播前至少要落盘多少字节（够播放器读 moov + 起播缓冲） */
private const val PROLOGUE_BYTES = 8L * 1024 * 1024

/** 容器探测最多看文件头多少字节（超出还认不出 moov 就按「等下完」处理） */
private const val HEAD_SCAN_LIMIT = 4L * 1024 * 1024

/** 起播等待上限：到点还没满足条件就放弃（调用方降级为「无法预览」） */
private const val READY_TIMEOUT_MS = 20_000L

/** 起播条件轮询间隔 */
private const val POLL_MS = 50L

/** 上游重定向最多跟几跳 */
private const val MAX_REDIRECTS = 5

/**
 * 单文件 MP4 在线预览会话：**边下载边落盘，播放器读本地 `file:` URI**。
 *
 * 为什么不是回环 HTTP 代理：实测 jfxmedia 对 `127.0.0.1` 的流式响应（chunked 或定长都一样）
 * 会间歇性报 `ERROR_MEDIA_INVALID`——同一个文件用 `file:` 打开从不失败（实测）。
 * 所以这里绕开传输层，直接把字节写到临时文件，让播放器读 `file:`。
 *
 * 起播时机（[prepare] 内阻塞判定，调用方在 IO 线程执行）：
 * - 文件是 faststart（`moov` 在 `mdat` 之前）→ 下够前导字节即可起播，剩余部分后台续写；
 * - 否则（`mdat` 在前、`moov` 在文件尾）→ 必须等下完，播放器才能解析容器。
 *
 * 生命周期：调用方关闭预览时必须 [cancel]，停止下载并删除临时文件。
 */
internal class Mp4PreviewSession private constructor(
    val file: File,
    private val job: DownloadJob
) {
    /** 播放器用的 file: URI */
    val uri: String = file.toURI().toString()

    fun cancel() = job.cancel()

    companion object {
        /** 临时文件目录（放在系统临时目录下，进程退出不残留重要数据） */
        private val cacheDir: File
            get() = File(System.getProperty("java.io.tmpdir"), "yunx-preview")

        /**
         * 准备一个 MP4 预览会话（阻塞：下载到可起播为止）。
         * 返回 null 表示该源不可预览（非 HTTPS / 网络失败 / 等待超时），调用方降级。
         */
        fun prepare(sourceUrl: String, headers: Map<String, String>): Mp4PreviewSession? {
            val url = HlsRequestPolicy.initialUrl(sourceUrl) ?: run {
                Log.w(TAG, "拒绝非 HTTPS 的 MP4 预览源")
                return null
            }
            return runCatching {
                val dir = cacheDir.apply { mkdirs() }
                sweepStaleFiles(dir)
                val file = File(dir, "preview-${UUID.randomUUID()}.mp4")
                val job = DownloadJob(url, headers, file)
                job.start()
                if (job.awaitPlayable(READY_TIMEOUT_MS, POLL_MS)) {
                    Mp4PreviewSession(file, job)
                } else {
                    job.cancel()
                    null
                }
            }.onFailure {
                Log.e(TAG, "MP4 预览会话创建失败: ${it::class.simpleName} ${it.message}")
            }.getOrNull()
        }

        /** 清掉上次异常退出残留的临时文件（1 天前的） */
        private fun sweepStaleFiles(dir: File) {
            val threshold = System.currentTimeMillis() - 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { candidate ->
                if (candidate.isFile && candidate.lastModified() < threshold) {
                    runCatching { candidate.delete() }
                }
            }
        }
    }
}

/**
 * 把单个 MP4 直链下载到 [file]，同时对外暴露下载进度供起播判定使用。
 *
 * 安全边界与 HLS 下载一致：只接受 HTTPS 源，重定向手动跟随并逐跳应用
 * [HlsRequestPolicy.headersFor]（跨域剥 Cookie/Referer 等敏感头）。
 */
private class DownloadJob(
    /** 起始 URL，同时充当凭证来源域（敏感头只在同域时下发） */
    private val url: HttpUrl,
    private val headers: Map<String, String>,
    private val file: File
) {
    private val client: OkHttpClient = HttpClients.apiClient().newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile
    private var downloaded = 0L

    @Volatile
    private var total = -1L

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var finished = false

    @Volatile
    private var cancelled = false

    /** 供 [cancel] 关闭以打断网络读 / 写盘（关闭后读写抛异常，由 cancelled 标志吞掉） */
    @Volatile
    private var response: Response? = null

    @Volatile
    private var out: OutputStream? = null

    @Volatile
    private var worker: Thread? = null

    /** 容器形态判定结果（见 [scanBoxes]），只算一次 */
    @Volatile
    private var headResult: Boolean? = null

    private var lastScanBytes = -1L

    fun start() {
        worker = Thread({ runDownload() }, "preview-mp4-writer").apply {
            isDaemon = true
            start()
        }
    }

    /** 停止下载并删除临时文件（可重复调用） */
    fun cancel() {
        if (cancelled) return
        cancelled = true
        runCatching { response?.close() }
        runCatching { out?.close() }
        runCatching { worker?.join(1000) }
        runCatching { file.delete() }
    }

    /**
     * 等待「可以交给播放器打开」的时刻，返回 false 表示失败/超时/被取消。
     *
     * faststart 文件下够前导就能播；非 faststart 必须等下完（容器尾部才有 moov）。
     */
    fun awaitPlayable(timeoutMs: Long, pollMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) return false
            if (failure != null) return false
            if (headScan() == true) {
                val need = if (total in 0 until PROLOGUE_BYTES) total else PROLOGUE_BYTES
                if (downloaded >= need) return true
            }
            if (finished) return downloaded > 0
            Thread.sleep(pollMs)
        }
        return false
    }

    private fun runDownload() {
        try {
            val resp = openStream() ?: throw IOException("无法连接上游")
            response = resp
            resp.use { r ->
                total = r.body?.contentLength() ?: -1L
                val body = r.body ?: throw IOException("响应无内容")
                val output = file.outputStream()
                out = output
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (!cancelled) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                    }
                }
                output.flush()
            }
            finished = true
        } catch (t: Throwable) {
            // 主动取消时的读写失败属正常路径
            if (!cancelled) {
                failure = t
                Log.w(TAG, "MP4 预览下载中断: ${t::class.simpleName} ${t.message}")
            }
        } finally {
            runCatching { out?.close() }
            if (cancelled) runCatching { file.delete() }
        }
    }

    /** 发起请求并手动跟随重定向；返回 null 表示无法拿到响应 */
    private fun openStream(): Response? {
        var current = url
        repeat(MAX_REDIRECTS + 1) { count ->
            val builder = Request.Builder().url(current)
            HlsRequestPolicy.headersFor(current, url, headers)
                .forEach { (name, value) -> builder.header(name, value) }
            val resp = runCatching { client.newCall(builder.get().build()).execute() }
                .getOrNull() ?: return null
            if (resp.code in 300..399) {
                val location = resp.header("Location")
                resp.close()
                val next = location?.let { HlsRequestPolicy.resolve(current, it) }
                if (next == null || count >= MAX_REDIRECTS) return null
                current = next
            } else if (resp.isSuccessful) {
                return resp
            } else {
                resp.close()
                return null
            }
        }
        return null
    }

    /**
     * 扫文件头的 ISO-BMFF box 链，判断容器形态：
     * - `true` = 先遇到完整的 `moov`（faststart，可提前起播）；
     * - `false` = 先遇到 `mdat`/`moof`（必须等下完）；
     * - `null` = 还判不出来（数据不够 / box 头跨过已落盘边界）。
     */
    private fun headScan(): Boolean? {
        headResult?.let { return it }
        val have = downloaded
        if (have < 8 || have == lastScanBytes) return headResult
        lastScanBytes = have
        val result = scanBoxes(have)
        headResult = result
        return result
    }

    private fun scanBoxes(available: Long): Boolean? {
        val limit = minOf(available, HEAD_SCAN_LIMIT)
        if (limit < 8) return null
        val data = ByteArray(limit.toInt())
        val read = runCatching { FileInputStream(file).use { it.read(data) } }.getOrDefault(-1)
        if (read < 8) return null
        var pos = 0
        while (pos + 8 <= read) {
            var size = readUInt32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            if (size == 1L) {
                // 64 位 largesize
                if (pos + 16 > read) return null
                size = readUInt64(data, pos + 8)
                headerSize = 16L
            } else if (size == 0L) {
                // 一直到文件末尾
                size = read - pos.toLong()
            }
            if (size < headerSize) return null
            if (type == "moov") {
                // moov 只有完整落盘才能让播放器解析
                return if (pos + size <= read) true else null
            }
            if (type == "mdat" || type == "moof") return false
            val next = pos + size
            if (next > read) return null
            pos = next.toInt()
        }
        return null
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun readUInt64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
