package com.yunx.app.data.download

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** 实验性多线程直写的核心写入路径：按绝对偏移写、Range 校验、200 回退信号 */
class ChunkDownloaderDirectTest {

    private lateinit var server: MockWebServer
    private val downloader = ChunkDownloader { OkHttpClient() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun writesRangeAtAbsoluteOffset(): Unit = runBlocking {
        val data = ByteArray(256 * 1024) { (it % 251).toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                    ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(data))
                val (s, e) = parseRange(range)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $s-$e/${data.size}")
                    .setBody(Buffer().write(data, s.toInt(), (e - s + 1).toInt()))
            }
        }
        val part = File("build/direct-downloader-test/part.bin").apply {
            parentFile?.mkdirs()
            delete()
            writeBytes(ByteArray(data.size))
        }
        val start = 64L * 1024
        val end = start + 64L * 1024 - 1
        FileChannel.open(
            part.toPath(),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { channel ->
            val result = downloader.downloadRangeToChannel(
                taskId = 1L,
                url = server.url("/file").toString(),
                start = start,
                end = end,
                channel = channel,
                headers = emptyMap()
            ) { }
            assertEquals(ChunkResult.OK, result)
        }
        val bytes = part.readBytes()
        val len = (end - start + 1).toInt()
        for (i in 0 until len) {
            assertEquals("offset $i", data[start.toInt() + i], bytes[start.toInt() + i])
        }
        // 区间之外的预分配区域应保持为 0（证明写的是绝对偏移，没有顺序覆盖）
        assertEquals(0, bytes[0].toInt())
        part.delete()
    }

    @Test
    fun returnsRangeIgnoredOn200(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))
        val part = File("build/direct-downloader-test/part-200.bin").apply {
            parentFile?.mkdirs()
            delete()
            writeBytes(ByteArray(10))
        }
        FileChannel.open(
            part.toPath(),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { channel ->
            val result = downloader.downloadRangeToChannel(
                taskId = 2L,
                url = server.url("/file").toString(),
                start = 0,
                end = 9,
                channel = channel,
                headers = emptyMap()
            ) { }
            assertEquals(ChunkResult.RANGE_IGNORED, result)
        }
        part.delete()
    }

    private fun parseRange(header: String): Pair<Long, Long> {
        val body = header.removePrefix("bytes=")
        val s = body.substringBefore('-').trim().toLong()
        val e = body.substringAfter('-').trim().toLong()
        return s to e
    }
}
