# 性能支线：万级下载任务（1 万+ 文件）不再卡顿

> 本仓库是 [tidain/YunX-Desktop](https://github.com/tidain/YunX-Desktop) 的**性能补丁支线**：
> 基于上游 `v1.3.3`（`main` @ 2026-11-04），改动集中在「任务表 → 内存列表 → Compose 列表」这条链路。
> 上游功能一行没删，只修性能与几处由此暴露的边界问题。

## 1. 这个支线解决什么

真实场景：从网盘把 U 盘备份拉回来，**10,000+ 个条目**。上游 1.3.3 在这个量级下：

- 批量入队时越来越慢（加到第 3000 个时，每加一个都在卡）；
- 下载页滚动、暂停、删除全部都会冻住几秒到几十秒；
- 内存与 CPU 被吃满，任务表越大越糟。

**根因不在下载引擎**（分片多线程其实做得很好），而是四处设计在 N 上千~上万时同时爆掉：

| # | 问题 | 位置（上游） | 复杂度 |
|---|---|---|---|
| 1 | 每次写库都 `loadAll()` 全表重读 + 发射新列表 | `AppDatabase.kt` `JdbcDownloadTaskDao.reload()` | 入队 N 条 = **O(N²)**（3000 条≈450 万行解析）；进度落盘每秒再来几次全表扫描 |
| 2 | 整个主界面根节点订阅整张任务表 | `MainScreen.kt` `downloadManager.tasks.collectAsState()` | 每次列表发射 → 全应用根组合失效 |
| 3 | 文件夹组把 N 个子任务塞进**一个** LazyColumn item，内部普通 `Column{forEach}` | `DownloadScreen.kt` `FolderDownloadGroup` | 一个含 N 个文件的文件夹 = **一次性组合 N 行**，LazyColumn 虚拟化完全失效 |
| 4 | 任务级并发上限用 300ms 轮询忙等 | `DownloadManager.awaitConcurrencySlot()` | 排队 1 万 = 约 3.3 万次唤醒/秒，且无 FIFO |

附加放大器（同样每次重组都跑）：`filter/groupBy/sortedBy` 没 `remember`；
分组键里逐条 `File.absolutePath` + `System.getProperty("os.name")`（`DownloadGrouping.normalizeRoot`）；
「全部暂停 / 删除全部」逐条写库（又是 O(N²)）。

## 2. 改了什么

7 个文件，+580 / −130。完整补丁：`patches/0001-yunx-desktop-perf.patch`。

### 2.1 `data/db/AppDatabase.kt` — DAO 改为增量内存索引（核心）

```
byId: LinkedHashMap<id, entity>   // 插入序 = createTime 升序；发射时反转成「最新在上」
```

- **所有写方法只改那一行**（`patchRow { it.copy(...) }`），彻底删掉 `reload()` 全表重读；
- 发射节流 `publishIntervalMs = 100ms`（尾沿合并）：批量入队 1 万 → 最多 10 次/秒列表发射，而不是 1 万次；
- 内存同步条件与 SQL **逐字对应**（`complete` 的 `CASE WHEN totalSize>0`、`repairCompletedProgress` 的 `status=3`、
  `markInterruptedAsPaused` 的 `status=0/1`），避免内存与库不一致；
- 新增 `updateStatusMany(ids, status)` / `deleteMany(ids)`：一条 SQL + 一次发射。

### 2.2 `data/download/DownloadManager.kt` — 真排队 + 批量操作

- 新增 `TaskSlotQueue`：**挂起等待 + 严格 FIFO**，替换 300ms 轮询忙等；协程被取消时不漏名额；
- `downloadingTaskIds` 区分「持有并发槽正在下载」与「只是排队等名额」——
  批量暂停时前者等协程退出后按磁盘真实大小回写进度，后者**必须取消协程**（否则暂停的任务迟早拿到名额自己下起来）；
- 新增 `pauseMany` / `removeMany`：O(1)/条 + 一次批量落库；
- 「下载调试」页的活跃任务数改为真实并发占用（原来把排队任务也算进去）。

### 2.3 `ui/screens/DownloadScreen.kt` — 真正的虚拟化

- 文件夹组 = **表头一条 item + 子任务各自一条 item**（原来是整组一个 item）；
- 折叠状态提升到页面级 `rememberSaveable`（切页签不丢），**大组（>400 文件）默认收起**；
- `filter/groupBy/sortedBy` 全部 `remember(tasks)`；组聚合 `FolderAggregate` 按组 remember（`stats` 每秒变十几次不会再触发 O(组大小) 扫描）；
- 「合并中」徽标改为只在少量活动任务里找（O(并发数)）。

### 2.4 `ui/MainScreen.kt` — 根节点只订阅一个布尔

`downloadManager.tasks.collectAsState()` → `map { list -> list.any{下载中} }.distinctUntilChanged()`。

### 2.5 其它

- `ui/viewmodel/DownloadViewModel.kt`：`pauseAll` / `removeAll` 走批量入口；
- `data/db/Daos.kt`：接口加两个批量方法；
- `ui/screens/AboutScreen.kt`：版本行加「· 本地性能补丁」标记（一眼确认跑的是补丁包）；
- `MainScreen.kt`：新增调试用 `YUNX_START_TAB=download|drive|settings`（默认行为不变，压测脚本用）。

## 3. 实测（本机 Windows 11 / 独立数据目录）

测试数据：10,000 / 9,000 条假任务（`YUNX_DESKTOP_DATA_DIR` 指向独立目录，不碰真实数据）。

| 项 | 结果 |
|---|---|
| 10,000 条任务启动 → 主窗口出现 | **4.1 s** |
| 下载页首屏（1000 条根任务） | 正常渲染、可滚动 |
| 单个文件夹组 9,000 文件（默认收起） | **1 张卡片**：`0/9000 个文件 · 12.6 GB / 28.5 GB · 44%` |
| 展开该组 | 虚拟化行（只组合可视 ~6 行），滑动流畅 |
| 收起状态滚动 | 约 6% 单核 |
| 展开 9000 行滚动 | 1.4 s / 约 2.4 核（Skia 软件渲染，滚动期 CPU 高但**不冻结**） |
| 内存（1 万条任务） | 472–567 MB |
| 补丁是否生效 | `jcmd <pid> GC.class_histogram` 能看到 `TaskSlotQueue` / `JdbcDownloadTaskDao$Companion` |

## 4. 怎么构建

上游构建链不变，只要求 **JDK 17**（内置运行时是 17.0.14，`jvmToolchain(17)`）：

```powershell
.\run.ps1 build        # 只编译 Kotlin → desktop\build\libs\desktop.jar
.\run.ps1 run          # 编译并启动开发态
.\run.ps1 package      # 便携版 → release\YunX-Desktop\
.\run.ps1 installer    # 安装包
```

> `run` / `package` / `installer` 需要 C++ 编译器编 `native/darkmode.cpp`（托盘暗色桥接）；
> 只编 Kotlin 用 `build` 即可。

## 5. 怎么把 jar 换成安装目录里的那一份（免重打包）

官方安装包是 jpackage 应用镜像，应用本体就是 `app\desktop.jar` 一个文件：

```powershell
# 关掉云析 → 备份 → 覆盖
$app = "$env:LOCALAPPDATA\Programs\YunX-Desktop"
Copy-Item "$app\app\desktop.jar" "$app\app\desktop.jar.orig" -Force
Copy-Item "desktop\build\libs\desktop.jar" "$app\app\desktop.jar" -Force
```

回滚把 `desktop.jar.orig` 拷回去即可。**升级官方新版会覆盖这个 jar**，升级后重新编一次。

## 6. 已知未做 / 注意

- 批量入队本身仍是**一条条取直链**（每个文件一次 API 调用）——1 万个文件建议分 300–800 个一批，避免网盘限频；
- `resumeAll`（全部开始）仍是逐条 `start()`（1 万条约 1 秒的 DB 单行查询，可接受）；
- `_stats` 仍是整张不可变 Map（只含活动任务，规模小）；
- 编译有两条 `ExperimentalCoroutinesApi` 警告（来自 `CompletableDeferred.getCompleted()`，可用 `@OptIn` 消掉，不影响运行）；
- 未做「万级任务 + 真实网盘链接」的端到端验证（需要真实账号与上万条链接）。

## 7. 许可

上游是 **AGPL-3.0**（见 `LICENSE`）。本支线同样以 AGPL-3.0 发布，并**已声明修改**：
改动清单见上文与 `patches/0001-yunx-desktop-perf.patch`。
