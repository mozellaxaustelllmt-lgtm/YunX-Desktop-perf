package com.yunx.app.data.db

/**
 * 视频解析账号（所有平台共用一张表，用 [platform] 区分）。
 *
 * ★ 为什么不学网盘那样「每个平台一张表」：网盘账号的凭证字段差异很大（cookie / access token /
 *   设备号 / 令牌刷新态），而视频平台**只需要一个完整 Cookie 头**，结构完全一致；用一张表意味着
 *   以后新增视频平台不必再写表结构迁移。
 *
 * [cookie] 与网盘凭证同等对待：经 `SecureAccountDaos.videoAccount` 加密后落库，
 * 明文只在内存中短暂存在。[id] / [isPrimary] / [remark] 沿用网盘多账号那套约定。
 */
data class VideoAccountEntity(
    override val id: String,
    /** 平台标识：`VideoPlatform.name`（如 `BILIBILI`） */
    val platform: String,
    /** 该平台完整 Cookie 头（加密存储） */
    val cookie: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = false,
    override val remark: String = ""
) : MultiAccountEntity
