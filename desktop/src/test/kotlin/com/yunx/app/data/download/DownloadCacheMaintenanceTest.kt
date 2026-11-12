package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * 缓存维护/磁盘守卫的纯逻辑测试。
 * 只覆盖不依赖 AppContext 全局目录、也不真正删用户数据的部分。
 */
class DownloadCacheMaintenanceTest {

    @Test
    fun safetyMarginIsAtLeast256MBOrTwoPercent() {
        assertEquals(256L * 1024 * 1024, DownloadCacheMaintenance.safetyMargin(0))
        assertEquals(256L * 1024 * 1024, DownloadCacheMaintenance.safetyMargin(1024))
        // 10GB 的 2% = 204.8MB < 256MB → 仍取 256MB
        assertEquals(256L * 1024 * 1024, DownloadCacheMaintenance.safetyMargin(10L * 1024 * 1024 * 1024))
        // 100GB 的 2% = 2GB > 256MB
        assertEquals(
            2L * 1024 * 1024 * 1024,
            DownloadCacheMaintenance.safetyMargin(100L * 1024 * 1024 * 1024)
        )
    }

    @Test
    fun detectsOsLevelDiskFullErrors() {
        assertTrue(DownloadCacheMaintenance.isDiskFullError(IOException("No space left on device")))
        assertTrue(
            DownloadCacheMaintenance.isDiskFullError(
                IOException("There is not enough space on the disk", IOException("write failed"))
            )
        )
        assertTrue(DownloadCacheMaintenance.isDiskFullError(IOException("ENOSPC")))
        assertFalse(DownloadCacheMaintenance.isDiskFullError(IOException("Connection reset")))
        // 用户可读的守卫异常不是 OS 级错误
        assertFalse(
            DownloadCacheMaintenance.isDiskFullError(
                DownloadCacheMaintenance.LowDiskSpaceException("缓存目录所在磁盘空间不足")
            )
        )
    }

    @Test
    fun filesUnderSameTempDirAreOnSameVolume() {
        val dir = File("build/cache-maintenance-test").apply { mkdirs() }
        val a = File(dir, "cache")
        val b = File(dir, "downloads")
        assertTrue(DownloadCacheMaintenance.sameVolume(a, b))
    }

    @Test
    fun formatsHumanReadableSizes() {
        assertEquals("0 B", DownloadCacheMaintenance.formatBytes(0))
        assertEquals("512 B", DownloadCacheMaintenance.formatBytes(512))
        assertEquals("1 KB", DownloadCacheMaintenance.formatBytes(1024))
        assertEquals("1.50 MB", DownloadCacheMaintenance.formatBytes(1024L * 1024 * 3 / 2))
        assertEquals("2.00 GB", DownloadCacheMaintenance.formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun guardLetsDownloadStartWhenSpaceIsPlentiful() {
        val dir = File("build/cache-maintenance-test/space").apply { mkdirs() }
        assertNull(DownloadCacheMaintenance.checkBeforeDownload(dir, dir, 0L))
        assertNull(DownloadCacheMaintenance.checkBeforeMerge(dir, 0L))
    }

    @Test
    fun sizeRequirementScalesWithFileAndMultiplier() {
        // 同一磁盘按 2 倍校验；这里只验证不需要知道实际余量也能通过的小文件
        val dir = File("build/cache-maintenance-test/small").apply { mkdirs() }
        assertNull(DownloadCacheMaintenance.checkBeforeDownload(dir, dir, 1L))
    }

    @Test
    fun differentVolumesNeedOneCopyOnCacheAndOneOnDestination() {
        val gb = 1024L * 1024 * 1024
        // 缓存 C 剩 70G、成品 F 剩 300G，下载 20G 文件：C 只需 1 份分片 → 通过
        assertNull(
            DownloadCacheMaintenance.evaluateDownloadSpace(
                cacheFree = 70 * gb, destFree = 300 * gb, sameVolume = false, totalBytes = 20 * gb
            )
        )
        // 缓存只剩 15G，放不下 20G 分片 → 拒绝
        assertNotNull(
            DownloadCacheMaintenance.evaluateDownloadSpace(
                cacheFree = 15 * gb, destFree = 300 * gb, sameVolume = false, totalBytes = 20 * gb
            )
        )
        // 成品盘只剩 15G → 拒绝
        assertNotNull(
            DownloadCacheMaintenance.evaluateDownloadSpace(
                cacheFree = 70 * gb, destFree = 15 * gb, sameVolume = false, totalBytes = 20 * gb
            )
        )
    }

    @Test
    fun sameVolumeRequiresTwoCopiesButCreditsResumeBytes() {
        val gb = 1024L * 1024 * 1024
        // 40G 文件、同盘、70G 空闲：峰值 80G → 拒绝
        assertNotNull(
            DownloadCacheMaintenance.evaluateDownloadSpace(
                cacheFree = 70 * gb, destFree = 70 * gb, sameVolume = true, totalBytes = 40 * gb
            )
        )
        // 已下 30G：只需再补 10G + 最终文件 40G ≈ 50G，55G 空闲 → 通过
        assertNull(
            DownloadCacheMaintenance.evaluateDownloadSpace(
                cacheFree = 55 * gb, destFree = 55 * gb, sameVolume = true,
                totalBytes = 40 * gb, alreadyOnDiskBytes = 30 * gb
            )
        )
    }

    @Test
    fun reservedBytesPreventConcurrentTasksFromOvercommittingCache() {
        val gb = 1024L * 1024 * 1024
        // 20G 文件、缓存剩 70G；其它 3 个任务还要写 60G → 合计 80G+ → 拒绝
        assertNotNull(
            DownloadCacheMaintenance.evaluateDownloadSpace(
                cacheFree = 70 * gb, destFree = 300 * gb, sameVolume = false,
                totalBytes = 20 * gb, reservedCacheBytes = 60 * gb
            )
        )
        // 其它任务只需 40G → 合计约 60G → 通过
        assertNull(
            DownloadCacheMaintenance.evaluateDownloadSpace(
                cacheFree = 70 * gb, destFree = 300 * gb, sameVolume = false,
                totalBytes = 20 * gb, reservedCacheBytes = 40 * gb
            )
        )
    }

    @Test
    fun mergeGuardAccountsForOtherConcurrentMerges() {
        val gb = 1024L * 1024 * 1024
        assertNull(DownloadCacheMaintenance.evaluateMergeSpace(300 * gb, 100 * gb))
        // 200G + 其它任务 150G > 300G → 拒绝
        assertNotNull(
            DownloadCacheMaintenance.evaluateMergeSpace(
                destFree = 300 * gb, totalBytes = 200 * gb, reservedBytes = 150 * gb
            )
        )
    }

    @Test
    fun permanentShortageOnlyWhenTheFileItselfCannotFit() {
        val gb = 1024L * 1024 * 1024
        // 缓存盘只剩 15G，20G 文件 → 永久不足，等待没有意义
        assertTrue(
            DownloadCacheMaintenance.isPermanentSpaceShortage(
                cacheFree = 15 * gb, destFree = 300 * gb, sameVolume = false, totalBytes = 20 * gb
            )
        )
        // 缓存盘 70G、只是被其它任务暂时占着 → 不是永久不足，应排队等待
        assertFalse(
            DownloadCacheMaintenance.isPermanentSpaceShortage(
                cacheFree = 70 * gb, destFree = 300 * gb, sameVolume = false, totalBytes = 20 * gb
            )
        )
        // 同盘 40G 文件、70G 空闲 → 峰值 80G，永久不足
        assertTrue(
            DownloadCacheMaintenance.isPermanentSpaceShortage(
                cacheFree = 70 * gb, destFree = 70 * gb, sameVolume = true, totalBytes = 40 * gb
            )
        )
        // 同盘已下 30G、55G 空闲 → 还能续传完
        assertFalse(
            DownloadCacheMaintenance.isPermanentSpaceShortage(
                cacheFree = 55 * gb, destFree = 55 * gb, sameVolume = true,
                totalBytes = 40 * gb, alreadyOnDiskBytes = 30 * gb
            )
        )
        // 大小未知：只要还在最低水位以上就不判永久不足
        assertFalse(
            DownloadCacheMaintenance.isPermanentSpaceShortage(
                cacheFree = 70 * gb, destFree = 300 * gb, sameVolume = false, totalBytes = 0
            )
        )
    }
}
