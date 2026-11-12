package com.yunx.app.data.download

import com.yunx.app.AppContext
import com.yunx.app.data.db.DownloadTaskDao
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.util.Log
import java.io.File

/**
 * 下载缓存维护 + 磁盘空间守卫。
 *
 * 背景（Issue #17）：分片下载在完成前会一直占用「下载缓存目录」（默认在 C 盘用户目录）。
 * 磁盘被写满后任务失败，而失败/暂停任务的分片按断点续传策略保留 ⇒ 缓存再也降不下来。
 * 这里补三类保护：
 * 1. **启动清理**：删除已完成任务、已不存在任务的分片，以及合并残留；
 * 2. **写盘守卫**：开始下载前 / 合并保存前检查可用空间，不足时带明确原因失败，
 *    而不是先把盘写满、再让下载「卡住」；
 * 3. **失败兜底**：真正遇到 OS 级 ENOSPC 时删除该任务的分片，立即释放空间。
 */
object DownloadCacheMaintenance {
    private const val TAG = "YunX-DL"

    /** 缓存盘最低保留空间：低于此值直接拒绝新的写盘（给系统留出喘息空间） */
    const val MIN_FREE_BYTES: Long = 512L * 1024 * 1024L

    /** 启动时缓存盘低于此值 → 连失败任务的分片也清掉（失败任务不会自动续传，留着只会继续占盘） */
    private const val STARTUP_EMERGENCY_FREE_BYTES = 2L * 1024 * 1024 * 1024L

    const val MB: Long = 1024L * 1024L

    /** 安全余量：至少 256MB 或 2%，取大者 */
    fun safetyMargin(totalBytes: Long): Long =
        maxOf(256L * MB, totalBytes.coerceAtLeast(0L) / 50)

    data class Usage(val bytes: Long, val dirs: Int)

    data class CleanResult(val freedBytes: Long, val deletedDirs: Int) {
        operator fun plus(other: CleanResult) =
            CleanResult(freedBytes + other.freedBytes, deletedDirs + other.deletedDirs)
    }

    /** 空间不足（可预期、不应重试）——任务直接失败并展示这条原因 */
    class LowDiskSpaceException(message: String) : IllegalStateException(message)

    private data class ChunkDir(val dir: File, val taskId: Long)

    /**
     * 缓存里的任务分片目录。兼容两种布局：
     * - `<cache>/download_tmp/<id>`；
     * - `<cache>/download_tmp/download_tmp/<id>`（当前 chunkDirOf 实际产生的双层目录）。
     */
    private fun chunkDirs(): List<ChunkDir> {
        val outer = AppContext.downloadTmpDir
        val inner = File(outer, "download_tmp")
        val out = mutableListOf<ChunkDir>()
        (outer.listFiles() ?: emptyArray()).forEach { f ->
            // outer 下的 download_tmp 是内层容器，不能当作任务目录
            if (f.isDirectory && f.name != "download_tmp") {
                f.name.toLongOrNull()?.let { out += ChunkDir(f, it) }
            }
        }
        (inner.listFiles() ?: emptyArray()).forEach { f ->
            if (f.isDirectory) f.name.toLongOrNull()?.let { out += ChunkDir(f, it) }
        }
        return out
    }

    /** 当前缓存占用（分片 + 合并临时文件） */
    fun usage(): Usage {
        var bytes = 0L
        var dirs = 0
        chunkDirs().forEach { entry ->
            bytes += sizeOf(entry.dir)
            dirs++
        }
        bytes += sizeOf(AppContext.mergeDir)
        return Usage(bytes, dirs)
    }

    /** 删除不属于 [keepTaskIds] 的分片目录 */
    fun cleanChunkDirsExcept(keepTaskIds: Set<Long>): CleanResult {
        var freed = 0L
        var deleted = 0
        chunkDirs().forEach { entry ->
            if (entry.taskId !in keepTaskIds) {
                freed += sizeOf(entry.dir)
                if (deleteDir(entry.dir)) deleted++
            }
        }
        return CleanResult(freed, deleted)
    }

    /** 清掉合并临时文件（hls_*）；进程重启后不可能还有合并在进行 */
    fun cleanMergeLeftovers(): CleanResult {
        var freed = 0L
        var deleted = 0
        (AppContext.mergeDir.listFiles() ?: emptyArray()).forEach { f ->
            val size = sizeOf(f)
            if (deleteDir(f)) {
                freed += size
                deleted++
            }
        }
        return CleanResult(freed, deleted)
    }

    /**
     * 启动清理：已完成任务的分片、已删除任务的分片、合并残留全部清掉；
     * 若缓存盘已接近写满，再把「失败」任务的分片也清掉（它们不会自动续传）。
     */
    suspend fun cleanAfterStartup(dao: DownloadTaskDao): CleanResult {
        val tasks = runCatching { dao.listAll() }.getOrElse {
            Log.w(TAG, "缓存清理：读取任务列表失败，只清理合并残留：${it.message}")
            return cleanMergeLeftovers()
        }
        // 已完成的任务不再需要分片；其余（等待/下载/暂停/失败）保留以便续传
        val keep = tasks
            .filter { it.status != DownloadTaskEntity.STATUS_COMPLETED }
            .map { it.id }
            .toSet()
        var result = cleanChunkDirsExcept(keep) + cleanMergeLeftovers()

        // 实验性直写：已完成任务/临时文件已丢失的任务，清掉目标目录里的 .part/.plan/.done 并清空 DB 记录
        var directFreed = 0L
        var directDeleted = 0
        tasks.forEach { task ->
            if (task.directPart.isBlank()) return@forEach
            val part = File(task.directPart)
            if (task.status == DownloadTaskEntity.STATUS_COMPLETED || !part.isFile) {
                directFreed += sizeOf(part) +
                    sizeOf(DownloadSaver.directPlanFile(part)) +
                    sizeOf(DownloadSaver.directDoneFile(part))
                DownloadSaver.deleteDirectPart(task.directPart)
                runCatching { dao.updateDirect(task.id, "", 0) }
                directDeleted++
            }
        }
        if (directFreed > 0 || directDeleted > 0) {
            Log.i(TAG, "启动清理直写临时文件：释放 ${formatBytes(directFreed)}，清理 $directDeleted 项")
        }

        val free = freeSpace(AppContext.cacheDir)
        if (free != null && free < STARTUP_EMERGENCY_FREE_BYTES) {
            val failedIds = tasks
                .filter { it.status == DownloadTaskEntity.STATUS_FAILED }
                .map { it.id }
                .toSet()
            if (failedIds.isNotEmpty()) {
                val extra = cleanChunkDirsFor(failedIds)
                result += extra
                Log.w(TAG, "缓存盘可用空间仅 ${formatBytes(free)}，已额外清理失败任务分片：${formatBytes(extra.freedBytes)}")
            }
        }
        if (result.freedBytes > 0) {
            Log.i(TAG, "启动缓存清理完成：释放 ${formatBytes(result.freedBytes)}，删除 ${result.deletedDirs} 个目录")
        }
        return result
    }

    /** 删除指定任务的分片目录（失败兜底 / 手动清理用） */
    fun cleanChunkDirsFor(taskIds: Set<Long>): CleanResult {
        var freed = 0L
        var deleted = 0
        chunkDirs().filter { it.taskId in taskIds }.forEach { entry ->
            freed += sizeOf(entry.dir)
            if (deleteDir(entry.dir)) deleted++
        }
        return CleanResult(freed, deleted)
    }

    /**
     * 手动「清理缓存」时一并清理实验性直写在目标目录里的临时文件。
     * 保留 [keepTaskIds]（正在下载/等待中）对应的临时文件；暂停/失败任务的直写进度会被删除。
     */
    suspend fun cleanDirectParts(dao: DownloadTaskDao, keepTaskIds: Set<Long>): CleanResult {
        val tasks = runCatching { dao.listAll() }.getOrElse { return CleanResult(0L, 0) }
        var freed = 0L
        var deleted = 0
        tasks.forEach { task ->
            if (task.directPart.isBlank() || task.id in keepTaskIds) return@forEach
            val part = File(task.directPart)
            freed += sizeOf(part) +
                sizeOf(DownloadSaver.directPlanFile(part)) +
                sizeOf(DownloadSaver.directDoneFile(part))
            DownloadSaver.deleteDirectPart(task.directPart)
            runCatching { dao.updateDirect(task.id, "", 0) }
            deleted++
        }
        return CleanResult(freed, deleted)
    }

    /** 删除单个任务的分片目录，返回释放的字节数 */
    fun deleteChunkDir(taskId: Long): Long {
        var freed = 0L
        chunkDirs().filter { it.taskId == taskId }.forEach { entry ->
            freed += sizeOf(entry.dir)
            deleteDir(entry.dir)
        }
        return freed
    }

    /** 各任务当前已落盘的分片字节数（一次扫描，供并发任务的空间合计校验） */
    fun chunkBytesByTask(): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        chunkDirs().forEach { entry ->
            out[entry.taskId] = (out[entry.taskId] ?: 0L) + sizeOf(entry.dir)
        }
        return out
    }

    /**
     * 下载开始前的空间检查。返回 null = 可以下；否则是给用户看的原因。
     *
     * - **不同磁盘**（缓存 C / 成品 F）：缓存盘只需再放「剩余分片」，成品盘需要一份完整文件；
     * - **同一磁盘**：合并阶段分片（1 份）与最终文件（1 份）会同时存在，按 2 倍文件大小校验，
     *   这也是「下载到 100% 合并时把盘写满」的根因；
     * - [alreadyOnDiskBytes]：该任务已经落盘的分片，从所需空间里扣除（续传不会重复占用）；
     * - [reservedCacheBytes]：其它正在下载的任务预计还要写入缓存盘的字节数。默认同时下 3 个任务，
     *   只看「本任务放不放得下」会让 3 个大文件同时通过检查、合计把缓存盘写爆。
     */
    fun checkBeforeDownload(
        cacheDir: File,
        saveDir: File,
        totalBytes: Long,
        alreadyOnDiskBytes: Long = 0L,
        reservedCacheBytes: Long = 0L
    ): String? = evaluateDownloadSpace(
        cacheFree = freeSpace(cacheDir),
        destFree = freeSpace(saveDir),
        sameVolume = sameVolume(cacheDir, saveDir),
        totalBytes = totalBytes,
        alreadyOnDiskBytes = alreadyOnDiskBytes,
        reservedCacheBytes = reservedCacheBytes
    )

    /** 纯逻辑版本（不读磁盘，便于单元测试） */
    internal fun evaluateDownloadSpace(
        cacheFree: Long?,
        destFree: Long?,
        sameVolume: Boolean,
        totalBytes: Long,
        alreadyOnDiskBytes: Long = 0L,
        reservedCacheBytes: Long = 0L
    ): String? {
        val total = totalBytes.coerceAtLeast(0L)
        val existing = alreadyOnDiskBytes.coerceIn(0L, total)
        val margin = safetyMargin(total)

        if (cacheFree != null) {
            if (cacheFree < MIN_FREE_BYTES) {
                return "缓存目录可用空间不足（剩余 ${formatBytes(cacheFree)}）：" +
                    "请到「设置 → 储存目录」更换下载缓存目录，或清理磁盘后重试。"
            }
            // 本任务还需写入缓存的字节：
            // - 不同盘：total - existing（只差没下完的分片）；
            // - 同盘：total - existing（补完分片）+ total（合并后的最终文件）＝ 2×total - existing。
            val ownNeed = when {
                total <= 0 -> 0L
                sameVolume -> (needBytes(total, 2) - existing).coerceAtLeast(0L)
                else -> (needBytes(total, 1) - existing).coerceAtLeast(0L)
            }
            val need = addClamped(ownNeed, reservedCacheBytes)
            if (total > 0 && cacheFree < need) {
                return "缓存目录所在磁盘空间不足：本次需要约 ${formatBytes(need)}，" +
                    "当前可用 ${formatBytes(cacheFree)}。" +
                    (if (reservedCacheBytes > 0) "（已为其它进行中的任务预留 ${formatBytes(reservedCacheBytes)}）" else "") +
                    "请到「设置 → 储存目录」更换下载缓存目录，或清理磁盘后重试。"
            }
        }

        if (!sameVolume && destFree != null) {
            if (destFree < MIN_FREE_BYTES) {
                return "下载目录可用空间不足（剩余 ${formatBytes(destFree)}）：" +
                    "请到「设置 → 储存目录」更换下载保存目录，或清理磁盘后重试。"
            }
            if (total > 0 && destFree < needBytes(total, 1)) {
                return "下载目录所在磁盘空间不足：本次需要约 ${formatBytes(needBytes(total, 1))}，" +
                    "当前可用 ${formatBytes(destFree)}。请到「设置 → 储存目录」更换下载保存目录，或清理磁盘后重试。"
            }
        }
        return null
    }

    /** 合并（写入最终文件）前的空间检查：分片占着缓存，目标位置还需要一整份文件的空间 */
    fun checkBeforeMerge(saveDir: File, totalBytes: Long, reservedBytes: Long = 0L): String? =
        evaluateMergeSpace(freeSpace(saveDir), totalBytes, reservedBytes)

    /**
     * 实验性直写模式的空间检查：完全不占缓存盘，目标盘只需要 1 份文件空间。
     * 直写会把临时文件预分配到目标盘，因此空间不足时立即失败（等待其它直写任务完成不会释放空间）。
     */
    fun checkBeforeDirectDownload(saveDir: File, totalBytes: Long): String? {
        val total = totalBytes.coerceAtLeast(0L)
        val free = freeSpace(saveDir) ?: return null
        if (free < MIN_FREE_BYTES) {
            return "下载目录可用空间不足（剩余 ${formatBytes(free)}）：" +
                "请到「设置 → 储存目录」更换下载保存目录，或清理磁盘后重试。"
        }
        if (total > 0 && free < needBytes(total, 1)) {
            return "下载目录所在磁盘空间不足（直写模式）：本次需要约 ${formatBytes(needBytes(total, 1))}，" +
                "当前可用 ${formatBytes(free)}。请清理目标磁盘或更换下载保存目录后重试。"
        }
        return null
    }

    /**
     * 「单个任务本身」是否已经放不下（不含其它任务的预留）。
     *
     * 用于区分两种空间不足：
     * - true：文件比磁盘可用空间还大 / 成品盘放不下 —— 等待也不会变好，直接失败并提示用户换目录；
     * - false：只是被其它正在下载的任务暂时占着 —— 应排队等待它们完成，而不是把整批任务都标记失败。
     */
    fun isPermanentSpaceShortage(
        cacheFree: Long?,
        destFree: Long?,
        sameVolume: Boolean,
        totalBytes: Long,
        alreadyOnDiskBytes: Long = 0L
    ): Boolean {
        val total = totalBytes.coerceAtLeast(0L)
        if (total <= 0) {
            // 大小未知：无法预估，只能守住最低水位；低于最低水位直接失败，避免无限等待
            if (cacheFree != null && cacheFree < MIN_FREE_BYTES) return true
            if (!sameVolume && destFree != null && destFree < MIN_FREE_BYTES) return true
            return false
        }
        val existing = alreadyOnDiskBytes.coerceIn(0L, total)
        if (cacheFree != null) {
            val ownNeed = if (sameVolume) {
                (needBytes(total, 2) - existing).coerceAtLeast(0L)
            } else {
                (needBytes(total, 1) - existing).coerceAtLeast(0L)
            }
            if (cacheFree < ownNeed) return true
        }
        if (!sameVolume && destFree != null && destFree < needBytes(total, 1)) return true
        return false
    }

    /** 纯逻辑版本（不读磁盘，便于单元测试） */
    internal fun evaluateMergeSpace(destFree: Long?, totalBytes: Long, reservedBytes: Long = 0L): String? {
        val total = totalBytes.coerceAtLeast(0L)
        if (total <= 0) return null
        val free = destFree ?: return null
        val need = addClamped(needBytes(total, 1), reservedBytes)
        if (free < need) {
            return "下载目录所在磁盘空间不足，无法合并保存：本次需要约 ${formatBytes(need)}，" +
                "当前可用 ${formatBytes(free)}。" +
                (if (reservedBytes > 0) "（已为其它正在合并的任务预留 ${formatBytes(reservedBytes)}）" else "") +
                "分片已保留，清理磁盘或更换下载目录后可继续。"
        }
        return null
    }

    /** 真正的磁盘写满错误（OS 级 ENOSPC）；用户可读的守卫异常不算 */
    fun isDiskFullError(error: Throwable): Boolean {
        var t: Throwable? = error
        while (t != null) {
            val msg = t.message.orEmpty().lowercase()
            if (msg.contains("no space left on device") ||
                msg.contains("there is not enough space") ||
                msg.contains("not enough space on the disk") ||
                msg.contains("enospc") ||
                msg.contains("disk full")
            ) {
                return true
            }
            t = t.cause
        }
        return false
    }

    /** 目录所在磁盘的可用空间；取不到返回 null */
    fun freeSpace(dir: File): Long? = runCatching {
        var cur: File? = dir.absoluteFile
        while (cur != null && !cur.exists()) cur = cur.parentFile
        cur?.usableSpace?.takeIf { it > 0 }
    }.getOrNull()

    /** 两个目录是否在同一磁盘卷（Windows 按盘符比较） */
    fun sameVolume(a: File, b: File): Boolean {
        val ra = volumeRoot(a) ?: return false
        val rb = volumeRoot(b) ?: return false
        return ra.absolutePath.equals(rb.absolutePath, ignoreCase = true)
    }

    private fun volumeRoot(dir: File): File? {
        var cur: File? = dir.absoluteFile
        while (cur != null && !cur.exists()) cur = cur.parentFile
        return cur?.toPath()?.root?.toFile()
    }

    /** 需要空间 = 文件大小 × [multiplier] + 安全余量（防溢出） */
    private fun needBytes(total: Long, multiplier: Int): Long {
        if (total <= 0) return safetyMargin(0)
        val margin = safetyMargin(total)
        return if (total > (Long.MAX_VALUE - margin) / multiplier) Long.MAX_VALUE
        else total * multiplier + margin
    }

    /** 加法饱和，避免多个超大任务的预留值相加时溢出 */
    private fun addClamped(a: Long, b: Long): Long {
        val x = a.coerceAtLeast(0L)
        val y = b.coerceAtLeast(0L)
        return if (x > Long.MAX_VALUE - y) Long.MAX_VALUE else x + y
    }

    /** 目录（或文件）总字节数；不可读时按 0 计 */
    private fun sizeOf(file: File): Long = runCatching {
        if (file.isFile) file.length()
        else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun deleteDir(file: File): Boolean = runCatching {
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }.getOrDefault(false)

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return if (i >= 2) "%.2f %s".format(value, units[i]) else "%.0f %s".format(value, units[i])
    }
}
