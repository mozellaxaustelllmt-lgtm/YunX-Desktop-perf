package com.yunx.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.VideoAccountEntity
import com.yunx.app.data.network.video.VideoParsers
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.ui.items.logoAsset

/**
 * 账号页「视频解析」区：列出全部视频平台及其登录态。
 *
 * ★ 平台列表由 [VideoParsers.supportedPlatforms] 驱动，这里不硬编码——新增平台只改解析器注册表即可。
 * ★ 只负责展示 + 把点击意图抛出去（登录 / 管理），弹窗与登录页交给 DriveScreen / MainScreen。
 *
 * @param loginState 各平台当前账号（platform → 账号），决定显示「未登录 / 昵称 / 备注」
 * @param accounts 全部平台的视频账号，用于统计「共 N 个账号」
 * @param columns 每行卡片数：跟随账号页的四宫格 / 列表模式（1 或 2）
 */
@Composable
internal fun VideoAccountSection(
    loginState: Map<VideoPlatform, VideoAccountEntity>,
    accounts: List<VideoAccountEntity>,
    onLogin: (VideoPlatform) -> Unit,
    onManage: (VideoPlatform) -> Unit,
    columns: Int = 1
) {
    val platforms = remember { VideoParsers.supportedPlatforms }
    // 每个平台已登录的账号数：只有多账号时才需要在状态里补「共 N 个账号」
    val counts = remember(accounts) { accounts.groupingBy { it.platform }.eachCount() }

    // 一个平台一张卡片（与「网盘账号」区的 DriveAccountCard 同款：一样的 shape / 底色 / 间距 / 字号）
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        platforms.chunked(columns.coerceAtLeast(1)).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { platform ->
                    Box(modifier = Modifier.weight(1f)) {
                        VideoPlatformCard(
                            platform = platform,
                            account = loginState[platform],
                            accountCount = counts[platform.name] ?: 0,
                            onLogin = onLogin,
                            onManage = onManage
                        )
                    }
                }
                // 末行不足一整行时补占位，保证卡片宽度与上一行一致
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 单个视频平台的卡片外壳：与 DriveScreen 的 DriveAccountCard 同款（shape = large、surfaceContainerLow） */
@Composable
private fun VideoPlatformCard(
    platform: VideoPlatform,
    account: VideoAccountEntity?,
    accountCount: Int,
    onLogin: (VideoPlatform) -> Unit,
    onManage: (VideoPlatform) -> Unit
) {
    val onClick: (() -> Unit)? = when {
        !platform.needsLogin -> null
        account != null -> { { onManage(platform) } }
        else -> { { onLogin(platform) } }
    }
    val cardShape = MaterialTheme.shapes.large
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )
    val content: @Composable () -> Unit = {
        VideoPlatformCardContent(
            platform = platform,
            account = account,
            accountCount = accountCount,
            onLogin = onLogin,
            onManage = onManage
        )
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = cardColors
        ) { content() }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = cardColors
        ) { content() }
    }
}

/** 单个视频平台卡片的内容行：版式与网盘卡片的内容行对齐（16dp 内边距、40dp Logo、titleMedium） */
@Composable
private fun VideoPlatformCardContent(
    platform: VideoPlatform,
    account: VideoAccountEntity?,
    accountCount: Int,
    onLogin: (VideoPlatform) -> Unit,
    onManage: (VideoPlatform) -> Unit
) {
    val enabled = platform.needsLogin

    Row(
        // 不再自己 clickable：点击手势由外层 Card 承担，否则会叠出两层涟漪
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(platform.logoAsset()),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .alpha(if (platform.needsLogin && account == null) 0.45f else 1f),
            contentScale = ContentScale.Fit
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = platform.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = videoStatusText(platform, account, accountCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when {
            !enabled -> Unit
            account != null -> TextButton(onClick = { onManage(platform) }) { Text("管理") }
            else -> TextButton(onClick = { onLogin(platform) }) { Text("登录") }
        }
    }
}

/** 平台状态文案：免登录 / 未登录 / 账号名（备注优先，其次昵称，都空则「已登录」） */
private fun videoStatusText(
    platform: VideoPlatform,
    account: VideoAccountEntity?,
    accountCount: Int
): String = when {
    !platform.needsLogin -> "无需登录，直接解析"
    account == null -> "未登录"
    else -> {
        val name = account.remark.ifBlank { account.nickname }.ifBlank { "已登录" }
        if (accountCount > 1) "$name · 共 $accountCount 个账号" else name
    }
}
