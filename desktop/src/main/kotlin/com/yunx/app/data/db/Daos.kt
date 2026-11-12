package com.yunx.app.data.db

import kotlinx.coroutines.flow.Flow

/**
 * 桌面版 DAO 接口：与原 Room DAO 方法签名完全一致。
 */

interface QuarkAccountDao {
    fun observeAccount(): Flow<QuarkAccountEntity?>
    fun observeAccounts(): Flow<List<QuarkAccountEntity>>
    suspend fun upsert(account: QuarkAccountEntity)
    suspend fun getAccount(): QuarkAccountEntity?
    suspend fun getAccounts(): List<QuarkAccountEntity>
    suspend fun setPrimary(id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun deleteById(id: String)
    suspend fun clear()
}

interface UCAccountDao {
    fun observeAccount(): Flow<UCAccountEntity?>
    fun observeAccounts(): Flow<List<UCAccountEntity>>
    suspend fun upsert(account: UCAccountEntity)
    suspend fun getAccount(): UCAccountEntity?
    suspend fun getAccounts(): List<UCAccountEntity>
    suspend fun setPrimary(id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun deleteById(id: String)
    suspend fun clear()
}

interface XunleiAccountDao {
    fun observeAccount(): Flow<XunleiAccountEntity?>
    fun observeAccounts(): Flow<List<XunleiAccountEntity>>
    suspend fun upsert(account: XunleiAccountEntity)
    suspend fun getAccount(): XunleiAccountEntity?
    suspend fun getAccounts(): List<XunleiAccountEntity>
    suspend fun setPrimary(id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun deleteById(id: String)
    suspend fun clear()
}

interface BaiduAccountDao {
    fun observeAccount(): Flow<BaiduAccountEntity?>
    fun observeAccounts(): Flow<List<BaiduAccountEntity>>
    suspend fun upsert(account: BaiduAccountEntity)
    suspend fun getAccount(): BaiduAccountEntity?
    suspend fun getAccounts(): List<BaiduAccountEntity>
    suspend fun setPrimary(id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun deleteById(id: String)
    suspend fun clear()
}

interface C139AccountDao {
    fun observeAccount(): Flow<C139AccountEntity?>
    fun observeAccounts(): Flow<List<C139AccountEntity>>
    suspend fun upsert(account: C139AccountEntity)
    suspend fun getAccount(): C139AccountEntity?
    suspend fun getAccounts(): List<C139AccountEntity>
    suspend fun setPrimary(id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun deleteById(id: String)
    suspend fun clear()
}

interface Pan123AccountDao {
    fun observeAccount(): Flow<Pan123AccountEntity?>
    fun observeAccounts(): Flow<List<Pan123AccountEntity>>
    suspend fun upsert(account: Pan123AccountEntity)
    suspend fun getAccount(): Pan123AccountEntity?
    suspend fun getAccounts(): List<Pan123AccountEntity>
    suspend fun setPrimary(id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun deleteById(id: String)
    suspend fun clear()
}

interface Pan115AccountDao {
    fun observeAccount(): Flow<Pan115AccountEntity?>
    fun observeAccounts(): Flow<List<Pan115AccountEntity>>
    suspend fun upsert(account: Pan115AccountEntity)
    suspend fun getAccount(): Pan115AccountEntity?
    suspend fun getAccounts(): List<Pan115AccountEntity>
    suspend fun setPrimary(id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun deleteById(id: String)
    suspend fun clear()
}

interface GuangYaAccountDao {
    fun observeAccount(): Flow<GuangYaAccountEntity?>
    fun observeAccounts(): Flow<List<GuangYaAccountEntity>>
    suspend fun upsert(account: GuangYaAccountEntity)
    suspend fun getAccount(): GuangYaAccountEntity?
    suspend fun getAccounts(): List<GuangYaAccountEntity>
    suspend fun setPrimary(id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun deleteById(id: String)
    suspend fun clear()
}

interface ILanzouAccountDao {
    fun observeAccount(): Flow<ILanzouAccountEntity?>
    fun observeAccounts(): Flow<List<ILanzouAccountEntity>>
    suspend fun upsert(account: ILanzouAccountEntity)
    suspend fun getAccount(): ILanzouAccountEntity?
    suspend fun getAccounts(): List<ILanzouAccountEntity>
    suspend fun setPrimary(id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun deleteById(id: String)
    suspend fun clear()
}

interface LanzouAccountDao {
    fun observeAccount(): Flow<LanzouAccountEntity?>
    fun observeAccounts(): Flow<List<LanzouAccountEntity>>
    suspend fun upsert(account: LanzouAccountEntity)
    suspend fun getAccount(): LanzouAccountEntity?
    suspend fun getAccounts(): List<LanzouAccountEntity>
    suspend fun setPrimary(id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun deleteById(id: String)
    suspend fun clear()
}

/**
 * 视频解析账号。所有平台共用一张表，因此**按 platform 过滤**的查询都要带上平台条件
 * （[setPrimary] / [clearPlatform] 尤其注意：不带平台条件会把别的平台的账号一起动掉）。
 */
interface VideoAccountDao {
    fun observeAccounts(): Flow<List<VideoAccountEntity>>
    fun observeAccountsOf(platform: String): Flow<List<VideoAccountEntity>>
    suspend fun getAccounts(): List<VideoAccountEntity>
    suspend fun getActive(platform: String): VideoAccountEntity?
    suspend fun countOf(platform: String): Int
    suspend fun upsert(account: VideoAccountEntity)
    suspend fun setPrimary(platform: String, id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun deleteById(id: String)
    suspend fun clearPlatform(platform: String)
}

interface DownloadTaskDao {
    fun observeAll(): Flow<List<DownloadTaskEntity>>
    suspend fun insert(task: DownloadTaskEntity): Long
    suspend fun get(id: Long): DownloadTaskEntity?
    /** 全量任务快照（启动清理缓存时需要知道哪些任务还活着） */
    suspend fun listAll(): List<DownloadTaskEntity>
    /** 修复旧版本已完成任务的显示进度：已知 total 时把 downloadedSize 补到 totalSize */
    suspend fun repairCompletedProgress()
    suspend fun updateProgress(id: Long, status: Int, downloadedSize: Long, totalSize: Long)
    suspend fun updatePlan(id: Long, chunkCount: Int, totalSize: Long)
    suspend fun updateRequestHeaders(id: Long, encryptedHeaders: String)
    suspend fun markInterruptedAsPaused()
    suspend fun updateStatus(id: Long, status: Int)
    suspend fun updateError(id: Long, errorMsg: String)
    suspend fun complete(id: Long, status: Int, savePath: String, avgSpeed: Long = 0L)
    suspend fun delete(id: Long)
    /**
     * 批量删除（「删除全部」/一次清掉上万条任务）。
     * 一条 SQL 搞定，且只触发一次列表发射 —— 逐条 [delete] 在几千条规模下是 O(N²)。
     */
    suspend fun deleteMany(ids: Collection<Long>)
    /**
     * 批量改状态（「全部暂停」/「全部开始」）。
     * 同样是一条 SQL + 一次发射，避免逐条 [updateStatus] 造成的重载风暴。
     */
    suspend fun updateStatusMany(ids: Collection<Long>, status: Int)
    /** 记录外部下载引擎（Gopeed）的任务 ID；空串表示该任务回到内置下载器语义 */
    suspend fun updateEngineTaskId(id: Long, engineTaskId: String)
    /** 记录实验性直写的临时文件路径与模式（空路径/0 = 非直写） */
    suspend fun updateDirect(id: Long, partPath: String, mode: Int)
    /** 磁力任务完成时把占位显示名换成真正的种子名（种子名要等引擎解析出元数据才有） */
    suspend fun updateFileName(id: Long, name: String)
    /** 还在外部引擎里执行、需要轮询同步进度的任务（engineTaskId 非空且未完成/未失败） */
    suspend fun listSyncableEngineTasks(): List<DownloadTaskEntity>
}

interface BookmarkDao {
    fun observeAll(): Flow<List<BookmarkEntity>>
    fun observeCategories(): Flow<List<String>>
    suspend fun insert(bookmark: BookmarkEntity): Long
    suspend fun updateCategory(id: Long, category: String)
    suspend fun delete(id: Long)
}

interface LinkHistoryDao {
    fun observeAll(): Flow<List<LinkHistoryEntity>>
    fun search(query: String): Flow<List<LinkHistoryEntity>>
    suspend fun insert(entity: LinkHistoryEntity): Long
    suspend fun delete(id: Long)
    suspend fun clear()
}
