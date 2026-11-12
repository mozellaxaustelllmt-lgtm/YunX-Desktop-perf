package com.yunx.app.data.db

import com.yunx.app.data.security.CredentialCipher
import com.yunx.app.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * DAO decorators: plaintext is exposed only in memory; every database write is encrypted.
 *
 * 性能修复（v1.2.6）：解密/加密全部切到 `Dispatchers.IO`。
 * 之前的实现里 Room suspend 查询返回后，`decrypt*` 在**调用方协程上下文**（viewModelScope = 主线程）执行
 * AndroidKeyStore（Binder IPC，单次 30~75ms）→ 网盘页下拉刷新时 6 平台并发把主线程占死 400~500ms → 全应用掉帧。
 */
internal object SecureAccountDaos {
    fun quark(raw: QuarkAccountDao, cipher: CredentialCipher): QuarkAccountDao = object : QuarkAccountDao {
        override fun observeAccount(): Flow<QuarkAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptQuark(raw, cipher, it) }
        }
        override suspend fun upsert(account: QuarkAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptQuark(cipher, account))
        }
        override fun observeAccounts(): Flow<List<QuarkAccountEntity>> = raw.observeAccounts().map { list ->
            list.mapNotNull { decryptQuark(raw, cipher, it) }
        }
        override suspend fun getAccount(): QuarkAccountEntity? = raw.getAccount()?.let { decryptQuark(raw, cipher, it) }
        override suspend fun getAccounts(): List<QuarkAccountEntity> =
            raw.getAccounts().mapNotNull { decryptQuark(raw, cipher, it) }
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun uc(raw: UCAccountDao, cipher: CredentialCipher): UCAccountDao = object : UCAccountDao {
        override fun observeAccount(): Flow<UCAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptUc(raw, cipher, it) }
        }
        override suspend fun upsert(account: UCAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptUc(cipher, account))
        }
        override fun observeAccounts(): Flow<List<UCAccountEntity>> = raw.observeAccounts().map { list ->
            list.mapNotNull { decryptUc(raw, cipher, it) }
        }
        override suspend fun getAccount(): UCAccountEntity? = raw.getAccount()?.let { decryptUc(raw, cipher, it) }
        override suspend fun getAccounts(): List<UCAccountEntity> =
            raw.getAccounts().mapNotNull { decryptUc(raw, cipher, it) }
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun baidu(raw: BaiduAccountDao, cipher: CredentialCipher): BaiduAccountDao = object : BaiduAccountDao {
        override fun observeAccount(): Flow<BaiduAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptBaidu(raw, cipher, it) }
        }
        override suspend fun upsert(account: BaiduAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptBaidu(cipher, account))
        }
        override fun observeAccounts(): Flow<List<BaiduAccountEntity>> = raw.observeAccounts().map { list ->
            list.mapNotNull { decryptBaidu(raw, cipher, it) }
        }
        override suspend fun getAccount(): BaiduAccountEntity? = raw.getAccount()?.let { decryptBaidu(raw, cipher, it) }
        override suspend fun getAccounts(): List<BaiduAccountEntity> =
            raw.getAccounts().mapNotNull { decryptBaidu(raw, cipher, it) }
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun c139(raw: C139AccountDao, cipher: CredentialCipher): C139AccountDao = object : C139AccountDao {
        override fun observeAccount(): Flow<C139AccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptC139(raw, cipher, it) }
        }
        override suspend fun upsert(account: C139AccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptC139(cipher, account))
        }
        override fun observeAccounts(): Flow<List<C139AccountEntity>> = raw.observeAccounts().map { list ->
            list.mapNotNull { decryptC139(raw, cipher, it) }
        }
        override suspend fun getAccount(): C139AccountEntity? = raw.getAccount()?.let { decryptC139(raw, cipher, it) }
        override suspend fun getAccounts(): List<C139AccountEntity> =
            raw.getAccounts().mapNotNull { decryptC139(raw, cipher, it) }
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun pan123(raw: Pan123AccountDao, cipher: CredentialCipher): Pan123AccountDao = object : Pan123AccountDao {
        override fun observeAccount(): Flow<Pan123AccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptPan123(raw, cipher, it) }
        }
        override suspend fun upsert(account: Pan123AccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptPan123(cipher, account))
        }
        override fun observeAccounts(): Flow<List<Pan123AccountEntity>> = raw.observeAccounts().map { list ->
            list.mapNotNull { decryptPan123(raw, cipher, it) }
        }
        override suspend fun getAccount(): Pan123AccountEntity? = raw.getAccount()?.let { decryptPan123(raw, cipher, it) }
        override suspend fun getAccounts(): List<Pan123AccountEntity> =
            raw.getAccounts().mapNotNull { decryptPan123(raw, cipher, it) }
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun pan115(raw: Pan115AccountDao, cipher: CredentialCipher): Pan115AccountDao = object : Pan115AccountDao {
        override fun observeAccount(): Flow<Pan115AccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptPan115(raw, cipher, it) }
        }
        override suspend fun upsert(account: Pan115AccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptPan115(cipher, account))
        }
        override fun observeAccounts(): Flow<List<Pan115AccountEntity>> = raw.observeAccounts().map { list ->
            list.mapNotNull { decryptPan115(raw, cipher, it) }
        }
        override suspend fun getAccount(): Pan115AccountEntity? = raw.getAccount()?.let { decryptPan115(raw, cipher, it) }
        override suspend fun getAccounts(): List<Pan115AccountEntity> =
            raw.getAccounts().mapNotNull { decryptPan115(raw, cipher, it) }
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun guangya(raw: GuangYaAccountDao, cipher: CredentialCipher): GuangYaAccountDao = object : GuangYaAccountDao {
        override fun observeAccount(): Flow<GuangYaAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptGuangYa(raw, cipher, it) }
        }
        override suspend fun upsert(account: GuangYaAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptGuangYa(cipher, account))
        }
        override fun observeAccounts(): Flow<List<GuangYaAccountEntity>> = raw.observeAccounts().map { list ->
            list.mapNotNull { decryptGuangYa(raw, cipher, it) }
        }
        override suspend fun getAccount(): GuangYaAccountEntity? = raw.getAccount()?.let { decryptGuangYa(raw, cipher, it) }
        override suspend fun getAccounts(): List<GuangYaAccountEntity> =
            raw.getAccounts().mapNotNull { decryptGuangYa(raw, cipher, it) }
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun ilanzou(raw: ILanzouAccountDao, cipher: CredentialCipher): ILanzouAccountDao = object : ILanzouAccountDao {
        override fun observeAccount(): Flow<ILanzouAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptILanzou(raw, cipher, it) }
        }
        override suspend fun upsert(account: ILanzouAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptILanzou(cipher, account))
        }
        override fun observeAccounts(): Flow<List<ILanzouAccountEntity>> = raw.observeAccounts().map { list ->
            list.mapNotNull { decryptILanzou(raw, cipher, it) }
        }
        override suspend fun getAccount(): ILanzouAccountEntity? = raw.getAccount()?.let { decryptILanzou(raw, cipher, it) }
        override suspend fun getAccounts(): List<ILanzouAccountEntity> =
            raw.getAccounts().mapNotNull { decryptILanzou(raw, cipher, it) }
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun lanzou(raw: LanzouAccountDao, cipher: CredentialCipher): LanzouAccountDao = object : LanzouAccountDao {
        override fun observeAccount(): Flow<LanzouAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptLanzou(raw, cipher, it) }
        }
        override suspend fun upsert(account: LanzouAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptLanzou(cipher, account))
        }
        override fun observeAccounts(): Flow<List<LanzouAccountEntity>> = raw.observeAccounts().map { list ->
            list.mapNotNull { decryptLanzou(raw, cipher, it) }
        }
        override suspend fun getAccount(): LanzouAccountEntity? = raw.getAccount()?.let { decryptLanzou(raw, cipher, it) }
        override suspend fun getAccounts(): List<LanzouAccountEntity> =
            raw.getAccounts().mapNotNull { decryptLanzou(raw, cipher, it) }
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun xunlei(raw: XunleiAccountDao, cipher: CredentialCipher): XunleiAccountDao = object : XunleiAccountDao {
        override fun observeAccount(): Flow<XunleiAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptXunlei(raw, cipher, it) }
        }
        override suspend fun upsert(account: XunleiAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptXunlei(cipher, account))
        }
        override fun observeAccounts(): Flow<List<XunleiAccountEntity>> = raw.observeAccounts().map { list ->
            list.mapNotNull { decryptXunlei(raw, cipher, it) }
        }
        override suspend fun getAccount(): XunleiAccountEntity? = raw.getAccount()?.let { decryptXunlei(raw, cipher, it) }
        override suspend fun getAccounts(): List<XunleiAccountEntity> =
            raw.getAccounts().mapNotNull { decryptXunlei(raw, cipher, it) }
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    private suspend fun decryptQuark(raw: QuarkAccountDao, cipher: CredentialCipher, stored: QuarkAccountEntity): QuarkAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrNull {
                val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "quark.cookie"))
                if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptQuark(cipher, plain))
                plain
            }
        }

    private suspend fun decryptUc(raw: UCAccountDao, cipher: CredentialCipher, stored: UCAccountEntity): UCAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrNull {
                val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "uc.cookie"))
                if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptUc(cipher, plain))
                plain
            }
        }

    private suspend fun decryptBaidu(raw: BaiduAccountDao, cipher: CredentialCipher, stored: BaiduAccountEntity): BaiduAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrNull {
                val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "baidu.cookie"))
                if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptBaidu(cipher, plain))
                plain
            }
        }

    private suspend fun decryptC139(raw: C139AccountDao, cipher: CredentialCipher, stored: C139AccountEntity): C139AccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrNull {
                val plain = stored.copy(
                    cookie = cipher.decrypt(stored.cookie, "c139.cookie"),
                    authorization = cipher.decrypt(stored.authorization, "c139.authorization")
                )
                if (!cipher.isEncrypted(stored.cookie) || !cipher.isEncrypted(stored.authorization)) {
                    raw.upsert(encryptC139(cipher, plain))
                }
                plain
            }
        }

    private suspend fun decryptPan123(raw: Pan123AccountDao, cipher: CredentialCipher, stored: Pan123AccountEntity): Pan123AccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrNull {
                val plain = stored.copy(accessToken = cipher.decrypt(stored.accessToken, "pan123.accessToken"))
                if (!cipher.isEncrypted(stored.accessToken)) raw.upsert(encryptPan123(cipher, plain))
                plain
            }
        }

    private suspend fun decryptPan115(raw: Pan115AccountDao, cipher: CredentialCipher, stored: Pan115AccountEntity): Pan115AccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrNull {
                val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "pan115.cookie"))
                if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptPan115(cipher, plain))
                plain
            }
        }

    private suspend fun decryptGuangYa(raw: GuangYaAccountDao, cipher: CredentialCipher, stored: GuangYaAccountEntity): GuangYaAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrNull {
                val plain = stored.copy(
                    accessToken = cipher.decrypt(stored.accessToken, "guangya.accessToken"),
                    refreshToken = cipher.decrypt(stored.refreshToken, "guangya.refreshToken"),
                    deviceId = cipher.decrypt(stored.deviceId, "guangya.deviceId"),
                    deviceSign = cipher.decrypt(stored.deviceSign, "guangya.deviceSign")
                )
                if (listOf(stored.accessToken, stored.refreshToken, stored.deviceId, stored.deviceSign)
                        .any { !cipher.isEncrypted(it) }
                ) {
                    raw.upsert(encryptGuangYa(cipher, plain))
                }
                plain
            }
        }

    private suspend fun decryptILanzou(raw: ILanzouAccountDao, cipher: CredentialCipher, stored: ILanzouAccountEntity): ILanzouAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrNull {
                val plain = stored.copy(
                    appToken = cipher.decrypt(stored.appToken, "ilanzou.appToken"),
                    password = cipher.decrypt(stored.password, "ilanzou.password")
                )
                if (!cipher.isEncrypted(stored.appToken) || !cipher.isEncrypted(stored.password)) {
                    raw.upsert(encryptILanzou(cipher, plain))
                }
                plain
            }
        }

    private suspend fun decryptLanzou(raw: LanzouAccountDao, cipher: CredentialCipher, stored: LanzouAccountEntity): LanzouAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrNull {
                val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "lanzou.cookie"))
                if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptLanzou(cipher, plain))
                plain
            }
        }

    private suspend fun decryptXunlei(raw: XunleiAccountDao, cipher: CredentialCipher, stored: XunleiAccountEntity): XunleiAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrNull {
                val plain = stored.copy(
                    accessToken = cipher.decrypt(stored.accessToken, "xunlei.accessToken"),
                    refreshToken = cipher.decrypt(stored.refreshToken, "xunlei.refreshToken"),
                    deviceId = cipher.decrypt(stored.deviceId, "xunlei.deviceId"),
                    captchaToken = cipher.decrypt(stored.captchaToken, "xunlei.captchaToken")
                )
                if (listOf(stored.accessToken, stored.refreshToken, stored.deviceId, stored.captchaToken).any { !cipher.isEncrypted(it) }) {
                    raw.upsert(encryptXunlei(cipher, plain))
                }
                plain
            }
        }

    /**
     * 视频解析账号：凭证只有一个 Cookie 头，加解密与网盘账号同一套列级加密（列名 `video.cookie`）。
     * 表内多平台共存，因此 setPrimary / clearPlatform 都按 platform 透传，不做跨平台操作。
     */
    fun videoAccount(raw: VideoAccountDao, cipher: CredentialCipher): VideoAccountDao = object : VideoAccountDao {
        override fun observeAccounts(): Flow<List<VideoAccountEntity>> = raw.observeAccounts().map { list ->
            list.mapNotNull { decryptVideo(raw, cipher, it) }
        }
        override fun observeAccountsOf(platform: String): Flow<List<VideoAccountEntity>> =
            raw.observeAccountsOf(platform).map { list -> list.mapNotNull { decryptVideo(raw, cipher, it) } }
        override suspend fun getAccounts(): List<VideoAccountEntity> =
            raw.getAccounts().mapNotNull { decryptVideo(raw, cipher, it) }
        override suspend fun getActive(platform: String): VideoAccountEntity? =
            raw.getActive(platform)?.let { decryptVideo(raw, cipher, it) }
        override suspend fun countOf(platform: String): Int = raw.countOf(platform)
        override suspend fun upsert(account: VideoAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptVideo(cipher, account))
        }
        override suspend fun setPrimary(platform: String, id: String) = raw.setPrimary(platform, id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clearPlatform(platform: String) = raw.clearPlatform(platform)
    }

    private fun encryptVideo(cipher: CredentialCipher, value: VideoAccountEntity) =
        value.copy(cookie = cipher.encrypt(value.cookie, "video.cookie"))

    private suspend fun decryptVideo(raw: VideoAccountDao, cipher: CredentialCipher, stored: VideoAccountEntity): VideoAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrNull {
                val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "video.cookie"))
                if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptVideo(cipher, plain))
                plain
            }
        }

    private fun encryptQuark(cipher: CredentialCipher, value: QuarkAccountEntity) =
        value.copy(cookie = cipher.encrypt(value.cookie, "quark.cookie"))
    private fun encryptUc(cipher: CredentialCipher, value: UCAccountEntity) =
        value.copy(cookie = cipher.encrypt(value.cookie, "uc.cookie"))
    private fun encryptBaidu(cipher: CredentialCipher, value: BaiduAccountEntity) =
        value.copy(cookie = cipher.encrypt(value.cookie, "baidu.cookie"))
    private fun encryptC139(cipher: CredentialCipher, value: C139AccountEntity) = value.copy(
        cookie = cipher.encrypt(value.cookie, "c139.cookie"),
        authorization = cipher.encrypt(value.authorization, "c139.authorization")
    )
    private fun encryptPan123(cipher: CredentialCipher, value: Pan123AccountEntity) =
        value.copy(accessToken = cipher.encrypt(value.accessToken, "pan123.accessToken"))
    private fun encryptPan115(cipher: CredentialCipher, value: Pan115AccountEntity) =
        value.copy(cookie = cipher.encrypt(value.cookie, "pan115.cookie"))
    private fun encryptGuangYa(cipher: CredentialCipher, value: GuangYaAccountEntity) = value.copy(
        accessToken = cipher.encrypt(value.accessToken, "guangya.accessToken"),
        refreshToken = cipher.encrypt(value.refreshToken, "guangya.refreshToken"),
        deviceId = cipher.encrypt(value.deviceId, "guangya.deviceId"),
        deviceSign = cipher.encrypt(value.deviceSign, "guangya.deviceSign")
    )
    private fun encryptILanzou(cipher: CredentialCipher, value: ILanzouAccountEntity) = value.copy(
        appToken = cipher.encrypt(value.appToken, "ilanzou.appToken"),
        password = cipher.encrypt(value.password, "ilanzou.password")
    )
    private fun encryptLanzou(cipher: CredentialCipher, value: LanzouAccountEntity) =
        value.copy(cookie = cipher.encrypt(value.cookie, "lanzou.cookie"))
    private fun encryptXunlei(cipher: CredentialCipher, value: XunleiAccountEntity) = value.copy(
        accessToken = cipher.encrypt(value.accessToken, "xunlei.accessToken"),
        refreshToken = cipher.encrypt(value.refreshToken, "xunlei.refreshToken"),
        deviceId = cipher.encrypt(value.deviceId, "xunlei.deviceId"),
        captchaToken = cipher.encrypt(value.captchaToken, "xunlei.captchaToken")
    )

    /**
     * 解密失败**绝不删数据**（对齐上游 #179「凭证加固」）。
     *
     * 旧实现 `catch { clear(); null }` 会把这个平台的**整张表清空**：
     * 只要密钥文件一时读不到（杀毒软件占用、目录正在搬迁、DPAPI 抖动），
     * 账号就会当场全丢。现在只在本次读取返回 null（界面显示未登录），数据原样保留，
     * 下一次读取（密钥恢复可用后）自动恢复正常。
     */
    private suspend fun <T> decryptOrNull(block: suspend () -> T): T? =
        try {
            block()
        } catch (error: Exception) {
            Log.w(
                "YunX-Crypto",
                "凭证解密失败，保留原数据、本次按未登录处理：${error.message ?: error.javaClass.simpleName}"
            )
            null
        }
}