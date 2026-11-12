package com.yunx.app.data.preview

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.yunx.app.util.Log
import java.awt.image.BufferedImage

/**
 * ffplay 窗口的 Win32 操作（Windows 专用）。
 *
 * 目的：把 ffplay 的顶层窗口用 `SetParent` 变成应用窗口的子窗口，让在线预览「长在」应用里，
 * 外观统一。嵌入失败（拿不到句柄 / API 不可用）由调用方降级为独立窗口。
 *
 * 实现要点：
 * - 子窗口按 **PID** 定位（不依赖标题，标题里可能带视频名）；要求 `IsWindowVisible`
 *   且无 `WS_EX_TOOLWINDOW` —— SDL2 会额外造一个隐藏的消息窗口，用它过滤掉；
 * - 嵌入前先 `ShowWindow(SW_HIDE)` 减少闪烁，改完样式再 `ShowWindow(SW_SHOW)`；
 * - 重量级原生子窗口会盖住 Compose 控件，所以控制条必须放在视频区**之外**（现有布局已满足）。
 */
internal object FfplayWindowFx {

    private const val TAG = "YunX-FfplayWin"

    private interface User32Ex : Library {
        fun EnumWindows(cb: WinUser.WNDENUMPROC, data: Pointer?): Boolean
        fun GetWindowThreadProcessId(hwnd: WinDef.HWND, pid: IntByReference): Int
        fun IsWindowVisible(hwnd: WinDef.HWND): Boolean
        fun SetParent(child: WinDef.HWND, parent: WinDef.HWND?): WinDef.HWND?
        fun GetWindowLongW(hwnd: WinDef.HWND, index: Int): Int
        fun SetWindowLongW(hwnd: WinDef.HWND, index: Int, value: Int): Int
        fun MoveWindow(hwnd: WinDef.HWND, x: Int, y: Int, w: Int, h: Int, repaint: Boolean): Boolean
        fun SetWindowPos(
            hwnd: WinDef.HWND,
            after: WinDef.HWND?,
            x: Int,
            y: Int,
            cx: Int,
            cy: Int,
            flags: Int
        ): Boolean
        fun ShowWindow(hwnd: WinDef.HWND, cmd: Int): Boolean
        fun PostMessageW(hwnd: WinDef.HWND, msg: Int, wParam: Long, lParam: Long): Boolean
        fun MapVirtualKeyW(code: Int, mapType: Int): Int
        fun GetWindowRect(hwnd: WinDef.HWND, rect: WinDef.RECT): Boolean
        fun GetClientRect(hwnd: WinDef.HWND, rect: WinDef.RECT): Boolean
        fun GetDC(hwnd: WinDef.HWND): Pointer?
        fun ReleaseDC(hwnd: WinDef.HWND, hdc: Pointer?): Int
        fun PrintWindow(hwnd: WinDef.HWND, hdc: Pointer?, flags: Int): Boolean
    }

    private interface Gdi32Ex : Library {
        fun CreateCompatibleDC(hdc: Pointer?): Pointer?
        fun DeleteDC(hdc: Pointer?): Boolean
        fun CreateDIBSection(
            hdc: Pointer?,
            info: Pointer?,
            usage: Int,
            bits: PointerByReference?,
            section: Pointer?,
            offset: Int
        ): Pointer?
        fun SelectObject(hdc: Pointer?, obj: Pointer?): Pointer?
        fun DeleteObject(obj: Pointer?): Boolean
        fun BitBlt(
            dst: Pointer?,
            x: Int, y: Int, w: Int, h: Int,
            src: Pointer?,
            srcX: Int, srcY: Int,
            rop: Int
        ): Boolean
    }

    /**
     * 组装 `BITMAPINFOHEADER`（40 字节，32bpp BI_RGB，无调色板）。
     *
     * 直接写原生内存而不用 JNA 的 `Structure`：`Structure` 靠反射回写字段，
     * 在 Kotlin 嵌套类上实测会抛 `Exception reading field 'biSize'`，手写更稳。
     * `Memory` 初始为全 0，所以调色板相关字段（偏移 24 起）不用管。
     */
    private fun buildBitmapInfo(width: Int, height: Int): Memory {
        val info = Memory(40)
        info.setInt(0, 40)                  // biSize
        info.setInt(4, width)               // biWidth
        info.setInt(8, -height)             // biHeight：负 = 自顶向下，省一次翻转
        info.setShort(12, 1.toShort())      // biPlanes
        info.setShort(14, 32.toShort())     // biBitCount
        info.setInt(16, 0)                  // biCompression = BI_RGB
        info.setInt(20, width * height * 4) // biSizeImage
        return info
    }

    private val user32: User32Ex? by lazy {
        runCatching {
            if (!FfplayManager.isWindows()) return@lazy null
            Native.load("user32", User32Ex::class.java)
        }.getOrNull()
    }

    private val gdi32: Gdi32Ex? by lazy {
        runCatching {
            if (!FfplayManager.isWindows()) return@lazy null
            Native.load("gdi32", Gdi32Ex::class.java)
        }.getOrNull()
    }

    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20

    private const val WS_CHILD = 0x40000000
    private const val WS_VISIBLE = 0x10000000
    private const val WS_POPUP = 0x80000000.toInt()
    private const val WS_CAPTION = 0x00C00000
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_MINIMIZEBOX = 0x00020000
    private const val WS_MAXIMIZEBOX = 0x00010000
    private const val WS_SYSMENU = 0x00080000

    private const val WS_EX_APPWINDOW = 0x00040000
    private const val WS_EX_TOOLWINDOW = 0x00000080

    private const val SW_HIDE = 0
    private const val SW_SHOWNORMAL = 1
    private const val SW_SHOW = 5

    /** [revealWindow] 兜底显示位置（屏幕坐标） */
    private const val REVEAL_X = 120
    private const val REVEAL_Y = 120

    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    private const val WM_SETFOCUS = 0x0007
    private const val WM_KEYDOWN = 0x0100
    private const val WM_KEYUP = 0x0101

    private const val PW_RENDERFULLCONTENT = 2
    private const val SRCCOPY = 0x00CC0020

    /** 抓到的画面里非黑像素占比低于该值 → 认为抓失败（D3D 交换链抓黑） */
    private const val MIN_NON_BLACK = 0.01

    private val CHILD_STYLE_MASK =
        WS_CAPTION or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX or WS_SYSMENU or WS_POPUP

    /** 本进程可见顶层窗口里面积最大的那个（宿主窗口兜底） */
    fun largestWindowOfCurrentProcess(): WinDef.HWND? {
        val api = user32 ?: return null
        val pid = ProcessHandle.current().pid()
        var best: WinDef.HWND? = null
        var bestArea = 0L
        runCatching {
            api.EnumWindows({ hwnd, _ ->
                if (belongsTo(hwnd, pid) && !isToolWindow(hwnd)) {
                    val rect = WinDef.RECT()
                    if (api.GetWindowRect(hwnd, rect)) {
                        val area = (rect.right - rect.left).toLong() * (rect.bottom - rect.top).toLong()
                        if (area > bestArea) {
                            bestArea = area
                            best = hwnd
                        }
                    }
                }
                true
            }, null)
        }
        return best
    }

    /** 按 PID 找 ffplay 的可见窗口（轮询调用，直到出现） */
    fun findWindowForProcess(pid: Long): WinDef.HWND? {
        val api = user32 ?: return null
        var found: WinDef.HWND? = null
        runCatching {
            api.EnumWindows({ hwnd, _ ->
                if (belongsTo(hwnd, pid) && !isToolWindow(hwnd)) {
                    found = hwnd
                    return@EnumWindows false
                }
                true
            }, null)
        }
        return found
    }

    private fun belongsTo(hwnd: WinDef.HWND, pid: Long): Boolean {
        val api = user32 ?: return false
        val ref = IntByReference()
        api.GetWindowThreadProcessId(hwnd, ref)
        if (ref.value.toLong() != pid) return false
        return api.IsWindowVisible(hwnd)
    }

    private fun isToolWindow(hwnd: WinDef.HWND): Boolean {
        val api = user32 ?: return true
        val ex = api.GetWindowLongW(hwnd, GWL_EXSTYLE)
        return (ex and WS_EX_TOOLWINDOW) != 0
    }

    /** 把 [child] 嵌入 [parent]：剥边框、加 WS_CHILD、定位并显示。返回是否成功。 */
    fun embed(child: WinDef.HWND, parent: WinDef.HWND, x: Int, y: Int, w: Int, h: Int): Boolean {
        val api = user32 ?: return false
        return runCatching {
            api.ShowWindow(child, SW_HIDE)
            api.SetParent(child, parent)
            val style = api.GetWindowLongW(child, GWL_STYLE)
            api.SetWindowLongW(
                child,
                GWL_STYLE,
                (style and CHILD_STYLE_MASK.inv()) or WS_CHILD or WS_VISIBLE
            )
            val ex = api.GetWindowLongW(child, GWL_EXSTYLE)
            api.SetWindowLongW(child, GWL_EXSTYLE, ex and WS_EX_APPWINDOW.inv())
            api.MoveWindow(child, x, y, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
            api.SetWindowPos(
                child,
                null,
                0, 0, 0, 0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED
            )
            api.ShowWindow(child, SW_SHOW)
            true
        }.onFailure { Log.w(TAG, "ffplay 窗口嵌入失败: ${it.message}") }.getOrDefault(false)
    }

    /** 同步子窗口位置（坐标为宿主**客户区**坐标，与 Compose 的 boundsInWindow 一致） */
    fun moveWindow(child: WinDef.HWND, x: Int, y: Int, w: Int, h: Int) {
        val api = user32 ?: return
        runCatching { api.MoveWindow(child, x, y, w.coerceAtLeast(1), h.coerceAtLeast(1), true) }
    }

    /**
     * 把窗口从屏幕外搬回可见区（嵌入失败时的兜底）。
     *
     * ffplay 的窗口是由 `-left/-top` **创建在屏幕外**的（见 [FfplayManager.buildCommand]），
     * 一旦嵌入没成功就必须搬回来，否则用户什么都看不到。
     */
    fun revealWindow(child: WinDef.HWND) {
        val api = user32 ?: return
        runCatching {
            val rect = WinDef.RECT()
            api.GetWindowRect(child, rect)
            val w = (rect.right - rect.left).coerceAtLeast(320)
            val h = (rect.bottom - rect.top).coerceAtLeast(240)
            api.MoveWindow(child, REVEAL_X, REVEAL_Y, w, h, true)
            api.ShowWindow(child, SW_SHOWNORMAL)
            api.SetWindowPos(
                child,
                null,
                0, 0, 0, 0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE
            )
        }.onFailure { Log.w(TAG, "ffplay 窗口搬回可见区失败: ${it.message}") }
    }

    /**
     * 抓取窗口当前画面（seek 期间用来「保留最后一帧」）。
     *
     * ffplay 用 D3D 渲染，`BitBlt` 未必能拿到画面，所以先 `BitBlt`，
     * 若结果几乎全黑再用 `PrintWindow(PW_RENDERFULLCONTENT)` 重抓一次；
     * 两次都不可用则返回 null（调用方降级为黑底 + 加载中）。
     */
    fun captureWindow(hwnd: WinDef.HWND?): BufferedImage? {
        val h = hwnd ?: return null
        val u = user32 ?: return null
        val g = gdi32 ?: return null
        return runCatching { capture(u, g, h) }
            .onFailure { Log.w(TAG, "抓取 ffplay 画面失败: ${it.message}") }
            .getOrNull()
    }

    private fun capture(u: User32Ex, g: Gdi32Ex, hwnd: WinDef.HWND): BufferedImage? {
        val rect = WinDef.RECT()
        if (!u.GetClientRect(hwnd, rect)) return null
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top
        if (w <= 0 || h <= 0) return null

        val srcDc = u.GetDC(hwnd) ?: return null
        try {
            val memDc = g.CreateCompatibleDC(srcDc) ?: return null
            try {
                val info = buildBitmapInfo(w, h)
                val bitsRef = PointerByReference()
                val dib = g.CreateDIBSection(srcDc, info, 0, bitsRef, null, 0) ?: return null
                val bits = bitsRef.value ?: return null
                val old = g.SelectObject(memDc, dib)
                try {
                    g.BitBlt(memDc, 0, 0, w, h, srcDc, 0, 0, SRCCOPY)
                    var pixels = readPixels(bits, w * h)
                    if (nonBlackRatio(pixels) < MIN_NON_BLACK) {
                        bits.clear((w.toLong() * h * 4))
                        u.PrintWindow(hwnd, memDc, PW_RENDERFULLCONTENT)
                        pixels = readPixels(bits, w * h)
                    }
                    if (nonBlackRatio(pixels) < MIN_NON_BLACK) return null
                    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                    image.setRGB(0, 0, w, h, pixels, 0, w)
                    return image
                } finally {
                    g.SelectObject(memDc, old)
                    g.DeleteObject(dib)
                }
            } finally {
                g.DeleteDC(memDc)
            }
        } finally {
            u.ReleaseDC(hwnd, srcDc)
        }
    }

    /** DIB 的 32bpp 内存是 BGRA（小端读出即 `0x00RRGGBB`），补上不透明 alpha */
    private fun readPixels(bits: Pointer, count: Int): IntArray {
        val px = bits.getIntArray(0, count)
        for (i in px.indices) px[i] = px[i] or ALPHA_OPAQUE
        return px
    }

    private fun nonBlackRatio(px: IntArray): Double {
        if (px.isEmpty()) return 0.0
        var hit = 0
        for (p in px) if ((p and 0x00FFFFFF) > 0x00080808) hit++
        return hit.toDouble() / px.size
    }

    private const val ALPHA_OPAQUE = 0xFF000000.toInt()

    /**
     * 转发一个按键给 ffplay（我方控制条 → ffplay 进程）。
     *
     * 前置一条 `WM_SETFOCUS`：SDL2 在 `WM_SETFOCUS` 里会 `SDL_SetKeyboardFocus`，
     * 对冲 SDL2 处理 `WM_KEYDOWN` 时的键盘焦点校验。
     */
    fun sendKey(child: WinDef.HWND, vk: Int, keyUp: Boolean) {
        val api = user32 ?: return
        runCatching {
            api.PostMessageW(child, WM_SETFOCUS, 0L, 0L)
            val scan = api.MapVirtualKeyW(vk, 0)
            var lParam = 1L or (scan.toLong() shl 16)
            if (isExtendedKey(vk)) lParam = lParam or (1L shl 24)
            if (keyUp) lParam = lParam or (1L shl 30) or (1L shl 31)
            api.PostMessageW(child, if (keyUp) WM_KEYUP else WM_KEYDOWN, vk.toLong(), lParam)
        }
    }

    /** 方向键等属于扩展键，lParam 的第 24 位要置 1（SDL2 靠它区分小键盘与方向键） */
    private fun isExtendedKey(vk: Int): Boolean = when (vk) {
        0x21, 0x22, 0x23, 0x24, // PageUp / PageDown / End / Home
        0x25, 0x26, 0x27, 0x28, // Left / Up / Right / Down
        0x2D, 0x2E,             // Insert / Delete
        0x5B, 0x5C, 0x5D        // LWin / RWin / Apps
        -> true
        else -> false
    }
}
