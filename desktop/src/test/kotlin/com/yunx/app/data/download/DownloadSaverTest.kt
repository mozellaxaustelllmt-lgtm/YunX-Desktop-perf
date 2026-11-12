package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「下载到…」的落盘路径测试：任务级保存目录必须覆盖全局设置，
 * 相对子目录仍保持在所选目录内（文件夹下载保持目录结构）。
 */
class DownloadSaverTest {

    @Test
    fun openDestinationWritesIntoPerTaskDirectory() {
        val base = File("build/download-saver-test/custom-dest").apply { mkdirs() }
        val dest = DownloadSaver.openDestination("sub/文件.bin", base.absolutePath)
        assertNotNull(dest)
        dest!!.open()!!.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        dest.commit()

        val saved = File(dest.path)
        assertTrue("目标文件应已创建", saved.isFile)
        assertTrue(
            "文件必须落在用户选择的目录内",
            saved.canonicalPath.startsWith(base.canonicalFile.canonicalPath)
        )
        saved.delete()
    }

    @Test
    fun prepareDirectPartAndCommitRenamesToFinalFile() {
        val base = File("build/download-saver-test/direct").apply { mkdirs() }
        val loc = DownloadSaver.prepareDirectPart("sub/文件.bin", base.absolutePath, 42L)
        assertNotNull(loc)
        val part = loc!!.partFile
        assertTrue("临时文件名应带任务 id", part.name.contains(".yunx-42.part"))
        part.writeBytes(byteArrayOf(1, 2, 3, 4))

        val saved = DownloadSaver.commitDirectPart(part, loc.destDir, loc.finalName)
        assertNotNull(saved)
        val savedFile = File(saved!!)
        assertTrue(savedFile.isFile)
        assertEquals(4L, savedFile.length())
        assertTrue(
            "最终文件必须落在用户选择的目录内",
            savedFile.canonicalPath.startsWith(base.canonicalFile.canonicalPath)
        )
        assertTrue("提交后 .part 应消失", !part.exists())
        DownloadSaver.deleteDirectPart(savedFile.absolutePath)
    }
}
