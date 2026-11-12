package com.yunx.app.ui.items

import com.yunx.app.data.network.video.VideoPlatform

/**
 * 视频平台 Logo 资源名（资源文件在 `src/main/resources/platform_logos/`，200×200 PNG）。
 *
 * 与上游的差异：Android 用 `@DrawableRes` + `R.drawable.*`；桌面没有 R 类，
 * Compose Desktop 的 `painterResource(String)` 直接按 classpath 路径取图，所以这里返回资源名。
 *
 * ★ 渲染约定与上游一致：**按 Logo 自身形状原样画**（不裁圆、不垫底色）。
 * ★ 换图只要覆盖同名 PNG，不用改代码；新增平台在这里补一行。
 */
fun VideoPlatform.logoAsset(): String = when (this) {
    VideoPlatform.DOUYIN -> "platform_logos/douyin.png"
    VideoPlatform.XHS -> "platform_logos/xhs.png"
    VideoPlatform.KUAISHOU -> "platform_logos/kuaishou.png"
    VideoPlatform.WEIBO -> "platform_logos/weibo.png"
    VideoPlatform.ACFUN -> "platform_logos/acfun.png"
    VideoPlatform.BILIBILI -> "platform_logos/bilibili.png"
    VideoPlatform.PIXIV -> "platform_logos/pixiv.png"
}
