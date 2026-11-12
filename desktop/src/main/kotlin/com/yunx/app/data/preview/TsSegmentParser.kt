package com.yunx.app.data.preview

import com.yunx.app.util.Log

/**
 * MPEG-TS 分片解析器：把一段 TS 字节解析成「访问单元」序列（视频 AU / 音频帧），
 * 并顺手捞出编码配置（SPS/PPS、AAC 采样率/声道）。
 *
 * 只支持 HLS 预览需要的子集：H.264（stream type 0x1B）+ AAC/ADTS（0x0F），
 * 不支持 HEVC / MP3 / 字幕轨（遇到即忽略，能播就播，不能播由上层报错）。
 *
 * 约定（广播级 TS 的实际形态，AcFun/各 CDN 均满足）：
 * - 一个 PES 包恰好装一个视频访问单元（AU）；
 * - 音频是 ADTS 帧序列，逐帧切出后去掉 7/9 字节头得到 raw AAC；
 * - PTS/DTS 是 90kHz、33bit，回绕由上层按连续性处理。
 */
internal class TsSegmentParser(private val data: ByteArray) {

    class VideoAu(
        /** AVCC 格式负载（每个 NAL 前 4 字节大端长度，无起始码） */
        val avccPayload: ByteArray,
        val pts: Long,
        val dts: Long,
        val keyframe: Boolean
    )

    class AudioAu(
        /** 去掉 ADTS 头的 raw AAC 帧 */
        val rawAac: ByteArray
    )

    class Result(
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val sps: ByteArray?,
        val pps: ByteArray?,
        /** SPS 解出的显示尺寸（无视频轨时为 0） */
        val width: Int,
        val height: Int,
        val sampleRate: Int,
        val channels: Int,
        val videos: List<VideoAu>,
        val audios: List<AudioAu>
    )

    fun parse(): Result {
        var pmtPid = -1
        var videoPid = -1
        var audioPid = -1

        // PES 组装缓冲（按 PID 分槽，pusi 到来时先冲刷旧的）
        var videoBuf = BytePacketBuffer()
        var audioBuf = BytePacketBuffer()
        var videoPesHeader: PesHeader? = null
        var audioPesHeader: PesHeader? = null
        /** 上一段音频里没拼完的半帧 */
        var audioCarry = ByteArray(0)

        val videos = mutableListOf<VideoAu>()
        val audios = mutableListOf<AudioAu>()
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var width = 0
        var height = 0
        var sampleRate = 0
        var channels = 0

        fun flushVideo() {
            val header = videoPesHeader
            if (header == null) {
                // 没有 PES 头就收不到有效时间戳，缓存直接丢弃（否则会跨 PES 累积）
                videoBuf = BytePacketBuffer()
                return
            }
            val payload = videoBuf.toByteArray()
            videoBuf = BytePacketBuffer()
            videoPesHeader = null
            if (payload.isEmpty()) return
            val au = annexBToAvcc(payload)
            if (au.nals.isEmpty()) return
            for (nal in au.nals) {
                val type = nal[0].toInt() and 0x1F
                if (type == 7 && sps == null) {
                    sps = nal
                    runCatching {
                        val dims = SpsParser.dimensions(nal)
                        width = dims.first
                        height = dims.second
                    }.onFailure { Log.w(TAG, "SPS 解析失败: ${it.message}") }
                } else if (type == 8 && pps == null) {
                    pps = nal
                }
            }
            videos += VideoAu(au.payload, header.pts, header.dts, au.hasIdr)
        }

        fun flushAudio() {
            if (audioPesHeader == null) {
                audioBuf = BytePacketBuffer()
                return
            }
            val payload = audioBuf.toByteArray()
            audioBuf = BytePacketBuffer()
            audioPesHeader = null
            // ADTS 帧流是连续的：PES 负载可能从帧中间开始、也可能把一帧切成两半，
            // 所以整段接起来扫，不完整的尾巴留到下一个 PES 继续拼。
            val stream = if (audioCarry.isEmpty()) payload else audioCarry + payload
            var offset = 0
            while (offset + 7 <= stream.size) {
                // 不同步就向前挪一字节（PES 负载可能从帧中间开始）
                if (!isAdtsSync(stream, offset)) {
                    offset++
                    continue
                }
                val frame = parseAdtsFrame(stream, offset) ?: break // 帧体还没到齐
                if (sampleRate == 0) {
                    sampleRate = frame.sampleRate
                    channels = frame.channels
                }
                audios += AudioAu(frame.rawAac)
                offset += frame.totalLength
            }
            // 没消费完的尾巴（半帧 / 不到 7 字节的帧头）留到下一个 PES 续上；
            // 超过上限说明流本身畸形，直接丢弃，避免无限增长
            audioCarry = if (offset < stream.size && stream.size - offset <= MAX_AUDIO_CARRY) {
                stream.copyOfRange(offset, stream.size)
            } else {
                ByteArray(0)
            }
        }

        var pos = 0
        while (pos + TS_PACKET_SIZE <= data.size) {
            if (data[pos] != TS_SYNC) {
                // 容错：向前找下一个同步字节（有些分片前面会粘半包）
                val next = (pos + 1 until minOf(pos + TS_PACKET_SIZE * 2, data.size))
                    .firstOrNull { data[it] == TS_SYNC } ?: break
                pos = next
            }
            val b1 = data[pos + 1].toInt()
            val b2 = data[pos + 2].toInt()
            val b3 = data[pos + 3].toInt()
            val pusi = b1 and 0x40 != 0
            val pid = (b1 and 0x1F shl 8) or b2
            val adaptationControl = b3 shr 4 and 0x03
            var payloadOffset = pos + 4
            if (adaptationControl and 0x02 != 0) {
                payloadOffset += 1 + (data[payloadOffset].toInt() and 0xFF)
            }
            val hasPayload = adaptationControl and 0x01 != 0 && payloadOffset < pos + TS_PACKET_SIZE

            if (hasPayload) {
                when (pid) {
                    0 -> if (pusi) pmtPid = parsePat(data, payloadOffset) ?: pmtPid
                    pmtPid -> if (pusi && pmtPid >= 0) {
                        val streams = parsePmt(data, payloadOffset)
                        if (streams.first >= 0 && videoPid < 0) videoPid = streams.first
                        if (streams.second >= 0 && audioPid < 0) audioPid = streams.second
                    }
                    videoPid -> {
                        if (pusi) {
                            flushVideo()
                            videoPesHeader = readPesHeader(data, payloadOffset)
                        }
                        val skip = pesSkip(data, payloadOffset, pusi)
                        videoBuf.write(data, payloadOffset + skip, pos + TS_PACKET_SIZE - payloadOffset - skip)
                    }
                    audioPid -> {
                        if (pusi) {
                            flushAudio()
                            audioPesHeader = readPesHeader(data, payloadOffset)
                        }
                        val skip = pesSkip(data, payloadOffset, pusi)
                        audioBuf.write(data, payloadOffset + skip, pos + TS_PACKET_SIZE - payloadOffset - skip)
                    }
                }
            }
            pos += TS_PACKET_SIZE
        }
        flushVideo()
        flushAudio()

        return Result(
            hasVideo = videoPid >= 0 && videos.isNotEmpty(),
            hasAudio = audioPid >= 0 && audios.isNotEmpty(),
            sps = sps, pps = pps,
            width = width, height = height,
            sampleRate = sampleRate, channels = channels,
            videos = videos, audios = audios
        )
    }

    // ---- PAT / PMT ----

    /** PAT → PMT 的 PID；无法解析返回 null。section 起始要跳 pointer field */
    private fun parsePat(data: ByteArray, offset: Int): Int? {
        val start = offset + 1 + (data[offset].toInt() and 0xFF) // pointer field
        if (start + 12 > data.size || data[start] != 0x00.toByte()) return null
        val sectionLength = (data[start + 1].toInt() and 0x0F shl 8) or (data[start + 2].toInt() and 0xFF)
        val end = minOf(start + 3 + sectionLength - 4, data.size) // 去掉 CRC
        var p = start + 8
        while (p + 4 <= end) {
            val program = (data[p].toInt() and 0xFF shl 8) or (data[p + 1].toInt() and 0xFF)
            val pid = (data[p + 2].toInt() and 0x1F shl 8) or (data[p + 3].toInt() and 0xFF)
            if (program != 0) return pid
            p += 4
        }
        return null
    }

    /** PMT → (视频 PID, 音频 PID)；没有对应流则为 -1 */
    private fun parsePmt(data: ByteArray, offset: Int): Pair<Int, Int> {
        val start = offset + 1 + (data[offset].toInt() and 0xFF)
        if (start + 12 > data.size || data[start] != 0x02.toByte()) return -1 to -1
        val sectionLength = (data[start + 1].toInt() and 0x0F shl 8) or (data[start + 2].toInt() and 0xFF)
        val programInfoLength = (data[start + 10].toInt() and 0x0F shl 8) or (data[start + 11].toInt() and 0xFF)
        var p = start + 12 + programInfoLength
        val end = minOf(start + 3 + sectionLength - 4, data.size)
        var video = -1
        var audio = -1
        while (p + 5 <= end) {
            val streamType = data[p].toInt() and 0xFF
            val pid = (data[p + 1].toInt() and 0x1F shl 8) or (data[p + 2].toInt() and 0xFF)
            val esInfoLength = (data[p + 3].toInt() and 0x0F shl 8) or (data[p + 4].toInt() and 0xFF)
            when (streamType) {
                STREAM_TYPE_H264 -> if (video < 0) video = pid
                STREAM_TYPE_AAC -> if (audio < 0) audio = pid
                STREAM_TYPE_HEVC -> Log.w(TAG, "HLS 流是 HEVC，JavaFX 不支持，跳过视频轨")
            }
            p += 5 + esInfoLength
        }
        return video to audio
    }

    // ---- PES ----

    private class PesHeader(val pts: Long, val dts: Long, val headerLength: Int)

    /** 读取 PES 头里的 PTS/DTS（90kHz）；无时间戳时用 0（上层按连续性补齐） */
    private fun readPesHeader(data: ByteArray, offset: Int): PesHeader? {
        if (offset + 9 > data.size) return null
        if (data[offset] != 0x00.toByte() || data[offset + 1] != 0x00.toByte() || data[offset + 2] != 0x01.toByte()) {
            return null
        }
        val flags = data[offset + 7].toInt() and 0xFF
        val headerLength = data[offset + 8].toInt() and 0xFF
        var pts = 0L
        var dts = 0L
        if (flags and 0x80 != 0 && offset + 14 <= data.size) {
            pts = decodePts(data, offset + 9)
        }
        if (flags and 0x40 != 0 && offset + 19 <= data.size) {
            dts = decodePts(data, offset + 14)
        } else {
            dts = pts
        }
        return PesHeader(pts, dts, headerLength)
    }

    /** 5 字节 PTS/DTS 字段 → 90kHz 时间戳 */
    private fun decodePts(data: ByteArray, o: Int): Long {
        return ((data[o].toLong() shr 1 and 0x07) shl 30) or
            ((data[o + 1].toLong() and 0xFF) shl 22) or
            ((data[o + 2].toLong() shr 1 and 0x7F) shl 15) or
            ((data[o + 3].toLong() and 0xFF) shl 7) or
            (data[o + 4].toLong() shr 1 and 0x7F)
    }

    /** pusi 包要跳过 PES 头（9 + headerLength）；非 pusi 包整个都是负载 */
    private fun pesSkip(data: ByteArray, offset: Int, pusi: Boolean): Int {
        if (!pusi) return 0
        if (offset + 9 > data.size) return 0
        return 9 + (data[offset + 8].toInt() and 0xFF)
    }

    // ---- Annex-B → AVCC ----

    private class AvccResult(val nals: List<ByteArray>, val payload: ByteArray, val hasIdr: Boolean)

    /** Annex-B 字节流切 NAL（3/4 字节起始码），再拼成 4 字节长度前缀的 AVCC 负载 */
    private fun annexBToAvcc(payload: ByteArray): AvccResult {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 <= payload.size) {
            if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() &&
                (payload[i + 2] == 1.toByte() || (i + 3 < payload.size && payload[i + 2] == 0.toByte() && payload[i + 3] == 1.toByte()))
            ) {
                starts += i
                i += if (payload[i + 2] == 1.toByte()) 3 else 4
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return AvccResult(emptyList(), ByteArray(0), false)
        val nals = mutableListOf<ByteArray>()
        for (index in starts.indices) {
            val nalStart = starts[index] + if (payload[starts[index] + 2] == 1.toByte()) 3 else 4
            val nalEnd = if (index + 1 < starts.size) starts[index + 1] else payload.size
            // 去掉尾部 0（起始码前的 trailing_zero_8bits）
            var end = nalEnd
            while (end > nalStart && payload[end - 1] == 0.toByte()) end--
            if (end > nalStart) nals += payload.copyOfRange(nalStart, end)
        }
        val hasIdr = nals.any { it[0].toInt() and 0x1F == 5 }
        val total = nals.sumOf { it.size + 4 }
        val out = ByteArray(total)
        var p = 0
        for (nal in nals) {
            out[p] = (nal.size ushr 24).toByte()
            out[p + 1] = (nal.size ushr 16).toByte()
            out[p + 2] = (nal.size ushr 8).toByte()
            out[p + 3] = nal.size.toByte()
            System.arraycopy(nal, 0, out, p + 4, nal.size)
            p += 4 + nal.size
        }
        return AvccResult(nals, out, hasIdr)
    }

    // ---- ADTS ----

    private class AdtsFrame(
        val rawAac: ByteArray,
        val totalLength: Int,
        val sampleRate: Int,
        val channels: Int
    )

    /** 解析 offset 处的一个 ADTS 帧；帧不完整/不同步返回 null */
    private fun parseAdtsFrame(payload: ByteArray, offset: Int): AdtsFrame? {
        if (offset + 7 > payload.size) return null
        if ((payload[offset].toInt() and 0xFF) != 0xFF || (payload[offset + 1].toInt() and 0xF0) != 0xF0) return null
        val protectionAbsent = payload[offset + 1].toInt() and 0x01
        val freqIndex = payload[offset + 2].toInt() shr 2 and 0x0F
        val channelConfig = (payload[offset + 2].toInt() and 0x01 shl 2) or (payload[offset + 3].toInt() shr 6 and 0x03)
        val frameLength = (payload[offset + 3].toInt() and 0x03 shl 11) or
            (payload[offset + 4].toInt() and 0xFF shl 3) or
            (payload[offset + 5].toInt() shr 5 and 0x07)
        if (frameLength <= 0 || offset + frameLength > payload.size) return null
        val headerSize = if (protectionAbsent == 1) 7 else 9
        if (frameLength <= headerSize) return null
        val rate = ADTS_SAMPLE_RATES.getOrElse(freqIndex) { 0 }
        return AdtsFrame(
            rawAac = payload.copyOfRange(offset + headerSize, offset + frameLength),
            totalLength = frameLength,
            sampleRate = rate,
            channels = channelConfig
        )
    }

    /** ADTS 同步字（0xFFF 后接 layer/版权位，这里只校验高 12 位） */
    private fun isAdtsSync(data: ByteArray, offset: Int): Boolean =
        (data[offset].toInt() and 0xFF) == 0xFF && (data[offset + 1].toInt() and 0xF0) == 0xF0

    /** 轻量 PES 负载缓冲（避免 ByteArrayOutputStream 的同步开销） */
    private class BytePacketBuffer {
        private var buf = ByteArray(64 * 1024)
        private var size = 0
        fun write(data: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            if (size + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + length))
            System.arraycopy(data, offset, buf, size, length)
            size += length
        }
        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    companion object {
        private const val TAG = "YunX-TsParse"
        private const val TS_PACKET_SIZE = 188
        private const val TS_SYNC: Byte = 0x47
        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_HEVC = 0x24
        private const val STREAM_TYPE_AAC = 0x0F
        /** 音频半帧 carry 上限（正常半帧 < 一帧大小，超了就是畸形流） */
        private const val MAX_AUDIO_CARRY = 64 * 1024

        /** ADTS sampling_frequency_index → Hz（0x0F 显式给值的情况不支持，按 0 处理让上层失败） */
        private val ADTS_SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350, 0, 0, 0
        )
    }
}

/**
 * 最小 SPS 解析器：只取显示宽高（tkhd/avc1 用）。
 * 处理 High profile 的 scaling list 跳过与帧裁剪，够覆盖 H.264 主流形态。
 */
internal object SpsParser {

    fun dimensions(sps: ByteArray): Pair<Int, Int> {
        // 去 emulation_prevention_three_byte（00 00 03 → 00 00）
        val rbsp = ArrayList<Byte>(sps.size)
        var i = 1 // 跳 NAL 头
        var zeros = 0
        while (i < sps.size) {
            val b = sps[i]
            if (zeros == 2 && b == 3.toByte()) {
                zeros = 0
                i++
                continue
            }
            zeros = if (b == 0.toByte()) zeros + 1 else 0
            rbsp += b
            i++
        }
        val bits = BitReader(ByteArray(rbsp.size) { rbsp[it] })
        return readDimensions(bits)
    }

    private fun readDimensions(bits: BitReader): Pair<Int, Int> {
        // SPS RBSP 布局：profile_idc(8) constraint(8) level_idc(8) sps_id(ue) ...
        val profileIdc = bits.u8()
        bits.u8() // constraint_set flags + reserved
        bits.u8() // level_idc
        bits.ue() // seq_parameter_set_id
        var chromaFormatIdc = 1
        var separateColourPlane = 0
        if (profileIdc in HIGH_PROFILES) {
            chromaFormatIdc = bits.ue()
            if (chromaFormatIdc == 3) separateColourPlane = bits.u1()
            bits.ue() // bit_depth_luma_minus8
            bits.ue() // bit_depth_chroma_minus8
            bits.u1() // qpprime_y_zero_transform_bypass_flag
            if (bits.u1() == 1) { // seq_scaling_matrix_present_flag
                val count = if (chromaFormatIdc != 3) 8 else 12
                for (idx in 0 until count) {
                    if (bits.u1() == 1) skipScalingList(bits, if (idx < 6) 16 else 64)
                }
            }
        }
        bits.ue() // log2_max_frame_num_minus4
        when (bits.ue()) { // pic_order_cnt_type
            0 -> bits.ue() // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                bits.u1() // delta_pic_order_always_zero_flag
                bits.se() // offset_for_non_ref_pic
                bits.se() // offset_for_top_to_bottom_field
                repeat(bits.ue()) { bits.se() } // offset_for_ref_frame[i]
            }
        }
        bits.ue() // max_num_ref_frames
        bits.u1() // gaps_in_frame_num_value_allowed_flag
        val picWidthInMbs = bits.ue() + 1
        val picHeightInMapUnits = bits.ue() + 1
        val frameMbsOnly = bits.u1()
        if (frameMbsOnly == 0) bits.u1() // mb_adaptive_frame_field_flag
        bits.u1() // direct_8x8_inference_flag
        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (bits.u1() == 1) { // frame_cropping_flag
            cropLeft = bits.ue()
            cropRight = bits.ue()
            cropTop = bits.ue()
            cropBottom = bits.ue()
        }
        val subWidthC = if (chromaFormatIdc == 0 || chromaFormatIdc == 3 || separateColourPlane == 1) 1 else 2
        val subHeightC = if (chromaFormatIdc == 0 || chromaFormatIdc == 3 || separateColourPlane == 1) 1 else 2
        val cropUnitX = subWidthC
        val cropUnitY = subHeightC * (2 - frameMbsOnly)
        val width = picWidthInMbs * 16 - (cropLeft + cropRight) * cropUnitX
        val height = picHeightInMapUnits * 16 * (2 - frameMbsOnly) - (cropTop + cropBottom) * cropUnitY
        return width to height
    }

    private fun skipScalingList(bits: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until size) {
            if (nextScale != 0) {
                val delta = bits.se()
                nextScale = (lastScale + delta + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    private val HIGH_PROFILES = setOf(
        100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135
    )

    /** MSB 优先的位读取器 */
    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0

        fun u1(): Int {
            val byteIndex = bitPos / 8
            if (byteIndex >= data.size) return 0
            val bit = (data[byteIndex].toInt() shr (7 - bitPos % 8)) and 1
            bitPos++
            return bit
        }

        fun bits(count: Int): Long {
            var value = 0L
            repeat(count) { value = (value shl 1) or u1().toLong() }
            return value
        }

        fun u8(): Int = bits(8).toInt()

        fun ue(): Int {
            var zeros = 0
            while (u1() == 0 && zeros < 32) zeros++
            if (zeros == 0) return 0
            return ((1L shl zeros) - 1 + bits(zeros)).toInt()
        }

        fun se(): Int {
            val k = ue()
            return if (k % 2 == 0) -(k / 2) else (k + 1) / 2
        }
    }
}
