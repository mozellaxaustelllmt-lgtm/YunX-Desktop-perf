package com.yunx.app.data.preview

import com.yunx.app.data.download.HlsRequestPolicy
import com.yunx.app.util.Log
import java.io.File
import java.io.OutputStream
import java.util.UUID

/**
 * HLS 在线预览会话：**边下载边重封装到本地临时文件**，播放器直接读这个文件。
 *
 * 为什么不是回环 HTTP：实测 jfxmedia 走 HTTP 时既不吃 chunked 也不吃定长响应下的
 * fragmented MP4（同一个文件用 `file:` 打开立刻能播），所以这里绕开传输层，
 * 用「文件持续变长 + 播放器按 file: 读」的方式实现真正的边下边播。
 *
 * 起播流程（[prepare] 内完成，调用方应在后台线程执行）：
 * 1. 拉播放列表 + 预取前几个分片，算出编码参数（[HlsFmp4Streamer.prepare]）；
 * 2. 同步写出 init 段 + 预取分片（通常几秒内容，落盘只要毫秒级）；
 * 3. 剩余分片交给守护线程续写，播放器此时即可开始播放，读到文件末尾时下载通常已经跑到前面。
 *
 * 生命周期：调用方关闭预览时必须 [cancel]，停止续写并删除临时文件。
 */
internal class HlsPreviewSession private constructor(
    val file: File,
    private val streamer: HlsFmp4Streamer,
    /** [HlsFmp4Streamer.writePrologue] 已消费的分片数 */
    private val consumed: Int,
    private val out: OutputStream
) {
    /** 播放器用的 file: URI */
    val uri: String = file.toURI().toString()

    @Volatile
    private var cancelled = false

    private var writer: Thread? = null

    private fun startWriting() {
        writer = Thread({
            try {
                streamer.writeRemaining(out, consumed)
            } catch (t: Throwable) {
                // 主动取消时的写失败属正常路径
                if (!cancelled) Log.w(TAG, "预览续写中断: ${t.message}")
            } finally {
                runCatching { out.close() }
                if (cancelled) runCatching { file.delete() }
            }
        }, "preview-hls-writer").apply {
            isDaemon = true
            start()
        }
    }

    /** 停止续写并删除临时文件（播放器关闭时调用，可重复调用） */
    fun cancel() {
        if (cancelled) return
        cancelled = true
        // 关闭输出流 → 续写线程下一次写立即失败退出
        runCatching { out.close() }
        runCatching { writer?.join(1000) }
        runCatching { file.delete() }
    }

    companion object {
        private const val TAG = "YunX-Preview"

        /** 临时文件目录（放在系统临时目录下，进程退出不残留重要数据） */
        private val cacheDir: File
            get() = File(System.getProperty("java.io.tmpdir"), "yunx-preview")

        /**
         * 准备一个 HLS 预览会话（阻塞：拉播放列表 + 探编码参数 + 写起播部分）。
         * 返回 null 表示该流不可预览（加密 / 非 H.264 / 网络失败等），调用方降级。
         */
        fun prepare(playlistUrl: String, headers: Map<String, String>): HlsPreviewSession? {
            val url = HlsRequestPolicy.initialUrl(playlistUrl) ?: run {
                Log.w(TAG, "拒绝非 HTTPS 的 HLS 预览源")
                return null
            }
            val streamer = HlsFmp4Streamer.prepare(url, headers) ?: return null
            return runCatching {
                val dir = cacheDir.apply { mkdirs() }
                sweepStaleFiles(dir)
                val file = File(dir, "preview-${UUID.randomUUID()}.mp4")
                val out = file.outputStream()
                try {
                    val consumed = streamer.writePrologue(out)
                    HlsPreviewSession(file, streamer, consumed, out).also { it.startWriting() }
                } catch (t: Throwable) {
                    runCatching { out.close() }
                    runCatching { file.delete() }
                    throw t
                }
            }.onFailure {
                Log.e(TAG, "HLS 预览会话创建失败: ${it::class.simpleName} ${it.message}")
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
