package com.yunx.app.util

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.yunx.app.AppContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * 单实例保护：同一份数据目录只允许一个进程在跑。
 *
 * 为什么需要：两个实例会共用同一个数据目录，其中 **CEF 的用户数据目录（`cache/jcef`）也会撞车**。
 * Chromium 是单例进程模型，第二个实例抢不到 profile 锁时会走它自己的「已有实例」处理，
 * 实测后果是第一个实例的渲染 / 网络子进程被杀（jcef.log 里能看到 Tab Killed、Network service crashed），
 * 个别机器上还会被 Chromium 反向拉起系统里注册的浏览器（那个浏览器自己的启动命令若带
 * `--no-sandbox`，就会看到「不受支持的命令行标记」提示条）。
 *
 * 做法：在数据目录上对 `<数据目录>/yunx-instance.lock` 加**独占文件锁**（进程退出时操作系统自动释放）。
 * 拿不到锁说明已有实例在跑 —— 把它的主窗口还原到前台，然后本进程直接退出。
 */
object SingleInstance {

    private const val TAG = "YunX-SingleInstance"

    /** 主窗口标题：与 Main.kt 里 Compose `Window(title = ...)` 保持一致 */
    private const val WINDOW_TITLE = "云析 YunX-Desktop"

    private var holder: RandomAccessFile? = null
    private var lock: FileLock? = null

    /**
     * 尝试成为唯一实例。
     * @return true = 本进程是第一个实例，继续启动；false = 已有实例在跑，调用方应退出
     */
    fun acquire(): Boolean {
        val file = File(AppContext.dataDir, "yunx-instance.lock")
        return try {
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            val acquired = raf.channel.tryLock()
            if (acquired == null) {
                raf.close()
                false
            } else {
                holder = raf
                lock = acquired
                true
            }
        } catch (t: Throwable) {
            // 锁文件不可用（权限 / 被占用）时宁可放行：不能因为保护机制让应用起不来
            Log.w(TAG, "单实例锁不可用，按多实例放行: ${t.message}")
            true
        }
    }

    /**
     * 把已有实例的主窗口还原并拉到前台（第二个实例启动时调用）。
     * 找不到窗口（例如它正在启动 / 已最小化到托盘）时短暂重试几次。
     */
    fun focusExistingWindow() {
        if (!System.getProperty("os.name", "").lowercase().contains("win")) return
        repeat(6) { attempt ->
            val hwnd = runCatching { User32.INSTANCE.FindWindow(null, WINDOW_TITLE) }.getOrNull()
            if (hwnd != null) {
                runCatching {
                    if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
                        User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW)
                    }
                    User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE)
                    User32.INSTANCE.SetForegroundWindow(hwnd)
                }
                Log.i(TAG, "已有实例在运行，已还原其窗口")
                return
            }
            if (attempt < 5) Thread.sleep(200L)
        }
        Log.w(TAG, "已有实例在运行，但没找到主窗口（可能仍在启动）")
    }
}
