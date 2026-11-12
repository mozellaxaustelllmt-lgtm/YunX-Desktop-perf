package com.yunx.app.data.download

import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.network.video.VideoMediaKind
import com.yunx.app.data.network.video.VideoParseResult

/**
 * 视频媒体 → 下载文件名。
 *
 * 规则：`标题[_清晰度][_序号].扩展名`
 * - 标题走 [DownloadPathPolicy.sanitizeName]（去掉路径分隔符等非法字符），空标题回退平台名；
 * - 多选时补序号，避免同一作品的多张图/多条视频互相覆盖；
 * - 扩展名优先取 URL 路径里的后缀；HLS 的分片合并产物是 TS，所以固定 `.ts`。
 */
internal object VideoDownloads {

    fun fileNameFor(
        result: VideoParseResult,
        media: VideoMedia,
        index: Int,
        total: Int
    ): String {
        val title = DownloadPathPolicy.sanitizeName(result.title.trim())
            .ifBlank { result.platform.displayName }
        val quality = media.quality.trim()
            .takeIf { it.isNotBlank() }
            ?.let { "_" + DownloadPathPolicy.sanitizeName(it) }
            .orEmpty()
        val order = if (total > 1) "_${index + 1}" else ""
        return "$title$quality$order.${extensionOf(media)}"
    }

    private fun extensionOf(media: VideoMedia): String {
        if (media.isHls) return "ts"
        val fromUrl = media.url
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.')
            .lowercase()
        return when (media.kind) {
            VideoMediaKind.IMAGE ->
                if (fromUrl in IMAGE_EXTS) fromUrl else "jpg"
            VideoMediaKind.AUDIO ->
                if (fromUrl in AUDIO_EXTS) fromUrl else "m4a"
            VideoMediaKind.VIDEO ->
                if (fromUrl in VIDEO_EXTS) fromUrl else "mp4"
        }
    }

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val VIDEO_EXTS = setOf("mp4", "mov", "mkv", "webm", "flv", "ts")
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg")
}
