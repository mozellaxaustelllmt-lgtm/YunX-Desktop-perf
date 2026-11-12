package com.yunx.app.util

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookieManager
import kotlin.coroutines.resume

/**
 * 退出登录时清除 JCEF 浏览器中指定网盘域名的 Cookie。
 *
 * 所有 AccountRepository.logout 都会调用本工具，避免再次登录时被
 * JCEF 已保留的会话 Cookie 自动登录。JCEF 未初始化（用户从未打开过登录页）时静默忽略。
 *
 * ★ 线程约束：CEF 的 Cookie 回调由 AWT EDT 上的 timer 泵动（见 JcefHolder），
 *   **绝不能在 EDT 上阻塞等待回调**，否则回调永远不回来、界面还会卡住。
 *   所以这里提供两种用法：
 *   - [clearCookiesForDomains]：发出去就返回（退出登录等无需等待的场景）；
 *   - [clearCookiesAndWait]：挂起等待完成（「添加账号」前必须先清干净旧登录态，
 *     否则网页登录页会直接把老账号再登回来）。
 */
object CookieCleaner {

    /**
     * 清除给定域名列表下的所有 Cookie（**不等待**完成）。
     * @param domains 域名片段列表，如 ["pan.baidu.com", "baidu.com"]；
     *                cookie 的 domain 命中任一（忽略前导点、忽略大小写）即清除。
     */
    fun clearCookiesForDomains(domains: List<String>) {
        if (domains.isEmpty()) return
        runCatching {
            visitAndDelete(domains) {}
        }
        Unit
    }

    /**
     * 清除并**等待**删除完成（最多 [timeoutMs]）；供「添加账号」这类必须先清干净的场景使用。
     * 调用方在协程里调用即可，不会阻塞 EDT。
     */
    suspend fun clearCookiesAndWait(domains: List<String>, timeoutMs: Long = 1500L) {
        if (domains.isEmpty()) return
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    visitAndDelete(domains) { if (cont.isActive) cont.resume(Unit) }
                }.onFailure { if (cont.isActive) cont.resume(Unit) }
            }
        }
    }

    /** 遍历全部 cookie（命中即标记删除），完成后回调 [onFinished]（可能在 CEF IO 线程） */
    private fun visitAndDelete(domains: List<String>, onFinished: () -> Unit) {
        val manager = CefCookieManager.getGlobalManager() ?: run {
            onFinished()
            return
        }
        manager.visitAllCookies(object : CefCookieVisitor {
            override fun visit(
                cookie: org.cef.network.CefCookie,
                count: Int,
                total: Int,
                delete: BoolRef
            ): Boolean {
                val cookieDomain = (cookie.domain ?: "").removePrefix(".").lowercase()
                val hit = domains.any { d ->
                    val base = d.lowercase()
                    cookieDomain == base || cookieDomain.endsWith(".$base")
                }
                if (hit) {
                    // 设置 delete 出参为 true，CEF 会在遍历结束后删除该 cookie
                    runCatching { delete.set(true) }
                }
                // 最后一条（count+1==total）后 CEF 会以 count=total 再回调一次表示结束
                if (count + 1 >= total) onFinished()
                return true
            }
        })
        // 兜底：按 URL 批量删除，http/https 都覆盖，cookieName=null 表示该 URL 下全部
        domains.forEach { d ->
            runCatching { manager.deleteCookies("https://$d", null) }
            runCatching { manager.deleteCookies("http://$d", null) }
        }
    }
}
