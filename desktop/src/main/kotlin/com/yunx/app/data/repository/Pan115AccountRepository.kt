package com.yunx.app.data.repository

import com.yunx.app.data.db.Pan115AccountDao
import com.yunx.app.data.db.Pan115AccountEntity
import com.yunx.app.data.db.resolveAccountSlot
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.Pan115Api
import com.yunx.app.data.network.Pan115Constants
import com.yunx.app.util.CookieCleaner
import kotlinx.coroutines.flow.Flow

/**
 * 115 网盘账号仓库：网页登录 Cookie（115.com 域）→ 落库。
 *
 * 凭证就是整串 Cookie（UID/CID/SEID/KID）：
 * 个人盘、转存、创建分享、下载直链全都靠它，所以不做字段级拆解，原样存原样用。
 */
class Pan115AccountRepository(
    private val dao: Pan115AccountDao,
    private val api: Pan115Api
) {

    fun observeAccount(): Flow<Pan115AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): Pan115AccountEntity? = dao.getAccount()

    /** 该平台全部账号可用的凭证（主账号在前）：多账号下载（实验性 C1）轮换取链用 */
    suspend fun allCredentials(): List<String> =
        dao.getAccounts().map { it.cookie }.filter { it.isNotBlank() }

    /**
     * 保存网页登录 Cookie：先用 `/user/info` 确认登录态有效并取昵称（脱敏手机号），成功才落库。
     * @param cookie 内嵌浏览器登录 115.com 后取到的完整 Cookie
     */
    suspend fun saveCookie(cookie: String, newAccount: Boolean = false): Boolean {
        val value = cookie.trim()
        if (!Pan115Constants.hasLoginCookie(value)) return false
        val nickname = api.fetchNickname(value) ?: return false
        val slot = resolveAccountSlot(DownloadPlatform.PAN115, dao.getAccount(), newAccount)
        dao.upsert(
            Pan115AccountEntity(
                id = slot.id,
                cookie = value,
                nickname = nickname.ifBlank { "115用户" },
                isPrimary = slot.isPrimary,
                remark = slot.remark
            )
        )
        return true
    }

    /** 校验当前 Cookie 是否仍有效（失效自动清库，下次重新登录；115 无 refresh 接口） */
    suspend fun validate(): Boolean {
        val account = dao.getAccount() ?: return false
        val ok = api.fetchNickname(account.cookie) != null
        if (!ok) dao.clear()
        return ok
    }

    /** 退出登录：清库 + 清理内嵌浏览器登录 Cookie（不清理的话再进登录页会被自动登录登回旧账号） */
    suspend fun logout() {
        CookieCleaner.clearCookiesForDomains(listOf("115.com", "115cdn.com"))
        dao.clear()
    }
}
