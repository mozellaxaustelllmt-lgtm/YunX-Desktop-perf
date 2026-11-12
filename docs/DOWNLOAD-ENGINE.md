# 下载引擎是怎么在「慢」的环境里跑出速度的

> 面向维护者：这套引擎为什么能在「单连接只有几十 KB/s」的网盘 CDN 上跑到几 MB/s，
> 以及哪些旋钮其实有假上限。所有数字都来自源码常量，改动前请先读本文。

## 0. 一句话原理

**网盘 CDN 限的是「每条连接」，不是「每个用户」。** 所以速度 = 单连接速度 × 有效并行连接数，
再叠加两条修正：①把异常慢的连接**换掉**；②把**收尾拖尾**用动态分片压平。

## 1. 先探测真实大小（决定后面怎么切）

`ChunkDownloader.getTotalSize()`：

- 发 `Range: bytes=0-0`，要求 **206** + `Content-Range` 解析出总大小；解析出的 `start/end` 必须是 `0-0`，否则视为不可信；
- 拿不到 Range（返回 200 = 服务器忽略 Range）→ 回退**单流整文件**下载，绝不把整文件塞进某个分片。

任务开始前一定重探一次（`runTask` 不信任调用方传的 `size`），避免区间算错导致文件截断/膨胀。

## 2. 分片规划：分片数 ≠ 线程数

`chunkCountFor(total, threads)`：

| 文件大小 | 基础分片数 |
|---|---|
| < 5 MB | 1（不分片） |
| < 50 MB | 8 |
| < 500 MB | 32 |
| ≥ 500 MB | 64 |

然后 `want = max(基础值, threads × 8)`，再夹到 `[total/256KB, 512]`。

**为什么要 ×8**：这是「任务池」而不是「一人一片」。`workers` 个 worker 从共享池里循环领片，
任意一片慢/卡住时，其他 worker 继续领新片 —— 尾部不会只剩一个线程在爬（1:1 映射的经典塌缩）。

## 3. 真正的并发闸门（不是「设置里的线程数」这么简单）

四层，从外到内：

1. **任务级**：`maxConcurrentDownloads`，默认 **3**，范围 1–10（本支线改成了 FIFO 真排队）；
2. **单任务 worker 数**：`threadsFor(task)` = 任务覆盖值，否则按平台设置（默认 **32**，可设 1–512）；
   但 `effectiveWorkers` 有一条例外：**只有迅雷**被压到 `RANGE_WORKERS_CAP = 8`
   （判定：请求头 UA 含 `xunlei` 或 URL 含 `xunlei`）——迅雷 CDN 对单文件并发 Range 有约 8 的阈值，
   超过会把多余请求降级成 200 整文件；其他平台保持满并发；
3. **全进程在飞分片信号量**：`inflightLimiter = Semaphore(MAX_INFLIGHT_CHUNKS)`，
   `MAX_INFLIGHT_CHUNKS = clamp(maxHeap / 8 / bufferSize, 8, 512)`。
   默认 `-Xmx4g` + 64KB 读缓冲 → 理论 8192，钳到 **512**；
   `actualWorkers = min(effectiveWorkers, MAX_INFLIGHT_CHUNKS)` 再钳一次，超出的 worker 根本不会创建；
4. **专用线程池** `yunx-chunk-io`：容量 = `MAX_INFLIGHT_CHUNKS`，按需创建、空闲 30s 回收。
   不用 `Dispatchers.IO`，因为它的并行度被钉死在 `max(64, 核数)` —— 设 256/512 线程时永远只跑得出 64 路。

> ★ **别把 OkHttp 的 `Dispatcher(maxRequests=64)` 当成并发上限。**
> 分片下载走的是**同步** `call.execute()`，根本不经过 Dispatcher 的排队队列
> （源码注释写得很明确：真实并发由 `inflightLimiter` 与 `chunkIoDispatcher` 决定）。
> 那一组 64 只对异步 `enqueue()` 调用有意义。
>
> 所以「每任务 32 路 × 同时 3 个任务 = 96 路在飞」是能真跑出来的；
> 想再多就调「最大同时下载数」或线程数，天花板是堆预算推导出的 512。

分片 IO 用专用线程池还有一个副作用：`chunkIoExecutor.queue.size` 会出现在「下载调试 → 全局资源」里，
队列长度 > 0 说明有 worker 在等线程（并发开得比池子大）。

## 4. 主池 + 弹性区（IDM 式动态分片）

每个任务切两段：

- **主池 70%**：等分成固定块，顺序发给 worker；
- **弹性区 30%**：`ElasticAllocator` 现场发块，块大小**按实测速度自适应**：
  - 块 ≈ 单 worker 下载 `TARGET_SECONDS = 5` 秒的字节量，夹在 `256KB ~ 4MB`；
  - **尾部收缩**：剩余 ≤ workers × 块 时，把块压到 `remaining / workers`（下限 64KB），
    让全部连接一起冲完最后一段 —— 这是治「最后一点特别慢」的关键；
  - 已完成的块区间乱序也安全（续传时跳过已完成区间，不重复下载）。

## 5. 慢连接抢占（治「收尾塌到 KB 级」）

`DownloadManager` 里的看门狗，每 `PREEMPT_TICK_MS = 5s` 采样每一路在飞分片的瞬时速度：

```
阈值 = max(12 KB/s, 本任务平均单连接速度 / 2)     // 相对值适配不同 CDN 的限速档次
判定：已跑 ≥ 15s 且 剩余 ≥ 128KB 且 瞬时速度 < 阈值
动作：置 preempt 标志 → ChunkDownloader 主动掐断这条连接 → 保留已写字节 → 立刻换新连接从「已收字节」续传
```

约束（避免变成重连风暴）：同一分片两次抢占冷却 10s、单分片最多 3 次、每轮最多抢 2 路；
**收尾期**（在飞 ≤ 3 路）放宽到「已跑 3s」就允许抢占，因为这时候每一路都直接决定总速。

**为什么有效**：实测网盘 CDN 大量连接在 40–80 KB/s，少数只有 3–7 KB/s。
总速塌到 KB 级通常就是「最后几路刚好都是慢节点」。换掉它们，总速立刻回到几百 KB/s–MB/s。

阈值细节（`DownloadTuning.preemptFloorBps`）：`floor = max(用户设定下限, 本任务平均单连接 / 2)`，
用相对值是为了避免「只剩一两路时，平均值被自己拉低 → 永远判定为正常」。

## 6. 错峰建连

第 i 个分片首请求前延迟 `min(i, 8) × 25ms`。只是把 TCP/TLS 握手摊开，不影响稳态并发，
避免一上来几十个 SYN 同时打到 CDN 被判异常（那会导致后面全被降级成 200）。

## 7. HTTP 层：默认 HTTP/1.1 是刻意的

`HttpClients.downloadClient`：

- `protocols = [HTTP_1_1]`（实验性功能里可开 HTTP/2，默认关）；
- `ConnectionPool(maxIdleConnections = 8, keepAlive = 1min)`；
- `connectTimeout 15s / readTimeout 60s / writeTimeout 30s`，`retryOnConnectionFailure = true`；
- Dispatcher `maxRequests = maxRequestsPerHost = 64`（默认 5 会把分片并发锁死）。

HTTP/2 的多路复用是共享**一条 TCP 连接**，单流窗口/拥塞控制会把「多连接聚合带宽」这个前提吃掉；
对「按连接限速」的网盘 CDN，HTTP/1.1 多条连接反而更快。

## 8. 断点续传 + 完整性

- 分片落临时文件：主池 `part_i`、弹性区 `seg_{start}_{end}.part`（文件名即区间）；
- `plan.txt` 缓存分片计划（`chunks=N total=N main=N`），恢复时能复用就不重算；
- 每片算 **SHA-256 摘要**，合并时逐片比对（续传跳过的块只校验长度，不重读全量）；
- 服务器偶发忽略 Range（限流中间态）容忍 3 次（`RANGE_IGNORED_TOLERANCE`），
  单分片内部再重试 `RANGE_RETRIES = 4` 次（指数退避）；仍不行才整任务回退单流；
- 瞬时 IO 异常 `CHUNK_RETRIES = 3` 次退避重试；任务级失败重试默认 3 次（上限 10），每次退避 `1.2s × 次数`；
- 磁盘空间守卫：把「其他进行中任务的预期写入量」也算进去（默认 3 个任务同时通过检查会把缓存盘写爆）。

## 9. 限速

全局令牌桶 `SpeedLimiter`：所有任务共享一个配额，按读块扣 token，不够就挂起 `1–200ms`。
所以在「设置 → 下载 → 限速」填的值是**全进程总量**，不是每任务。

## 10. 特殊通道

| 场景 | 走法 |
|---|---|
| HLS（`.m3u8`，如 UC 转码流） | 不走 Range 分片，拉分片列表后按序下载再合并（`HlsDownloader`） |
| B 站高清（音视频分离） | DASH 双轨各 32 路上限（`DASH_WORKERS_CAP`，视频 CDN 无 8 路 Range 阈值），下完 ffmpeg `-c copy` 合轨 |
| 磁力 / ed2k / BT | 交给外部 Gopeed 内核（内置分片下载器是纯 HTTP Range 实现） |
| 多账号多源（实验性） | 同一文件的多个直链轮流分配给不同分片（`altSources`） |

## 11. 一张表看「旋钮」的真实边界

| 设置项 | 默认 | 真实边界 / 说明 |
|---|---|---|
| 最大同时下载数 | 3（1–10） | 任务级并发；本支线为 FIFO 排队 |
| 每平台线程数 | 32（1–512） | 单任务 worker 数；再被 `MAX_INFLIGHT_CHUNKS` 钳一次 |
| 迅雷线程数 | 8（固定） | `RANGE_WORKERS_CAP`：迅雷 CDN 超过约 8 路并发 Range 会把多余请求降级为 200 整文件 |
| 全局在飞分片 | `clamp(堆/8/读缓冲, 8, 512)`，-Xmx4g 时 = 512 | 硬性闸门（共享信号量 + 线程池容量） |
| OkHttp Dispatcher | 64 | **只对异步调用生效**；分片是同步 `execute()`，不受它限制 |
| 读缓冲 | 64 KB | 每路在飞占一份；调大 → 在飞路数上限下降（内存预算恒定） |
| 慢连接抢占 | 开 / 12 KB/s / 15s | 见 §5 |
| HTTP/2 | 关 | 对按连接限速的 CDN，HTTP/1.1 多连接更快 |
| 限速 | 不限 | 全进程总量（令牌桶） |
