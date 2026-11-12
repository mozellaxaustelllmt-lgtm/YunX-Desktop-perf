package com.yunx.app.data.download

/**
 * 一个下载源：直链 + 它专属的请求头。
 *
 * ★ 单文件多源分片（实验性 C2）下，同一个文件的多条直链分属**不同账号**，
 *   请求头里的 Cookie 不能混用 —— 所以「直链 + 请求头」必须成对携带，
 *   不能像单源那样全任务共用一份 headers（那会拿 A 账号的直链配 B 账号的 Cookie，CDN 直接 412/403）。
 */
data class DownloadSource(
    val url: String,
    val headers: Map<String, String>
)
