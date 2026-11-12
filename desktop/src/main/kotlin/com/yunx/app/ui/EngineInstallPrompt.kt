package com.yunx.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.preview.FfplayManager
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.components.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 全局「引擎安装提示」：本机没有 FFmpeg（ffplay / ffmpeg.exe）时，问一次是否下载。
 *
 * FFmpeg 精简版不随安装包发布，视频预览（ffplay）与 DASH 合轨（ffmpeg）都依赖它。
 * 两处共用一个「已提醒」标记（[SettingsRepository.ffmpegEngineAsked]）：
 * - 视频预览：首次遇到「自动模式但没引擎」时提示；选「暂不」则继续用内置 JavaFX 预览；
 * - DASH 合轨：ffmpeg 为主，缺引擎时**只提示不阻塞**（本次仍用内置 Java 合轨完成）。
 *
 * 引擎下载到应用私有目录（不污染系统环境变量），下载完成后无需重启，后续会自动生效。
 */
object EngineInstallPrompt {

    /** 触发场景（决定提示文案） */
    enum class Reason { PREVIEW, MERGE }

    /** 待处理的提示请求（非空 = 显示询问弹窗） */
    data class Request(val reason: Reason)

    /** 引擎下载进度 */
    data class Downloading(val read: Long, val total: Long)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    private val _downloading = MutableStateFlow<Downloading?>(null)
    val downloading: StateFlow<Downloading?> = _downloading.asStateFlow()

    /** 引擎下载成功次数（+1 即表示本机刚装好引擎，订阅方据此立即切换播放内核） */
    private val _installed = MutableStateFlow(0)
    val installed: StateFlow<Int> = _installed.asStateFlow()

    private var job: Job? = null

    /**
     * 尝试提示用户安装引擎；本机已就绪或已提醒过则不打扰。
     * @return 是否真的弹出了提示
     */
    fun ask(reason: Reason): Boolean {
        if (FfplayManager.currentPath() != null) return false
        val repo = SettingsRepository()
        if (repo.ffmpegEngineAsked) return false
        repo.ffmpegEngineAsked = true
        _request.value = Request(reason)
        return true
    }

    fun dismiss() {
        _request.value = null
    }

    /** 后台下载引擎（应用私有目录）；已在下载中则忽略 */
    fun startDownload(scope: CoroutineScope) {
        if (job?.isActive == true) return
        _downloading.value = Downloading(0L, 0L)
        job = scope.launch {
            try {
                FfplayManager.download { read, total -> _downloading.value = Downloading(read, total) }
                    .onSuccess {
                        SnackbarController.show("FFmpeg 已就绪，视频预览与合轨将使用它")
                        _installed.value += 1
                    }
                    .onFailure { SnackbarController.show("引擎下载失败：${it.message ?: "未知错误"}") }
            } catch (c: CancellationException) {
                SnackbarController.show("已取消引擎下载")
                throw c
            } finally {
                _downloading.value = null
                job = null
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
    }
}

/**
 * 渲染引擎安装提示与下载进度弹窗。放在应用根部的常驻组合中（与 [OverlayDialogHost] 同级），
 * 以便 DASH 合轨在后台触发提示时也能弹出来。
 */
@Composable
fun EngineInstallPromptHost() {
    val request by EngineInstallPrompt.request.collectAsState()
    val downloading by EngineInstallPrompt.downloading.collectAsState()
    val scope = rememberCoroutineScope()

    request?.let { req ->
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { EngineInstallPrompt.dismiss() },
            title = { Text("需要下载视频引擎（FFmpeg）吗？") },
            text = {
                Column {
                    Text(
                        text = when (req.reason) {
                            EngineInstallPrompt.Reason.PREVIEW ->
                                "检测到本机没有 FFmpeg，视频预览将使用内置解码：起播较慢、拖动进度会重新缓冲。"
                            EngineInstallPrompt.Reason.MERGE ->
                                "检测到本机没有 FFmpeg，本次合轨仍会用内置方式完成（不影响这次下载）。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "下载 FFmpeg 精简版（约 50–90MB）到本应用私有目录：只给本应用使用，" +
                            "不会改动系统环境变量。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    EngineInstallPrompt.dismiss()
                    EngineInstallPrompt.startDownload(scope)
                }) { Text("下载引擎") }
            },
            dismissButton = {
                TextButton(onClick = { EngineInstallPrompt.dismiss() }) { Text("暂不") }
            }
        )
    }

    downloading?.let { d ->
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { },
            title = { Text("正在下载视频引擎") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = {
                            if (d.total > 0) (d.read.toFloat() / d.total.toFloat()).coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            d.total > 0 -> "${formatBytes(d.read)} / ${formatBytes(d.total)}"
                            d.read > 0 -> "已下载 ${formatBytes(d.read)}"
                            else -> "正在测速并选择最快镜像…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { EngineInstallPrompt.cancelDownload() }) { Text("取消") }
            }
        )
    }
}
