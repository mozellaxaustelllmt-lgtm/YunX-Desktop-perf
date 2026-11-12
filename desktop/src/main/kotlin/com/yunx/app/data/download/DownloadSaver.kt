package com.yunx.app.data.download

import com.yunx.app.util.DesktopActions
import com.yunx.app.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private const val TAG = "YunX-DL"

/**
 * 保存目的地：**直接向最终位置流式写入**，不再经过私有缓存副本。
 *
 * 大文件下载由此把峰值存储占用从「分片 + 合并副本 + 目标副本」的 3 份降到 ≈ 1 份：
 * 上层可边写边删分片（见 [ChunkDownloader.mergeChunksToStream]）。
 *
 * 生命周期：open() → 写入 → 校验 → commit()；中途失败/取消必须 abort() 删除半成品。
 */
class DownloadDestination internal constructor(
    /** 成功后返回上层、用于展示/删除的标识（文件绝对路径） */
    val path: String,
    private val openStream: () -> OutputStream?,
    private val onCommit: () -> Unit,
    private val onAbort: () -> Unit
) {
    /** 打开目标输出流；失败返回 null，由调用方按失败处理（此时应 abort） */
    fun open(): OutputStream? = runCatching { openStream() }.getOrNull()

    /** 写入完成且大小校验通过后调用（桌面版普通文件为 no-op） */
    fun commit() {
        runCatching { onCommit() }.onFailure { Log.e(TAG, "提交下载目标失败: ${it.message}") }
    }

    /** 失败/取消：删除半成品，避免残留占位数据 */
    fun abort() {
        runCatching { onAbort() }.onFailure { Log.e(TAG, "清理下载目标失败: ${it.message}") }
    }
}

/**
 * 桌面版完成文件保存：直接写普通文件系统。
 * - 默认目录：<用户目录>/Downloads；
 * - 自定义目录：设置页选择的绝对路径（原 SAF tree Uri 概念在桌面映射为普通目录路径）；
 * - 同名文件自动加时间戳防重，绝不覆盖用户已有文件。
 */
object DownloadSaver {

    /** 大文件拷贝缓冲：1MB */
    private const val COPY_BUFFER_SIZE = 1 * 1024 * 1024

    /**
     * 当前生效的下载保存目录：设置里的自定义目录优先，否则系统「下载」文件夹。
     * 内置下载器与 Gopeed 引擎都走这里，保证「设置 → 储存目录 → 下载保存目录」对两者一致生效。
     */
    fun resolveSaveDir(custom: String?): File =
        custom?.takeIf { it.isNotBlank() }?.let { File(it) } ?: DesktopActions.defaultDownloadDir

    // ---------- 实验性：直写目标目录的临时文件 ----------

    /** 直写临时文件信息：`partFile` 与最终文件同目录，完成后 rename 到 `finalName` */
    data class DirectPartLocation(
        val partFile: File,
        val destDir: File,
        val finalName: String
    )

    /**
     * 准备直写临时文件：`<目标目录>/<文件名>.yunx-<任务id>.part`。
     *
     * 不直接写最终文件名，避免暂停/失败后目标目录出现“看起来完整、实际残缺”的文件。
     * 任务 id 保证同一目录下多个同名任务不会共用临时文件；路径过长/越界/目录不可建时返回 null，
     * 调用方应回退到缓存分片模式。
     */
    fun prepareDirectPart(
        fileName: String,
        targetDirUri: String?,
        taskId: Long
    ): DirectPartLocation? {
        val safePath = DownloadPathPolicy.sanitize(
            fileName,
            fallbackName = "download_${System.currentTimeMillis()}"
        ) ?: run {
            Log.e(TAG, "直写：拒绝不安全的下载相对路径")
            return null
        }
        return runCatching {
            val baseDir = if (!targetDirUri.isNullOrBlank()) File(targetDirUri) else DesktopActions.defaultDownloadDir
            val canonicalBase = baseDir.canonicalFile
            val destDir = (if (safePath.relativeDirectory.isBlank()) {
                canonicalBase
            } else {
                File(canonicalBase, safePath.relativeDirectory)
            }).canonicalFile
            if (destDir != canonicalBase && !DownloadPathPolicy.isContained(canonicalBase, destDir)) {
                throw SecurityException("下载目录越界")
            }
            if (!destDir.exists() && !destDir.mkdirs()) {
                throw java.io.IOException("无法创建目录：$destDir")
            }
            val part = File(destDir, "${safePath.fileName}.yunx-$taskId.part").canonicalFile
            if (!DownloadPathPolicy.isContained(canonicalBase, part)) {
                throw SecurityException("直写临时文件越界")
            }
            // Windows 默认路径上限 260；留出 .plan/.done 后缀余量，过长则回退缓存模式
            if (part.absolutePath.length > 235) {
                throw java.io.IOException("直写临时文件路径过长：${part.absolutePath}")
            }
            DirectPartLocation(part, destDir, safePath.fileName)
        }.onFailure {
            Log.w(TAG, "直写：准备临时文件失败：${it.message}")
        }.getOrNull()
    }

    /** 直写完成后把 `.part` 改名为最终文件（同名自动加时间戳）；返回最终绝对路径 */
    fun commitDirectPart(partFile: File, destDir: File, finalName: String): String? {
        val candidates = buildList {
            add(finalName)
            repeat(3) { i -> add(timestampedName(finalName, i)) }
        }.map { File(destDir, it).canonicalFile }
            .filter { DownloadPathPolicy.isContained(destDir.canonicalFile, it) }
        val dest = candidates.firstOrNull { !it.exists() } ?: return null
        return runCatching {
            try {
                java.nio.file.Files.move(
                    partFile.toPath(), dest.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(partFile.toPath(), dest.toPath())
            } catch (e: Exception) {
                // 极少数文件系统/占用场景：退化为复制后删除（仍不覆盖已有文件）
                partFile.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
                }
                partFile.delete()
            }
            // 临时文件已改名，进度清单不再需要
            directPlanFile(partFile).delete()
            directDoneFile(partFile).delete()
            dest.absolutePath
        }.onFailure {
            Log.e(TAG, "直写：提交最终文件失败：${it.message}")
        }.getOrNull()
    }

    /** 直写多线程模式的进度清单路径（单线程模式不需要） */
    fun directPlanFile(partFile: File): File = File(partFile.absolutePath + ".plan")

    fun directDoneFile(partFile: File): File = File(partFile.absolutePath + ".done")

    /** 删除直写临时文件及其清单；失败不抛出 */
    fun deleteDirectPart(partPath: String?) {
        if (partPath.isNullOrBlank()) return
        runCatching {
            val part = File(partPath)
            part.delete()
            directPlanFile(part).delete()
            directDoneFile(part).delete()
        }.onFailure { Log.w(TAG, "直写：清理临时文件失败：${it.message}") }
    }

    /**
     * 打开「直接向最终位置写入」的保存目的地（大文件下载主路径）。
     *
     * 与 [save] 的区别：不接收源文件、不自己做拷贝，由调用方边合并分片边写入、边删分片。
     * 只在「选名/建目标」阶段换候选名；**写入阶段的失败一律由调用方 abort() 处理**，
     * 绝不在写失败后换名重写整文件。
     *
     * @param fileName 可为**相对路径**（如 "文件夹A/子/文件.mp4"，用于下载整个文件夹保持目录结构）；
     *                 纯文件名时保存到根目录。
     * @param targetDirUri 自定义保存目录（桌面版为普通目录绝对路径）；null 时用系统默认 Downloads
     * @return 保存目的地；路径非法/无可用目标时返回 null
     */
    fun openDestination(fileName: String, targetDirUri: String? = null): DownloadDestination? {
        val safePath = DownloadPathPolicy.sanitize(
            fileName,
            fallbackName = "download_${System.currentTimeMillis()}"
        ) ?: run {
            Log.e(TAG, "拒绝不安全的下载相对路径")
            return null
        }
        val safeName = safePath.fileName
        val safeDir = safePath.relativeDirectory
        return runCatching {
            val baseDir: File = if (!targetDirUri.isNullOrBlank()) {
                File(targetDirUri)
            } else {
                DesktopActions.defaultDownloadDir
            }
            val canonicalBase = baseDir.canonicalFile
            val destDir = (if (safeDir.isBlank()) canonicalBase else File(canonicalBase, safeDir)).canonicalFile
            if (destDir != canonicalBase && !DownloadPathPolicy.isContained(canonicalBase, destDir)) {
                throw SecurityException("下载目录越界")
            }
            if (!destDir.exists() && !destDir.mkdirs()) {
                throw java.io.IOException("无法创建目录：$destDir")
            }
            val candidates = buildList {
                add(safeName)
                repeat(3) { i -> add(timestampedName(safeName, i)) }
            }
            val dest = candidates.asSequence()
                .map { File(destDir, it).canonicalFile }
                .firstOrNull { candidate ->
                    DownloadPathPolicy.isContained(canonicalBase, candidate) && !candidate.exists()
                } ?: return@runCatching null
            DownloadDestination(
                path = dest.absolutePath,
                openStream = { FileOutputStream(dest) },
                onCommit = {},
                onAbort = { dest.delete() }
            )
        }.onFailure {
            Log.e(TAG, "打开下载目标失败: ${it.message}")
        }.getOrNull()
    }

    /**
     * 保存文件到下载目录。
     * @param fileName 可为**相对路径**（如 "文件夹A/子/文件.mp4"，用于下载整个文件夹保持目录结构）；
     *                 纯文件名时保存到根目录。
     * @param targetDirUri 自定义保存目录（桌面版为普通目录绝对路径）；null 时用系统默认 Downloads
     * @return 保存成功后的文件绝对路径；失败返回 null
     */
    fun save(fileName: String, source: File, targetDirUri: String? = null): String? {
        val safePath = DownloadPathPolicy.sanitize(
            fileName,
            fallbackName = "download_${System.currentTimeMillis()}"
        ) ?: run {
            Log.e(TAG, "拒绝不安全的下载相对路径")
            return null
        }
        val safeName = safePath.fileName
        val safeDir = safePath.relativeDirectory

        return runCatching {
            val baseDir: File = if (!targetDirUri.isNullOrBlank()) {
                File(targetDirUri)
            } else {
                DesktopActions.defaultDownloadDir
            }
            val canonicalBase = baseDir.canonicalFile
            val destDir = (if (safeDir.isBlank()) canonicalBase else File(canonicalBase, safeDir)).canonicalFile
            if (destDir != canonicalBase && !DownloadPathPolicy.isContained(canonicalBase, destDir)) {
                throw SecurityException("下载目录越界")
            }
            if (!destDir.exists() && !destDir.mkdirs()) {
                throw java.io.IOException("无法创建目录：$destDir")
            }
            val candidates = buildList {
                add(safeName)
                repeat(3) { i -> add(timestampedName(safeName, i)) }
            }
            val dest = candidates.asSequence()
                .map { File(destDir, it).canonicalFile }
                .firstOrNull { candidate ->
                    DownloadPathPolicy.isContained(canonicalBase, candidate) && !candidate.exists()
                } ?: return@runCatching null
            source.inputStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            dest.absolutePath
        }.onFailure {
            Log.e(TAG, "保存失败: ${it.message}")
        }.getOrNull()
    }

    /** 在文件名扩展名前加时间戳防重：base.apk → base_20260812165000.apk */
    private fun timestampedName(fileName: String, attempt: Int): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val ts = System.currentTimeMillis()
        return if (attempt == 0) "${base}_$ts$ext" else "${base}_${ts}_${attempt + 1}$ext"
    }

    /** 自定义目录展示名：桌面版直接返回目录路径 */
    fun safDirDisplay(uriString: String): String = uriString.ifBlank { "自定义目录" }

    /**
     * 删除已保存的本地文件（配合任务删除）。
     * @return 是否删除成功（false 表示未找到或删除失败）
     */
    fun delete(savePath: String): Boolean {
        if (savePath.isBlank()) return false
        return runCatching {
            val file = File(savePath)
            // 目录分支：磁力/多文件种子落盘是**目录**（<下载目录>/<种子名>/...），
            // File.delete() 对非空目录必然返回 false ⇒ 用户勾了「同时删除本地文件」却什么都没删掉
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }.onFailure {
            Log.e(TAG, "删除本地文件失败: ${it.message}")
        }.getOrDefault(false)
    }
}
