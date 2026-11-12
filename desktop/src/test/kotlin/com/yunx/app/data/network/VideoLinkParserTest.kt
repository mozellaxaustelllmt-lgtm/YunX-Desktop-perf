package com.yunx.app.data.network

import com.yunx.app.data.network.video.VideoLinkParser
import com.yunx.app.data.network.video.VideoPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLinkParserTest {

    @Test
    fun detectsAllSupportedPlatforms() {
        val cases = listOf(
            "https://www.douyin.com/video/7123456789012345678" to VideoPlatform.DOUYIN,
            "https://www.xiaohongshu.com/explore/65f0a1b2c3d4e5f60718293a" to VideoPlatform.XHS,
            "https://www.kuaishou.com/short-video/3xa1b2c3d4e" to VideoPlatform.KUAISHOU,
            "https://weibo.com/1234567/NabcDEFghi" to VideoPlatform.WEIBO,
            "https://www.acfun.cn/v/ac123456" to VideoPlatform.ACFUN,
            "https://www.bilibili.com/video/BV1xx411c7mD" to VideoPlatform.BILIBILI,
            "https://www.pixiv.net/artworks/12345678" to VideoPlatform.PIXIV
        )

        cases.forEach { (text, platform) ->
            val parsed = VideoLinkParser.parse(text)
            assertEquals("平台识别失败：$text", platform, parsed?.platform)
        }
    }

    @Test
    fun shortLinksHaveNoIdAndAreFlagged() {
        val parsed = VideoLinkParser.parse("复制打开抖音 https://v.douyin.com/iAbCdEf/ 看看")!!
        assertEquals(VideoPlatform.DOUYIN, parsed.platform)
        assertTrue(parsed.isShortLink)
        assertEquals("", parsed.id)
    }

    @Test
    fun trailingChinesePunctuationIsStripped() {
        val parsed = VideoLinkParser.parse("看这个 https://www.bilibili.com/video/BV1xx411c7mD。")!!
        assertEquals(VideoPlatform.BILIBILI, parsed.platform)
        assertEquals("BV1xx411c7mD", parsed.id)
    }

    @Test
    fun netdiskLinkIsNotMistakenForVideo() {
        assertNull(VideoLinkParser.parse("https://pan.quark.cn/s/Abc123?pwd=a1B2"))
    }

    @Test
    fun bareNumericIdIsNotRecognized() {
        // 网盘提取码 / 迅雷口令里常有一串数字，不能被当成抖音作品
        assertNull(VideoLinkParser.parse("提取码 7123456789012345678"))
    }
}
