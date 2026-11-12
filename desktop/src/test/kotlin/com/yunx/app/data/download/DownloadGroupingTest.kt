package com.yunx.app.data.download

import com.yunx.app.data.db.DownloadTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * 下载页文件夹分组键：
 * - 同一文件夹下载到不同保存位置必须是两个组；
 * - 同一位置（无论来自完成任务的 savePath 还是活动任务的 saveDir）必须归为同一组。
 */
class DownloadGroupingTest {

    @Test
    fun sameFolderAtDifferentRootsProducesDifferentGroupKeys() {
        val root = File("build/grouping-unit-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val saveA = File(root, "saveA").apply { mkdirs() }
        val saveB = File(root, "saveB").apply { mkdirs() }
        val fileA = File(saveA, "夸克精选壁纸/01.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val fileB = File(saveB, "夸克精选壁纸/01.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(2))
        }
        val global = File(root, "global").apply { mkdirs() }.absolutePath

        val completedA = task("夸克精选壁纸/01.jpg", savePath = fileA.absolutePath)
        val completedB = task("夸克精选壁纸/01.jpg", savePath = fileB.absolutePath)
        val activeA = task("夸克精选壁纸/01.jpg", saveDir = saveA.absolutePath)
        val activeGlobal = task("夸克精选壁纸/01.jpg")

        assertEquals(saveA.absolutePath, DownloadGrouping.folderSaveRoot(completedA, global))
        assertNotEquals(
            DownloadGrouping.groupKey(completedA, global),
            DownloadGrouping.groupKey(completedB, global)
        )
        // 活动任务用 saveDir，与同位置的已完成任务归为同一组
        assertEquals(
            DownloadGrouping.groupKey(completedA, global),
            DownloadGrouping.groupKey(activeA, global)
        )
        // 无任务级 saveDir 的活动任务回退到当前全局保存目录
        assertEquals(
            DownloadGrouping.normalizeRoot(global),
            DownloadGrouping.groupKey(activeGlobal, global).first
        )
    }

    @Test
    fun nestedFileStripsAllRelativeFoldersFromSavePath() {
        val root = File("build/grouping-unit-test/nested").apply {
            deleteRecursively()
            mkdirs()
        }
        val save = File(root, "save").apply { mkdirs() }
        val file = File(save, "夸克精选壁纸/sub/02.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(3))
        }
        val task = task("夸克精选壁纸/sub/02.jpg", savePath = file.absolutePath)
        assertEquals(save.absolutePath, DownloadGrouping.folderSaveRoot(task, root.absolutePath))
    }

    @Test
    fun trailingSeparatorDoesNotSplitGroups() {
        val root = File("build/grouping-unit-test/trailing").apply {
            deleteRecursively()
            mkdirs()
        }
        val withSep = root.absolutePath + File.separator
        assertEquals(
            DownloadGrouping.normalizeRoot(root.absolutePath),
            DownloadGrouping.normalizeRoot(withSep)
        )
    }

    private fun task(
        fileName: String,
        savePath: String = "",
        saveDir: String = ""
    ) = DownloadTaskEntity(
        url = "http://example.com/file",
        fileName = fileName,
        savePath = savePath,
        saveDir = saveDir,
        status = DownloadTaskEntity.STATUS_COMPLETED
    )
}
