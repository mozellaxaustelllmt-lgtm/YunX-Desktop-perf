package com.yunx.app.data.download

import com.yunx.app.data.db.DownloadTaskEntity
import java.io.File

/**
 * 下载页「文件夹分组」的键计算。
 *
 * 分组必须同时看**保存根目录**和**顶级文件夹名**：
 * 同一个文件夹下载到两个不同位置时，文件名相对路径完全相同，只看文件名会把两组任务错误合并，
 * 展开后每个文件出现两条记录。Gopeed 与内置下载器共用这套逻辑。
 */
object DownloadGrouping {

    /**
     * 任务的保存根目录（最终文件所在的那一层“下载目录”）：
     * - 已完成任务优先从 `savePath` 反推：`savePath` 去掉 `fileName` 里的相对目录层级，最准确，
     *   也不受之后修改全局保存目录影响；
     * - 未完成任务 / 文件已移动时回退到任务级 `saveDir`，再回退到当前全局保存目录。
     */
    fun folderSaveRoot(task: DownloadTaskEntity, globalSaveRoot: String?): String {
        val name = task.fileName.replace('\\', '/')
        if (task.savePath.isNotBlank() && name.contains('/')) {
            // 只做路径字符串推导，不 stat 磁盘：任务列表每次进度刷新都会重算分组，
            // 上百个任务时 canonicalPath/isFile 会带来明显的 UI 卡顿。
            var dir = File(task.savePath).parentFile
            val segments = name.substringBeforeLast('/')
                .split('/')
                .filter { it.isNotBlank() }
            repeat(segments.size) { dir = dir?.parentFile }
            if (dir != null) return dir.absolutePath
        }
        return task.saveDir.takeIf { it.isNotBlank() } ?: globalSaveRoot.orEmpty()
    }

    /**
     * 分组键：`(规范化保存根目录, 顶级文件夹名)`。
     * 规范化只用于比较（Windows 不区分大小写、忽略末尾分隔符），展示时仍用原始路径。
     */
    fun groupKey(task: DownloadTaskEntity, globalSaveRoot: String?): Pair<String, String> {
        val root = normalizeRoot(folderSaveRoot(task, globalSaveRoot))
        val folder = task.fileName.replace('\\', '/').substringBefore('/')
        return root to folder
    }

    /** 规范化根目录用于分组比较；取 absolutePath（不碰磁盘），Windows 下转小写并去掉末尾分隔符 */
    fun normalizeRoot(root: String): String {
        if (root.isBlank()) return ""
        val path = File(root).absolutePath
        val trimmed = path.trimEnd('\\', '/')
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        return if (isWindows) trimmed.lowercase() else trimmed
    }
}
