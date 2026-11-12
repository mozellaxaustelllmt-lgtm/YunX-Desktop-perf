package com.yunx.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.jna.platform.win32.WinDef
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.preview.FfplayManager
import com.yunx.app.data.preview.FfplayWindowFx
import com.yunx.app.data.preview.HlsPreviewSession
import com.yunx.app.data.preview.Mp4PreviewSession
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.EngineInstallPrompt
import com.yunx.app.util.Log
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.SwingUtilities
import java.util.Locale

/**
 * 视频预览播放器（M6）。
 *
 * 播放内核是 JavaFX `MediaView`，通过 [SwingPanel] 把 `JFXPanel` 嵌进 Compose：
 * 实测 MediaView 必须挂在「可见窗口的 Scene」里，否则 jfxmedia 的视频 preroll 会随机死锁
 * （实测结论），这里 JFXPanel + 真实窗口天然满足。
 *
 * 播放源由 [prepareSource] 决定，单文件 MP4 与 HLS 都走「本地临时文件 + `file:` URI」：
 * - 单文件 MP4（含 B 站 [VideoMedia.previewUrl] 合好的 mp4）→ [Mp4PreviewSession]
 *   边下载边落盘，faststart 文件下够前导即可起播，非 faststart 等下完；
 * - HLS（m3u8）→ [HlsPreviewSession] 边下边重封装成本地增长文件（jfxmedia 不能播 m3u8）。
 *
 * 不再走回环 HTTP 代理：实测 jfxmedia 对 `127.0.0.1` 的流式响应会间歇性报 `ERROR_MEDIA_INVALID`
 * （同一文件用 `file:` 打开始终正常），而 `file:` 这条路径始终可用。
 */
@Composable
fun VideoPreviewOverlay(media: VideoMedia?, onDismiss: () -> Unit) {
    val link = media
    // 关闭时保留最后一次内容跑淡出，避免硬切
    var lastMedia by remember { mutableStateOf<VideoMedia?>(null) }
    if (link != null) lastMedia = link
    if (lastMedia == null) return
    val shown = lastMedia!!
    val visible = link != null

    var source by remember(shown) { mutableStateOf<PreviewSource?>(null) }
    var error by remember(shown) { mutableStateOf<String?>(null) }
    var fullscreen by remember(shown) { mutableStateOf(false) }

    // 预览引擎：ffplay（真·在线预览，直链边下边播）优先，JavaFX 作兜底。
    // ★ 必须在下面的 LaunchedEffect 之前定好并把它条件化：ffplay 模式不该再去建本地落盘会话
    //   （否则就又变成「下载完再播」，白白多下一份文件）。
    var ffplayMode by remember(shown) { mutableStateOf(resolveUseFfplay()) }

    // 引擎刚下载完成：立刻重新判定播放内核，让当前这次预览直接切到 ffplay 重新起播，
    // 而不是让用户关掉预览、重开一次才生效。
    val engineInstalled by EngineInstallPrompt.installed.collectAsState()
    LaunchedEffect(engineInstalled) {
        if (engineInstalled > 0 && !ffplayMode) {
            ffplayMode = resolveUseFfplay()
        }
    }

    // 「自动」模式下本机没有 ffplay → 首次预览时问一次是否下载引擎（选「暂不」继续用内置预览）。
    // 强制 FFPLAY 模式不在此提示：[FfplayPlayer] 自己有下载引导；强制内置 JavaFX 是用户明确选择。
    LaunchedEffect(shown) {
        if (!ffplayMode && SettingsRepository().previewEngine == SettingsRepository.PREVIEW_AUTO) {
            EngineInstallPrompt.ask(EngineInstallPrompt.Reason.PREVIEW)
        }
    }

    // 关闭预览时如果还停在全屏，先把窗口还原（否则留下一个没有内容的空白全屏窗口）
    LaunchedEffect(visible) {
        if (!visible) fullscreen = false
    }

    // 全屏开关跟随窗口状态：进入时记住原布局（最大化/普通），退出时还原
    DisposableEffect(fullscreen) {
        PreviewFullscreen.set(fullscreen)
        onDispose { if (fullscreen) PreviewFullscreen.set(false) }
    }

    BackHandler(enabled = visible) {
        if (fullscreen) fullscreen = false else onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(120))
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // 播放源与内容同生命周期：淡出动画结束、内容被移除时才释放
            // （HLS 会停止续写并删除临时文件；MP4 只注销代理登记）
            // ffplay 模式不需要本地会话，整段跳过（见 ffplayMode 的说明）。
            if (!ffplayMode) {
                LaunchedEffect(shown) {
                    // NonCancellable：用户在「准备中」关掉预览时，让准备动作跑完并拿到句柄再释放，
                    // 否则 HLS 会话（续写线程 + 临时文件）会悬空
                    val result = withContext(Dispatchers.IO + NonCancellable) {
                        runCatching { prepareSource(shown) }
                    }
                    if (!isActive) {
                        result.getOrNull()?.close()
                        return@LaunchedEffect
                    }
                    result.onSuccess { prepared ->
                        if (prepared == null) error = "该视频暂时无法在线预览" else source = prepared
                    }.onFailure {
                        Log.e(TAG, "预览源准备失败: ${it.message}")
                        error = "视频预览失败：${it.message ?: "未知错误"}"
                    }
                }
                DisposableEffect(shown) {
                    onDispose {
                        // 先释放再清状态：同一个媒体再次打开时不能复用已关闭的会话
                        source?.close()
                        source = null
                        error = null
                    }
                }
            }

            val prepared = source
            when {
                ffplayMode -> FfplayPlayer(
                    media = shown,
                    title = shown.fileName.ifBlank { shown.quality },
                    fullscreen = fullscreen,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onClose = onDismiss
                )

                error != null -> Text(
                    text = error!!,
                    color = Color(0xFFE6E6E6),
                    style = MaterialTheme.typography.bodyMedium
                )

                prepared == null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("正在准备预览…", color = Color(0xFFE6E6E6))
                }

                else -> VideoPlayer(
                    uri = prepared.uri,
                    title = shown.fileName.ifBlank { shown.quality },
                    fullscreen = fullscreen,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onClose = onDismiss
                )
            }

            // 关闭按钮固定在右上角（JFXPanel 是重量级组件，控件不能压在视频上，
            // 所以放在视频区域之外的表层；全屏时同样可见）
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭预览", tint = Color.White)
            }
        }
    }
}

// ---- 播放器本体 ----

@Composable
private fun VideoPlayer(
    uri: String,
    title: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    val panel = remember { JFXPanel() }
    val controller = remember(uri) { FxVideoController(panel) }

    DisposableEffect(uri) {
        controller.open(uri)
        onDispose { controller.dispose() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val error = controller.error
            if (error != null) {
                Text(error, color = Color(0xFFE6E6E6))
            } else {
                SwingPanel(
                    background = Color.Black,
                    factory = { panel },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        PlayerControls(
            controller = controller,
            title = title,
            fullscreen = fullscreen,
            onToggleFullscreen = onToggleFullscreen,
            onClose = onClose
        )
    }
}

@Composable
private fun PlayerControls(
    controller: FxVideoController,
    title: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    var speedMenu by remember { mutableStateOf(false) }

    val durationMs = controller.durationMs
    val seekable = durationMs > 0
    val positionMs = if (dragging) (dragValue * durationMs).toLong() else controller.positionMs
    val progress = if (seekable) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.togglePlay() }) {
                Icon(
                    imageVector = if (controller.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (controller.playing) "暂停" else "播放",
                    tint = Color.White
                )
            }
            Text(
                text = formatTime(positionMs),
                color = Color(0xFFDDDDDD),
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = progress,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    if (seekable) controller.seekTo((dragValue * durationMs).toLong())
                    dragging = false
                },
                enabled = seekable,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(
                text = if (seekable) formatTime(durationMs) else "--:--",
                color = Color(0xFFDDDDDD),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { controller.toggleMute() }) {
                Icon(
                    imageVector = if (controller.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    contentDescription = if (controller.muted) "取消静音" else "静音",
                    tint = Color.White
                )
            }
            // 倍速：不用 DropdownMenu —— 弹层会被重量级 JFXPanel 盖住（点开看起来「没反应」），
            // 改成在控制条内部展开一行按钮，同时把当前倍速显示在按钮上
            TextButton(onClick = { speedMenu = !speedMenu }) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = "播放速度",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = "${formatRate(controller.rate)}x",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    contentDescription = if (fullscreen) "退出全屏" else "全屏",
                    tint = Color.White
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭预览", tint = Color.White)
            }
        }
        // 倍速选项：展开在控制条内（在视频区域之外，不会被 JFXPanel 盖住）
        if (speedMenu) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "倍速",
                    color = Color(0xFF9E9E9E),
                    style = MaterialTheme.typography.labelSmall
                )
                SPEEDS.forEach { speed ->
                    val selected = kotlin.math.abs(controller.rate - speed) < 0.01
                    TextButton(
                        onClick = {
                            controller.applyRate(speed)
                            speedMenu = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "${formatRate(speed)}x",
                            color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFDDDDDD),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
        if (title.isNotBlank()) {
            Text(
                text = title,
                color = Color(0xFF9E9E9E),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

// ---- JavaFX 控制 ----

/**
 * 把 JavaFX 播放器的属性桥接成 Compose 状态：监听器都在 FX 线程触发，
 * 直接写 snapshot state（Compose 快照写入跨线程安全），避免轮询。
 */
private class FxVideoController(private val panel: JFXPanel) {
    var playing by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var muted by mutableStateOf(false)
    var rate by mutableStateOf(1.0)
    var error by mutableStateOf<String?>(null)

    private var player: MediaPlayer? = null
    private var mediaView: MediaView? = null
    private var sceneWidth = 0.0
    private var sceneHeight = 0.0
    private var disposed = false

    /** 用户最新拖动目标（去抖合并：只保留最后一个） */
    private var desiredMs: Long? = null

    /** 已下发、等待落地的目标；非 null 表示有一段 seek 在途 */
    private var activeTargetMs: Long? = null

    /** 本轮拖动开始时播放器是否在播放（决定 seek 完成后要不要恢复播放） */
    private var resumeAfterSeek = false

    /** 用户期望的倍速（seek 会把它打回 1.0，收尾时要重新对齐） */
    private var desiredRate = 1.0

    /** seek 落地超时兜底定时器 */
    private var seekTimeout: javafx.animation.PauseTransition? = null

    fun open(uri: String) {
        JavaFxRuntime.ensureInitialized()
        Platform.runLater {
            if (disposed) return@runLater
            runCatching {
                val loaded = Media(uri)
                val fxPlayer = MediaPlayer(loaded)
                player = fxPlayer

                val view = MediaView(fxPlayer)
                view.isPreserveRatio = true
                mediaView = view
                // Scene 尺寸不能直接 setWidth（包私有），只能按需重建 Scene 来跟随面板尺寸
                panel.background = java.awt.Color.BLACK
                panel.addComponentListener(object : ComponentAdapter() {
                    override fun componentResized(event: ComponentEvent) {
                        Platform.runLater { applyScene() }
                    }
                })

                loaded.errorProperty().addListener { _, _, err ->
                    if (err != null) {
                        Log.e(TAG, "媒体加载失败: ${err.type} ${err.message}", err)
                        error = "媒体加载失败：${err.message}"
                    }
                }
                fxPlayer.setOnError {
                    val e = fxPlayer.error
                    Log.e(TAG, "播放失败: ${e?.type} ${e?.message}", e)
                    error = "播放失败：${e?.message}"
                }
                fxPlayer.setOnReady {
                    durationMs = toMillis(loaded.duration)
                    fxPlayer.play()
                }
                fxPlayer.currentTimeProperty().addListener { _, _, time ->
                    val ms = toMillis(time)
                    positionMs = ms
                    // seek 落地判定：currentTime 逼近在途目标即认为到位
                    val target = activeTargetMs
                    if (target != null && kotlin.math.abs(ms - target) <= SEEK_TOLERANCE_MS) {
                        finishSeek(fxPlayer)
                    }
                }
                fxPlayer.statusProperty().addListener { _, _, status ->
                    playing = status == MediaPlayer.Status.PLAYING
                }
                fxPlayer.muteProperty().addListener { _, _, value -> muted = value }
                fxPlayer.rateProperty().addListener { _, _, value -> rate = value.toDouble() }

                durationMs = toMillis(loaded.duration)
                applyScene()
                fxPlayer.play()
            }.onFailure {
                Log.e(TAG, "JavaFX 播放器创建失败: ${it.message}")
                error = "播放器初始化失败：${it.message}"
            }
        }
    }

    /** 在 FX 线程按面板当前尺寸重建 Scene（尺寸没变就跳过） */
    private fun applyScene() {
        val view = mediaView ?: return
        val width = panel.width.toDouble()
        val height = panel.height.toDouble()
        if (width <= 0.0 || height <= 0.0) return
        if (width == sceneWidth && height == sceneHeight) return
        sceneWidth = width
        sceneHeight = height
        val root = StackPane(view).apply {
            background = Background(BackgroundFill(javafx.scene.paint.Color.BLACK, null, null))
        }
        val scene = Scene(root, width, height)
        view.fitWidthProperty().bind(scene.widthProperty())
        view.fitHeightProperty().bind(scene.heightProperty())
        panel.scene = scene
    }

    fun togglePlay() = post { p ->
        when (p.status) {
            MediaPlayer.Status.PLAYING -> p.pause()
            MediaPlayer.Status.PAUSED, MediaPlayer.Status.READY, MediaPlayer.Status.STOPPED -> p.play()
            else -> p.play()
        }
    }

    /**
     * 跳转到指定位置。
     *
     * 两个约束（实测结论）：
     * 1. Windows 上播放中 seek 不可靠，必须先 `pause` 再 `seek`（JDK 记录的稳定交互是「暂停 → 拖 → 播放」）；
     * 2. 连续快速拖动时**不能每拖一次就下发一次 seek**——jfxmedia 的音频时钟会被拖坏，
     *    听感上就是音频被降速（慢放变调）。所以这里把拖动去抖合并：新的拖动只更新
     *    [desiredMs]，等上一段 seek 落到目标位置（或超时兜底）后再下发下一段。
     *
     * 恢复播放前会把速率重新对齐到 [desiredRate]（seek 后 jfxmedia 可能把 rate 退回 1.0）。
     */
    fun seekTo(ms: Long) {
        Platform.runLater {
            val p = player ?: return@runLater
            desiredMs = ms
            if (activeTargetMs == null) {
                resumeAfterSeek = p.status == MediaPlayer.Status.PLAYING
                startNextSeek()
            }
        }
    }

    /** 下发当前待处理的目标（同一时刻只允许一段 seek 在途） */
    private fun startNextSeek() {
        val target = desiredMs ?: return
        val p = player ?: return
        desiredMs = null
        runCatching {
            activeTargetMs = target
            if (p.status == MediaPlayer.Status.PLAYING) p.pause()
            p.seek(javafx.util.Duration.millis(target.toDouble()))
            armSeekTimeout()
        }
    }

    /** seek 落地（currentTime 逼近目标）或超时兜底后的收尾 */
    private fun finishSeek(p: MediaPlayer) {
        activeTargetMs = null
        seekTimeout?.let { runCatching { it.stop() } }
        seekTimeout = null
        // seek 后 jfxmedia 可能把速率退回 1.0，恢复成用户期望的倍速
        if (p.rate != desiredRate) runCatching { p.rate = desiredRate }
        if (desiredMs != null) {
            // 拖动期间又产生了新目标：继续下一段，等全部合并完再恢复播放
            startNextSeek()
        } else if (resumeAfterSeek) {
            runCatching { p.play() }
        }
    }

    /** 兜底：到点还没等到 currentTime 逼近目标也照样收尾，避免卡在暂停 */
    private fun armSeekTimeout() {
        seekTimeout?.let { runCatching { it.stop() } }
        val timer = javafx.animation.PauseTransition(
            javafx.util.Duration.millis(SEEK_TIMEOUT_MS.toDouble())
        )
        timer.setOnFinished {
            val p = player
            if (p != null && activeTargetMs != null) finishSeek(p)
        }
        seekTimeout = timer
        timer.play()
    }

    /** 设置播放倍速（属性 [rate] 的 setter 与 setRate 同名会撞 JVM 签名，故用 applyRate） */
    fun applyRate(value: Double) = post { p ->
        desiredRate = value
        p.rate = value
        // rateProperty 的回调在 FX 线程，这里先写一次让按钮上的文案立刻变
        rate = value
    }

    fun toggleMute() = post { p -> p.isMute = !p.isMute }

    fun dispose() {
        disposed = true
        val fxPlayer = player
        player = null
        mediaView = null
        seekTimeout?.let { runCatching { it.stop() } }
        seekTimeout = null
        activeTargetMs = null
        desiredMs = null
        Platform.runLater {
            runCatching { fxPlayer?.stop() }
            runCatching { fxPlayer?.dispose() }
            runCatching { panel.scene = null }
        }
    }

    private fun post(block: (MediaPlayer) -> Unit) {
        Platform.runLater {
            val p = player ?: return@runLater
            runCatching { block(p) }
        }
    }
}

/** JavaFX toolkit 懒初始化：创建首个 JFXPanel 即完成启动（Compose 的 UI 线程就是 EDT） */
private object JavaFxRuntime {
    private var initialized = false

    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        // 已经在 EDT 上（Compose 的组合线程）→ 直接创建；否则转投 EDT。
        // 两种分支都不能反过来：在 EDT 里 invokeAndWait 会自锁。
        if (SwingUtilities.isEventDispatchThread()) {
            JFXPanel()
        } else {
            SwingUtilities.invokeAndWait { JFXPanel() }
        }
        Platform.setImplicitExit(false)
        initialized = true
    }
}

/**
 * 全屏开关：Main 把主窗口的 [WindowState] 与 AWT 窗口注入进来，播放器切窗口布局。
 *
 * 进入用 Compose 的 [WindowState.placement]；**退出必须走 AWT 的 `setFullScreenWindow(null)`**
 * （实测 `placement = Floating` 不会真正退出系统级全屏：窗口尺寸会变，但 `device.fullScreenWindow`
 * 仍非 null、`placement` 还会被同步回 `Fullscreen`，表现为「窗口是小了 / 内部 UI 仍是全屏比例」）。
 */
internal object PreviewFullscreen {
    var windowState: WindowState? = null

    /** 主窗口（退出全屏 / 还原窗口矩形都要真实窗口，只靠 WindowState 不可靠） */
    var window: java.awt.Window? = null

    /**
     * 是否由本对象打开着全屏。幂等标志：重复 set(true) / set(false) 只生效一次。
     * 必要，因为 `DisposableEffect(fullscreen)` 在 fullscreen 由 true→false 时会先后触发
     * 旧 effect 的 onDispose 与新 effect 的 body，`set(false)` 被连续调用两次——
     * 第二次会把还没恢复的最大化又打回 Floating，形成竞态（窗口最大化 / UI 不还原）。
     */
    private var active = false

    /** 进入全屏前 Compose 的布局与窗口矩形（最大化时矩形不用记），退出时原样还原 */
    private var previousPlacement = WindowPlacement.Floating
    private var previousBounds: java.awt.Rectangle? = null

    fun set(on: Boolean) {
        val win = window
        val state = windowState
        if (on) {
            if (active) return
            if (state?.placement == WindowPlacement.Fullscreen) return
            if (win != null && win.graphicsConfiguration?.device?.fullScreenWindow != null) return
            active = true
            // 以进入前的 Compose 布局判断是否最大化：系统级全屏会把 AWT 的 MAXIMIZED_BOTH 位清掉，
            // 用 extendedState 判定会拿到错误结果，从而把普通窗口错误地恢复成最大化
            previousPlacement = state?.placement ?: WindowPlacement.Floating
            previousBounds =
                if (previousPlacement == WindowPlacement.Maximized) null else win?.bounds
            state?.placement = WindowPlacement.Fullscreen
        } else {
            if (!active) return
            active = false
            val wasMaximized = previousPlacement == WindowPlacement.Maximized
            val bounds = previousBounds
            previousPlacement = WindowPlacement.Floating
            previousBounds = null
            val device = win?.graphicsConfiguration?.device
            if (device != null && device.fullScreenWindow != null) {
                // 退出系统级全屏：Compose 自身在 Win/Linux 进全屏也走这套 API，是唯一可靠的出口
                runCatching { device.setFullScreenWindow(null) }
            }
            if (wasMaximized) {
                // 恢复最大化要晚于退出全屏一拍：同一 tick 直接改 extendedState 会让窗口被异常放大
                // （实测 2062x1118 → 2578x1398）。放到下一轮 EDT 事件里再恢复即正常。
                val frame = win as? java.awt.Frame
                if (frame != null) {
                    SwingUtilities.invokeLater {
                        runCatching {
                            frame.extendedState = frame.extendedState or java.awt.Frame.MAXIMIZED_BOTH
                        }
                        refresh(frame)
                    }
                }
            } else if (win != null && bounds != null) {
                // 普通状态：显式还原矩形，不依赖 AWT 的「记忆普通尺寸」（可能被污染而变小）
                runCatching {
                    win.bounds = bounds
                    win.validate()
                }
                refresh(win)
            }
        }
    }

    /**
     * 退出系统级全屏后强制窗口重排并重建渲染表面。
     *
     * Windows 的 [java.awt.GraphicsDevice.setFullScreenWindow] 只把窗口矩形改回来，
     * Compose/Skiko 的渲染表面不一定会跟着重建，表现为「窗口已经缩小、界面仍按全屏比例显示」，
     * 手动拖一下窗口（触发一次真实 resize）即恢复。这里自动复刻那次拖拽：把高度临时 +1px，
     * 约 60ms 后再改回原矩形——分两拍是为了给 AWT/Skiko 留出处理第一次 WM_SIZE 的时间，
     * 否则同一轮 EDT 内两次 setBounds 会被合并、Skiko 认为尺寸没变而跳过表面重建。
     */
    private fun refresh(win: java.awt.Window) {
        SwingUtilities.invokeLater {
            runCatching {
                val frame = win as? java.awt.Frame
                val maximized = frame != null &&
                    (frame.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != 0
                val b = win.bounds
                if (maximized) {
                    // 最大化窗口本身就会经历一次真实尺寸变化，只需重绘，避免抖动把它打回 Floating
                    win.validate()
                    win.repaint()
                } else {
                    win.bounds = java.awt.Rectangle(b.x, b.y, b.width, b.height + 1)
                    val timer = javax.swing.Timer(60) { evt ->
                        (evt.source as javax.swing.Timer).stop()
                        runCatching {
                            win.bounds = b
                            win.validate()
                            win.repaint()
                        }
                    }
                    timer.isRepeats = false
                    timer.start()
                }
            }
        }
    }
}

/** 播放源：本地增长文件会话（单文件 MP4 或 HLS），播放器读 `file:` URI */
private sealed class PreviewSource {
    abstract val uri: String

    fun close() {
        when (this) {
            is Local -> session.cancel()
            is Hls -> session.cancel()
        }
    }

    /** 单文件 MP4：下载到本地临时文件后边下边播 */
    class Local(val session: Mp4PreviewSession) : PreviewSource() {
        override val uri: String get() = session.uri
    }

    /** HLS：重封装成本地增长文件 */
    class Hls(val session: HlsPreviewSession) : PreviewSource() {
        override val uri: String get() = session.uri
    }
}

/**
 * 阻塞解析播放源（调用方在 IO 线程执行）；null 表示不可预览
 */
private fun prepareSource(media: VideoMedia): PreviewSource? {
    // 预览直链优先（哔哩哔哩 DASH：主直链是 .m4s 分轨，JavaFX 播不了，解析时另给了一份合好的 mp4）
    if (media.previewUrl.isNotBlank()) {
        val session = Mp4PreviewSession.prepare(media.previewUrl, media.headers) ?: return null
        return PreviewSource.Local(session)
    }
    if (media.isHls) {
        val session = HlsPreviewSession.prepare(media.url, media.headers) ?: return null
        return PreviewSource.Hls(session)
    }
    // 分轨流（音视频分离）没有预览直链时无法在线播放：JavaFX 只能吃单文件
    if (media.audioUrl.isNotBlank()) return null
    val session = Mp4PreviewSession.prepare(media.url, media.headers) ?: return null
    return PreviewSource.Local(session)
}

/**
 * 是否走 ffplay（真·在线预览）。
 *
 * - 设置里选了「内置 JavaFX」→ 一律 false，尊重用户选择；
 * - 选了「FFplay」→ 一律 true（本机没有 ffplay 时由 [FfplayPlayer] 展示下载引导，不偷偷回退）；
 * - 默认「自动」→ 检测到 ffplay 才走 ffplay，否则回退内置 JavaFX。
 */
private fun resolveUseFfplay(): Boolean = when (
    SettingsRepository().previewEngine
) {
    SettingsRepository.PREVIEW_JAVA_FX -> false
    SettingsRepository.PREVIEW_FFPLAY -> true
    else -> FfplayManager.locate() != null
}

private const val TAG = "YunX-VideoPreview"

/** seek 落地判定容差（毫秒）：currentTime 与目标相差这么多以内即视为到位 */
private const val SEEK_TOLERANCE_MS = 300L

/** seek 落地兜底超时（毫秒）：到点仍未逼近目标也照样收尾 */
private const val SEEK_TIMEOUT_MS = 1500L

private val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

private fun toMillis(duration: javafx.util.Duration?): Long {
    if (duration == null) return 0L
    val ms = duration.toMillis()
    return if (ms.isFinite() && ms > 0) ms.toLong() else 0L
}

/** 倍速文案：1.0 → "1"，1.25 → "1.25" */
private fun formatRate(rate: Double): String =
    if (rate == rate.toLong().toDouble()) rate.toLong().toString() else rate.toString()

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
