package com.yunx.app.ui.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.ui.jcef.JcefLoginPane
import com.yunx.app.ui.viewmodel.VideoAccountViewModel

/**
 * 视频平台登录页。
 *
 * 与上游（Android WebView 版 675 行）的差异：
 * - 桌面 JCEF 就是真正的桌面 Chromium，UA / client hints / 第三方 Cookie / viewport 全都是原生行为，
 *   上游为移动 WebView 打的补丁（对齐 client hints、强制桌面宽度、放开第三方 Cookie）在这里不需要；
 * - 因此这里只做两件事：按平台给出「登录成功的 Cookie 键」，再复用已有的 [JcefLoginPane] 轮询保存；
 * - 没有网页登录页的平台（`manualCookieOnly`）直接落到粘贴 Cookie 页。
 *
 * 自动检测只代表「Cookie 里出现了这些键」；是否真的登录由各平台解析器的 `validateCookie`
 * 网络校验决定 —— 小红书 / Pixiv 在未登录时也会下发游客 Cookie，校验失败时页面会提示并留在原地。
 *
 * @param newAccount true = 「添加账号」：这次登录落成新的一行，不覆盖当前账号
 */
@Composable
fun VideoLoginScreen(
    platform: VideoPlatform,
    newAccount: Boolean,
    viewModel: VideoAccountViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    var pasteMode by remember(platform) { mutableStateOf(platform.manualCookieOnly) }

    val save: suspend (String) -> Boolean = { cookie ->
        viewModel.save(platform, cookie, newAccount) != null
    }

    val hasWebLogin = platform.loginUrl.isNotBlank() && !platform.manualCookieOnly
    if (!hasWebLogin || pasteMode) {
        CookieLoginContent(
            title = "${platform.displayName}登录",
            loginUrl = platform.loginUrl.ifBlank { "https://${platform.cookieDomain}" },
            platform = platform.displayName,
            cookieRequirement = "包含 ${cookieKeys(platform).joinToString(" / ")} 的完整 Cookie",
            validityHint = platform.loginBenefit,
            onSave = save,
            onBack = onBack,
            onSaved = onSaved
        )
        return
    }

    JcefLoginPane(
        loginUrl = platform.loginUrl,
        domains = listOf(platform.cookieDomain),
        requiredKeys = cookieKeys(platform),
        platform = platform.displayName,
        onSave = save,
        onBack = onBack,
        onSaved = onSaved,
        onSwitchToPaste = { pasteMode = true }
    )
}

/**
 * 各平台「登录成功后才会出现」的 Cookie 键。
 *
 * 只用于登录页的自动检测（决定何时亮起「保存登录」按钮），不参与解析；
 * 解析器真正判定登录态仍走 `validateCookie` 的网络校验。
 */
private fun cookieKeys(platform: VideoPlatform): List<String> = when (platform) {
    VideoPlatform.BILIBILI -> listOf("SESSDATA")
    VideoPlatform.XHS -> listOf("web_session")
    VideoPlatform.PIXIV -> listOf("PHPSESSID")
    else -> emptyList()
}
