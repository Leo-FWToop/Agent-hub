package com.tailnet.agenthub

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenClaw Gateway 客户端。
 *
 * 协议（源码：openclaw v2026.4.x src/gateway）：
 * - 健康检查：GET /health（无需认证）→ {"ok":true,"status":"live"}
 * - 会话：WebSocket 帧协议（同一端口 18789）
 *   1. 服务端连上后立刻推 event "connect.challenge"（含 nonce）
 *   2. 首帧必须是 connect 请求；v2026.4+ 对仅 token/password 认证的无设备签名
 *      客户端会在握手后清空 scopes（自声明权限不可信），因此必须带上
 *      Ed25519 设备签名（device 块），并完成一次设备配对批准
 *   3. connect 成功回包（hello-ok）后即可发送任意方法请求
 *   4. chat.send {sessionKey, message, idempotencyKey} 触发一轮运行，
 *      event:"chat" 事件流：state=status/delta/final/error/aborted
 *      （v3 的 delta 在 message.content 内是累计全量文本；v4 为 deltaText 增量）
 *   5. skills.status / skills.update 管理已安装技能（见 method-scopes.ts）
 */
class OpenClawClient(
    private val appContext: Context? = null,
    private val identityStore: DeviceIdentityStore? = null,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 高频 ping：确保 NAT/Tailscale 映射在 agent 长时间思考期间不超时
        .pingInterval(20, TimeUnit.SECONDS)
        // 注意：不能用 ConnectionPool(0,0,...) 禁用连接池——OkHttp 要求
        // keepAliveDuration > 0，否则构造时抛 IllegalArgumentException 闪退。
        // WebSocket（Connection: Upgrade）本身不会从连接池复用连接，无需处理。
        .retryOnConnectionFailure(true)
        .build()

    // ---------------- 连接保活锁 ----------------

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * 等待回复期间持有 WiFi 高性能锁 + CPU 唤醒锁。
     * Android 的 WiFi 省电模式会在连接空闲数十秒后进入低功耗状态，
     * 直接掐断 TCP（表现为 Software caused connection abort）——这正是
     * "agent 思考很久后连接中断" 的主要根因。
     */
    private fun acquireLocks() {
        val ctx = appContext ?: return
        try {
            if (wakeLock == null) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm != null) {
                    @Suppress("WakelockTimeout")
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "agenthub:chat"
                    ).apply {
                        setReferenceCounted(false)
                        acquire(CHAT_TIMEOUT_MS + 60_000L)
                    }
                }
            }
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                val wm = ctx.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    @Suppress("DEPRECATION")
                    wifiLock = wm.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "agenthub:chat"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            }
        } catch (_: Exception) {
            // 锁只是保活手段，获取失败不影响功能
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    class OpenClawException(message: String) : Exception(message)

    /**
     * 每轮对话的用量元数据（从 chat final 事件的 payload 提取）。
     * 字段容错：不同协议版本字段名可能不同，解析时尝试多个候选键。
     */
    data class ChatMeta(
        val model: String = "",
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cacheReadTokens: Int = 0,
        val cacheCreationTokens: Int = 0,
    ) {
        /** 缓存命中率：cacheRead / (cacheRead + cacheCreation + input) */
        val cacheHitRate: Int
            get() {
                val total = cacheReadTokens + cacheCreationTokens + inputTokens
                if (total == 0) return 0
                return (cacheReadTokens * 100) / total
            }

        val totalTokens: Int get() = inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens
    }

    /** 健康检查：GET {base}/health */
    suspend fun health(baseUrl: String): String = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder().url(trimSlash(baseUrl) + "/health").get().build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw OpenClawException("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            try {
                val obj = JSONObject(body)
                if (obj.optBoolean("ok", false)) "在线（${obj.optString("status", "live")}）"
                else body.ifBlank { "可达" }
            } catch (_: Exception) {
                body.ifBlank { "可达" }
            }
        }
    }

    /**
     * 发送一条消息并等待完整回复。
     * 连接类失败（连接失败/连接被断开）且尚未收到任何内容时自动重试一次，
     * 重试复用同一 idempotencyKey（服务端幂等去重），避免重复执行。
     *
     * @param agentId 可选；仅用于无 sessionKey 时派生默认会话键
     * @param sessionKey 指定会话键（多对话隔离）；为空时用服务端 mainSessionKey
     * @param onDelta 增量文本（replace=true 表示全量刷新，先清空再显示）
     * @param onPhase 启动阶段提示
     * @param onActivity agent 执行步骤（工具/命令/补丁，来自 agent 事件流）
     * @return 助手最终回复文本
     */
    suspend fun chat(
        baseUrl: String,
        token: String,
        agentId: String?,
        message: String,
        sessionKey: String? = null,
        onDelta: (text: String, replace: Boolean) -> Unit = { _, _ -> },
        onPhase: (phase: String) -> Unit = {},
        onActivity: (entry: String) -> Unit = {},
        onMeta: (ChatMeta) -> Unit = {},
    ): String {
        acquireLocks()
        try {
            // 每次重试独立享有 CHAT_TIMEOUT_MS 超时预算，不再共享。
            // 服务端用幂等键去重，重连后可继续等待或直接拿到已完成的结果。
            // 总上限 MAX_TOTAL_CHAT_MS 防止无限重试。
            val totalDeadline = System.currentTimeMillis() + MAX_TOTAL_CHAT_MS
            val idempotencyKey = "idem-" + UUID.randomUUID()
            var lastError: OpenClawException? = null
            var attempt = 0
            while (true) {
                if (System.currentTimeMillis() >= totalDeadline) {
                    throw lastError
                        ?: OpenClawException("等待回复超时（超过 ${MAX_TOTAL_CHAT_MS / 1000} 秒）")
                }
                try {
                    return chatWithTimeout(
                        baseUrl, token, agentId, message, sessionKey,
                        timeoutMs = CHAT_TIMEOUT_MS,
                        onDelta = onDelta,
                        onPhase = onPhase,
                        onActivity = onActivity,
                        onMeta = onMeta,
                        idempotencyKey = idempotencyKey,
                    )
                } catch (e: TimeoutCancellationException) {
                    // 超时也可能是静默断连：服务端可能已完成或仍在处理，
                    // 用幂等键重连可能直接拿到结果
                    if (attempt < MAX_RETRIES &&
                        System.currentTimeMillis() < totalDeadline
                    ) {
                        lastError = OpenClawException(
                            "等待回复超时（超过 ${CHAT_TIMEOUT_MS / 1000} 秒），自动重连…"
                        )
                        attempt++
                        onPhase("回复超时，自动重连（第 $attempt 次）…")
                        delay(minOf(2_000L * (1 shl (attempt - 1)), 30_000L))
                        continue
                    }
                    throw OpenClawException(
                        "等待回复超时（超过 ${MAX_TOTAL_CHAT_MS / 1000} 秒）"
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 连接类错误一律自动重试：即使已收到过部分内容。
                    // 服务端受理后 agent 长时间思考，连接被 NAT/WiFi 省电掐断。
                    // 幂等键保证服务端不会重复执行这条消息。
                    if (attempt < MAX_RETRIES && isConnectionError(e) &&
                        !isSubscriptionError(e) &&
                        System.currentTimeMillis() < totalDeadline
                    ) {
                        lastError = e as? OpenClawException
                            ?: OpenClawException(e.message ?: "连接失败")
                        attempt++
                        val backoff = minOf(2_000L * (1 shl (attempt - 1)), 30_000L)
                        onPhase("连接中断，自动重连（第 $attempt 次）…")
                        // 不清空已有文本：如果服务端已经返回了 delta 但 final 事件
                        // 未到达，重连后复用幂等键，服务端会返回缓存的结果。
                        // onDelta("", true) 会导致已显示的回复被擦除。
                        // 但是需要确保不会重复处理相同的 delta
                        delay(backoff)
                        continue
                    }
                    throw e
                }
            }
        } finally {
            releaseLocks()
        }
    }

    /** 已安装技能列表（skills.status，需 operator.pairing 权限） */
    suspend fun skillsStatus(baseUrl: String, token: String): JSONArray =
        rpcWithRetry("获取技能列表") {
            rpc(baseUrl, token, "skills.status", JSONObject())
                .optJSONArray("skills") ?: JSONArray()
        }

    /** 启用/停用技能（skills.update，写服务端配置，需 operator.admin 权限） */
    suspend fun skillsSetEnabled(baseUrl: String, token: String, skillKey: String, enabled: Boolean) {
        rpcWithRetry("更新技能状态") {
            rpc(
                baseUrl, token, "skills.update",
                JSONObject().apply {
                    put("skillKey", skillKey)
                    put("enabled", enabled)
                }
            )
        }
    }

    /** RPC 连接类失败自动重试一次（技能列表等轻量查询） */
    private suspend fun <T> rpcWithRetry(label: String, block: suspend () -> T): T {
        var lastError: Exception? = null
        for (attempt in 0..1) {
            try {
                return block()
            } catch (e: TimeoutCancellationException) {
                throw OpenClawException("${label}超时")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 0 && isConnectionError(e)) {
                    lastError = e
                    delay(1000)
                    continue
                }
                throw e
            }
        }
        throw lastError ?: OpenClawException("${label}失败")
    }

    /** 判断是否为连接层错误（可安全重试） */
    private fun isConnectionError(e: Throwable): Boolean {
        if (e !is OpenClawException) return false
        val msg = e.message ?: return false
        return msg.startsWith("连接失败") || msg.startsWith("连接已关闭")
    }

    /** 判断是否为订阅相关错误（不应重试） */
    private fun isSubscriptionError(e: Throwable): Boolean {
        if (e !is OpenClawException) return false
        val msg = e.message ?: return false
        return msg.contains("CodingPlan") || 
               msg.contains("subscription") || 
               msg.contains("订阅") ||
               msg.contains("400")
    }

    // ---------------- 会话握手 ----------------

    /** 网关会话回调：握手完成后回调 onHello，其余 res/event 帧交给 onFrame */
    private interface GatewaySessionListener {
        fun onHello(webSocket: WebSocket, helloPayload: JSONObject)
        fun onFrame(webSocket: WebSocket, obj: JSONObject)
        fun onError(error: Throwable)
    }

    /**
     * 打开 WebSocket 并完成 connect 握手（connect.challenge 等待 + Ed25519 设备签名）。
     * 握手成功后剩余帧转交 listener，socket 由调用方负责关闭。
     */
    private fun connectGateway(
        baseUrl: String,
        token: String,
        listener: GatewaySessionListener,
    ): WebSocket {
        val connectId = "c-${UUID.randomUUID()}"

        // connect.challenge 等待：nonce 到了就发 connect；超时则按无设备签名兜底发送
        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "oc-challenge").apply { isDaemon = true }
        }
        val connectSent = AtomicBoolean(false)
        val challengeTimeout = AtomicReference<ScheduledFuture<*>?>(null)

        fun sendConnect(webSocket: WebSocket, nonce: String?) {
            if (!connectSent.compareAndSet(false, true)) return
            challengeTimeout.get()?.cancel(false)
            scheduler.shutdownNow()
            webSocket.send(frame(connectId, "connect", buildConnectParams(token, nonce)))
        }

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (identityStore == null) {
                    // 不支持设备签名时直接握手（旧版服务端无 scopes 强制）
                    sendConnect(webSocket, null)
                } else {
                    // 最多等 3 秒 connect.challenge；没等到也照发（nonce 为空时不带 device 块）
                    challengeTimeout.set(
                        scheduler.schedule({
                            sendConnect(webSocket, null)
                        }, 3, TimeUnit.SECONDS)
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    return
                }
                when (obj.optString("type")) {
                    "res" -> {
                        if (obj.optString("id") == connectId) {
                            if (obj.optBoolean("ok", false)) {
                                listener.onHello(
                                    webSocket,
                                    obj.optJSONObject("payload") ?: JSONObject()
                                )
                            } else {
                                listener.onError(OpenClawException(handshakeError(obj)))
                                webSocket.close(1000, "handshake-failed")
                            }
                        } else {
                            listener.onFrame(webSocket, obj)
                        }
                    }
                    "event" -> {
                        if (obj.optString("event") == "connect.challenge" && !connectSent.get()) {
                            val nonce = obj.optJSONObject("payload")
                                ?.optString("nonce").orEmpty()
                            sendConnect(webSocket, nonce.ifBlank { null })
                            return
                        }
                        listener.onFrame(webSocket, obj)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduler.shutdownNow()
                val detail = response?.let { "HTTP ${it.code}" } ?: ""
                listener.onError(
                    OpenClawException("连接失败：${t.message ?: "网络错误"} $detail".trim())
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduler.shutdownNow()
                // 正常完成后的主动关闭不走这里；意外关闭则报错
                listener.onError(
                    OpenClawException("连接已关闭（$code ${reason.ifBlank { "" }}）".trim())
                )
            }
        }

        return wsClient.newWebSocket(
            Request.Builder().url(toWsUrl(baseUrl)).build(), wsListener
        )
    }

    /** 一次性 RPC：握手后发送单个请求并等待其响应 */
    private suspend fun rpc(
        baseUrl: String,
        token: String,
        method: String,
        params: JSONObject,
    ): JSONObject = withTimeout(RPC_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            val wsRef = AtomicReference<WebSocket?>(null)
            val done = AtomicBoolean(false)

            suspendCancellableCoroutine { cont ->
                fun succeed(payload: JSONObject) {
                    if (done.compareAndSet(false, true)) {
                        wsRef.get()?.close(1000, "done")
                        if (cont.isActive) cont.resumeWith(Result.success(payload))
                    }
                }

                fun fail(error: Throwable) {
                    if (done.compareAndSet(false, true)) {
                        wsRef.get()?.cancel()
                        if (cont.isActive) cont.resumeWith(Result.failure(error))
                    }
                }

                val requestId = "r-${UUID.randomUUID()}"
                val sessionListener = object : GatewaySessionListener {
                    override fun onHello(webSocket: WebSocket, hello: JSONObject) {
                        webSocket.send(frame(requestId, method, params))
                    }

                    override fun onFrame(webSocket: WebSocket, obj: JSONObject) {
                        if (obj.optString("type") == "res" && obj.optString("id") == requestId) {
                            if (obj.optBoolean("ok", false)) {
                                succeed(obj.optJSONObject("payload") ?: JSONObject())
                            } else {
                                fail(OpenClawException("$method 失败：${readError(obj)}"))
                            }
                        }
                    }

                    override fun onError(error: Throwable) {
                        fail(error)
                    }
                }

                val ws = connectGateway(baseUrl, token, sessionListener)
                wsRef.set(ws)
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
    }

    // ---------------- 聊天 ----------------

    private suspend fun chatWithTimeout(
        baseUrl: String,
        token: String,
        agentId: String?,
        message: String,
        sessionKey: String?,
        timeoutMs: Long,
        onDelta: (String, Boolean) -> Unit,
        onPhase: (String) -> Unit,
        onActivity: (String) -> Unit,
        onMeta: (ChatMeta) -> Unit,
        idempotencyKey: String,
    ): String = withTimeout(timeoutMs) {
        withContext(Dispatchers.IO) {
            val wsRef = AtomicReference<WebSocket?>(null)
            val done = AtomicBoolean(false)
            // 静默断连看门狗：记录最近一次收到任何 WebSocket 帧的时间戳。
            // 服务端受理后若长时间没有任何事件（agent 思考期间连接被 NAT
            // 省电静默掐断但 OkHttp 未感知到 onFailure），主动关闭 socket
            // 触发上层重连（复用 idempotencyKey 幂等去重）。
            val lastMessageAt = AtomicLong(System.currentTimeMillis())
            val watchdogExecutor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "oc-watchdog").apply { isDaemon = true }
            }
            val watchdog = AtomicReference<java.util.concurrent.ScheduledFuture<*>?>(null)

            suspendCancellableCoroutine { cont ->
                fun cleanupWatchdog() {
                    watchdog.get()?.cancel(false)
                    try { watchdogExecutor.shutdownNow() } catch (_: Throwable) {}
                }

                fun succeed(value: String, meta: ChatMeta) {
                    if (done.compareAndSet(false, true)) {
                        cleanupWatchdog()
                        wsRef.get()?.close(1000, "done")
                        if (cont.isActive) {
                            onMeta(meta)
                            cont.resumeWith(Result.success(value))
                        }
                    }
                }

                fun fail(error: Throwable) {
                    if (done.compareAndSet(false, true)) {
                        cleanupWatchdog()
                        wsRef.get()?.cancel()
                        if (cont.isActive) cont.resumeWith(Result.failure(error))
                    }
                }

                val sendId = "s-${UUID.randomUUID()}"
                val sb = StringBuilder()
                // 标记是否收到过 delta（用于断连时判断是否已有回复内容）
                val receivedDelta = AtomicBoolean(false)
                // 本轮对话实际使用的会话键（onHello 时确定），用于过滤 agent 事件
                val usedSessionKey = AtomicReference<String?>(null)

                val sessionListener = object : GatewaySessionListener {
                    override fun onHello(webSocket: WebSocket, hello: JSONObject) {
                        lastMessageAt.set(System.currentTimeMillis())
                        val key = resolveSessionKey(hello, agentId, sessionKey)
                        usedSessionKey.set(key)
                        webSocket.send(
                            frame(
                                sendId, "chat.send",
                                JSONObject().apply {
                                    put("sessionKey", key)
                                    put("message", message)
                                    put("idempotencyKey", idempotencyKey)
                                }
                            )
                        )
                        // 握手成功后启动看门狗：定期检查静默时长
                        watchdog.set(
                            watchdogExecutor.scheduleAtFixedRate({
                                if (done.get()) return@scheduleAtFixedRate
                                val silentFor = System.currentTimeMillis() - lastMessageAt.get()
                                if (silentFor > SILENCE_TIMEOUT_MS) {
                                    // 如果已经收到过 delta 内容，视为"回复不完整"而非"连接失败"
                                    if (receivedDelta.get() && sb.isNotEmpty()) {
                                        succeed(sb.toString(), ChatMeta())
                                    } else if (sb.isEmpty()) {
                                        // 空回复：可能是 agent 还在思考，或者连接已断开
                                        // 记录日志但不立即失败，让重连逻辑处理
                                        android.util.Log.w("OpenClawClient", 
                                            "看门狗检测到 ${silentFor/1000} 秒无事件，可能正在思考或连接断开")
                                        // 不立即失败，让超时逻辑处理
                                    } else {
                                        fail(OpenClawException(
                                            "连接失败：超过 ${SILENCE_TIMEOUT_MS / 1000} 秒无任何事件，疑似静默断连"
                                        ))
                                    }
                                }
                            }, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS)
                        )
                    }

                    override fun onFrame(webSocket: WebSocket, obj: JSONObject) {
                        lastMessageAt.set(System.currentTimeMillis())
                        if (obj.optString("type") == "res" && obj.optString("id") == sendId) {
                            if (!obj.optBoolean("ok", false)) {
                                fail(OpenClawException("发送失败：${readError(obj)}"))
                                return
                            }
                            // 受理成功。某些协议版本会在 res 里直接携带结果，
                            // 检查 payload 是否包含 message/reply 内容
                            val payload = obj.optJSONObject("payload")
                            if (payload != null) {
                                val directMsg = extractMessageText(payload.optJSONObject("message"))
                                    ?: payload.optString("reply", "").ifBlank { null }
                                    ?: payload.optString("text", "").ifBlank { null }
                                if (directMsg != null && directMsg.isNotBlank()) {
                                    sb.setLength(0)
                                    sb.append(directMsg)
                                    onDelta(directMsg, true)
                                    receivedDelta.set(true)
                                }
                            }
                            return
                        }
                        handleAgentEvent(obj, usedSessionKey.get(), onActivity)
                        // 记录是否收到过 delta 内容（用于断连时保留已有回复）
                        val prevLen = sb.length
                        handleChatEvent(obj, usedSessionKey.get(), sb, onDelta, onPhase, onMeta, ::succeed, ::fail)
                        if (sb.length > prevLen || obj.optString("event") == "chat") {
                            val payload = obj.optJSONObject("payload")
                            if (payload?.optString("state") == "delta" ||
                                payload?.optString("state") == "final"
                            ) {
                                receivedDelta.set(true)
                            }
                        }
                    }

                    override fun onError(error: Throwable) {
                        fail(error)
                    }
                }

                val ws = connectGateway(baseUrl, token, sessionListener)
                wsRef.set(ws)
                cont.invokeOnCancellation {
                    cleanupWatchdog()
                    ws.cancel()
                }
            }
        }
    }

    /** 会话键优先级：显式指定 > agent:<id>:<mainKey> > 服务端 mainSessionKey */
    private fun resolveSessionKey(
        hello: JSONObject,
        agentId: String?,
        explicit: String?,
    ): String {
        explicit?.let { if (it.isNotBlank()) return it }
        val defaults = hello.optJSONObject("snapshot")
            ?.optJSONObject("sessionDefaults") ?: JSONObject()
        val mainKey = defaults.optString("mainKey").ifBlank { "main" }
        return if (!agentId.isNullOrBlank()) {
            "agent:${agentId.trim()}:$mainKey"
        } else {
            defaults.optString("mainSessionKey").ifBlank { "agent:main:main" }
        }
    }

    /** 构建 connect 参数；拿到 challenge nonce 时附加 Ed25519 设备签名 */
    private fun buildConnectParams(token: String, nonce: String?): JSONObject {
        val auth = JSONObject()
        if (token.isNotBlank()) {
            // token/password 两种认证模式都带上，服务端按配置取用
            auth.put("token", token)
            auth.put("password", token)
        }
        val params = JSONObject().apply {
            // 协议协商范围 1..4：v3 服务端（v2026.4.x 及更早）和
            // v4 服务端（v2026.5.x 起）都能在此范围内匹配
            put("minProtocol", 1)
            put("maxProtocol", 4)
            put("client", JSONObject().apply {
                // 枚举值来自 GATEWAY_CLIENT_IDS / GATEWAY_CLIENT_MODES（client-info.ts）
                put("id", CLIENT_ID)
                put("version", CLIENT_VERSION)
                put("platform", "android")
                put("mode", CLIENT_MODE)
            })
            put("role", "operator")
            // 顺序必须与签名负载中的 joinToString(",") 完全一致
            put("scopes", JSONArray(SCOPES))
            if (auth.length() > 0) put("auth", auth)
        }

        val store = identityStore
        if (nonce != null && store != null) {
            try {
                val identity = store.loadOrCreate()
                val signedAt = System.currentTimeMillis()
                // 签名负载格式与 services 端 buildDeviceAuthPayloadV3 完全一致：
                // v3|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce|platform|deviceFamily
                val payload = listOf(
                    "v3",
                    identity.deviceId,
                    CLIENT_ID,
                    CLIENT_MODE,
                    "operator",
                    SCOPES.joinToString(","),
                    signedAt.toString(),
                    token.ifBlank { "" },
                    nonce,
                    "android",
                    "",
                ).joinToString("|")
                val signature = store.signPayload(payload, identity)
                val publicKey = store.publicKeyBase64Url(identity)
                if (!signature.isNullOrBlank() && !publicKey.isNullOrBlank()) {
                    params.put("device", JSONObject().apply {
                        put("id", identity.deviceId)
                        put("publicKey", publicKey)
                        put("signature", signature)
                        put("signedAt", signedAt)
                        put("nonce", nonce)
                    })
                }
            } catch (_: Throwable) {
                // 签名失败则退回无设备模式（新服务端会报 missing scope，旧服务端可用）
            }
        }
        return params
    }

    /**
     * 解析 agent 事件流中的 item 步骤（工具/命令/补丁），作为执行过程展示。
     * 服务端把 item 事件广播给所有连接（server-chat.ts broadcast("agent", ...)），
     * 用 sessionKey 过滤掉其他会话（如心跳）的步骤。
     */
    private fun handleAgentEvent(
        obj: JSONObject,
        sessionKey: String?,
        onActivity: (String) -> Unit,
    ) {
        if (obj.optString("event") != "agent") return
        val payload = obj.optJSONObject("payload") ?: return
        if (payload.optString("stream") != "item") return
        // 只统计本会话的步骤
        val key = payload.optString("sessionKey", "")
        if (sessionKey != null && key.isNotBlank() && key != sessionKey) return
        val data = payload.optJSONObject("data") ?: return
        if (data.optString("phase") != "start") return
        val title = data.optString("title").ifBlank { data.optString("name") }
        if (title.isBlank()) return
        val kind = data.optString("kind")
        onActivity(
            when (kind) {
                "tool" -> "🔧 $title"
                "command" -> "⌨️ $title"
                "patch" -> "📝 $title"
                "search" -> "🔍 $title"
                "analysis" -> "🧠 $title"
                else -> "• $title"
            }
        )
    }

    private fun handleChatEvent(
        obj: JSONObject,
        sessionKey: String?,
        sb: StringBuilder,
        onDelta: (String, Boolean) -> Unit,
        onPhase: (String) -> Unit,
        onMeta: (ChatMeta) -> Unit,
        succeed: (String, ChatMeta) -> Unit,
        fail: (Throwable) -> Unit,
    ) {
        if (obj.optString("event") != "chat") return
        val payload = obj.optJSONObject("payload") ?: return
        
        // 会话键过滤：只处理当前会话的事件
        val eventSessionKey = payload.optString("sessionKey", "")
        if (sessionKey != null && eventSessionKey.isNotBlank() && eventSessionKey != sessionKey) {
            android.util.Log.d("OpenClawClient", "过滤掉其他会话的事件: $eventSessionKey != $sessionKey")
            return
        }
        
        when (payload.optString("state")) {
            "status" -> {
                onPhase(phaseLabel(payload.optString("phase")))
                // status 事件也可能包含 model 信息，提前提取
                val m = extractMeta(payload)
                if (m.model.isNotBlank()) onMeta(m)
            }
            "delta" -> {
                if (payload.has("deltaText")) {
                    val text = payload.optString("deltaText", "")
                    val replace = payload.optBoolean("replace", false)
                    if (replace) sb.setLength(0)
                    sb.append(text)
                    onDelta(text, replace)
                } else {
                    val full = extractMessageText(payload.optJSONObject("message"))
                    if (full != null) {
                        sb.setLength(0)
                        sb.append(full)
                        onDelta(full, true)
                    }
                }
                // delta 事件也可能携带 model/usage，实时提取
                val m = extractMeta(payload)
                if (m.model.isNotBlank() || m.totalTokens > 0) onMeta(m)
            }
            "final" -> {
                // 优先从 message 对象提取；其次从 payload 直接找文本字段；
                // 最后用已累积的 delta 文本
                val messageObj = payload.optJSONObject("message")
                val finalText = extractMessageText(messageObj)
                    ?: payload.optString("text", "").ifBlank { null }
                    ?: payload.optString("reply", "").ifBlank { null }
                    ?: sb.toString().ifBlank { null }
                
                android.util.Log.d("OpenClawClient", "收到 final 事件: message=$messageObj, finalText=$finalText, sb长度=${sb.length}")
                
                if (finalText != null && finalText.isNotBlank()) {
                    succeed(finalText, extractMeta(payload))
                } else {
                    // 空回复：可能是 agent 还在思考，或者服务端返回了空结果
                    // 记录日志并返回空字符串，让调用者处理
                    android.util.Log.w("OpenClawClient", "收到 final 事件但内容为空，payload: $payload")
                    succeed("", extractMeta(payload))
                }
            }
            "error" -> {
                val msg = payload.optString("errorMessage", "")
                    .ifBlank { payload.optString("errorKind", "未知错误") }
                fail(OpenClawException("运行出错：$msg"))
            }
            "aborted" -> {
                val msg = payload.optString("errorMessage", "")
                fail(OpenClawException("运行已中止${if (msg.isNotBlank()) "：$msg" else ""}"))
            }
        }
    }

    /**
     * 从 chat final 事件的 payload 提取模型与 token 用量。
     * 容错解析：递归在 payload 中查找 usage/message 等对象，
     * 不同协议版本字段名可能不同，逐个尝试候选键。
     */
    private fun extractMeta(payload: JSONObject): ChatMeta {
        val model = findStringDeep(payload, "model").orEmpty()
        val usage = findObjectDeep(payload, "usage")
        // 兜底：直接在 payload 或 message 里找各 token 字段
        val fallbackSources = buildList {
            add(payload)
            findObjectDeep(payload, "message")?.let { add(it) }
            usage?.let { add(it) }
        }
        val input = fallbackSources.intOrFirst(
            "input_tokens", "inputTokens", "input", "prompt_tokens", "promptTokens"
        )
        val output = fallbackSources.intOrFirst(
            "output_tokens", "outputTokens", "output", "completion_tokens", "completionTokens"
        )
        val cacheRead = fallbackSources.intOrFirst(
            "cache_read_input_tokens", "cacheReadInputTokens",
            "cache_read", "cacheRead", "prompt_cache_hit_tokens"
        )
        val cacheCreate = fallbackSources.intOrFirst(
            "cache_creation_input_tokens", "cacheCreationInputTokens",
            "cache_creation", "cacheCreation", "prompt_cache_miss_tokens"
        )
        android.util.Log.d("OpenClawClient", "extractMeta: model=$model input=$input output=$output cacheRead=$cacheRead cacheCreate=$cacheCreate payloadKeys=${payload.keys().asSequence().toList()}")
        return ChatMeta(
            model = model,
            inputTokens = input,
            outputTokens = output,
            cacheReadTokens = cacheRead,
            cacheCreationTokens = cacheCreate,
        )
    }

    /** 深度优先遍历 JSON，找到第一个键为 key 的 JSONObject */
    private fun findObjectDeep(root: JSONObject, key: String): JSONObject? {
        val stack = ArrayDeque<Any?>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast() ?: continue
            when (node) {
                is JSONObject -> {
                    if (node.has(key)) {
                        val v = node.optJSONObject(key)
                        if (v != null) return v
                    }
                    val iter = node.keys()
                    while (iter.hasNext()) {
                        stack.addLast(node.opt(iter.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) stack.addLast(node.opt(i))
                }
            }
        }
        return null
    }

    /** 深度优先遍历 JSON，找到第一个值为字符串的指定键 */
    private fun findStringDeep(root: JSONObject, key: String): String? {
        val stack = ArrayDeque<Any?>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast() ?: continue
            when (node) {
                is JSONObject -> {
                    if (node.has(key)) {
                        val v = node.opt(key)
                        if (v is String && v.isNotEmpty()) return v
                    }
                    val iter = node.keys()
                    while (iter.hasNext()) {
                        stack.addLast(node.opt(iter.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) stack.addLast(node.opt(i))
                }
            }
        }
        return null
    }

    /** 在多个 JSON 对象中依次尝试指定键，返回第一个找到的值（包括 0） */
    private fun Collection<JSONObject>.intOrFirst(vararg keys: String): Int {
        for (obj in this) {
            for (k in keys) {
                if (obj.has(k)) {
                    val v = obj.opt(k)
                    if (v is Number) return v.toInt()
                    if (v is String) return v.toIntOrNull() ?: 0
                    // 值存在但不是数字，继续找其他候选键
                }
            }
        }
        return 0
    }

    /** 握手失败转成可操作的提示（尤其是一次性设备配对引导） */
    private fun handshakeError(obj: JSONObject): String {
        val err = obj.optJSONObject("error") ?: return "未知错误"
        val code = err.optString("code", "")
        if (code == "NOT_PAIRED") {
            val requestId = err.optJSONObject("details")
                ?.optString("requestId").orEmpty().trim()
            return buildString {
                append("需要设备配对批准（仅需一次）。\n")
                append("OpenClaw v2026.4+ 要求远程客户端完成设备配对后才能授予会话权限。")
                append("若此前已批准过，本次是因新增技能管理权限触发的重新授权。\n")
                append("请在运行 OpenClaw 的那台设备上执行：\n")
                append("  openclaw devices list\n")
                if (requestId.isNotBlank()) {
                    append("  openclaw devices approve $requestId\n")
                } else {
                    append("  openclaw devices approve <requestId>\n")
                }
                append("批准后回到这里重试即可。")
            }
        }
        return readError(obj)
    }

    /** 从 chat final 事件的 message.content 数组提取文本块 */
    private fun extractMessageText(message: JSONObject?): String? {
        message ?: return null
        // content 可能是 JSONArray（标准格式）或纯 String（简化协议）
        val content = message.opt("content") ?: return null
        val out = StringBuilder()
        when (content) {
            is JSONArray -> {
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text" || block.has("text")) {
                        out.append(block.optString("text", ""))
                    }
                }
            }
            is String -> out.append(content)
        }
        return out.toString().ifBlank { null }
    }

    private fun readError(obj: JSONObject): String {
        val err = obj.optJSONObject("error") ?: return "未知错误"
        val msg = err.optString("message", "")
        val code = err.optString("code", "")
        return listOf(code, msg).filter { it.isNotBlank() }.joinToString("：")
    }

    private fun frame(id: String, method: String, params: JSONObject): String =
        JSONObject().apply {
            put("type", "req")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

    private fun phaseLabel(phase: String): String = when (phase) {
        "preparing_workspace" -> "准备工作区…"
        "provisioning_environment" -> "配置环境…"
        "preparing_context" -> "准备上下文…"
        "starting_model" -> "启动模型…"
        else -> phase
    }

    private fun toWsUrl(baseUrl: String): String {
        val url = trimSlash(baseUrl)
        return when {
            url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
            url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
            url.startsWith("ws://") || url.startsWith("wss://") -> url
            else -> "ws://$url"
        }
    }

    private fun trimSlash(url: String): String = url.trim().trimEnd('/')

    companion object {
        // 每次尝试的超时：5 分钟（独立预算，不共享）
        private const val CHAT_TIMEOUT_MS = 300_000L
        // 总超时上限：15 分钟（含所有重试），防止无限等待
        private const val MAX_TOTAL_CHAT_MS = 900_000L
        private const val RPC_TIMEOUT_MS = 60_000L
        // 静默断连看门狗：5 分钟无任何事件才判定断连
        // （agent 思考期间可能数分钟无输出，120 秒太激进会误杀正常连接）
        private const val SILENCE_TIMEOUT_MS = 300_000L
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val MAX_RETRIES = 5
        private const val CLIENT_ID = "openclaw-android"
        private const val CLIENT_MODE = "ui"
        private const val CLIENT_VERSION = "1.13"

        // 技能管理需要：skills.status→operator.pairing，skills.update→operator.admin
        private val SCOPES = listOf(
            "operator.read",
            "operator.write",
            "operator.pairing",
            "operator.admin",
        )
    }
}
