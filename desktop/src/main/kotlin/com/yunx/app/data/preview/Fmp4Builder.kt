package com.yunx.app.data.preview

import java.io.ByteArrayOutputStream

/**
 * fragmented MP4（ISO/IEC 14496-12）封装器：把「H.264 + AAC」访问单元输出成
 * `ftyp + moov(mvex/trex) + 逐段 moof+mdat` 的**渐进式**字节流。
 *
 * 为什么需要它：JavaFX 17/Windows 的 jfxmedia 不能播 HLS/m3u8（实测），AcFun 在线预览只能由
 * [HlsFmp4Streamer] 把 TS 分片边下载边重封装成 fMP4 喂给播放器。
 *
 * 关键约定：
 * - 初始化段里 `mvhd/mdhd` 的 duration 写 0（直播形态），时长由播放器按已到达的分片推算；
 * - 视频轨 timescale 固定 90kHz（TS 的 PTS/DTS 原生单位，免换算误差）；
 * - 音频轨 timescale = 采样率，每个 AAC 帧固定 1024 个采样；
 * - 每个 mdat 装一段分片的全部分片数据（视频在前、音频在后），`trun` 的
 *   `data_offset` 相对 `moof` 起始（`tfhd` 的 `default-base-is-moof`）。
 */
internal class Fmp4Builder(
    private val width: Int,
    private val height: Int,
    private val sps: ByteArray,
    private val pps: ByteArray,
    private val sampleRate: Int,
    private val channels: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    /**
     * 整段媒体的名义时长（毫秒，来自播放列表 EXTINF 之和）；0 表示未知（直播形态）。
     * jfxmedia 走 HTTP 时依赖 moov 里的时长，写 0 会一直等不到 READY（实测见 §9.2）。
     */
    private val durationMs: Long = 0
) {

    /** 单个分片样本：`data` 是编解码器裸负载（视频 AVCC / 音频 raw AAC） */
    class Sample(
        val data: ByteArray,
        val duration: Int,
        val flags: Int,
        val compositionOffset: Int
    )

    private val videoTrackId = 1
    private val audioTrackId = 2

    /** 初始化段：播放器只会在流的最前面读一次 */
    fun initSegment(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ftypBox())
        out.write(moovBox())
        return out.toByteArray()
    }

    /**
     * 生成一个 `moof + mdat` 分片。
     *
     * @param videoDecodeTime 视频轨首个样本的解码时间（90kHz）
     * @param audioDecodeTime 音频轨首个样本的解码时间（采样数单位）
     */
    fun fragment(
        sequenceNumber: Int,
        videoDecodeTime: Long,
        videoSamples: List<Sample>,
        audioDecodeTime: Long,
        audioSamples: List<Sample>
    ): ByteArray {
        require(videoSamples.isNotEmpty() || audioSamples.isNotEmpty())
        // data_offset 宽度固定，先按 0 生成一遍量出 moof 尺寸，再带真实偏移重建
        var moof = buildMoof(
            sequenceNumber, videoDecodeTime, videoSamples, audioDecodeTime, audioSamples, 0, 0
        )
        val videoOffset = moof.size + MDAT_HEADER_SIZE
        val audioOffset = videoOffset + videoSamples.sumOf { it.data.size }
        moof = buildMoof(
            sequenceNumber, videoDecodeTime, videoSamples, audioDecodeTime, audioSamples,
            videoOffset, audioOffset
        )

        val payloadSize = videoSamples.sumOf { it.data.size } + audioSamples.sumOf { it.data.size }
        val out = ByteArrayOutputStream(moof.size + MDAT_HEADER_SIZE + payloadSize)
        out.write(moof)
        out.write(ByteWriter().apply { u32(MDAT_HEADER_SIZE + payloadSize.toLong()); ascii("mdat") }.toByteArray())
        videoSamples.forEach { out.write(it.data) }
        audioSamples.forEach { out.write(it.data) }
        return out.toByteArray()
    }

    // ---- 初始化段 ----

    private fun ftypBox(): ByteArray = ByteWriter().box("ftyp") {
        ascii("isom")
        u32(512)
        ascii("isom")
        ascii("iso5")
        ascii("avc1")
        ascii("mp41")
    }

    private fun moovBox(): ByteArray = ByteWriter().box("moov") {
        bytes(mvhdBox())
        if (hasVideo) bytes(videoTrakBox())
        if (hasAudio) bytes(audioTrakBox())
        bytes(mvexBox())
    }

    private fun mvhdBox(): ByteArray = ByteWriter().box("mvhd") {
        fullBox(1, 0)
        u64(0) // creation_time
        u64(0) // modification_time
        u32(MOVIE_TIMESCALE.toLong())
        u64(durationMs) // duration（未知时为 0）
        u32(0x00010000) // rate = 1.0
        u16(0x0100) // volume = 1.0
        zeros(10) // reserved
        matrix()
        zeros(24) // pre_defined
        u32(trackCount() + 1L) // next_track_ID
    }

    private fun videoTrakBox(): ByteArray = ByteWriter().box("trak") {
        bytes(tkhdBox(videoTrackId, isAudio = false))
        bytes(
            ByteWriter().box("mdia") {
                bytes(mdhdBox(VIDEO_TIMESCALE, durationMs * VIDEO_TIMESCALE / 1000))
                bytes(hdlrBox("vide", "VideoHandler"))
                bytes(
                    ByteWriter().box("minf") {
                        bytes(
                            ByteWriter().box("vmhd") {
                                fullBox(0, 1)
                                u16(0) // graphicsmode
                                u16(0); u16(0); u16(0) // opcolor
                            }
                        )
                        bytes(dinfBox())
                        bytes(
                            ByteWriter().box("stbl") {
                                bytes(videoStsdBox())
                                // 分片 MP4 不需要静态索引表，占位空表
                                bytes(ByteWriter().box("stts") { fullBox(0, 0); u32(0) })
                                bytes(ByteWriter().box("stsc") { fullBox(0, 0); u32(0) })
                                bytes(ByteWriter().box("stsz") { fullBox(0, 0); u32(0); u32(0) })
                                bytes(ByteWriter().box("stco") { fullBox(0, 0); u32(0) })
                            }
                        )
                    }
                )
            }
        )
    }

    private fun audioTrakBox(): ByteArray = ByteWriter().box("trak") {
        bytes(tkhdBox(audioTrackId, isAudio = true))
        bytes(
            ByteWriter().box("mdia") {
                bytes(mdhdBox(sampleRate, durationMs * sampleRate / 1000))
                bytes(hdlrBox("soun", "SoundHandler"))
                bytes(
                    ByteWriter().box("minf") {
                        bytes(ByteWriter().box("smhd") { fullBox(0, 0); u16(0); u16(0) })
                        bytes(dinfBox())
                        bytes(
                            ByteWriter().box("stbl") {
                                bytes(audioStsdBox())
                                bytes(ByteWriter().box("stts") { fullBox(0, 0); u32(0) })
                                bytes(ByteWriter().box("stsc") { fullBox(0, 0); u32(0) })
                                bytes(ByteWriter().box("stsz") { fullBox(0, 0); u32(0); u32(0) })
                                bytes(ByteWriter().box("stco") { fullBox(0, 0); u32(0) })
                            }
                        )
                    }
                )
            }
        )
    }

    private fun trackCount(): Int = (if (hasVideo) 1 else 0) + (if (hasAudio) 1 else 0)

    private fun mvexBox(): ByteArray = ByteWriter().box("mvex") {
        if (durationMs > 0) {
            // 分片媒体总时长（movie timescale）：部分解析器只认这个字段
            bytes(ByteWriter().box("mehd") { fullBox(0, 0); u32(durationMs) })
        }
        if (hasVideo) bytes(trexBox(videoTrackId))
        if (hasAudio) bytes(trexBox(audioTrackId))
    }

    private fun trexBox(trackId: Int): ByteArray = ByteWriter().box("trex") {
        fullBox(0, 0)
        u32(trackId.toLong())
        u32(1) // default_sample_description_index
        u32(0) // default_sample_duration
        u32(0) // default_sample_size
        u32(0) // default_sample_flags
    }

    private fun tkhdBox(trackId: Int, isAudio: Boolean): ByteArray = ByteWriter().box("tkhd") {
        fullBox(1, 0x000007) // enabled | in movie | in preview
        u64(0) // creation_time
        u64(0) // modification_time
        u32(trackId.toLong())
        u32(0) // reserved
        u64(durationMs) // duration（movie timescale）
        zeros(8) // reserved
        u16(0) // layer
        u16(0) // alternate_group
        u16(if (isAudio) 0x0100 else 0) // volume
        u16(0) // reserved
        matrix()
        u32((width shl 16).toLong())
        u32((height shl 16).toLong())
    }

    private fun mdhdBox(timescale: Int, duration: Long): ByteArray = ByteWriter().box("mdhd") {
        fullBox(1, 0)
        u64(0) // creation_time
        u64(0) // modification_time
        u32(timescale.toLong())
        u64(duration)
        u16(0x55C4) // language = "und"
        u16(0) // pre_defined
    }

    private fun hdlrBox(handlerType: String, name: String): ByteArray = ByteWriter().box("hdlr") {
        fullBox(0, 0)
        u32(0) // pre_defined
        ascii(handlerType)
        zeros(12) // reserved
        ascii(name)
        u8(0)
    }

    private fun dinfBox(): ByteArray {
        val url = ByteWriter().box("url ") { fullBox(0, 1) } // 自包含
        val dref = ByteWriter().box("dref") { fullBox(0, 0); u32(1); bytes(url) }
        return ByteWriter().box("dinf") { bytes(dref) }
    }

    private fun videoStsdBox(): ByteArray {
        val avcC = ByteWriter().box("avcC") {
            u8(1) // configurationVersion
            u8(sps[1].toInt() and 0xFF) // AVCProfileIndication
            u8(sps[2].toInt() and 0xFF) // profile_compatibility
            u8(sps[3].toInt() and 0xFF) // AVCLevelIndication
            u8(0xFF) // lengthSizeMinusOne = 3
            u8(0xE1) // numOfSequenceParameterSets = 1
            u16(sps.size)
            bytes(sps)
            u8(1) // numOfPictureParameterSets
            u16(pps.size)
            bytes(pps)
        }
        val avc1 = ByteWriter().box("avc1") {
            zeros(6) // reserved
            u16(1) // data_reference_index
            u16(0); u16(0) // pre_defined, reserved
            zeros(12) // pre_defined[3]
            u16(width)
            u16(height)
            u32(0x00480000) // horizresolution = 72dpi
            u32(0x00480000) // vertresolution
            u32(0) // reserved
            u16(1) // frame_count
            zeros(32) // compressorname
            u16(0x0018) // depth
            u16(0xFFFF) // pre_defined
            bytes(avcC)
        }
        return ByteWriter().box("stsd") { fullBox(0, 0); u32(1); bytes(avc1) }
    }

    private fun audioStsdBox(): ByteArray {
        val rateIndex = sampleRateIndex(sampleRate)
        // AudioSpecificConfig：AAC-LC + 采样率索引 + 声道数
        val asc = byteArrayOf(
            ((AAC_OBJECT_TYPE shl 3) or (rateIndex shr 1)).toByte(),
            (((rateIndex and 1) shl 7) or ((channels and 0x0F) shl 3)).toByte()
        )
        val dsi = ByteWriter().descriptor(0x05, asc)
        val decoderConfig = ByteWriter().descriptor(
            0x04,
            ByteWriter().apply {
                u8(0x40) // objectTypeIndication = MPEG-4 Audio
                u8(0x15) // streamType = audio
                u24(0) // bufferSizeDB
                u32(0) // maxBitrate
                u32(0) // avgBitrate
                bytes(dsi)
            }.toByteArray()
        )
        val slConfig = ByteWriter().descriptor(0x06, byteArrayOf(0x02))
        val es = ByteWriter().descriptor(
            0x03,
            ByteWriter().apply {
                u16(0) // ES_ID
                u8(0) // flags
                bytes(decoderConfig)
                bytes(slConfig)
            }.toByteArray()
        )
        val esds = ByteWriter().box("esds") { fullBox(0, 0); bytes(es) }
        val mp4a = ByteWriter().box("mp4a") {
            zeros(6) // reserved
            u16(1) // data_reference_index
            zeros(8) // reserved
            u16(channels)
            u16(16) // samplesize
            u16(0) // pre_defined
            u16(0) // reserved
            u32(sampleRate.toLong() shl 16)
            bytes(esds)
        }
        return ByteWriter().box("stsd") { fullBox(0, 0); u32(1); bytes(mp4a) }
    }

    // ---- 分片 ----

    private fun buildMoof(
        sequenceNumber: Int,
        videoDecodeTime: Long,
        videoSamples: List<Sample>,
        audioDecodeTime: Long,
        audioSamples: List<Sample>,
        videoOffset: Int,
        audioOffset: Int
    ): ByteArray = ByteWriter().box("moof") {
        bytes(ByteWriter().box("mfhd") { fullBox(0, 0); u32(sequenceNumber.toLong()) })
        if (videoSamples.isNotEmpty()) {
            bytes(trafBox(videoTrackId, videoDecodeTime, videoSamples, videoOffset))
        }
        if (audioSamples.isNotEmpty()) {
            bytes(trafBox(audioTrackId, audioDecodeTime, audioSamples, audioOffset))
        }
    }

    private fun trafBox(trackId: Int, decodeTime: Long, samples: List<Sample>, dataOffset: Int): ByteArray =
        ByteWriter().box("traf") {
            bytes(
                ByteWriter().box("tfhd") {
                    fullBox(0, 0x020000) // default-base-is-moof
                    u32(trackId.toLong())
                }
            )
            bytes(
                ByteWriter().box("tfdt") {
                    fullBox(1, 0)
                    u64(decodeTime)
                }
            )
            bytes(
                ByteWriter().box("trun") {
                    fullBox(1, TRUN_ALL_PER_SAMPLE_FIELDS)
                    u32(samples.size.toLong())
                    i32(dataOffset)
                    samples.forEach { sample ->
                        u32(sample.duration.toLong())
                        u32(sample.data.size.toLong())
                        u32(sample.flags.toLong())
                        i32(sample.compositionOffset)
                    }
                }
            )
        }

    /** 极简 ISO BMFF 写箱器：`u32` 取 Long 便于写 0x40000000 这类高位值 */
    private class ByteWriter {
        private val out = ByteArrayOutputStream()

        fun u8(value: Int) {
            out.write(value and 0xFF)
        }

        fun u16(value: Int) {
            u8(value ushr 8)
            u8(value)
        }

        fun u24(value: Int) {
            u8(value ushr 16)
            u8(value ushr 8)
            u8(value)
        }

        fun u32(value: Long) {
            u8((value ushr 24).toInt())
            u8((value ushr 16).toInt())
            u8((value ushr 8).toInt())
            u8(value.toInt())
        }

        fun i32(value: Int) {
            u32(value.toLong())
        }

        fun u64(value: Long) {
            u32(value ushr 32)
            u32(value and 0xFFFFFFFFL)
        }

        fun bytes(value: ByteArray) {
            out.write(value)
        }

        fun ascii(value: String) {
            bytes(value.toByteArray(Charsets.US_ASCII))
        }

        fun zeros(count: Int) {
            repeat(count) { out.write(0) }
        }

        fun fullBox(version: Int, flags: Int) {
            u8(version)
            u24(flags)
        }

        /** 单位矩阵（16.16 定点：1.0 / 0.0 / 3.0） */
        fun matrix() {
            u32(0x00010000); u32(0); u32(0)
            u32(0); u32(0x00010000); u32(0)
            u32(0); u32(0); u32(0x40000000)
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        fun box(type: String, block: ByteWriter.() -> Unit): ByteArray {
            val payload = ByteWriter().apply(block).toByteArray()
            val header = ByteWriter().apply {
                u32(BOX_HEADER_SIZE + payload.size.toLong())
                ascii(type)
            }.toByteArray()
            return header + payload
        }

        /** MPEG-4 描述符：tag + 变长长度 + 负载 */
        fun descriptor(tag: Int, payload: ByteArray): ByteArray {
            val size = payload.size
            val length = when {
                size < 0x80 -> byteArrayOf(size.toByte())
                size < 0x4000 -> byteArrayOf(
                    (0x80 or (size shr 7)).toByte(),
                    (size and 0x7F).toByte()
                )
                else -> byteArrayOf(
                    (0x80 or (size shr 14)).toByte(),
                    (0x80 or ((size shr 7) and 0x7F)).toByte(),
                    (size and 0x7F).toByte()
                )
            }
            return byteArrayOf(tag.toByte()) + length + payload
        }
    }

    companion object {
        private const val MOVIE_TIMESCALE = 1000
        private const val VIDEO_TIMESCALE = 90_000
        private const val BOX_HEADER_SIZE = 8L
        private const val MDAT_HEADER_SIZE = 8
        private const val AAC_OBJECT_TYPE = 2 // AAC-LC

        /** trun：data-offset + 每样本 duration/size/flags/cts-offset */
        private const val TRUN_ALL_PER_SAMPLE_FIELDS = 0x000F01

        /** ADTS sampling_frequency_index 表（与 [TsSegmentParser] 一致） */
        private val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350
        )

        /** 采样率 → AudioSpecificConfig 里的索引；不支持返回 -1 */
        fun sampleRateIndex(rate: Int): Int = SAMPLE_RATES.indexOf(rate)
    }
}
