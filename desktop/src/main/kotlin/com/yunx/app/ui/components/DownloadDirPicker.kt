package com.yunx.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「下载到…」目录选择器。
 *
 * 返回一个回调：调用后弹出系统原生目录选择器，选中时执行 [onPicked]，取消则不执行。
 * 对话框在 IO 线程打开（Windows 原生选择器会阻塞当前线程），与「设置 → 储存目录」的用法一致。
 */
@Composable
fun rememberDownloadDirPicker(): (onPicked: (String) -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        { onPicked ->
            scope.launch {
                val dir = withContext(Dispatchers.IO) { DesktopActions.pickDirectory() }
                if (!dir.isNullOrBlank()) onPicked(dir)
            }
        }
    }
}
