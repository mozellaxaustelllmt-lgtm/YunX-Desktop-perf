package com.yunx.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.download.DownloadSaver
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.SnackbarController
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载保存目录行：显示当前落盘目录，并允许**就地更换**。
 *
 * 用于「开始下载」的确认弹窗（直链确认 / 手动添加任务）——此前那里只显示目录、
 * 想改必须绕到「设置 → 储存目录」，而用户此刻正盯着「文件存哪儿」，就地改最顺手。
 *
 * 与「设置 → 储存目录 → 下载保存目录」是**同一项配置**（[SettingsRepository.downloadDirUri]），
 * 改完双方都生效；下载落盘位置在任务入队时读取，所以立刻生效、不需要重启。
 */
@Composable
fun DownloadDirRow(modifier: Modifier = Modifier) {
    val settingsRepo = remember { SettingsRepository() }
    val scope = rememberCoroutineScope()
    var dirUri by remember { mutableStateOf(settingsRepo.downloadDirUri) }
    val shownPath = DownloadSaver.resolveSaveDir(dirUri).absolutePath

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (dirUri.isNullOrBlank()) "保存到（默认：系统「下载」）" else "保存到（已自定义）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = shownPath,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = {
                scope.launch {
                    // 原生目录选择器是阻塞式模态对话框，必须在 IO 线程打开，否则卡住 UI
                    val picked = withContext(Dispatchers.IO) { DesktopActions.pickDirectory() }
                    if (picked != null) {
                        settingsRepo.downloadDirUri = picked
                        dirUri = picked
                        SnackbarController.show("下载保存目录已更新")
                    }
                }
            }) { Text("更改") }
        }
    }
}
