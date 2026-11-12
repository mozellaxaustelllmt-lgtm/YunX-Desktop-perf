package com.yunx.app.data.network

import com.yunx.app.AppContext
import com.yunx.app.data.security.FileCredentialCipher
import com.yunx.app.util.Log

/**
 * GitHub Token 加密存储。
 *
 * 安全说明：
 * - Token 仅用于提升 GitHub API 限额（未认证 60 次/小时/IP，认证后 5000 次/小时）；
 * - **严禁明文存储**：统一经 [FileCredentialCipher] AES-GCM 加密（密钥不可导出）后落盘；
 * - **严禁输出到日志**，**严禁在 UI 中回显完整 Token**（输入框用 PasswordVisualTransformation）。
 *
 * 桌面版以 [AppContext.miscPrefs]（java.util.prefs）替代 Android SharedPreferences，键名保持语义一致。
 */
object GitHubTokenStore {

    private const val KEY_TOKEN = "github_token_encrypted"
    private const val PURPOSE = "github_token"

    private val cipher = FileCredentialCipher()

    /** 读取已加密 Token 并解密；未配置或**当前解不开**返回 null */
    fun getToken(): String? {
        val stored = AppContext.miscPrefs.get(KEY_TOKEN, null) ?: return null
        return runCatching { cipher.decrypt(stored, PURPOSE) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        // ★ 解密失败**不要删**（对齐上游 #179「凭证加固」）：密钥一时读不到（文件被占用等）就删掉，
        //   等于把用户配置的 Token 永久丢掉。本次返回 null（界面按「未配置」处理），
        //   下次读取能解开时自动恢复。
    }

    /** 设置/更新 Token；传 null 或空串则清除。返回是否确实写入成功 */
    fun setToken(token: String?): Boolean {
        val prefs = AppContext.miscPrefs
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.remove(KEY_TOKEN)
            return true
        }
        return runCatching {
            prefs.put(KEY_TOKEN, cipher.encrypt(trimmed, PURPOSE))
            true
        }.getOrElse {
            // 加密失败（密钥暂时不可用）：保留原值，不要覆盖成空
            Log.w("YunX-GitHub", "Token 加密失败，保留原值：${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    /**
     * 是否「已配置**且当前能读出来**」。
     *
     * ★ 刻意解密一次：密文读不出来时若仍报「已配置」，界面会显示已登录 GitHub，
     *   实际每个请求都在静默走匿名限额（60 次/小时）。调用方应 remember 结果、别每帧调。
     */
    fun hasToken(): Boolean = getToken() != null
}
