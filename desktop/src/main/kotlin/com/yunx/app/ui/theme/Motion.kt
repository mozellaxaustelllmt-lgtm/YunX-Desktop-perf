package com.yunx.app.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import com.yunx.app.data.prefs.SettingsRepository

/**
 * 动效降级开关。
 *
 * `reducedMotion = 软件渲染 || 用户开关「减少动画」`：
 * - 软件渲染：`RenderApiChooser` 在老显卡上把 skiko 切到 SOFTWARE，每帧 CPU 光栅化，
 *   位移动画/列表增删跟不动，反而更卡（由 [install] 在启动时写入）；
 * - 用户开关：设置 → 主题与外观 → 动效，面向晕动症用户或「机器老但没触发 SOFTWARE」。
 *
 * 两者的值都是 Compose 可观察状态：改开关后已经挂着的动画入口会在下一次组合时读到新值。
 */
object MotionPolicy {

    /** 当前是否软件渲染（启动时由 Main.kt 写入，运行中不变） */
    @Volatile
    var softwareRendering: Boolean = false
        private set

    /** 用户「减少动画」开关（设置 → 动效） */
    var userReducedMotion by mutableStateOf(false)
        private set

    /** 是否降级动效（只读口径，UI 层统一读这一个） */
    val reducedMotion: Boolean
        get() = softwareRendering || userReducedMotion

    /** 启动时装配：写入软件渲染判定 + 读取用户开关 */
    fun install(softwareRendering: Boolean) {
        this.softwareRendering = softwareRendering
        userReducedMotion = runCatching { SettingsRepository().reduceMotion }.getOrDefault(false)
    }

    /** 用户切换开关：立即生效并持久化 */
    fun updateUserReducedMotion(value: Boolean) {
        userReducedMotion = value
        runCatching { SettingsRepository().reduceMotion = value }
    }
}

/**
 * 动画入口统一收口：所有「装饰性」动效都从这里取时长 / 转场，降级时返回 0。
 *
 * 进度、加载文字这类信息反馈不属于装饰，不经过这里。
 */
object Motion {

    /** 动画时长（毫秒）：降级时 0（等价直接切换） */
    fun duration(baseMs: Int): Int = if (MotionPolicy.reducedMotion) 0 else baseMs

    /**
     * 通用淡入淡出转场（页面切换 / 目录切换）；降级时 0ms 直接切换。
     *
     * 注意：`transitionSpec` 不是 @Composable lambda，所以这里也不能是 @Composable；
     * 每次切换开始时都会重新求值，改开关后下一次切页即生效。
     */
    fun fade(baseInMs: Int = 200, baseOutMs: Int = 140): ContentTransform =
        fadeIn(tween(duration(baseInMs))) togetherWith fadeOut(tween(duration(baseOutMs)))

    /** 主 Tab 切换：正常为横向位移 + 淡入，降级时只做极短淡入（无位移） */
    fun tabSwitch(forward: Boolean): ContentTransform {
        if (MotionPolicy.reducedMotion) {
            return fadeIn(tween(duration(80))) togetherWith fadeOut(tween(0))
        }
        return if (forward) {
            (fadeIn(tween(duration(220))) + slideInHorizontally(tween(duration(220))) { it / 4 })
                .togetherWith(fadeOut(tween(duration(160))) + slideOutHorizontally(tween(duration(160))) { -it / 4 })
        } else {
            (fadeIn(tween(duration(220))) + slideInHorizontally(tween(duration(220))) { -it / 4 })
                .togetherWith(fadeOut(tween(duration(160))) + slideOutHorizontally(tween(duration(160))) { it / 4 })
        }
    }
}

/**
 * 列表增删动画：降级时不套 `animateItem`（软件渲染下这项最吃 CPU）。
 *
 * `animateItem` 是 `LazyItemScope` / `LazyGridItemScope` 的成员扩展，所以这里也只能声明成
 * 对应 scope 的扩展，**调用时不要写 `Modifier.` 前缀**：在 `LazyColumn { items {} }` /
 * `LazyVerticalGrid { items {} }` 里直接 `animateItemIfAllowed()`。
 */
@Composable
fun LazyItemScope.animateItemIfAllowed(): Modifier =
    if (MotionPolicy.reducedMotion) Modifier else Modifier.animateItem()

/** 网格版（LazyVerticalGrid）；见上方说明 */
@Composable
fun LazyGridItemScope.animateItemIfAllowed(): Modifier =
    if (MotionPolicy.reducedMotion) Modifier else Modifier.animateItem()

/**
 * 空指示（无点击水波纹）：降级动效时由主题顶层提供，
 * 让 `clickable` / `selectable` 这类基础组件直接失去涟漪，而不是逐个组件改。
 * （Material3 的 Button / IconButton 走 `LocalRippleConfiguration`，见 Theme.kt）
 */
internal object NoIndication : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode()

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}

/** 只画内容、不画任何指示的 Modifier.Node */
private class NoIndicationNode : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        drawContent()
    }
}
