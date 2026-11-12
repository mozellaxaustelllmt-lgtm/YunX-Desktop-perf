package com.yunx.app.data.repository

import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 夸克分享解析仓库：token → 列表 → 转存临时目录 → 下载直链。
 * 所有 API 失败统一携带服务端 message（QuarkApiException）透传给 UI。
 */
class QuarkResolveRepository(private val api: QuarkApi) : ShareResolveRepository {

    /**
     * 创建分享会话：解析链接 → 获取 stoken（请求体携带提取码）。
     */
    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val parsed = ShareLinkParser.parse(link)
            ?: return Result.failure(IllegalArgumentException("无法识别分享链接"))
        val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd
        return runCatching {
            val token = api.getShareToken(parsed.shareId, effectivePwd, cookie)
                ?: throw IllegalStateException("未获取到分享凭证")
            ShareSession(parsed.shareId, token.stoken, token.title)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )
    }

    /** 获取指定目录下的文件列表（自动翻页取全量；服务端单页可能只返回 50 项） */
    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            // 按 fid 去重：服务端截断单页大小（常见为 50）时，不能靠「本页是否满 100」判断结束
            val all = LinkedHashMap<String, ShareFile>()
            var page = 1
            while (page <= MAX_PAGES) {
                val batch = api.getShareFiles(session.shareId, session.stoken, dirFid, cookie, page, PAGE_SIZE)
                    ?: throw IllegalStateException("未获取到文件列表")
                if (batch.isEmpty()) break
                val before = all.size
                for (file in batch) {
                    val key = file.fid.ifBlank { "${file.pdirFid}/${file.fname}" }
                    all.putIfAbsent(key, file)
                }
                // 整页没有新条目：已到尽头（或服务端忽略了页码，避免死循环）
                if (all.size == before) break
                page++
            }
            all.values.toList()
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    /**
     * 确保「YunX临时转存」目录存在，返回其 fid；不存在则创建。
     */
    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        val rootFiles = api.getFileList(QuarkConstants.DEFAULT_PDIR_FID, cookie)
            ?: throw IllegalStateException("获取网盘目录失败")
        rootFiles.firstOrNull { it.isdir && it.fname == QuarkConstants.TEMP_DIR_NAME }?.fid
            ?: api.createFolder(QuarkConstants.TEMP_DIR_NAME, QuarkConstants.DEFAULT_PDIR_FID, cookie)
            ?: throw IllegalStateException("创建临时目录失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /**
     * 转存分享文件到临时目录，等待异步任务完成。
     * @return 转存后的新 fid（取直链必须用它，分享 fid 转存后已变更）
     */
    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<String> = saveToCloud(session, file, toDirFid, cookie, skipSpaceCheck)

    /** 获取文件下载直链（转存后调用） */
    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> = runCatching {
        api.getDownloadLink(fid, cookie)
            ?: throw IllegalStateException("获取下载链接失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 夸克取直链（修复版，文档《夸克网盘重复获取直链失败修复》方案二）：
     *  1) 每次转存落到「YunX临时转存」下的【唯一子目录 tr_<时间戳>_<随机>】，
     *     使夸克 sharepage/save 去重键（to_pdir_fid）每次不同 → 永远生成新 fid，
     *     从根上避免「二次转存返回已删除 fid → download 404 code:21001」。
     *  2) 取链成功后【不立即删】，把临时子目录 fid 通过 DownloadLink.cleanupDirFid 带回，
     *     由下载完成的 onComplete 回调删除（见 ResolveViewModel），保证下载期间 fid 一直存活。
     *  3) 移除原来的 per-click clearTempDir（对去重无效，且可能误删进行中的文件）。
     */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<DownloadLink> = runCatching {
        // 空间校验放在创建临时目录之前：空间不足时直接抛出，不在网盘留下空目录
        checkSpace(file, cookie, skipSpaceCheck)
        val baseDir = ensureTempDir(cookie).getOrThrow()

        // 唯一临时子目录：to_pdir_fid 每次不同 → 绕开夸克去重
        val subDirName = "tr_${System.nanoTime()}_${(Math.random() * 1_000_000).toInt()}"
        val subDirFid = api.createFolder(subDirName, baseDir, cookie)
            ?: throw IllegalStateException("创建临时转存目录失败")

        val savedFid = transferFileTo(session, file, subDirFid, cookie).getOrThrow()
        val link = api.getDownloadLink(savedFid, cookie)
            ?: throw IllegalStateException("获取下载链接失败")

        // 不在此删除！下载完成后再删整个子目录（含文件）
        link.copy(cleanupDirFid = subDirFid)
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /**
     * 免转存取链（登录态）：不建临时目录、不转存，直接把分享凭证（pwd_id / stoken / fids /
     * share_fid_token）交给 file/download 换直链。省掉整条「建目录 → 转存 → 轮询 → 下载完再删」
     * 链路，也就不存在转存去重与临时目录残留问题。
     *
     * 注意：个别分享类型服务端仍要求「先转存再取链」，此时本方法会失败，由调用方
     * （ResolveViewModel.resolveShareLink）回退到 [getShareDownloadLink]。
     */
    override suspend fun getShareDownloadLinkWithoutSave(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        api.getShareDownloadLinkWithoutSave(
            fid = file.fid,
            fidToken = file.fidToken,
            shareId = session.shareId,
            stoken = session.stoken,
            cookie = cookie
        ) ?: throw IllegalStateException("获取下载链接失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /**
     * 游客取链（未登录）：不碰用户网盘 —— 不建临时目录、不转存，直接按分享参数打 download 接口，
     * 带回服务端随响应下发的游客态 __pugs。夸克只放行约 50MB 以内的小文件（超出报 23018）。
     */
    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = runCatching {
        api.getGuestShareDownloadLink(
            fid = file.fid,
            fidToken = file.fidToken,
            shareId = session.shareId,
            stoken = session.stoken
        ) ?: throw IllegalStateException("获取下载链接失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 转存分享文件到用户网盘指定目录（转存功能：不删除，长期保存） */
    suspend fun saveToCloud(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String,
        skipSpaceCheck: Boolean = false
    ): Result<String> = runCatching {
        checkSpace(file, cookie, skipSpaceCheck)
        transferFileTo(session, file, toDirFid, cookie).getOrThrow()
    }

    /** 批量转存前的整批空间预算校验：只查一次配额，不足时抛出（不被批量循环容错吞掉） */
    override suspend fun ensureBatchSpace(sizes: List<Long>, credential: String): Boolean =
        TransferSpaceGuard.ensureEnoughSpaceForBatch(sizes, "夸克") { api.getQuota(credential) }

    /** 转存前置空间校验：空间不足直接抛出，不再走后面的转存与轮询（避免被误报「转存超时」） */
    private suspend fun checkSpace(file: ShareFile, cookie: String, skip: Boolean = false) {
        // 批量入口已做过整批预算校验 → 跳过逐项校验，避免同一批多次查配额
        if (skip) return
        TransferSpaceGuard.ensureEnoughSpace(file.fsize.takeIf { it > 0 }, "夸克") { api.getQuota(cookie) }
    }

    /** 转存到指定目录并轮询拿到新 fid（toPdirFid 由调用方指定） */
    private suspend fun transferFileTo(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val taskId = api.saveShareFile(
            shareId = session.shareId,
            stoken = session.stoken,
            pdirFid = file.pdirFid,
            fid = file.fid,
            fidToken = file.fidToken,
            toPdirFid = toDirFid,
            cookie = cookie
        ) ?: throw IllegalStateException("转存失败")
        api.pollTask(taskId, cookie)
            ?: throw IllegalStateException("转存超时，请稍后重试")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 下载完成后清理：删除临时子目录（连同其中的转存文件）；失败不阻断 */
    override suspend fun cleanupTempDir(dirFid: String, cookie: String) {
        runCatching { api.deleteFile(dirFid, cookie) }
    }

    private companion object {
        /** 分享列表单页条数（服务端可能截到 50，因此翻页逻辑不按本页条数判断结束） */
        const val PAGE_SIZE = 50

        /** 翻页安全上限（50 × 200 = 1 万项），防止异常响应导致死循环 */
        const val MAX_PAGES = 200
    }
}
