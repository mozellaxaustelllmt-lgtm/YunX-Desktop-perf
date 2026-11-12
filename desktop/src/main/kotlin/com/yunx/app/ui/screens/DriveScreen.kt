package com.yunx.app.ui.screens

import com.yunx.app.ui.theme.animateItemIfAllowed
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.BaiduAccountEntity
import com.yunx.app.data.db.C139AccountEntity
import com.yunx.app.data.db.GuangYaAccountEntity
import com.yunx.app.data.db.ILanzouAccountEntity
import com.yunx.app.data.db.LanzouAccountEntity
import com.yunx.app.data.db.Pan115AccountEntity
import com.yunx.app.data.db.Pan123AccountEntity
import com.yunx.app.data.db.QuarkAccountEntity
import com.yunx.app.data.db.UCAccountEntity
import com.yunx.app.data.db.VideoAccountEntity
import com.yunx.app.data.db.XunleiAccountEntity
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.DriveQuotaViewModel
import com.yunx.app.ui.viewmodel.GuangYaCloudViewModel
import com.yunx.app.ui.viewmodel.ILanzouCloudViewModel
import com.yunx.app.ui.viewmodel.LanzouCloudViewModel
import com.yunx.app.ui.viewmodel.MultiAccountViewModel
import com.yunx.app.ui.viewmodel.Pan115CloudViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel
import com.yunx.app.util.maskAccountText

/**
 * 网盘账号展示模型。
 * TODO: 迅雷 / UC 后续接入 cookie 登录后，isLoggedIn 由真实登录态驱动。
 */
private data class DriveAccount(
    val id: String,
    val name: String,
    val description: String,
    val avatarText: String,
    val isLoggedIn: Boolean = false
)

/**
 * 网盘页：
 * - 夸克未登录：点击进入登录页；
 * - 夸克已登录：副标题显示昵称，点击弹出账号信息底部弹窗（可查看 Cookie / 退出登录）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    quarkAccount: QuarkAccountEntity?,
    ucAccount: UCAccountEntity?,
    xunleiAccount: XunleiAccountEntity?,
    baiduAccount: BaiduAccountEntity?,
    c139Account: C139AccountEntity?,
    pan123Account: Pan123AccountEntity?,
    pan115Account: Pan115AccountEntity?,
    guangyaAccount: GuangYaAccountEntity?,
    ilanzouAccount: ILanzouAccountEntity?,
    lanzouAccount: LanzouAccountEntity?,
    /** 夸克云盘浏览 ViewModel（网盘 Tab 内切换展示，非全屏） */
    quarkCloudViewModel: QuarkCloudViewModel,
    /** UC 网盘云盘浏览 ViewModel */
    ucCloudViewModel: UCCoudViewModel,
    /** 迅雷网盘云盘浏览 ViewModel */
    xunleiCloudViewModel: XunleiCloudViewModel,
    /** 百度网盘云盘浏览 ViewModel */
    baiduCloudViewModel: BaiduCloudViewModel,
    /** 139 网盘云盘浏览 ViewModel */
    c139CloudViewModel: C139CloudViewModel,
    /** 123 云盘浏览 ViewModel */
    pan123CloudViewModel: Pan123CloudViewModel,
    /** 115 网盘浏览 ViewModel */
    pan115CloudViewModel: Pan115CloudViewModel,
    /** 光鸭云盘浏览 ViewModel */
    guangyaCloudViewModel: GuangYaCloudViewModel,
    /** 蓝奏云优享版云盘浏览 ViewModel */
    ilanzouCloudViewModel: ILanzouCloudViewModel,
    /** 蓝奏云云盘浏览 ViewModel */
    lanzouCloudViewModel: LanzouCloudViewModel,
    /** 网盘空间详情 ViewModel（顶部空间总览） */
    driveQuotaViewModel: DriveQuotaViewModel,
    onQuarkLogin: () -> Unit,
    onQuarkLogout: () -> Unit,
    /** 夸克云盘下载入队后切换到「下载」Tab */
    onDownloadStarted: () -> Unit = {},
    onUCLogin: () -> Unit,
    onUCLogout: () -> Unit,
    onXunleiLogin: () -> Unit,
    onXunleiLogout: () -> Unit,
    onBaiduLogin: () -> Unit,
    onBaiduLogout: () -> Unit,
    onC139Login: () -> Unit,
    onC139Logout: () -> Unit,
    onPan123Login: () -> Unit,
    onPan123Logout: () -> Unit,
    onPan115Login: () -> Unit,
    onPan115Logout: () -> Unit,
    onGuangYaLogin: () -> Unit,
    onGuangYaLogout: () -> Unit,
    onILanzouLogin: () -> Unit,
    onILanzouLogout: () -> Unit,
    onLanzouLogin: () -> Unit,
    onLanzouLogout: () -> Unit,
    /** 是否已配置 GitHub Token（控制 GitHub 卡片副标题与登录态样式） */
    githubHasToken: Boolean = false,
    /** 点击 GitHub 卡片：已配置 Token 时进入「我的主页」；未配置时打开 Token 管理弹窗 */
    onGitHubTokenClick: () -> Unit = {},
    /** 已配置 Token 时点击卡片主体：进入当前 Token 账号的仓库列表 */
    onGitHubBrowseHome: () -> Unit = {},
    /** 「更多」菜单中清除 Token（二次确认后回调） */
    onGitHubClearToken: () -> Unit = {},
    /** 多账号：账号列表 / 设主账号 / 改备注 / 退出单个账号 */
    multiAccount: MultiAccountViewModel,
    /** 视频解析：各平台登录态（platform → 当前账号） */
    videoLoginState: Map<VideoPlatform, VideoAccountEntity> = emptyMap(),
    /** 视频解析：全部平台账号（用于统计「共 N 个账号」） */
    videoAccounts: List<VideoAccountEntity> = emptyList(),
    /** 视频平台：打开登录页 */
    onVideoLogin: (VideoPlatform) -> Unit = {},
    /** 视频平台：打开账号管理弹窗 */
    onVideoManage: (VideoPlatform) -> Unit = {},
    /** 「添加账号」：传入平台常量，由 MainScreen 清网页登录态并打开该平台登录页 */
    onAddAccount: (String) -> Unit = {},
    /** 「四宫格（两列）/ 列表」布局：由 MainScreen 顶部栏右上角按钮切换 */
    gridMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showQuarkSheet by remember { mutableStateOf(false) }
    var showUCSheet by remember { mutableStateOf(false) }
    var showXunleiSheet by remember { mutableStateOf(false) }
    var showBaiduSheet by remember { mutableStateOf(false) }
    var showC139Sheet by remember { mutableStateOf(false) }
    var showPan123Sheet by remember { mutableStateOf(false) }
    var showPan115Sheet by remember { mutableStateOf(false) }
    var showGuangYaSheet by remember { mutableStateOf(false) }
    var showILanzouSheet by remember { mutableStateOf(false) }
    var showLanzouSheet by remember { mutableStateOf(false) }
    // GitHub 卡片「更多」菜单（浏览主页 / 配置 Token / 清除 Token）
    var showGitHubSheet by remember { mutableStateOf(false) }
    // 夸克云盘浏览：网盘 Tab 内切换（非全屏），切 Tab 再回来仍保留
    var showCloud by rememberSaveable { mutableStateOf(false) }
    // UC 网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showUCCloud by rememberSaveable { mutableStateOf(false) }
    // 迅雷网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showXunleiCloud by rememberSaveable { mutableStateOf(false) }
    // 百度网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showBaiduCloud by rememberSaveable { mutableStateOf(false) }
    // 139 网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showC139Cloud by rememberSaveable { mutableStateOf(false) }
    // 123 云盘浏览：网盘 Tab 内切换（非全屏）
    var showPan123Cloud by rememberSaveable { mutableStateOf(false) }
    // 115 网盘浏览：网盘 Tab 内切换（非全屏）
    var showPan115Cloud by rememberSaveable { mutableStateOf(false) }
    // 光鸭云盘浏览：网盘 Tab 内切换（非全屏）
    var showGuangYaCloud by rememberSaveable { mutableStateOf(false) }
    // 蓝奏云优享版云盘浏览：网盘 Tab 内切换（非全屏）
    var showILanzouCloud by rememberSaveable { mutableStateOf(false) }
    // 蓝奏云云盘浏览：网盘 Tab 内切换（非全屏）
    var showLanzouCloud by rememberSaveable { mutableStateOf(false) }

    // 夸克：登录态由数据库驱动；已登录则副标题显示昵称
    val quark = DriveAccount(
        id = "quark",
        name = "夸克网盘",
        description = quarkAccount?.nickname?.let { maskAccountText(it) } ?: "点击登录，支持解析下载",
        avatarText = "夸",
        isLoggedIn = quarkAccount != null
    )
    val uc = DriveAccount(
        id = "uc",
        name = "UC网盘",
        description = ucAccount?.nickname?.let { maskAccountText(it) } ?: "点击登录，支持解析下载",
        avatarText = "UC",
        isLoggedIn = ucAccount != null
    )
    val xunlei = DriveAccount(
        id = "xunlei",
        name = "迅雷网盘",
        description = xunleiAccount?.nickname?.let { maskAccountText(it) } ?: "点击登录，支持解析下载",
        avatarText = "迅",
        isLoggedIn = xunleiAccount != null
    )
    val baidu = DriveAccount(
        id = "baidu",
        name = "百度网盘",
        description = baiduAccount?.nickname?.let { maskAccountText(it) } ?: "点击登录，支持解析下载",
        avatarText = "度",
        isLoggedIn = baiduAccount != null
    )
    val c139 = DriveAccount(
        id = "c139",
        name = "139网盘",
        description = c139Account?.nickname?.let { maskAccountText(it) } ?: "点击登录，支持解析下载",
        avatarText = "139",
        isLoggedIn = c139Account != null
    )
    val pan123 = DriveAccount(
        id = "pan123",
        name = "123云盘",
        description = pan123Account?.nickname?.let { maskAccountText(it) } ?: "点击登录，支持解析下载",
        avatarText = "123",
        isLoggedIn = pan123Account != null
    )
    val pan115 = DriveAccount(
        id = "pan115",
        name = "115网盘",
        description = pan115Account?.nickname?.let { maskAccountText(it) } ?: "点击登录，支持解析下载",
        avatarText = "115",
        isLoggedIn = pan115Account != null
    )
    val guangya = DriveAccount(
        id = "guangya",
        name = "光鸭云盘",
        description = guangyaAccount?.nickname?.let { maskAccountText(it) } ?: "点击登录，支持解析下载",
        avatarText = "光",
        isLoggedIn = guangyaAccount != null
    )
    val ilanzou = DriveAccount(
        id = "ilanzou",
        name = "蓝奏云优享版",
        description = ilanzouAccount?.nickname?.let { maskAccountText(it) } ?: "点击登录，支持解析下载",
        avatarText = "优",
        isLoggedIn = ilanzouAccount != null
    )
    val lanzou = DriveAccount(
        id = "lanzou",
        name = "蓝奏云",
        description = lanzouAccount?.nickname?.let { maskAccountText(it) } ?: "点击登录，支持解析下载",
        avatarText = "蓝",
        isLoggedIn = lanzouAccount != null
    )
    // GitHub：把 GitHub 当网盘浏览下载；此处仅做 Token 管理（提升 API 限额），浏览入口在解析页
    val github = DriveAccount(
        id = "github",
        name = "GitHub",
        description = if (githubHasToken) "已配置 Token" else "点击配置 Token",
        avatarText = "GH",
        isLoggedIn = githubHasToken
    )
    val others = remember {
        emptyList<DriveAccount>()
    }

    // 进入网盘页加载空间详情（仅已登录平台）
    LaunchedEffect(Unit) {
        driveQuotaViewModel.loadAll()
    }
    // 账号列表 ↔ 夸克云盘 ↔ UC 云盘 ↔ 迅雷云盘 ↔ 百度云盘 ↔ 139 云盘 ↔ 123 云盘：平滑过渡（淡入 + 轻微缩放，不僵硬）
    AnimatedContent(
        targetState = when {
            showCloud -> 1
            showUCCloud -> 2
            showXunleiCloud -> 3
            showBaiduCloud -> 4
            showC139Cloud -> 5
            showPan123Cloud -> 6
            showPan115Cloud -> 7
            showGuangYaCloud -> 8
            showILanzouCloud -> 9
            showLanzouCloud -> 10
            else -> 0
        },
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.98f))
                .togetherWith(fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.98f))
        },
        label = "driveContent"
    ) { target ->
        when (target) {
            1 -> CloudDriveScreen(
                viewModel = quarkCloudViewModel,
                scrollBehavior = scrollBehavior,
                onExit = { showCloud = false },
                onDownloadStarted = onDownloadStarted
            )
            2 -> UCCoudScreen(
            viewModel = ucCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showUCCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        3 -> XunleiCloudScreen(
            viewModel = xunleiCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showXunleiCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        4 -> BaiduCloudScreen(
            viewModel = baiduCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showBaiduCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        5 -> C139CloudScreen(
            viewModel = c139CloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showC139Cloud = false },
            onDownloadStarted = onDownloadStarted
        )
        6 -> Pan123CloudScreen(
            viewModel = pan123CloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showPan123Cloud = false },
            onDownloadStarted = onDownloadStarted
        )
        7 -> Pan115CloudScreen(
            viewModel = pan115CloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showPan115Cloud = false },
            onDownloadStarted = onDownloadStarted
        )
        8 -> GuangYaCloudScreen(
            viewModel = guangyaCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showGuangYaCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        9 -> ILanzouCloudScreen(
            viewModel = ilanzouCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showILanzouCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        10 -> LanzouCloudScreen(
            viewModel = lanzouCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showLanzouCloud = false },
            onDownloadStarted = onDownloadStarted
        )
            else -> LazyVerticalGrid(
                // 四宫格 = 两列；列表 = 单列。切换列数复用同一棵列表，卡片状态不丢。
                columns = GridCells.Fixed(if (gridMode) 2 else 1),
                modifier = modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "登录后即可自动携带凭证解析与下载",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                item(key = quark.id) {
                    DriveAccountCard(
                        account = quark,
                        quota = driveQuotaViewModel.quarkQuota.collectAsState().value,
                        onClick = if (quark.isLoggedIn) {
                            { showCloud = true }
                        } else {
                            onQuarkLogin
                        },
                        onMoreClick = if (quark.isLoggedIn) {
                            { showQuarkSheet = true }
                        } else {
                            null
                        }
                    )
                }
                item(key = uc.id) {
                    DriveAccountCard(
                        account = uc,
                        quota = driveQuotaViewModel.ucQuota.collectAsState().value,
                        onClick = if (uc.isLoggedIn) {
                            { showUCCloud = true }
                        } else {
                            onUCLogin
                        },
                        onMoreClick = if (uc.isLoggedIn) {
                            { showUCSheet = true }
                        } else {
                            null
                        }
                    )
                }
                item(key = xunlei.id) {
                    DriveAccountCard(
                        account = xunlei,
                        quota = driveQuotaViewModel.xunleiQuota.collectAsState().value,
                        onClick = if (xunlei.isLoggedIn) {
                            { showXunleiCloud = true }
                        } else {
                            onXunleiLogin
                        },
                        onMoreClick = if (xunlei.isLoggedIn) {
                            { showXunleiSheet = true }
                        } else {
                            null
                        }
                    )
                }
                item(key = baidu.id) {
                    DriveAccountCard(
                        account = baidu,
                        quota = driveQuotaViewModel.baiduQuota.collectAsState().value,
                        onClick = if (baidu.isLoggedIn) {
                            { showBaiduCloud = true }
                        } else {
                            onBaiduLogin
                        },
                        onMoreClick = if (baidu.isLoggedIn) {
                            { showBaiduSheet = true }
                        } else {
                            null
                        }
                    )
                }
                item(key = c139.id) {
                    DriveAccountCard(
                        account = c139,
                        quota = driveQuotaViewModel.c139Quota.collectAsState().value,
                        onClick = if (c139.isLoggedIn) {
                            { showC139Cloud = true }
                        } else {
                            onC139Login
                        },
                        onMoreClick = if (c139.isLoggedIn) {
                            { showC139Sheet = true }
                        } else {
                            null
                        }
                    )
                }
                item(key = pan123.id) {
                    DriveAccountCard(
                        account = pan123,
                        quota = driveQuotaViewModel.pan123Quota.collectAsState().value,
                        onClick = if (pan123.isLoggedIn) {
                            { showPan123Cloud = true }
                        } else {
                            onPan123Login
                        },
                        onMoreClick = if (pan123.isLoggedIn) {
                            { showPan123Sheet = true }
                        } else {
                            null
                        }
                    )
                }
                item(key = pan115.id) {
                    DriveAccountCard(
                        account = pan115,
                        quota = driveQuotaViewModel.pan115Quota.collectAsState().value,
                        onClick = if (pan115.isLoggedIn) {
                            { showPan115Cloud = true }
                        } else {
                            onPan115Login
                        },
                        onMoreClick = if (pan115.isLoggedIn) {
                            { showPan115Sheet = true }
                        } else {
                            null
                        }
                    )
                }
                item(key = guangya.id) {
                    DriveAccountCard(
                        account = guangya,
                        quota = driveQuotaViewModel.guangyaQuota.collectAsState().value,
                        onClick = if (guangya.isLoggedIn) {
                            { showGuangYaCloud = true }
                        } else {
                            onGuangYaLogin
                        },
                        onMoreClick = if (guangya.isLoggedIn) {
                            { showGuangYaSheet = true }
                        } else {
                            null
                        }
                    )
                }
                item(key = ilanzou.id) {
                    DriveAccountCard(
                        account = ilanzou,
                        quota = driveQuotaViewModel.ilanzouQuota.collectAsState().value,
                        onClick = if (ilanzou.isLoggedIn) {
                            { showILanzouCloud = true }
                        } else {
                            onILanzouLogin
                        },
                        onMoreClick = if (ilanzou.isLoggedIn) {
                            { showILanzouSheet = true }
                        } else {
                            null
                        }
                    )
                }
                item(key = lanzou.id) {
                    // 蓝奏云官方无配额接口，不传 quota（不显示空间条）
                    DriveAccountCard(
                        account = lanzou,
                        onClick = if (lanzou.isLoggedIn) {
                            { showLanzouCloud = true }
                        } else {
                            onLanzouLogin
                        },
                        onMoreClick = if (lanzou.isLoggedIn) {
                            { showLanzouSheet = true }
                        } else {
                            null
                        }
                    )
                }
                items(others, key = { it.id }) { account ->
                    DriveAccountCard(account = account, modifier = animateItemIfAllowed())
                }
                item(key = github.id) {
                    // GitHub：已配置 Token 点击主体进入「我的主页」；未配置打开 Token 弹窗；更多按钮在登录后显示
                    DriveAccountCard(
                        account = github,
                        onClick = if (githubHasToken) onGitHubBrowseHome else onGitHubTokenClick,
                        onMoreClick = if (githubHasToken) { { showGitHubSheet = true } } else null
                    )
                }
                // ---- 视频解析：各平台登录态与账号管理（与网盘区共用同一套卡片版式）----
                item(key = "video_header", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "视频解析",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                    )
                }
                item(key = "video_section", span = { GridItemSpan(maxLineSpan) }) {
                    VideoAccountSection(
                        loginState = videoLoginState,
                        accounts = videoAccounts,
                        // 跟随网盘卡片的列数：四宫格时视频平台也排两列，否则切换模式只有上面变了
                        columns = if (gridMode) 2 else 1,
                        onLogin = onVideoLogin,
                        onManage = onVideoManage
                    )
                }
            }
        }
    }

    // 已登录夸克：点击卡片弹出账号信息底部弹窗（多账号）
    if (showQuarkSheet && quarkAccount != null) {
        val accounts by multiAccount.accountsOf(DownloadPlatform.QUARK).collectAsState()
        QuarkAccountSheet(
            account = quarkAccount,
            accounts = accounts,
            onSetPrimary = { multiAccount.setPrimary(DownloadPlatform.QUARK, it) },
            onRename = { id, remark -> multiAccount.rename(DownloadPlatform.QUARK, id, remark) },
            onRemove = { multiAccount.remove(DownloadPlatform.QUARK, it) },
            onAddAccount = {
                showQuarkSheet = false
                onAddAccount(DownloadPlatform.QUARK)
            },
            onDismiss = { showQuarkSheet = false }
        )
    }

    // 已登录 UC：点击卡片弹出账号信息底部弹窗（多账号）
    if (showUCSheet && ucAccount != null) {
        val accounts by multiAccount.accountsOf(DownloadPlatform.UC).collectAsState()
        UCAccountSheet(
            account = ucAccount,
            accounts = accounts,
            onSetPrimary = { multiAccount.setPrimary(DownloadPlatform.UC, it) },
            onRename = { id, remark -> multiAccount.rename(DownloadPlatform.UC, id, remark) },
            onRemove = { multiAccount.remove(DownloadPlatform.UC, it) },
            onAddAccount = {
                showUCSheet = false
                onAddAccount(DownloadPlatform.UC)
            },
            onDismiss = { showUCSheet = false }
        )
    }

    // 已登录迅雷：点击卡片弹出账号信息底部弹窗（多账号）
    if (showXunleiSheet && xunleiAccount != null) {
        val accounts by multiAccount.accountsOf(DownloadPlatform.XUNLEI).collectAsState()
        XunleiAccountSheet(
            account = xunleiAccount,
            accounts = accounts,
            onSetPrimary = { multiAccount.setPrimary(DownloadPlatform.XUNLEI, it) },
            onRename = { id, remark -> multiAccount.rename(DownloadPlatform.XUNLEI, id, remark) },
            onRemove = { multiAccount.remove(DownloadPlatform.XUNLEI, it) },
            onAddAccount = {
                showXunleiSheet = false
                onAddAccount(DownloadPlatform.XUNLEI)
            },
            onDismiss = { showXunleiSheet = false }
        )
    }

    // 已登录百度：点击卡片弹出账号信息底部弹窗（多账号）
    if (showBaiduSheet && baiduAccount != null) {
        val accounts by multiAccount.accountsOf(DownloadPlatform.BAIDU).collectAsState()
        BaiduAccountSheet(
            account = baiduAccount,
            accounts = accounts,
            onSetPrimary = { multiAccount.setPrimary(DownloadPlatform.BAIDU, it) },
            onRename = { id, remark -> multiAccount.rename(DownloadPlatform.BAIDU, id, remark) },
            onRemove = { multiAccount.remove(DownloadPlatform.BAIDU, it) },
            onAddAccount = {
                showBaiduSheet = false
                onAddAccount(DownloadPlatform.BAIDU)
            },
            onDismiss = { showBaiduSheet = false }
        )
    }

    // 已登录 139：点击卡片弹出账号信息底部弹窗（多账号）
    if (showC139Sheet && c139Account != null) {
        val accounts by multiAccount.accountsOf(DownloadPlatform.C139).collectAsState()
        C139AccountSheet(
            account = c139Account,
            accounts = accounts,
            onSetPrimary = { multiAccount.setPrimary(DownloadPlatform.C139, it) },
            onRename = { id, remark -> multiAccount.rename(DownloadPlatform.C139, id, remark) },
            onRemove = { multiAccount.remove(DownloadPlatform.C139, it) },
            onAddAccount = {
                showC139Sheet = false
                onAddAccount(DownloadPlatform.C139)
            },
            onDismiss = { showC139Sheet = false }
        )
    }

    // 已登录 123：点击卡片弹出账号信息底部弹窗（多账号）
    if (showPan123Sheet && pan123Account != null) {
        val accounts by multiAccount.accountsOf(DownloadPlatform.PAN123).collectAsState()
        Pan123AccountSheet(
            account = pan123Account,
            accounts = accounts,
            onSetPrimary = { multiAccount.setPrimary(DownloadPlatform.PAN123, it) },
            onRename = { id, remark -> multiAccount.rename(DownloadPlatform.PAN123, id, remark) },
            onRemove = { multiAccount.remove(DownloadPlatform.PAN123, it) },
            onAddAccount = {
                showPan123Sheet = false
                onAddAccount(DownloadPlatform.PAN123)
            },
            onDismiss = { showPan123Sheet = false }
        )
    }

    // 已登录 115：点击卡片弹出账号信息底部弹窗（多账号）
    if (showPan115Sheet && pan115Account != null) {
        val accounts by multiAccount.accountsOf(DownloadPlatform.PAN115).collectAsState()
        Pan115AccountSheet(
            account = pan115Account,
            accounts = accounts,
            onSetPrimary = { multiAccount.setPrimary(DownloadPlatform.PAN115, it) },
            onRename = { id, remark -> multiAccount.rename(DownloadPlatform.PAN115, id, remark) },
            onRemove = { multiAccount.remove(DownloadPlatform.PAN115, it) },
            onAddAccount = {
                showPan115Sheet = false
                onAddAccount(DownloadPlatform.PAN115)
            },
            onDismiss = { showPan115Sheet = false }
        )
    }

    // 已登录光鸭云盘：点击卡片弹出账号信息底部弹窗（多账号）
    if (showGuangYaSheet && guangyaAccount != null) {
        val accounts by multiAccount.accountsOf(DownloadPlatform.GUANGYA).collectAsState()
        GuangYaAccountSheet(
            account = guangyaAccount,
            accounts = accounts,
            onSetPrimary = { multiAccount.setPrimary(DownloadPlatform.GUANGYA, it) },
            onRename = { id, remark -> multiAccount.rename(DownloadPlatform.GUANGYA, id, remark) },
            onRemove = { multiAccount.remove(DownloadPlatform.GUANGYA, it) },
            onAddAccount = {
                showGuangYaSheet = false
                onAddAccount(DownloadPlatform.GUANGYA)
            },
            onDismiss = { showGuangYaSheet = false }
        )
    }

    // 已登录蓝奏云优享版：点击卡片弹出账号信息底部弹窗（多账号）
    if (showILanzouSheet && ilanzouAccount != null) {
        val accounts by multiAccount.accountsOf(DownloadPlatform.ILANZOU).collectAsState()
        ILanzouAccountSheet(
            account = ilanzouAccount,
            accounts = accounts,
            onSetPrimary = { multiAccount.setPrimary(DownloadPlatform.ILANZOU, it) },
            onRename = { id, remark -> multiAccount.rename(DownloadPlatform.ILANZOU, id, remark) },
            onRemove = { multiAccount.remove(DownloadPlatform.ILANZOU, it) },
            onAddAccount = {
                showILanzouSheet = false
                onAddAccount(DownloadPlatform.ILANZOU)
            },
            onDismiss = { showILanzouSheet = false }
        )
    }

    // 已登录蓝奏云：点击卡片弹出账号信息底部弹窗（多账号）
    if (showLanzouSheet && lanzouAccount != null) {
        val accounts by multiAccount.accountsOf(DownloadPlatform.LANZOU).collectAsState()
        LanzouAccountSheet(
            account = lanzouAccount,
            accounts = accounts,
            onSetPrimary = { multiAccount.setPrimary(DownloadPlatform.LANZOU, it) },
            onRename = { id, remark -> multiAccount.rename(DownloadPlatform.LANZOU, id, remark) },
            onRemove = { multiAccount.remove(DownloadPlatform.LANZOU, it) },
            onAddAccount = {
                showLanzouSheet = false
                onAddAccount(DownloadPlatform.LANZOU)
            },
            onDismiss = { showLanzouSheet = false }
        )
    }

    // GitHub 卡片「更多」菜单：浏览我的主页 / 配置 Token / 清除 Token（清除由上层做二次确认）
    if (showGitHubSheet) {
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { showGitHubSheet = false },
            title = { Text("GitHub") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showGitHubSheet = false
                            onGitHubBrowseHome()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("浏览我的主页") }
                    TextButton(
                        onClick = {
                            showGitHubSheet = false
                            onGitHubTokenClick()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("配置 Token") }
                    TextButton(
                        onClick = {
                            showGitHubSheet = false
                            onGitHubClearToken()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("清除 Token", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGitHubSheet = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DriveAccountCard(
    account: DriveAccount,
    modifier: Modifier = Modifier,
    /** 网盘空间详情（已登录且有数据时在卡片内显示进度条）；null 不显示 */
    quota: QuotaInfo? = null,
    onClick: (() -> Unit)? = null,
    /** 已登录时右侧「三个点」更多按钮（打开账号弹窗）；null 则不显示 */
    onMoreClick: (() -> Unit)? = null
) {
    val cardShape = MaterialTheme.shapes.large
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )
    val content: @Composable () -> Unit = {
        DriveAccountCardContent(
            account = account,
            quota = quota,
            clickable = onClick != null,
            onMoreClick = onMoreClick
        )
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = cardShape,
            colors = cardColors
        ) { content() }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = cardShape,
            colors = cardColors
        ) { content() }
    }
}

@Composable
private fun DriveAccountCardContent(
    account: DriveAccount,
    quota: QuotaInfo? = null,
    clickable: Boolean,
    onMoreClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 品牌头像（暂用首字母，后续可替换为品牌图标）；尺寸与视频平台 Logo 对齐（#185：48→40dp）
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = if (account.isLoggedIn) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = account.avatarText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = account.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = account.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 已登录且有空间数据：卡片内展示剩余空间进度条（出现时淡入 + 纵向展开，避免突兀）
            AnimatedVisibility(
                visible = account.isLoggedIn && quota != null,
                enter = fadeIn(tween(300)) + expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(300)
                ),
                exit = fadeOut(tween(200)) + shrinkVertically(animationSpec = tween(200))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    quota?.let { QuotaInlineBar(it) }
                }
            }
        }

        when {
            account.isLoggedIn && onMoreClick != null -> IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            account.isLoggedIn -> LoginBadge(isLoggedIn = true)
            clickable -> Text(
                text = "去登录",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            else -> LoginBadge(isLoggedIn = false)
        }
    }
}

/** 网盘卡片内空间进度条：已用 / 总容量 + 细进度条 */
@Composable
private fun QuotaInlineBar(quota: QuotaInfo) {
    val ratio = if (quota.total > 0) {
        (quota.used.toFloat() / quota.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column {
        Text(
            text = "已用 ${formatBytes(quota.used)} / ${formatBytes(quota.total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun LoginBadge(isLoggedIn: Boolean) {
    val (label, color) = if (isLoggedIn) {
        "已登录" to MaterialTheme.colorScheme.primary
    } else {
        "未登录" to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 字节数格式化：B / KB / MB / GB / TB */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B"
    else String.format("%.1f %s", value, units[unit])
}