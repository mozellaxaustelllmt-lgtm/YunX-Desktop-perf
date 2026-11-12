package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.MultiAccountEntity
import com.yunx.app.data.db.VideoAccountEntity
import com.yunx.app.data.network.video.VideoPlatform

/**
 * 某个视频平台的账号弹窗：列出该平台的全部账号（设主账号 / 改备注 / 退出），
 * 并提供「添加账号」与「退出全部账号」。
 *
 * 账号行的版式与交互完全复用网盘那套 [AccountListSection]，不另写一套多账号逻辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoAccountSheet(
    platform: VideoPlatform,
    accounts: List<VideoAccountEntity>,
    onSetPrimary: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onAddAccount: () -> Unit,
    onLogoutAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "${platform.displayName}账号",
                style = MaterialTheme.typography.titleLarge
            )
            if (platform.loginBenefit.isNotBlank()) {
                Text(
                    text = platform.loginBenefit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AccountListSection(
                accounts = accounts as List<MultiAccountEntity>,
                onSetPrimary = onSetPrimary,
                onRename = onRename,
                onRemove = onRemove,
                onAddAccount = onAddAccount,
                onEmpty = onDismiss
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onLogoutAll) {
                    Text(
                        text = "退出全部账号",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
