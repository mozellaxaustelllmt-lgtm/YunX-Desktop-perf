package com.yunx.app.data.security

import com.yunx.app.AppContext
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 凭证加解密接口（与原 Android 版一致）。
 */
internal interface CredentialCipher {
    fun encrypt(plaintext: String, purpose: String): String
    fun decrypt(stored: String, purpose: String): String
    fun isEncrypted(stored: String): Boolean
}

/**
 * 桌面版凭证加密：AES-256-GCM。
 * 主密钥为首次启动生成的随机 32 字节，保存在 `<数据库目录>/credential.key`；
 * 密钥本身按「设置 → 储存目录 → 数据库加密等级」的档位包裹（明文 / 本机派生密钥 / DPAPI，见 [DbKeyStore]）。
 * 输出格式与原 Android 版完全相同："yunx:v1:<iv Base64>:<ct Base64>"，AAD = purpose。
 */
internal class FileCredentialCipher : CredentialCipher {

    private val key: SecretKeySpec by lazy {
        val keyFile = DbKeyStore.keyFile(AppContext.dbDir, AppContext.dataDir)
        val level = DbEncryptionLevel.fromId(SettingsRepository().dbEncryptionLevel)
        val bytes = loadKeyWithRetry(keyFile, level)
        require(bytes.size == 32) { "invalid credential key file" }
        SecretKeySpec(bytes, "AES")
    }

    /**
     * 读取主密钥；**只有确认密钥彻底不可恢复时才换新密钥**（对齐上游 #179「凭证加固」）。
     *
     * 旧实现只要 `loadOrCreate` 抛异常就改名 + 新建：密钥文件被杀毒软件占用、DPAPI 一时失败、
     * 机器标识临时读不到……都会触发，于是库里的全部凭证永久解不开 —— 这正是「账号全丢」事故。
     * 现在：先原样重试一次；仍失败时区分
     * - **暂时不可用**（IO 失败 / 机器标识临时缺失）：直接向上抛，不换密钥、不动文件，下次读取自动重试；
     * - **确实解不开**（换机器 / 换 Windows 用户 / 封装格式损坏）：旧密钥本来就已经废了，才换新。
     */
    private fun loadKeyWithRetry(keyFile: File, level: DbEncryptionLevel): ByteArray {
        runCatching { DbKeyStore.loadOrCreate(keyFile, level) }.getOrNull()?.let { return it }
        val retry = runCatching { DbKeyStore.loadOrCreate(keyFile, level) }
        retry.getOrNull()?.let { return it }

        val error = retry.exceptionOrNull() ?: IllegalStateException("credential key unavailable")
        if (isTransient(error)) throw error

        Log.e(TAG, "凭证密钥确实解不开（${error.message}），改用新密钥：旧文件保留为 ${keyFile.name}.unreadable")
        runCatching {
            keyFile.renameTo(File(keyFile.parentFile, "${keyFile.name}.unreadable"))
        }
        return DbKeyStore.loadOrCreate(keyFile, level)
    }

    /** 暂时性失败（读不到 / 被占用 / 机器标识临时缺失）：本次失败即可，绝不能据此换密钥 */
    private fun isTransient(error: Throwable): Boolean =
        error is DbKeyStore.MachineFingerprintUnavailable || error is java.io.IOException

    override fun encrypt(plaintext: String, purpose: String): String {
        // 诊断日志只记「用途 + 明文长度」，绝不打明文/密文本身
        DiagnosticLog.log(
            DiagnosticLog.CRYPTO, "encrypt", size = plaintext.toByteArray(Charsets.UTF_8).size.toLong(),
            summary = "purpose=$purpose"
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val ctB64 = Base64.getEncoder().encodeToString(ct)
        return "$PREFIX$ivB64:$ctB64"
    }

    override fun decrypt(stored: String, purpose: String): String {
        // 诊断日志：解密是「密钥失效」类问题的第一现场（只记用途与长度，不记内容）
        DiagnosticLog.log(
            DiagnosticLog.CRYPTO, "decrypt", size = stored.length.toLong(),
            summary = "purpose=$purpose"
        )
        require(stored.startsWith(PREFIX)) { "stored value is not encrypted" }
        val parts = stored.removePrefix(PREFIX).split(":")
        require(parts.size == 2) { "malformed encrypted value" }
        val iv = Base64.getDecoder().decode(parts[0])
        val ct = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    override fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    private companion object {
        const val PREFIX = "yunx:v1:"
        const val TAG = "YunX-Crypto"
    }
}
