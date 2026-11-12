package com.yunx.app.ui.login

import com.yunx.app.ui.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunx.app.util.DesktopActions

/**
 * 迅雷安全验证（桌面版）：
 * 验证页由用户点「打开验证页」在系统浏览器打开（**不自动拉起浏览器**，
 * 自动弹浏览器会让用户误以为应用劫持了默认浏览器）；
 * 用户完成验证后回到应用点击「验证完成，重新登录」重试密码登录。
 */
@Composable
fun XunleiVerifyWebViewScreen(
    verifyUrl: String,
    deviceId: String,
    onResult: (success: Boolean, extra: String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    AlertDialog(
        onDismissRequest = onBack,
        icon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
        title = { Text("迅雷安全验证") },
        text = {
            Column {
                Text(
                    text = "账号触发了迅雷的安全验证。桌面版无内嵌浏览器，请按以下步骤操作：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "1. 点击下方「打开验证页」，在系统浏览器里完成人机验证\n" +
                        "2. 验证成功后，回到本窗口点击「验证完成，重新登录」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { if (verifyUrl.isNotBlank()) DesktopActions.openUrl(verifyUrl) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("打开验证页")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onResult(true, "") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("验证完成，重新登录")
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text("返回") }
        }
    )
}
