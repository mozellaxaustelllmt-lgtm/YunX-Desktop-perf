package com.yunx.app.util

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * 启动时选择 Skiko 渲染后端（仅 Windows 用）。
 *
 * 背景：默认的 OPENGL（ANGLE）在**老旧 / 基础显示适配器**上会出现
 * 「界面有形状、但一个文字都不渲染」的问题（典型：ATI Radeon 3000 这类 DX10 时代的集显）。
 * 这类设备自动退到 SOFTWARE（CPU 光栅化）：帧率低一些，但文字与图形一定正确。
 *
 * ★ 仍可用环境变量 `YUNXPC_RENDER_API=OPENGL / DIRECT3D / SOFTWARE` 强制指定，优先级高于本检测。
 */
internal object RenderApiChooser {

    /**
     * 已知在 ANGLE/OpenGL 下容易丢失文字的老旧 / 基础显卡关键字（小写匹配）。
     * 只列「确定有问题」的类型，避免误伤能正常跑 GPU 渲染的机器。
     */
    private val LEGACY_GPU_MARKERS = listOf(
        // AMD/ATI 老集显与入门卡
        "radeon 3000", "radeon 3100", "radeon 3200", "radeon 3300", "radeon 3400",
        "radeon hd 2", "radeon hd 3", "radeon hd 4", "radeon hd 5",
        "radeon x1", "radeon x2", "radeon x3", "radeon x6",
        // Intel 老集显
        "intel(r) gma", "intel gma", "mobile intel",
        // 没有真实 GPU 驱动时的兜底适配器
        "microsoft basic", "standard vga",
        // 常见虚拟机显卡
        "vmware svga", "virtualbox", "qemu", "cirrus"
    )

    /** 返回写入 `skiko.renderApi` 的值；拿不到显卡信息时维持 OPENGL（保持现状） */
    fun chooseWindows(): String {
        val gpu = gpuNames().firstOrNull { name ->
            val n = name.lowercase()
            LEGACY_GPU_MARKERS.any { n.contains(it) }
        }
        if (gpu != null) {
            Log.w("YunX-Render", "检测到老旧/基础显卡「$gpu」，改用 SOFTWARE 渲染（避免文字不显示）")
            return "SOFTWARE"
        }
        return "OPENGL"
    }

    /**
     * 从注册表取当前显示适配器名称：
     * `HKLM\HARDWARE\DEVICEMAP\VIDEO` 的 `\Device\VideoN` 指向 `Control\Video\{GUID}\0000`，
     * 该键的 `DriverDesc` 即驱动的显示名。毫秒级、不起子进程。
     *
     * ★ 刻意只读 DEVICEMAP 指向的**当前**适配器键：直接枚举 `Control\Video\*` 会把历史遗留的
     *   老显卡条目也读出来，导致换过显卡的机器被误判成老设备。
     */
    private fun gpuNames(): List<String> = runCatching {
        val names = mutableListOf<String>()
        for (index in 0 until 8) {
            val devicePath = runCatching {
                Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE,
                    "HARDWARE\\DEVICEMAP\\VIDEO",
                    "\\Device\\Video$index"
                )
            }.getOrNull() ?: break
            val relative = devicePath.substringAfter("\\Registry\\Machine\\", "").trim()
            if (relative.isEmpty()) continue
            val desc = runCatching {
                Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, relative, "DriverDesc")
            }.getOrNull()
            if (!desc.isNullOrBlank()) names += desc
        }
        names
    }.getOrDefault(emptyList())
}
