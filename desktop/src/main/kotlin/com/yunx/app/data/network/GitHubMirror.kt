package com.yunx.app.data.network

import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * GitHub 下载镜像统一入口。
 *
 * 背景：同为 gh-proxy 系的五个镜像实测差异极大（快的 9MB/s、慢的 60KB/s），
 * **延迟最小 ≠ 吞吐最大**（axisnow 64ms 却只有 v4 的 1/8），所以自动选择按「实测下载速度」定序，
 * 而不是纯 ping；ping 只作为展示给用户的参考值。
 *
 * 生效规则（[activePrefix]）：
 * 1. 用户在设置里固定了前缀（预选或自定义）→ 用它；
 * 2. 否则用最近一次测速中最快的镜像；
 * 3. 都没有（全新安装尚未测速）→ 预选池第一个。
 *
 * 应用内所有走 GitHub 的下载都从这里取前缀，保证「设置里选的镜像」全局一致生效。
 */
object GitHubMirror {

    private const val TAG = "YunX-Mirror"

    /** 内置预选镜像前缀池（顺序即设置页展示顺序） */
    val PRESETS: List<String> = listOf(
        "https://v4.gh-proxy.org/",
        "https://v6.gh-proxy.org/",
        "https://gh-proxy.org/",
        "https://cdn.gh-proxy.org/",
        "https://axisnow.gh-proxy.org/"
    )

    /**
     * 测速样本：GyanD/codexffmpeg 的固定版本 Release 资产（约 88MB，支持 Range）。
     * 只需拉它的一个小分片即可量出延迟与吞吐。
     */
    private const val PROBE_URL =
        "https://github.com/GyanD/codexffmpeg/releases/download/7.1/ffmpeg-7.1-essentials_build.zip"

    private const val PROBE_UA = "YunX-Desktop"

    /** 测速分片大小（256KB）：太小测不准，太大拖慢刷新 */
    private const val PROBE_BYTES = 256L * 1024

    /** 单个镜像的测速硬超时：慢镜像不能拖死整体刷新 */
    private const val PROBE_TIMEOUT_MS = 8_000L

    /** 自动测速结果的有效期：过期后下次启动重新测一次 */
    private const val TTL_MS = 6L * 60 * 60 * 1000

    /**
     * 单个镜像的测速结果。
     * @param pingMs TCP 建连耗时（毫秒）；null = 建连阶段就失败
     * @param speedBps 实测下载速度（字节/秒）；null = 未取到数据（失败 / 超时 / 不支持 Range）
     */
    data class Probe(val prefix: String, val pingMs: Long?, val speedBps: Long?) {
        /** 是否可用（取到了数据） */
        val ok: Boolean get() = speedBps != null
    }

    /** 测速快照 */
    data class Snapshot(
        /** 是否正在测速 */
        val testing: Boolean = false,
        /** 各预选镜像的测速结果（尚未测过时为空） */
        val probes: List<Probe> = emptyList()
    )

    private val _snapshot = MutableStateFlow(Snapshot())

    /** 供设置页实时展示的测速状态/结果 */
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一时刻只允许一轮测速在跑 */
    private val refreshing = AtomicBoolean(false)

    /**
     * 测速专用客户端：**禁用空闲连接复用**（maxIdle=0），
     * 否则复用连接时 OkHttp 不触发 connectStart/connectEnd，拿不到本次的 TCP 建连耗时。
     */
    private val baseProbeClient: OkHttpClient by lazy {
        HttpClients.downloadClient().newBuilder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .build()
    }

    /** 当前最快的预选镜像：优先用本次测速结果，其次用上次持久化的结果 */
    fun fastestPrefix(): String? =
        snapshot.value.probes.filter { it.ok }.maxByOrNull { it.speedBps!! }?.prefix
            ?: SettingsRepository().githubMirrorFastest?.takeIf { it.isNotBlank() }

    /** 当前生效的镜像前缀（见类注释的生效规则） */
    fun activePrefix(): String {
        SettingsRepository().githubMirrorPrefix?.takeIf { it.isNotBlank() }?.let { return it }
        return fastestPrefix() ?: PRESETS.first()
    }

    /** 启动时调用：仅在测速结果过期（或从未测过）时异步刷新一次 */
    fun ensureFresh() {
        val age = System.currentTimeMillis() - SettingsRepository().githubMirrorProbedAt
        if (age > TTL_MS) refresh()
    }

    /**
     * 触发一次全量测速（并发探测 [PRESETS]），结果写入 [snapshot]，并把最快的一个持久化。
     * 重复调用会被忽略（已在测速中直接返回）。
     */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        _snapshot.update { it.copy(testing = true) }
        scope.launch {
            try {
                val results = coroutineScope {
                    PRESETS.map { prefix -> async { probe(prefix) } }.awaitAll()
                }
                _snapshot.update { Snapshot(testing = false, probes = results) }
                val fastest = results.filter { it.ok }.maxByOrNull { it.speedBps!! }
                SettingsRepository().let { repo ->
                    repo.githubMirrorFastest = fastest?.prefix
                    repo.githubMirrorProbedAt = System.currentTimeMillis()
                }
                Log.i(
                    TAG,
                    "镜像测速：" + results.joinToString {
                        "${it.prefix} ping=${it.pingMs?.let { ms -> "${ms}ms" } ?: "-"} " +
                            "速度=${it.speedBps?.let { bps -> "${bps / 1024}KB/s" } ?: "失败"}"
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "镜像测速失败：${t.message}")
                _snapshot.update { it.copy(testing = false) }
            } finally {
                refreshing.set(false)
            }
        }
    }

    /**
     * 探测单个镜像：
     * - ping = OkHttp EventListener 捕获的 TCP 建连耗时（connectStart → connectEnd）；
     * - 速度 = 拉 [PROBE_BYTES] 分片，用「读到数据的耗时」算字节速率（不含建连，纯吞吐）。
     */
    private fun probe(prefix: String): Probe {
        val connectStartNs = AtomicLong(0L)
        val pingMs = AtomicLong(-1L)
        val client = baseProbeClient.newBuilder()
            .eventListener(object : EventListener() {
                override fun connectStart(
                    call: Call,
                    inetSocketAddress: InetSocketAddress,
                    proxy: Proxy
                ) {
                    connectStartNs.set(System.nanoTime())
                }

                override fun connectEnd(
                    call: Call,
                    inetSocketAddress: InetSocketAddress,
                    proxy: Proxy,
                    protocol: Protocol?
                ) {
                    val started = connectStartNs.get()
                    if (started > 0L) pingMs.set((System.nanoTime() - started) / 1_000_000)
                }
            })
            .build()

        val ping = { pingMs.get().takeIf { it >= 0L } }

        val call = client.newCall(
            Request.Builder()
                .url(prefix + PROBE_URL)
                .header("User-Agent", PROBE_UA)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .build()
        )
        // 慢镜像不能拖死整体：单次调用硬超时覆盖连接 + 读取全过程
        call.timeout().timeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { resp ->
                val headersAt = System.nanoTime()
                val body = resp.body
                if (resp.code != 206 || body == null) {
                    Probe(prefix, ping(), null)
                } else {
                    var read = 0L
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (read < PROBE_BYTES) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            read += n
                        }
                    }
                    val elapsedMs = (System.nanoTime() - headersAt) / 1_000_000
                    val speed = if (read > 0L && elapsedMs > 0L) read * 1000L / elapsedMs else null
                    Probe(prefix, ping(), speed)
                }
            }
        } catch (t: Throwable) {
            // 建连可能已成功（有 ping），只是读取失败 / 超时
            Probe(prefix, ping(), null)
        }
    }
}
