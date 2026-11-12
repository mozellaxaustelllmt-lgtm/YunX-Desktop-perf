package com.yunx.app.data.download

import com.yunx.app.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 多线程直写的进度清单（与 `.yunx-*.part` 同目录）：
 * - `.plan`：total / chunks / chunkSize —— 恢复时校验分片计划是否仍可复用；
 * - `.done`：每行 `chunkIndex sha256hex`，只记录**完整分片**。
 *
 * 进程崩溃时最多丢最近几条 done 记录 → 对应分片重下；
 * 恢复时会逐片重读并重算 SHA-256，能发现“清单已写但数据未落盘/被截断”的坏片。
 */
internal object DirectPartManifest {

    data class Plan(val total: Long, val chunkCount: Int, val chunkSize: Long)

    fun writePlan(part: File, plan: Plan): Boolean = runCatching {
        val tmp = File(part.absolutePath + ".plan.tmp")
        tmp.writeText("total=${plan.total}\nchunks=${plan.chunkCount}\nchunkSize=${plan.chunkSize}\n")
        val target = DownloadSaver.directPlanFile(part)
        target.delete()
        tmp.renameTo(target)
    }.isSuccess

    fun readPlan(part: File): Plan? = runCatching {
        val file = DownloadSaver.directPlanFile(part)
        if (!file.isFile) return null
        val map = file.readLines()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0].trim() to it[1].trim() }
        val total = map["total"]?.toLongOrNull() ?: return null
        val chunks = map["chunks"]?.toIntOrNull() ?: return null
        val chunkSize = map["chunkSize"]?.toLongOrNull() ?: return null
        if (total <= 0 || chunks <= 0 || chunkSize <= 0) null else Plan(total, chunks, chunkSize)
    }.getOrNull()

    fun readDone(part: File): Map<Int, String> = runCatching {
        val file = DownloadSaver.directDoneFile(part)
        if (!file.isFile) return emptyMap()
        val out = HashMap<Int, String>()
        file.forEachLine { line ->
            val pieces = line.trim().split(' ', limit = 2)
            if (pieces.size == 2) {
                val idx = pieces[0].toIntOrNull()
                val hex = pieces[1].trim()
                if (idx != null && idx >= 0 && hex.length == 64) out[idx] = hex
            }
        }
        out
    }.getOrDefault(emptyMap())

    @Synchronized
    fun appendDone(part: File, index: Int, hash: String) {
        runCatching {
            FileOutputStream(DownloadSaver.directDoneFile(part), true).use { out ->
                out.write("$index $hash\n".toByteArray())
                out.flush()
            }
        }.onFailure { Log.w("YunX-DL", "直写：写进度清单失败：${it.message}") }
    }

    @Synchronized
    fun rewriteDone(part: File, entries: Map<Int, String>) {
        runCatching {
            val tmp = File(part.absolutePath + ".done.tmp")
            tmp.bufferedWriter().use { w ->
                entries.toSortedMap().forEach { (i, h) -> w.write("$i $h\n") }
            }
            val target = DownloadSaver.directDoneFile(part)
            target.delete()
            tmp.renameTo(target)
        }.onFailure { Log.w("YunX-DL", "直写：重写进度清单失败：${it.message}") }
    }

    /** 读取文件指定区间的 SHA-256（恢复/提交前校验用）；读取不完整返回 null */
    fun hashRange(file: File, start: Long, length: Long): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(1024 * 1024)
            var remaining = length
            while (remaining > 0) {
                val read = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (read <= 0) break
                digest.update(buf, 0, read)
                remaining -= read
            }
            if (remaining != 0L) return null
        }
        digest.digest().toHexDigest()
    }.getOrNull()
}
