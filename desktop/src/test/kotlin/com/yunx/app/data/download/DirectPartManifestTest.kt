package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class DirectPartManifestTest {

    @Test
    fun planAndDoneRoundTrip() {
        val dir = File("build/direct-manifest-test").apply { mkdirs() }
        val part = File(dir, "file.yunx-1.part").apply {
            DownloadSaver.deleteDirectPart(absolutePath)
            writeBytes(ByteArray(1024) { (it % 251).toByte() })
        }
        val plan = DirectPartManifest.Plan(1024, 4, 256)
        assertTrue(DirectPartManifest.writePlan(part, plan))
        assertEquals(plan, DirectPartManifest.readPlan(part))

        DirectPartManifest.appendDone(part, 1, "a".repeat(64))
        DirectPartManifest.appendDone(part, 3, "b".repeat(64))
        assertEquals(
            mapOf(1 to "a".repeat(64), 3 to "b".repeat(64)),
            DirectPartManifest.readDone(part)
        )

        DirectPartManifest.rewriteDone(part, mapOf(3 to "b".repeat(64)))
        assertEquals(mapOf(3 to "b".repeat(64)), DirectPartManifest.readDone(part))

        val expected = MessageDigest.getInstance("SHA-256")
            .also { it.update(ByteArray(256) { i -> (i % 251).toByte() }) }
            .digest().toHexDigest()
        assertEquals(expected, DirectPartManifest.hashRange(part, 0, 256))

        DownloadSaver.deleteDirectPart(part.absolutePath)
    }

    @Test
    fun hashRangeDetectsShortFile() {
        val dir = File("build/direct-manifest-test").apply { mkdirs() }
        val part = File(dir, "short.yunx-2.part").apply {
            DownloadSaver.deleteDirectPart(absolutePath)
            writeBytes(ByteArray(10))
        }
        assertNotNull(DirectPartManifest.hashRange(part, 0, 10))
        // 请求超过文件长度：不能返回一个“看起来有效”的摘要
        assertEquals(null, DirectPartManifest.hashRange(part, 0, 11))
        DownloadSaver.deleteDirectPart(part.absolutePath)
    }
}
