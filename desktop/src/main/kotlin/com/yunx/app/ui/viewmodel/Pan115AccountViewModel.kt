package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.yunx.app.data.db.Pan115AccountEntity
import com.yunx.app.data.repository.Pan115AccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 115 网盘账号 ViewModel：内嵌浏览器登录 Cookie → 落库，暴露登录态供主页/登录页/解析页共享。
 */
class Pan115AccountViewModel(
    private val repository: Pan115AccountRepository
) : ViewModel() {

    val pan115Account: StateFlow<Pan115AccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /**
     * 「添加账号」标记：网盘页账号弹窗点「添加账号」时置位，登录页关闭时清掉。
     * 置位后这一次登录会落成新的账号行，而不是覆盖当前主账号（见 resolveAccountSlot）。
     */
    private var addNewAccount = false

    fun beginAddAccount() { addNewAccount = true }

    fun cancelAddAccount() { addNewAccount = false }

    /** 保存登录 Cookie；返回是否保存成功 */
    suspend fun saveCookie(cookie: String): Boolean {
        val saved = repository.saveCookie(cookie, newAccount = addNewAccount)
        if (saved) addNewAccount = false
        return saved
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    class Factory(
        private val repository: Pan115AccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            require(modelClass == Pan115AccountViewModel::class)
            return Pan115AccountViewModel(repository) as T
        }
    }
}
