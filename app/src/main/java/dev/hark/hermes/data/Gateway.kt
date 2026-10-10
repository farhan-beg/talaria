package dev.hark.hermes.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One author's emoji on a message (server: message.react / message.reaction). */
data class Reaction(val emoji: String, val author: String)

internal fun parseReactions(el: JsonElement?): List<Reaction> = runCatching {
    val obj = when (el) { is JsonObject -> el; is JsonPrimitive -> el.contentOrNull?.let { Jsonx.parseToJsonElement(it) as? JsonObject }; else -> null }
    val arr = (obj?.get("reactions") ?: (el as? JsonArray)) as? JsonArray ?: return emptyList()
    arr.mapNotNull { r -> (r as? JsonObject)?.let { o -> o.sn("emoji")?.takeIf { it.isNotBlank() }?.let { Reaction(it, o.sn("author") ?: "user") } } }
}.getOrDefault(emptyList())

sealed interface ChatItem {
    val key: String
    data class User(override val key: String, val text: String, val raw: String = text, val rowId: Long? = null, val ordinal: Int = -1, val files: List<Attachment> = emptyList(), val reactions: List<Reaction> = emptyList()) : ChatItem
    data class Assistant(
        override val key: String, val text: String, val reasoning: String = "", val streaming: Boolean = false,
        // nerd stats: wall-clock marks (ms) and how much streamed
        val startMs: Long = 0, val firstMs: Long = 0, val endMs: Long = 0, val chars: Int = 0, val outTokens: Long = 0,
        // durable messages.id (reactions address it) and the Tapback-style reactions on it
        val rowId: Long? = null, val reactions: List<Reaction> = emptyList(),
    ) : ChatItem {
        /** Output tokens: exact when the server reported usage, otherwise ~4 chars per token. */
        val tokens: Long get() = if (outTokens > 0) outTokens else (chars / 4).toLong()
        val exact: Boolean get() = outTokens > 0
        fun tps(now: Long = System.currentTimeMillis()): Double {
            if (firstMs == 0L) return 0.0
            val end = if (endMs > 0) endMs else now
            val secs = (end - firstMs) / 1000.0
            return if (secs < 0.25) 0.0 else tokens / secs
        }
    }
    data class Tool(override val key: String, val name: String, val preview: String, val done: Boolean, val summary: String = "", val duration: Double = 0.0, val startMs: Long = 0, val endMs: Long = 0,
        /** Hermes flagged this tool's output (prompt injection, a leaked secret): the risk level and what it found. */
        val risk: String = "", val findings: List<String> = emptyList()) : ChatItem
    /** `boundary`: Hermes started this turn itself (a background agent or process reported back), not you. */
    data class Notice(override val key: String, val text: String, val error: Boolean = false, val boundary: Boolean = false) : ChatItem
    /** Output of a slash command, shown as a terminal-style card. */
    data class Output(override val key: String, val command: String, val text: String) : ChatItem
}

/** A file staged into the session before the next prompt. Images ride the turn; files become @file refs. */
data class Attachment(val name: String, val kind: String, val path: String = "", val ref: String = "", val pages: Int = 0, val thumb: String = "")

data class SlashHint(val text: String, val display: String, val meta: String, val skill: Boolean, val usage: Long = 0)

data class ServerAsk(val id: JsonElement, val method: String, val params: JsonObject)

/** One delegated child, kept current from subagent.* events. */
data class Subagent(val id: String, val goal: String, val status: String, val model: String = "", val depth: Int = 0,
                    val toolCount: Int = 0, val lastTool: String = "", val summary: String = "", val startedAt: Long = 0, val endedAt: Long = 0,
                    val inTok: Long = 0, val outTok: Long = 0) {
    val done get() = status in setOf("completed", "failed", "error", "timeout", "interrupted")
}

/** A row of the agent's todo checklist (todo.updated). */
data class Todo(val id: String, val text: String, val status: String)

/** An out-of-band notice Hermes asked the client to show (notification.show). */
data class HermesToast(val text: String, val level: String, val key: String?, val ttlMs: Long)

enum class Conn { Idle, Connecting, Ready, Failed }

/** JSON-RPC client for the dashboard's /api/ws gateway — the same transport Hermes Desktop uses for native chat. */
/** Where a turn begins: your message, or a turn Hermes started on its own. */
fun ChatItem.startsTurn() = this is ChatItem.User || (this is ChatItem.Notice && boundary)

/** Backend-authored rows Hermes stores as "user" turns but nobody typed (desktop renders these as system rows). */
private val NOTICE_KINDS = setOf("model_switch", "async_delegation_complete", "process_complete", "auto_continue", "personality_switch", "failed_turn")
private val LEGACY_HEARTBEAT = Regex("^\\[Background process \\S+ heartbeat #\\d+ ")

/** null: an ordinary message you wrote. "": hide it. Otherwise the one-line system note to show instead. */
internal fun machineNote(kind: String?, text: String, meta: kotlinx.serialization.json.JsonElement?): String? {
    val md = when (meta) {
        is JsonObject -> meta
        is JsonPrimitive -> meta.contentOrNull?.let { runCatching { Jsonx.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        else -> null
    }
    val shown = md?.sn("display_text")?.takeIf { it.isNotBlank() }
    val count = md?.get("task_count")?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
    val t = text.trimStart()
    return when {
        kind == "hidden" -> ""
        kind == "model_switch" -> "Model changed"
        kind == "auto_continue" -> "Resumed interrupted turn"
        kind == "personality_switch" -> "Personality changed"
        kind == "failed_turn" -> shown ?: "The turn failed"
        kind == "process_complete" -> shown ?: "Background process finished"
        kind == "async_delegation_complete" || t.startsWith("[ASYNC DELEGATION") -> shown ?: (count ?: Regex("— (\\d+) subagent").find(t)?.groupValues?.get(1)?.toIntOrNull())
            ?.let { "$it background agent${if (it == 1) "" else "s"} finished" } ?: "Background agent work finished"
        kind in NOTICE_KINDS -> shown ?: "Hermes note"
        // older Hermes builds store these untyped
        t.startsWith("[IMPORTANT: ") && t.contains("background process", ignoreCase = true) -> "Background process finished"
        LEGACY_HEARTBEAT.containsMatchIn(t) -> ""
        else -> null
    }
}

/** A JSON-RPC error frame from Hermes: the server answered, so the call is never retried. */
class RpcError(val code: Int, msg: String) : java.io.IOException(msg)

/** Window-owned bridges only a Desktop window showing the chat can answer; everyone else declines with 4404. */
private val WINDOW_ONLY_ASKS = setOf("preview.read", "preview.act", "terminal.read", "window.read", "tour", "display.install.sudo")
/** Server requests this app can actually answer. */
private val KNOWN_ASKS = setOf("approval", "clarify", "sudo", "secret", "vault.unlock_prompt", "vault.code", "vault.save_login")
/** Reads that are safe to repeat after a dropped socket. */
private val RETRYABLE = setOf("session.history", "config.get", "model.options", "commands.catalog", "complete.slash", "complete.path",
    "subagent.list", "subagent.tail", "ping", "session.usage", "session.resume", "session.create",
    "session.context_breakdown", "session.control.read", "rollback.list", "rollback.diff", "delegation.status")

class Gateway(private val api: Api, private val store: Store, val serverId: String = "") {
    /** The server the app is showing. Other servers keep their own live socket so their chats keep running. */
    val isActive get() = serverId.isBlank() || serverId == store.activeId.value
    /** The profile this connection talks to; frozen when you switch to another server. */
    @Volatile private var frozenProfile = store.profile.value
    private fun profileNow() = botProfile.value ?: if (isActive) store.profile.value else frozenProfile
    /** Set while a bot's forever-chat is open: that chat runs on the bot's own profile, whatever profile the app manages. */
    val botProfile = MutableStateFlow<String?>(null)
    fun deactivate() { frozenProfile = store.profile.value }
    fun activate() {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var readyGate = CompletableDeferred<Unit>()

    val conn = MutableStateFlow(Conn.Idle)
    val connError = MutableStateFlow<String?>(null)
    val items = MutableStateFlow<List<ChatItem>>(emptyList())
    val busy = MutableStateFlow(false)
    /** How the last turn ended, for the "reply ready" notification. */
    data class Outcome(val status: String, val text: String, val at: Long)
    val lastOutcome = MutableStateFlow<Outcome?>(null)
    /** Calls that look the chat up in a profile's own store; without `profile` a non-default profile's chat isn't found. */
    private val PROFILE_AWARE = setOf("session.create", "session.resume", "session.history", "message.react", "config.get", "config.set",
        "complete.path", "complete.slash", "commands.catalog", "session.title", "session.usage", "session.compress")
    /**
     * The session source Hermes records. Any client but desktop gets the same toolsets, and "tui" would add a
     * prompt hint that MEDIA: tags don't render (they do here), so we identify as "mobile", like hermes-go.
     */
    private val SOURCE = "mobile"
    private val TURN_STARTERS = setOf("prompt.submit", "command.dispatch", "slash.exec", "session.steer", "session.compress", "prompt.btw")
    private val LIVE_EVENTS = setOf("message.delta", "reasoning.delta", "tool.generating", "tool.start", "message.interim")
    val status = MutableStateFlow("")
    val title = MutableStateFlow("New chat")
    val model = MutableStateFlow("")
    val usage = MutableStateFlow<JsonObject?>(null)
    val asks = MutableStateFlow<List<ServerAsk>>(emptyList())
    val loadingSession = MutableStateFlow(false)
    val yolo = MutableStateFlow(false)
    val attachments = MutableStateFlow<List<Attachment>>(emptyList())

    @Volatile var runtimeSid: String = ""; private set
    /** A chat started on this phone keeps its Talaria label when compression moves it onto a new id. */
    @Volatile var storedSid: String = ""
        private set(v) {
            if (v.isNotBlank() && v != field && field.isNotBlank() && store.isMine(field)) store.markMine(v)
            field = v
            if (v.isNotBlank()) runCatching {
                store.tagSession(v, serverId)
                if (isActive) { store.lastChat = v; store.lastChatBot = botProfile.value.orEmpty() } else store.setLastChat(serverId, frozenProfile, v)
            }
        }
    private var seq = 0L
    private var userSeen = 0
    private fun k(p: String) = "$p-${seq++}"

    // ── resilience: keep the socket alive across network switches and backgrounding ──
    @Volatile private var wanted = false
    @Volatile private var attempt = 0
    private var retryJob: Job? = null
    @Volatile private var lastFrameAt = 0L
    val reconnecting = MutableStateFlow(false)

    /** Watch the network so a new Wi-Fi/mobile link reconnects immediately instead of waiting out a backoff. */
    fun watchNetwork(ctx: android.content.Context) {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val prev = currentNet; currentNet = network
                    Diag.i("net", "Default network now $network (${Diag.networkSummary(ctx)})${if (prev != null && prev != network) ", was $prev" else ""}")
                    if (!wanted) return
                    if (conn.value != Conn.Ready) reconnectNow() else if (prev != null && prev != network) probe("network switched", 600)
                }
                override fun onLost(network: android.net.Network) {
                    // VPNs like Tailscale swap the default network often, and the old network's onLost can land
                    // after the new one's onAvailable. Killing the socket on that stale event is what dropped a
                    // freshly reconnected chat half a second later. Only react to the network we're on, and even
                    // then check the socket instead of assuming it's dead (TCP often survives the swap).
                    if (network != currentNet) { Diag.d("net", "Ignored onLost for old network $network"); return }
                    currentNet = null
                    Diag.w("net", "Default network lost ($network)")
                    if (wanted) probe("network lost", 2_000)
                }
            })
        }
    }

    @Volatile private var currentNet: android.net.Network? = null
    private var netProbe: Job? = null

    /** After a network event: ping the socket and only reconnect when it really stopped answering. */
    private fun probe(why: String, afterMs: Long) {
        netProbe?.cancel()
        netProbe = scope.launch {
            delay(afterMs)
            if (!wanted || conn.value != Conn.Ready) return@launch
            val ok = runCatching { withTimeout(6_000) { rpcRaw("ping", JsonObject(emptyMap())) } }.isSuccess
            if (ok) Diag.i("net", "Chat link survived ($why)") else { Diag.w("net", "Chat link dead after $why; reconnecting"); ws?.cancel() }
        }
    }

    /** Called when the app comes back to the foreground: verify the socket is really alive. */
    fun wake() {
        if (!wanted) return
        if (conn.value != Conn.Ready) { reconnectNow(); return }
        scope.launch {
            val ok = runCatching { withTimeout(5_000) { rpcRaw("ping", JsonObject(emptyMap())) } }.isSuccess
            if (!ok) { Diag.w("ws", "Socket dead after resume (ping timed out); reconnecting"); ws?.cancel() }
        }
    }

    /** "Tap to reconnect": a fresh start, including another go at refreshing the sign-in. */
    fun retry() {
        Diag.i("ws", "Manual reconnect")
        authRecoveries = 0; retryJob?.cancel(); attempt = 0
        if (conn.value != Conn.Ready) { conn.value = Conn.Idle; connect() }
    }

    @Volatile private var authRecoveries = 0

    /**
     * The server turned the chat socket away (HTTP 401/403 on the upgrade, or close 4401/4403). Before 1.14.16 this
     * was final and "Tap to reconnect" re-sent the same dead token, so it failed again within half a second. Now:
     * ask the REST side whether the token is still good, refresh it when it isn't, and only then give up.
     */
    private fun authRejected(what: String) {
        Diag.w("ws", "Chat socket rejected: $what")
        dropped(null)
        retryJob?.cancel()
        if (!wanted) return
        conn.value = Conn.Connecting; reconnecting.value = true
        val n = ++authRecoveries
        retryJob = scope.launch {
            val stale = api.accessTokenFor(serverId)
            val st = api.authStatusFor(serverId, stale)
            Diag.i("auth", "Session check after rejection #$n: HTTP $st")
            when {
                st == 401 -> if (n <= 2 && api.forceRefreshFor(serverId, stale)) { Diag.i("ws", "Reconnecting with a refreshed token"); conn.value = Conn.Idle; connect() } else authFailed("refresh after socket rejection failed ($what)")
                st in 200..299 -> if (n <= 2) { delay(400L * n); conn.value = Conn.Idle; connect() } else refused(what)
                else -> { reconnecting.value = true; scheduleRetry() }   // can't tell yet (server unreachable): keep trying quietly
            }
        }
    }

    private fun authFailed(reason: String) {
        conn.value = Conn.Failed; connError.value = AUTH_ERR; reconnecting.value = false
        if (!readyGate.isCompleted) readyGate.completeExceptionally(java.io.IOException(AUTH_ERR))
        if (serverId.isBlank() || serverId == store.activeId.value) api.signalExpired(reason) else Diag.e("auth", "Session expired on ${store.serverLabel(serverId)}: $reason")
    }

    private fun refused(what: String) {
        Diag.e("ws", "Signed in, but the server keeps refusing the chat socket ($what). Likely dashboard.public_url / Host mismatch behind a proxy or Tailscale Serve, or embedded chat disabled.")
        conn.value = Conn.Failed; reconnecting.value = false
        connError.value = "Hermes refused the chat link"
    }

    private fun reconnectNow() {
        // an attempt already in flight (not just waiting out a backoff): don't start a second socket beside it
        if (conn.value == Conn.Connecting && retryJob?.isActive != true) return
        retryJob?.cancel(); attempt = 0; conn.value = Conn.Idle; connect()
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        val delayMs = (500L shl attempt.coerceAtMost(5)).coerceAtMost(15_000L)
        attempt++
        Diag.i("ws", "Retry #$attempt in ${delayMs} ms")
        reconnecting.value = true
        retryJob = scope.launch { delay(delayMs); if (wanted && conn.value != Conn.Ready) { conn.value = Conn.Idle; connect() } }
    }

    fun connect() {
        wanted = true
        if (conn.value == Conn.Connecting || conn.value == Conn.Ready) return
        conn.value = Conn.Connecting
        connError.value = null
        readyGate = CompletableDeferred()
        scope.launch {
            val needsAuth = store.serverAuth(serverId)?.authRequired != false
            val url = if (needsAuth) {
                val t = api.wsTicketFor(serverId)
                if (t.authDead) { if (wanted) authFailed("token refresh failed while opening chat") else conn.value = Conn.Idle; return@launch }
                if (t.ticket != null) api.wsTicketUrlFor(serverId, "/api/ws", t.ticket)
                else api.wsUrlFor(serverId, "/api/ws", try { api.accessTokenFor(serverId) } catch (e: Exception) { "" })
            } else api.wsUrlFor(serverId, "/api/ws", try { api.accessTokenFor(serverId) } catch (e: Exception) { "" })
            Diag.i("ws", "Connecting to ${store.serverAuth(serverId)?.host ?: "?"} (${if (url.contains("ticket=")) "ticket" else if (url.contains("token=")) "token" else "no auth"})")
            val req = Request.Builder().url(url).build()
            val gen = ++connGen
            if (!wanted) return@launch
            ws?.let { old -> ws = null; old.cancel() }   // its callbacks are now stale and ignored
            ws = api.http.newWebSocket(req, Listener(gen))
        }
    }

    fun disconnect() {
        wanted = false; retryJob?.cancel(); reconnecting.value = false; watchdog?.cancel(); connGen++
        ws?.close(1000, "bye"); ws = null
        conn.value = Conn.Idle
    }

    /** Bumped for every socket; callbacks from an older socket are ignored so a late close can't kill a newer link. */
    @Volatile private var connGen = 0
    @Volatile private var everOpened = false
    private var watchdog: Job? = null

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        private fun stale(w: WebSocket) = gen != connGen || (ws != null && ws !== w)
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (stale(webSocket)) { webSocket.close(1000, "superseded"); return }
            ws = webSocket
            val wasReconnect = reconnecting.value || attempt > 0 || everOpened
            everOpened = true
            authRecoveries = 0
            Diag.i("ws", "Connected${if (wasReconnect) " (reconnect)" else ""}")
            conn.value = Conn.Ready
            connError.value = null
            attempt = 0
            lastFrameAt = System.currentTimeMillis()
            readyGate.complete(Unit)
            scope.launch {
                runCatching { rpc("client.capabilities", jsonOf("server_requests" to true)) }
                // re-attach to the chat we were in so streaming and history pick up where they left off
                if (wasReconnect && storedSid.isNotBlank() && !loadingSession.value) runCatching {
                    val keep = storedSid
                    val r = resumeRpc(keep)
                    if (storedSid == keep) applySnapshot(r)
                } else if (!wasReconnect && storedSid.isBlank() && items.value.isEmpty() && isActive) {
                    // cold start (process was killed while away): reopen the chat you were in, not a blank one
                    val last = store.lastChat
                    if (last.isNotBlank()) runCatching { resume(last, store.lastChatTitle.ifBlank { null }, bot = store.lastChatBot.ifBlank { null }) }
                }
                reconnecting.value = false
            }
            // a link can die silently (NAT timeout, captive Wi-Fi): while a turn runs, probe it when it goes quiet
            watchdog?.cancel()
            watchdog = scope.launch {
                while (gen == connGen && conn.value == Conn.Ready) {
                    delay(15_000)
                    if (gen != connGen) break
                    if (busy.value && System.currentTimeMillis() - lastFrameAt > 40_000) {
                        val ok = runCatching { withTimeout(8_000) { rpcRaw("ping", JsonObject(emptyMap())) } }.isSuccess
                        if (!ok && gen == connGen) webSocket.cancel()
                    }
                }
            }
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (stale(webSocket)) return
            lastFrameAt = System.currentTimeMillis()
            text.split('\n').filter { it.isNotBlank() }.forEach { line ->
                runCatching { handle(Jsonx.parseToJsonElement(line).jsonObject) }
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (stale(webSocket)) return
            Diag.w("ws", "Closed by server: $code ${reason.take(120)}")
            if (code == 4401 || code == 4403) authRejected("close $code ${reason.take(80)}") else dropped(null)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stale(webSocket)) return
            val rc = response?.code
            Diag.w("ws", "Socket failed: ${if (rc != null) "HTTP $rc · " else ""}${t.javaClass.simpleName}: ${t.message}")
            if (rc == 401 || rc == 403) authRejected("HTTP $rc on upgrade") else dropped(t.message ?: "Connection lost")
        }
    }

    private fun dropped(err: String?) {
        if (ws == null && conn.value == Conn.Idle) return
        ws = null
        if (err != null) Diag.w("ws", "Dropped: $err")
        val auth = err == AUTH_ERR
        // transient drops retry quietly; only auth failures or a long outage surface as an error
        if (wanted && !auth && attempt < 8) {
            conn.value = Conn.Connecting
            connError.value = null
            scheduleRetry()
        } else {
            conn.value = if (err != null) Conn.Failed else Conn.Idle
            connError.value = if (err != null && !auth) "Can't reach Hermes" else err
            reconnecting.value = false
        }
        if (!readyGate.isCompleted) readyGate.completeExceptionally(java.io.IOException(err ?: "closed"))
        pending.values.forEach { it.completeExceptionally(java.io.IOException(err ?: "Connection closed")) }
        pending.clear()
        busy.value = false
        status.value = ""
    }

    private suspend fun ensureReady() {
        if (conn.value == Conn.Failed) { attempt = 0; conn.value = Conn.Idle }
        if (conn.value != Conn.Ready && conn.value != Conn.Connecting) connect()
        withTimeout(25_000) {
            while (true) {
                val gate = readyGate
                try { gate.await(); return@withTimeout } catch (e: Exception) { if (!wanted) throw e; delay(300) }
            }
        }
    }

    /** One retry across a reconnect for calls that are safe to repeat. */
    /** Set when this phone asked for the next turn, so a turn Hermes starts itself can be told apart. */
    @Volatile private var ownTurnAt = 0L
    suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        if (method in TURN_STARTERS) ownTurnAt = now()
        return try { rpcRaw(method, params) } catch (e: java.io.IOException) {
            // only a lost reply on a repeatable call is retried; an error the server sent is final
            if (e is RpcError || method !in RETRYABLE || !wanted) throw e
            delay(400); rpcRaw(method, params)
        }
    }

    private suspend fun rpcRaw(method: String, params: JsonObject): JsonObject {
        ensureReady()
        val id = "m${ids.getAndIncrement()}"
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        val prof = profileNow()
        val sent = if (prof.isNotBlank() && method in PROFILE_AWARE && params["profile"] == null) JsonObject(params + ("profile" to JsonPrimitive(prof))) else params
        val frame = jsonOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to sent)
        if (ws?.send(frame.toString()) != true) { pending.remove(id); throw java.io.IOException("Not connected") }
        return try { withTimeout(120_000) { d.await() } } finally { pending.remove(id) }
    }

    private fun handle(m: JsonObject) {
        val method = m.s("method")
        when {
            method == "event" -> onEvent(m.o("params") ?: return)
            method.isNotBlank() && m["id"] != null -> {
                val p = m.o("params") ?: JsonObject(emptyMap())
                when (method) {
                    in WINDOW_ONLY_ASKS -> replyError(m["id"]!!, 4404, "not shown")
                    in KNOWN_ASKS -> asks.update { it + ServerAsk(m["id"]!!, method, p) }
                    else -> replyError(m["id"]!!, -32601, "Not supported on Talaria")
                }
            }
            m["id"] != null -> {
                val id = m.s("id")
                val d = pending.remove(id) ?: return
                val err = m.o("error")
                if (err != null) d.completeExceptionally(RpcError(err.l("code").toInt(), err.s("message").ifBlank { "Request failed" }))
                else d.complete(m.o("result") ?: JsonObject(emptyMap()))
            }
        }
    }

    private fun mine(sid: String) = sid.isBlank() || sid == runtimeSid

    private fun replyError(id: JsonElement, code: Int, msg: String) {
        ws?.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", code); put("message", msg) }) }.toString())
    }

    private fun onEvent(p: JsonObject) {
        val type = p.s("type")
        val sid = p.s("session_id")
        val pl = p.o("payload") ?: JsonObject(emptyMap())
        when (type) {
            "gateway.ready" -> {}
            "approval.cancelled" -> {
                val ids = pl.a("request_ids").strs().toSet()
                asks.update { l -> l.filterNot { it.method == "approval" && ((it.id as? JsonPrimitive)?.content in ids || it.params.s("request_id") in ids) } }
            }
            "session.reclaimed" -> {
                // Global broadcast: the frame's session_id is always "", so match on the payload. Every client hears
                // every reap (other chats idling out, other devices), and only OUR runtime/chat may trigger a resume.
                val dead = pl.s("session_id")
                val stored = pl.s("stored_session_id")
                val ours = (dead.isNotBlank() && dead == runtimeSid) || (dead.isBlank() && stored.isNotBlank() && stored == storedSid)
                if (ours && storedSid.isNotBlank()) {
                    val keep = storedSid
                    scope.launch { runCatching { if (storedSid == keep) applySnapshot(resumeRpc(keep)) } }
                }
            }
            "request.cancel" -> { val id = pl.s("id"); asks.update { l -> l.filterNot { (it.id as? JsonPrimitive)?.content == id } } }
            // the server's lists moved: screens showing them refetch instead of polling
            "sessions.changed" -> sessionsChanged.update { it + 1 }
            "cron.changed" -> cronChanged.update { it + 1 }
            "notification.show" -> {
                val t = pl.s("text")
                if (t.isNotBlank() && mine(sid)) toasts.tryEmit(HermesToast(t, pl.s("level"), pl.sn("key") ?: pl.sn("id"), pl.l("ttl_ms")))
            }
            "notification.clear" -> pl.sn("key")?.let { clearedToasts.tryEmit(it) }
            "background.complete" -> {
                val q = pl.sn("question").orEmpty()
                val t = pl.s("text")
                val ours = mine(sid) || pl.s("task_id") in backgroundTasks
                if (ours) {
                    backgroundTasks.remove(pl.s("task_id"))
                    items.update { it + ChatItem.Output(k("o"), "background" + if (q.isNotBlank()) " · " + q.take(60) else "", t.ifBlank { "(no output)" }) }
                    backgroundDone.tryEmit(q.ifBlank { "Background task" } to t)
                }
            }
            "skin.changed" -> {}
            else -> if (mine(sid)) onSessionEvent(type, pl, sid.isNotBlank())
        }
    }

    private fun lastStreaming(): Int = items.value.indexOfLast { it is ChatItem.Assistant && it.streaming }

    var turnStart = 0L
        private set
    private var usageOutAtStart = 0L
    /** Whether usage was known when the turn began; a delta against an unknown baseline is the whole session's output. */
    private var usageKnownAtStart = false
    private fun markUsageBase() { usageOutAtStart = outOf(usage.value); usageKnownAtStart = usage.value != null }
    private fun outOf(u: JsonObject?): Long = u?.let { maxOf(it.l("output"), it.l("output_tokens"), it.l("completion_tokens")) } ?: 0L
    private fun now() = System.currentTimeMillis()
    private fun ChatItem.Assistant.ended() = if (streaming || endMs == 0L) copy(streaming = false, endMs = now()) else copy(streaming = false)
    private fun ChatItem.Assistant.got(t: String) = copy(firstMs = if (firstMs == 0L && t.isNotEmpty()) now() else firstMs, chars = chars + t.length)

    /** A reply that arrived whole (no deltas): stamp it so the turn still gets stats. */
    private fun whole(t: String) = now().let { n -> ChatItem.Assistant(k("a"), t, startMs = if (turnStart > 0) turnStart else n, firstMs = n, endMs = n, chars = t.length) }

    private fun ensureAssistant(): Int {
        val i = lastStreaming()
        if (i >= 0) return i
        items.update { it + ChatItem.Assistant(k("a"), "", streaming = true, startMs = if (turnStart > 0) turnStart else now()) }
        return items.value.lastIndex
    }

    private fun mutate(i: Int, f: (ChatItem) -> ChatItem) = items.update { l -> l.toMutableList().also { if (i in it.indices) it[i] = f(it[i]) } }

    private fun onSessionEvent(type: String, pl: JsonObject, targeted: Boolean = true) {
        // any sign of life from a running turn means it's still running (keeps Stop available across multi-step turns)
        if (type in LIVE_EVENTS && !busy.value) {
            // a turn whose message.start we missed: open a fresh turn so its stats don't absorb the previous one's
            busy.value = true
            if (turnStart == 0L) {
                turnStart = now(); markUsageBase()
                markServerTurn()
            }
        }
        when (type) {
            "btw.complete" -> items.update { it + ChatItem.Output(k("o"), "btw · " + pl.s("question").take(60), pl.s("text")) }
            "todo.updated" -> applyTodos(pl.a("todos"), pl.l("revision"))
            "subagent.spawn_requested", "subagent.start", "subagent.thinking", "subagent.tool", "subagent.progress", "subagent.complete" -> onSubagent(type, pl)
            "session.control.update" -> control.value = pl.o("control")
            "review.summary" -> pl.s("text").takeIf { it.isNotBlank() }?.let { t -> items.update { it + ChatItem.Output(k("o"), "review", t) } }
            "tool.output_risk" -> {
                val key = "t-" + pl.s("tool_id")
                val f = pl.a("findings").strs()
                items.update { l -> l.map { if (it is ChatItem.Tool && it.key == key) it.copy(risk = pl.s("risk").ifBlank { "risky" }, findings = f) else it } }
            }
            "message.start" -> {
                val wasIdle = !busy.value
                busy.value = true
                // message.start can repeat inside one turn; only a turn that was idle restarts the clock
                if (wasIdle || turnStart == 0L) { turnStart = now(); markUsageBase() }
                // nobody here asked for this turn (a background agent, process, loop or heartbeat): mark where it begins
                if (wasIdle) markServerTurn()
                ensureAssistant()
            }
            "message.delta" -> { if (status.value.isNotEmpty()) status.value = ""; val i = ensureAssistant(); val t = pl.s("text"); mutate(i) { (it as ChatItem.Assistant).got(t).copy(text = it.text + t) } }
            "reasoning.delta" -> { status.value = ""; val i = ensureAssistant(); val t = pl.s("text"); mutate(i) { (it as ChatItem.Assistant).got(t).copy(reasoning = it.reasoning + t) } }
            // spinner frames and "waiting for provider" notices, not reasoning: they only drive the status line
            "thinking.delta" -> if (busy.value) status.value = pl.s("text").trim()
            "reasoning.available" -> {
                // providers that don't stream thinking hand it over whole
                val t = pl.s("text"); if (t.isNotBlank()) { val i = ensureAssistant(); mutate(i) { a -> (a as ChatItem.Assistant).let { if (it.reasoning.isBlank()) it.copy(reasoning = t) else it } } }
            }
            "message.interim" -> {
                val t = pl.s("text")
                val i = lastStreaming()
                if (i >= 0) mutate(i) { a -> (a as ChatItem.Assistant).ended().let { it.copy(text = it.text.ifBlank { t }) } }
                else if (t.isNotBlank()) items.update { it + whole(t) }
            }
            "tool.generating" -> status.value = "Preparing ${pl.s("name")}…"
            "tool.start" -> {
                val i = lastStreaming()
                if (i >= 0) mutate(i) { (it as ChatItem.Assistant).ended() }
                items.update { l ->
                    l.filterNot { it is ChatItem.Assistant && it.text.isBlank() && it.reasoning.isBlank() } +
                        ChatItem.Tool("t-" + pl.s("tool_id"), pl.s("name"), pl.s("context").ifBlank { pl.s("preview").ifBlank { pl.s("args_text") } }, false, startMs = now())
                }
                status.value = ""
            }
            "tool.complete" -> {
                if (pl["todos"] is JsonArray) applyTodos(pl.a("todos"), pl.l("revision"))
                val key = "t-" + pl.s("tool_id")
                items.update { l -> l.map { if (it is ChatItem.Tool && it.key == key) it.copy(done = true, summary = pl.s("summary"), duration = pl.d("duration_s"), endMs = now()) else it } }
            }
            "status.update" -> when (pl.s("kind")) {
                "compacting", "compressing" -> status.value = "Compressing…"
                "compacted", "ready" -> if (!busy.value || status.value == "Compressing…") status.value = ""
                // a background process is about to report back: name the turn it starts after it
                "process" -> serverTurnLabel = pl.s("text").trim().takeIf { it.isNotBlank() }
                // goal / loop / heartbeat moved: the control strip re-reads the snapshot
                "goal", "loop", "heartbeat" -> scope.launch { runCatching { loadControl() } }
                else -> if (busy.value) status.value = pl.s("text")
            }
            "message.reaction" -> applyReactions(pl.l("row_id"), parseReactions(pl["reactions"]), pl.s("role"))
            "session.usage" -> usage.value = pl.o("usage")
            "session.title" -> { title.value = pl.s("title").ifBlank { title.value }; runCatching { if (isActive) store.lastChatTitle = title.value } }
            "session.info" -> { applyInfo(pl, allowMove = targeted) }
            "message.complete" -> {
                val text = pl["text"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
                val i = lastStreaming()
                val reused = pl.b("response_reused")
                pl.o("usage")?.let { usage.value = it }
                val turnFrom0 = items.value.indexOfLast { it.startsTurn() } + 1
                val estOut = items.value.drop(turnFrom0).filterIsInstance<ChatItem.Assistant>().sumOf { (it.chars + it.reasoning.length) / 4L }
                // trust the usage delta only against a known baseline and when it's in the same league as what streamed
                val turnOut = (outOf(usage.value) - usageOutAtStart).coerceAtLeast(0)
                    .takeIf { usageKnownAtStart && it <= maxOf(estOut * 6, estOut + 8_000) } ?: 0L
                val why = pl.sn("reasoning").orEmpty()
                if (i >= 0) mutate(i) { a -> (a as ChatItem.Assistant).ended().let { it.copy(text = if (it.text.isBlank() && !reused) text else it.text, reasoning = it.reasoning.ifBlank { why }) } }
                if (i < 0 && text.isNotBlank() && !reused && items.value.lastOrNull().let { it !is ChatItem.Assistant || it.text != text }) {
                    items.update { it + whole(text) }
                }
                // every reply in this turn carries the turn's exact output tokens (stats aggregate per turn)
                val turnFrom = items.value.indexOfLast { it.startsTurn() } + 1
                items.update { l -> l.mapIndexed { n, it ->
                    if (n >= turnFrom && it is ChatItem.Assistant) {
                        val a = if (turnOut > 0) it.copy(outTokens = turnOut) else it
                        (if (a.firstMs == 0L && a.text.isNotBlank()) a.copy(startMs = turnStart.takeIf { s -> s > 0 } ?: now(), firstMs = now(), endMs = now()) else a)
                    } else it
                } }
                items.value.drop(turnFrom).filterIsInstance<ChatItem.Assistant>().forEach { StatsCache.save(it) }
                items.update { l -> l.filterNot { it is ChatItem.Assistant && it.text.isBlank() && it.reasoning.isBlank() } }
                // durable ids for this turn, straight from the server (reactions address these)
                pl.o("persisted_turn")?.let { pt ->
                    pt["final_assistant_row_id"]?.let { pt.l("final_assistant_row_id") }?.takeIf { it > 0 }?.let { row ->
                        items.update { l -> val j = l.indexOfLast { it is ChatItem.Assistant && it.text.isNotBlank() }
                            if (j < 0) l else l.toMutableList().also { x -> x[j] = (x[j] as ChatItem.Assistant).copy(rowId = row) } }
                    }
                    pt["user_row_id"]?.let { pt.l("user_row_id") }?.takeIf { it > 0 }?.let { stampUserRow(it) }
                }
                val st = pl.s("status")
                if (st == "error") {
                    val es = pl.o("error_surface")
                    val until = es?.sn("resets_at")?.let { " Try again after ${prettyReset(it)}." }.orEmpty()
                    val retry = if (es?.b("retryable") == true || pl.b("recoverable")) " You can retry." else ""
                    items.update { it + ChatItem.Notice(k("n"), (pl.sn("error") ?: pl.sn("failure_reason") ?: "The turn failed") + until.ifBlank { retry }, true) }
                }
                if (st == "interrupted") items.update { it + ChatItem.Notice(k("n"), "Stopped") }
                pl.sn("warning")?.let { w -> items.update { it + ChatItem.Notice(k("n"), w) } }
                pl.o("usage")?.let { usage.value = it }
                lastOutcome.value = Outcome(st.ifBlank { "complete" }, items.value.lastOrNull { it is ChatItem.Assistant && it.text.isNotBlank() }.let { (it as? ChatItem.Assistant)?.text.orEmpty() }, now())
                busy.value = false
                status.value = ""
                // the turn is over: the next one (yours, or one Hermes starts) times and counts from scratch
                turnStart = 0L; markUsageBase()
                val serverTurn = serverTurnKey
                serverTurnKey = null
                if (serverTurn != null || items.value.any { it is ChatItem.Assistant && it.rowId == null && it.text.isNotBlank() })
                    scope.launch { runCatching { reconcile(serverTurn) } }
            }
            "error" -> { items.update { it + ChatItem.Notice(k("n"), pl.s("message"), true) }; busy.value = false; status.value = "" }
        }
    }

    /** Text of the last `status.update{kind:"process"}`, used to label the turn Hermes starts next. */
    private var serverTurnLabel: String? = null
    /** Key of the boundary row for a turn Hermes started itself, fixed up from history once the turn ends. */
    private var serverTurnKey: String? = null

    private fun markServerTurn() {
        if (now() - ownTurnAt < 15_000) return
        val last = items.value.lastOrNull() ?: return
        if (!(last is ChatItem.Assistant || last is ChatItem.Tool || (last is ChatItem.Notice && !last.boundary) || last is ChatItem.Output)) return
        val key = k("n")
        items.update { it + ChatItem.Notice(key, serverTurnLabel ?: "Hermes picked this up", boundary = true) }
        serverTurnLabel = null
        serverTurnKey = key
    }

    /** End the turn without a message.complete (muted notification turns, or a missed terminal frame). */
    private fun settleTurn() {
        busy.value = false; status.value = ""
        items.update { l -> l.map {
            when {
                it is ChatItem.Assistant && it.streaming -> it.ended()
                it is ChatItem.Tool && !it.done -> it.copy(done = true, endMs = now())
                else -> it
            }
        }.filterNot { it is ChatItem.Assistant && it.text.isBlank() && it.reasoning.isBlank() } }
        turnStart = 0L; markUsageBase()
        val serverTurn = serverTurnKey; serverTurnKey = null
        scope.launch { runCatching { reconcile(serverTurn) } }
    }

    /**
     * After a turn: learn durable row ids, and replace the live label on a turn Hermes started itself with
     * what history says it was (a hidden heartbeat wake vanishes, a /loop prompt becomes your message).
     */
    private suspend fun reconcile(boundaryKey: String?) {
        if (boundaryKey != null && runtimeSid.isNotBlank()) {
            val hist = rpc("session.history", jsonOf("session_id" to runtimeSid)).a("messages").objs()
            val u0 = hist.lastOrNull { it.s("role") == "user" }
            // the turn's own row may be hidden (dropped from history): then the newest row is one we already show
            val known = items.value.filterIsInstance<ChatItem.User>()
            val maxRow = known.mapNotNull { it.rowId }.maxOrNull() ?: 0L
            val stale = u0 != null && ((u0.l("row_id") in 1..maxRow) || (maxRow == 0L && known.lastOrNull()?.raw?.trim() == u0.s("text").trim()))
            val u = if (stale) null else u0
            if (u0 != null && u == null) items.update { l -> l.map { if (it.key == boundaryKey) ChatItem.Notice(it.key, "", boundary = true) else it } }
            if (u != null) {
                val txt = u.s("text").ifBlank { (u["content"] as? JsonPrimitive)?.contentOrNull ?: "" }
                val note = machineNote(u.sn("display_kind"), txt, u["display_metadata"])
                items.update { l -> l.mapNotNull {
                    if (it.key != boundaryKey) it
                    else when {
                        note == null && txt.isNotBlank() -> userFromHistory(u, txt, -1)
                        note.isNullOrEmpty() -> ChatItem.Notice(it.key, "", boundary = true)   // keeps the turn split, draws nothing
                        else -> (it as ChatItem.Notice).copy(text = note)
                    }
                } }
            }
        }
        if (items.value.any { it is ChatItem.Assistant && it.rowId == null && it.text.isNotBlank() }) syncRowIds()
    }

    private val ATTACH_LINE = Regex("^@(image|file|folder):(\\S.*)$")

    /** A stored user row as a bubble: leading @file refs and trailing @image refs become chips, `raw` keeps the original. */
    private fun userFromHistory(m: JsonObject, txt: String, ord: Int): ChatItem.User {
        val lines = txt.lines()
        val files = mutableListOf<Attachment>()
        var from = 0
        while (from < lines.size) {
            val l = lines[from].trim()
            if (l.isEmpty()) { from++; continue }
            // "@file:a @file:b" on one line is how this app sends several
            val toks = l.split(Regex("\\s+"))
            if (toks.all { ATTACH_LINE.matches(it) }) {
                toks.forEach { t -> ATTACH_LINE.find(t)!!.groupValues.let { g -> files += Attachment(g[2].substringAfterLast('/').trim('`', '"'), if (g[1] == "image") "image" else "file", path = g[2], ref = t) } }
                from++
            } else break
        }
        var to = lines.size
        while (to > from) {
            val l = lines[to - 1].trim()
            if (l.isEmpty()) { to--; continue }
            val g = ATTACH_LINE.find(l)?.groupValues ?: break
            if (g[1] != "image") break
            files += Attachment(g[2].substringAfterLast('/'), "image", path = g[2]); to--
        }
        val shown = if (files.isEmpty()) txt else lines.subList(from, to).joinToString("\n").trim()
        return ChatItem.User(k("u"), shown, txt, m["row_id"]?.let { m.l("row_id") }?.takeIf { it > 0 }, ord, files, reactions = parseReactions(m["display_metadata"]))
    }

    // ── public chat API ──────────────────────────────────────────────────────

    private fun applySnapshot(r: JsonObject) {
        val sameChat = storedSid.isNotBlank() && (r.sn("stored_session_id") ?: storedSid) == storedSid
        runtimeSid = r.s("session_id")
        storedSid = r.sn("stored_session_id") ?: storedSid
        r.o("info")?.let { applyInfo(it) }
        val list = mutableListOf<ChatItem>()
        var ord = 0
        var turnTs = 0L   // when the current history turn's user message was sent
        var firstStep = true
        r.a("messages").objs().forEach { m ->
            val ts = histTime(m["timestamp"])
            val txt = m.s("text").ifBlank { (m["content"] as? JsonPrimitive)?.contentOrNull ?: "" }
            when (m.s("role")) {
                "user" -> {
                    val o = ord++
                    if (m.sn("display_kind") != "steer") { turnTs = ts; firstStep = true }
                    val kind = m.sn("display_kind")
                    val note = machineNote(kind, txt, m["display_metadata"])
                    when {
                        // a mid-turn correction: part of the turn it steered, not a new one
                        kind == "steer" -> list += ChatItem.Notice(k("n"), "↪ Steered: " + txt.take(120))
                        note != null -> list += ChatItem.Notice(k("n"), note, boundary = true)   // "" still splits the turn, draws nothing
                        txt.isNotBlank() -> list += userFromHistory(m, txt, o)
                    }
                }
                "assistant" -> if (m.sn("display_kind") == "failed_turn") {
                    val md = (m["display_metadata"] as? JsonObject) ?: (m["display_metadata"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Jsonx.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    list += ChatItem.Notice(k("n"), md?.sn("error")?.takeIf { it.isNotBlank() } ?: md?.o("error_surface")?.sn("message") ?: txt.ifBlank { "The turn failed" }, error = true)
                } else if (txt.isNotBlank()) {
                    val a = StatsCache.restore(ChatItem.Assistant(k("a"), txt, m.s("reasoning"),
                        rowId = m["row_id"]?.let { m.l("row_id") }?.takeIf { it > 0 }, reactions = parseReactions(m["display_metadata"])))
                    // no phone-side timing: mark the turn's span from server timestamps (no fake token stats)
                    list += if (a.firstMs > 0 || ts <= 0) a else a.copy(startMs = if (firstStep && turnTs in 1..ts) turnTs else 0, endMs = ts)
                    firstStep = false
                    val md = m["display_metadata"] as? JsonObject
                    if (md?.b("interrupted") == true) list += ChatItem.Notice(k("n"), "Stopped")
                }
                "tool" -> {
                    list += ChatItem.Tool(k("t"), m.s("name").ifBlank { "tool" }, m.s("context"), true,
                        startMs = if (firstStep && turnTs in 1..ts) turnTs else ts.coerceAtLeast(0), endMs = ts.coerceAtLeast(0))
                    firstStep = false
                }
                "system" -> {}
            }
        }
        // a turn still running on the server: show what it has said so far and keep streaming into it
        val running = r.b("running") || r.o("info")?.b("running") == true
        if (running) {
            // the running turn's own message isn't in history until the turn ends: bring it back from the
            // server's inflight copy (or what we sent from this phone), so steps attach to the right turn
            val inf = r.o("inflight")
            val pending = (inf?.get("user") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (inf?.o("user"))?.s("text")?.takeIf { it.isNotBlank() }
                ?: if (sameChat) (items.value.lastOrNull { it is ChatItem.User } as? ChatItem.User)?.text else null
            val lastHist = list.lastOrNull { it is ChatItem.User } as? ChatItem.User
            val pendingNote = pending?.let { machineNote(inf?.sn("display_kind") ?: inf?.o("user")?.sn("display_kind"), it, inf?.get("display_metadata")) }
            if (pendingNote != null) {
                if (pendingNote.isNotEmpty() && (list.lastOrNull { it.startsTurn() } as? ChatItem.Notice)?.text != pendingNote) list += ChatItem.Notice(k("n"), pendingNote, boundary = true)
            } else if (pending != null && pending.trim() != lastHist?.text?.trim()) {
                val mine = if (!sameChat) null else items.value.lastOrNull { it is ChatItem.User && it.text.trim() == pending.trim() } as? ChatItem.User
                list += mine?.copy(ordinal = ord) ?: ChatItem.User(k("u"), pending, pending, null, ord)
                ord++
            }
            // time the turn from when the server started it, not from when we reconnected
            turnStart = serverTime(r["turn_started_at"] ?: r.o("info")?.get("turn_started_at")) ?: turnStart.takeIf { it > 0 } ?: now()
            // joined mid-turn: the session usage already includes part of this turn, so its delta can't be trusted
            usageKnownAtStart = false
            // steps of this turn already in history: drop any cached timings (a short line like "Let me check."
            // can match an older turn's) and anchor the live block on the turn's real start
            val from = list.indexOfLast { it.startsTurn() } + 1
            var anchored = false
            for (j in from until list.size) {
                val it = list[j]
                list[j] = when (it) {
                    is ChatItem.Assistant -> it.copy(startMs = if (!anchored) turnStart else 0, firstMs = 0, endMs = 0).also { anchored = true }
                    is ChatItem.Tool -> it.copy(startMs = if (!anchored) turnStart else 0).also { anchored = true }
                    else -> it
                }
            }
            r.o("inflight")?.let { inf ->
                val partial = inf.s("assistant")
                if (partial.isNotBlank()) list += ChatItem.Assistant(k("a"), partial, streaming = true, startMs = turnStart)
                inf.sn("status")?.let { status.value = it }
            }
        }
        items.value = list
        userSeen = ord
        if (running) busy.value = true
        // questions that were waiting for us while we were away
        val waiting = mutableListOf<ServerAsk>()
        r.a("open_requests").objs().forEach { q -> q["id"]?.let { id ->
            when (q.s("method")) {
                in KNOWN_ASKS -> waiting += ServerAsk(id, q.s("method"), q.o("params") ?: JsonObject(emptyMap()))
                in WINDOW_ONLY_ASKS -> replyError(id, 4404, "not shown")
            }
        } }
        r.o("pending_approval")?.let { pa ->
            val rid = pa.s("request_id")
            if (waiting.none { it.method == "approval" }) waiting += ServerAsk(JsonPrimitive("resume-approval:$rid"), "approval", pa)
        }
        if (waiting.isNotEmpty()) asks.update { l -> l + waiting.filter { w -> l.none { it.id == w.id } } }
        r.o("todo_state")?.let { applyTodos(it.a("todos"), it.l("revision")) }
        if (!sameChat) { subagents.value = emptyMap(); control.value = null }
        val sid = runtimeSid
        scope.launch {
            runCatching { loadControl() }
            // children already running when we (re)attached: seed the live list once, events keep it current
            runCatching { if (sid == runtimeSid) seedSubagents() }
        }
    }

    /** A transcript timestamp (epoch seconds/ms or ISO) to millis; 0 when absent. */
    private fun histTime(v: JsonElement?): Long {
        val c = (v as? JsonPrimitive)?.contentOrNull ?: return 0
        c.toDoubleOrNull()?.let { return if (it < 1e12) (it * 1000).toLong() else it.toLong() }
        return runCatching { java.time.OffsetDateTime.parse(c).toInstant().toEpochMilli() }
            .recoverCatching { java.time.LocalDateTime.parse(c).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(0)
    }

    /** Server epoch (seconds or ms) to phone millis; null when missing or not believable. */
    private fun serverTime(v: JsonElement?): Long? {
        val d = (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
        val ms = if (d < 1e12) (d * 1000).toLong() else d.toLong()
        val n = now()
        return ms.takeIf { it in (n - 24 * 3600_000L)..(n + 60_000L) }?.coerceAtMost(n)
    }

    private fun stampUserRow(row: Long) = items.update { l ->
        val j = l.indexOfLast { it is ChatItem.User }
        if (j < 0 || (l[j] as ChatItem.User).rowId != null) l else l.toMutableList().also { x -> x[j] = (x[j] as ChatItem.User).copy(rowId = row) }
    }

    private fun prettyReset(v: String): String = runCatching {
        val ms = v.toDoubleOrNull()?.let { if (it < 1e12) (it * 1000).toLong() else it.toLong() } ?: java.time.Instant.parse(v).toEpochMilli()
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }.getOrDefault(v)

    /** model / yolo / speed from session.info or a snapshot's info. */
    private fun applyInfo(pl: JsonObject, allowMove: Boolean = true) {
        pl.sn("model")?.let { if (it.isNotBlank()) model.value = it }
        pl["yolo"]?.let { yolo.value = pl.b("yolo") }
        fastFromInfo(pl)?.let { fast.value = it }
        if (pl.b("running") && busy.value) serverTime(pl["turn_started_at"])?.let { if (turnStart == 0L || it < turnStart) turnStart = it }
        // compression moves the chat onto a new stored id mid-session
        if (allowMove) pl.sn("stored_session_id")?.takeIf { it.isNotBlank() }?.let { storedSid = it }
        // cumulative counters for the live agent: the real baseline for per-turn token stats
        pl.o("usage")?.let { usage.value = it }
        // the server's word that the turn is over, sent even when a muted turn emitted no message.complete
        if (pl["running"] != null && !pl.b("running") && busy.value && now() - ownTurnAt > 2_000 && status.value != "Stopping…") settleTurn()
        pl.sn("reasoning_effort")?.let { if (it.isNotBlank()) reasoning.value = it }
        // the agent is built now: carry your saved speed into this chat once (settings sent before the build are dropped)
        val built = pl.sn("model").orEmpty().isNotBlank() && !pl.b("lazy")
        if (built && fastAssertFor != runtimeSid && runtimeSid.isNotBlank()) {
            fastAssertFor = runtimeSid
            val tier = pl.sn("service_tier")?.lowercase().orEmpty()
            if (store.fastPref.value && tier != "priority" && tier != "ultrafast") {
                val sid = runtimeSid
                scope.launch { runCatching { rpc("config.set", jsonOf("session_id" to sid, "key" to "fast", "value" to "fast")).sn("value")?.let { if (sid == runtimeSid) fast.value = it != "normal" } } }
            }
        }
    }
    private var fastAssertFor = ""

    /** Resume that builds the agent up front (so per-chat settings stick), falling back for older servers. */
    private suspend fun resumeRpc(stored: String): JsonObject {
        fastAssertFor = ""
        return try { rpc("session.resume", jsonOf("session_id" to stored, "cols" to 80, "source" to SOURCE, "inline_images" to false, "eager_build" to true)) }
        catch (e: java.io.IOException) {
            if (!e.message.orEmpty().contains("eager_build")) throw e
            rpc("session.resume", jsonOf("session_id" to stored, "cols" to 80, "source" to SOURCE, "inline_images" to false))
        }
    }

    private fun applyReactions(row: Long, rs: List<Reaction>, role: String) {
        if (row <= 0) return
        items.update { l ->
            var hit = false
            val m = l.map {
                when {
                    it is ChatItem.Assistant && it.rowId == row -> { hit = true; it.copy(reactions = rs) }
                    it is ChatItem.User && it.rowId == row -> { hit = true; it.copy(reactions = rs) }
                    else -> it
                }
            }
            if (hit) m else {
                // a live row we haven't learned the id of yet: the newest message of that role
                val i = m.indexOfLast { if (role == "user") it is ChatItem.User else it is ChatItem.Assistant }
                if (i < 0) m else m.toMutableList().also { x ->
                    x[i] = when (val it = x[i]) { is ChatItem.Assistant -> it.copy(rowId = row, reactions = rs); is ChatItem.User -> it.copy(rowId = row, reactions = rs); else -> it }
                }
            }
        }
    }

    /** Learns durable row ids for messages streamed this session (by matching text), so reactions can address them. */
    private suspend fun syncRowIds() {
        if (runtimeSid.isBlank()) return
        val hist = rpc("session.history", jsonOf("session_id" to runtimeSid)).a("messages").objs()
        val asst = hist.filter { it.s("role") == "assistant" && it["row_id"] != null }.map { it.s("text").trim() to it }.toMutableList()
        items.update { l -> l.map { item ->
            if (item is ChatItem.Assistant && item.rowId == null && item.text.isNotBlank()) {
                val idx = asst.indexOfFirst { it.first == item.text.trim() }
                if (idx >= 0) { val m = asst.removeAt(idx).second; item.copy(rowId = m.l("row_id"), reactions = parseReactions(m["display_metadata"]).ifEmpty { item.reactions }) } else item
            } else item
        } }
    }

    /** Your Tapback on a message: one per author, the same emoji again (or null) takes it back. */
    suspend fun react(key: String, emoji: String?) {
        val target = items.value.firstOrNull { it.key == key } ?: return
        val (row, role, before) = when (target) {
            is ChatItem.Assistant -> Triple(target.rowId, "assistant", target.reactions)
            is ChatItem.User -> Triple(target.rowId, "user", target.reactions)
            else -> return
        }
        val mineBefore = before.firstOrNull { it.author == "user" }?.emoji
        val next = before.filter { it.author != "user" } + (if (emoji != null && emoji != mineBefore) listOf(Reaction(emoji, "user")) else emptyList())
        fun put(rs: List<Reaction>, id: Long? = null) = items.update { l -> l.map {
            if (it.key != key) it else when (it) { is ChatItem.Assistant -> it.copy(reactions = rs, rowId = id ?: it.rowId); is ChatItem.User -> it.copy(reactions = rs, rowId = id ?: it.rowId); else -> it }
        } }
        put(next)   // optimistic, then the server's list wins
        if (row == null) runCatching { syncRowIds() }
        val fresh = items.value.firstOrNull { it.key == key }.let { (it as? ChatItem.Assistant)?.rowId ?: (it as? ChatItem.User)?.rowId }
        val isNewest = items.value.indexOfLast { if (role == "user") it is ChatItem.User else it is ChatItem.Assistant } == items.value.indexOfFirst { it.key == key }
        try {
            if (fresh == null && !isNewest) throw IllegalStateException("This reply isn't saved yet. Try again in a moment.")
            val r = rpc("message.react", buildJsonObject {
                put("session_id", runtimeSid)
                if (fresh != null) put("row_id", fresh) else put("newest_role", role)
                put("emoji", if (emoji == null || emoji == mineBefore) JsonNull else JsonPrimitive(emoji))
                put("author", "user")
            })
            put(parseReactions(r["reactions"]), r.l("row_id").takeIf { it > 0 })
        } catch (e: Exception) { put(before); throw e }
    }

    /** @-reference completion for files, folders and agent profiles on Hermes' machine. */
    suspend fun completePath(word: String): List<SlashHint> {
        val r = rpc("complete.path", buildJsonObject {
            put("word", word)
            if (runtimeSid.isNotBlank()) put("session_id", runtimeSid)
            profileNow().takeIf { it.isNotBlank() }?.let { put("profile", it) }
        })
        return r.a("items").objs().map { SlashHint(it.s("text"), it.sn("display") ?: it.s("text"), it.s("meta"), false) }
    }

    suspend fun newChat() = startChat(bot = null, title = null)

    /** Opens a bot's one forever-chat: resume it when the server knows it, else mint it titled "Bot Chat". */
    suspend fun openBot(name: String, chatId: String?) {
        if (chatId != null) resume(chatId, Bots.CHAT_TITLE, bot = name)
        else startChat(bot = name, title = Bots.CHAT_TITLE)
    }

    val inBotChat get() = botProfile.value != null

    private suspend fun startChat(bot: String?, title: String?) {
        loadingSession.value = true
        try {
            items.value = emptyList(); this.title.value = title ?: "New chat"; usage.value = null; busy.value = false; attachments.value = emptyList()
            botProfile.value = bot
            val p = profileNow()
            todos.value = emptyList(); subagents.value = emptyMap(); control.value = null
            runCatching { if (isActive) store.lastChatTitle = title.orEmpty() }
            fastAssertFor = ""
            // the speed rides on create itself; a config.set before the agent is built would be dropped
            // one key per new chat, so a retry after a lost reply can't mint a second session
            val idem = java.util.UUID.randomUUID().toString()
            fun params(fast: Boolean) = jsonOf("cols" to 80, "source" to SOURCE, "profile" to p.ifBlank { null },
                "fast" to (if (fast && store.fastPref.value) true else null), "idempotency_key" to idem, "title" to title)
            val r = try { rpc("session.create", params(true)) }
                catch (e: java.io.IOException) { if (!e.message.orEmpty().contains("fast")) throw e; rpc("session.create", params(false)) }
            storedSid = r.s("stored_session_id")
            store.markMine(storedSid)
            applySnapshot(r)
            if (title != null) this.title.value = title
            runCatching { loadRunSettings() }
        } finally { loadingSession.value = false }
    }

    suspend fun resume(stored: String, t: String?, bot: String? = null) {
        loadingSession.value = true
        try {
            items.value = emptyList(); title.value = t?.ifBlank { null } ?: "Chat"; usage.value = null; busy.value = false; attachments.value = emptyList()
            botProfile.value = bot
            runCatching { if (isActive) store.lastChatTitle = t.orEmpty() }
            todos.value = emptyList(); subagents.value = emptyMap(); control.value = null
            storedSid = stored
            applySnapshot(resumeRpc(stored))
            runCatching { loadRunSettings() }
        } finally { loadingSession.value = false }
    }

    suspend fun send(text: String, queue: Boolean = busy.value) {
        if (runtimeSid.isBlank()) newChat()
        val staged = attachments.value
        attachments.value = emptyList()
        val refs = staged.filter { it.ref.isNotBlank() }.joinToString(" ") { it.ref }
        val body = listOf(refs, text).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "See attached." }
        val shown = (staged.map { "\uD83D\uDCCE " + it.name } + listOf(text).filter { it.isNotBlank() }).joinToString("\n")
        val wasBusy = busy.value
        val key = k("u")
        val ord = userSeen++
        items.update { it + ChatItem.User(key, if (staged.isNotEmpty()) text else shown.ifBlank { body }, body, null, ord, staged) }
        busy.value = true
        try {
            // queued: "run after this turn", never a live correction that would interrupt the running reply
            val r = rpc("prompt.submit", jsonOf("session_id" to runtimeSid, "text" to body, "queued" to (if (queue) true else null)))
            val row = r.l("user_row_id")
            if (row > 0) items.update { l -> l.map { if (it is ChatItem.User && it.key == key) it.copy(rowId = row) else it } }
        } catch (e: Exception) {
            if (wasBusy) { items.update { it + ChatItem.Notice(k("n"), e.message ?: "Couldn't send", true) }; return }
            busy.value = false
            items.update { it + ChatItem.Notice(k("n"), e.message ?: "Couldn't send", true) }
        }
    }

    // ── attachments ──────────────────────────────────────────────────────────

    suspend fun attach(bytes: ByteArray, name: String, mime: String, thumb: String = "") {
        if (runtimeSid.isBlank()) newChat()
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val isImage = mime.startsWith("image/")
        val isPdf = mime == "application/pdf" || name.endsWith(".pdf", true)
        val a = when {
            isImage -> {
                val r = rpc("image.attach_bytes", jsonOf("session_id" to runtimeSid, "content_base64" to b64, "filename" to name))
                Attachment(name, "image", path = r.s("path"))
            }
            isPdf -> try {
                val r = rpc("pdf.attach", jsonOf("session_id" to runtimeSid, "content_base64" to b64, "filename" to name))
                Attachment(name, "pdf", path = r.a("pages").objs().joinToString("\n") { it.s("path") }, pages = r.l("pages_attached").toInt())
            } catch (e: Exception) { stageFile(b64, name, mime) }  // no poppler on the host: hand over the raw file
            else -> stageFile(b64, name, mime)
        }
        attachments.update { it + a.copy(thumb = thumb) }
    }

    private suspend fun stageFile(b64: String, name: String, mime: String): Attachment {
        val r = rpc("file.attach", jsonOf("session_id" to runtimeSid, "name" to name, "data_url" to "data:${mime.ifBlank { "application/octet-stream" }};base64,$b64"))
        return Attachment(r.s("name").ifBlank { name }, "file", path = r.s("path"), ref = r.s("ref_text"))
    }

    suspend fun detach(a: Attachment) {
        attachments.update { l -> l - a }
        if (a.kind == "image" || a.kind == "pdf") a.path.split('\n').filter { it.isNotBlank() }.forEach { path ->
            runCatching { rpc("image.detach", jsonOf("session_id" to runtimeSid, "path" to path)) }
        }
    }

    // ── model, yolo ──────────────────────────────────────────────────────────

    suspend fun modelOptions(): JsonObject = rpc("model.options", jsonOf("session_id" to runtimeSid.ifBlank { null }))

    /** Returns Hermes' confirmation question when it wants a yes first (price, context loss); nothing switched yet. */
    suspend fun setModel(modelId: String, provider: String, confirmed: Boolean = false): String? {
        if (runtimeSid.isBlank()) newChat()
        val v = buildString { append(modelId); if (provider.isNotBlank()) append(" --provider ").append(provider); append(" --session") }
        val r = rpc("config.set", jsonOf("key" to "model", "session_id" to runtimeSid, "value" to v, "confirm_expensive_model" to (if (confirmed) true else null)))
        if (r.b("confirm_required")) return r.sn("confirm_message")?.takeIf { it.isNotBlank() } ?: "Switch to $modelId?"
        if (r.b("deferred")) items.update { it + ChatItem.Notice(k("n"), "Switches to $modelId after this reply") }
        else { model.value = modelId; items.update { it + ChatItem.Notice(k("n"), "Switched to $modelId") } }
        r.sn("warning")?.takeIf { it.isNotBlank() }?.let { w -> items.update { it + ChatItem.Notice(k("n"), w) } }
        return null
    }

    suspend fun setYolo(on: Boolean) {
        if (runtimeSid.isBlank()) newChat()
        val r = rpc("config.set", jsonOf("key" to "yolo", "session_id" to runtimeSid, "value" to if (on) "on" else "off"))
        yolo.value = r.s("value").let { if (it.isBlank()) on else it == "1" || it == "on" || it == "true" }
    }

    // ── slash commands ───────────────────────────────────────────────────────

    /** Every command and skill, fetched once per connection and filtered on-device as you type. */
    val catalog = MutableStateFlow<List<SlashHint>>(emptyList())
    suspend fun loadCatalog(force: Boolean = false) {
        if (catalog.value.isNotEmpty() && !force) return
        val r = rpc("commands.catalog", jsonOf("session_id" to runtimeSid.ifBlank { null }, "profile" to profileNow().ifBlank { null }))
        val skills = r.o("skills")?.keys ?: emptySet()
        val usage = r.o("skills")?.mapValues { (it.value as? JsonObject)?.l("usage") ?: 0L } ?: emptyMap()
        catalog.value = r.a("pairs").mapNotNull { (it as? JsonArray)?.takeIf { a -> a.size >= 1 } }.map { a ->
            val name = (a[0] as JsonPrimitive).content.let { if (it.startsWith("/")) it else "/$it" }
            val desc = (a.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty()
            SlashHint(name, name, desc, name in skills || name.removePrefix("/") in skills, usage[name] ?: usage[name.removePrefix("/")] ?: 0)
        }.distinctBy { it.text }
    }

    /** Fold a message into the running turn right after its current step. */
    suspend fun steer(text: String) {
        val r = rpc("session.steer", jsonOf("session_id" to runtimeSid, "text" to text))
        if (r.s("status") == "rejected") throw IllegalStateException("Hermes couldn't steer this turn — queue it instead")
        items.update { it + ChatItem.Notice(k("n"), "↪ Steered: " + text.take(120)) }
    }

    /** Messages waiting to go out once the current turn finishes. */
    val queued = MutableStateFlow<List<String>>(emptyList())
    fun enqueue(text: String) { queued.update { it + text } }
    fun unqueue(i: Int) { queued.update { l -> l.toMutableList().also { if (i in it.indices) it.removeAt(i) } } }
    init {
        scope.launch {
            lastOutcome.collect { o ->
                if (o == null || o.status == "interrupted") return@collect   // a deliberate stop holds the queue
                delay(250)
                val next = queued.value.firstOrNull() ?: return@collect
                if (busy.value) return@collect
                queued.update { it.drop(1) }
                send(next, queue = true)
            }
        }
    }
    suspend fun sendQueuedNow(i: Int) {
        val t = queued.value.getOrNull(i) ?: return
        unqueue(i)
        if (busy.value) steer(t) else send(t)
    }

    /** Completions for the text being typed; second value is where the replacement starts. */
    suspend fun completeSlash(text: String): Pair<List<SlashHint>, Int> {
        val r = rpc("complete.slash", jsonOf("text" to text, "session_id" to runtimeSid.ifBlank { null }, "profile" to profileNow().ifBlank { null }))
        val hints = r.a("items").objs().map { SlashHint(it.s("text"), it.s("display").ifBlank { it.s("text") }, it.s("meta"), it.s("kind") == "skill") }
        return hints to r.l("replace_from").toInt()
    }

    /** Runs a /command. Returns text to put back into the composer when the command asks for it. */
    suspend fun runSlash(cmd: String): String? {
        val line = cmd.trim()
        if (line == "/new") { newChat(); return null }
        if (runtimeSid.isBlank()) newChat()
        items.update { it + ChatItem.User(k("u"), line) }
        val r = try {
            rpc("slash.exec", jsonOf("session_id" to runtimeSid, "command" to line))
        } catch (e: Exception) {
            // skills, bundles and some built-ins only run through command.dispatch
            val parts = line.removePrefix("/").split(Regex("\\s+"), limit = 2)
            try {
                rpc("command.dispatch", jsonOf("session_id" to runtimeSid, "name" to parts[0], "arg" to parts.getOrElse(1) { "" }))
            } catch (e2: Exception) {
                items.update { it + ChatItem.Notice(k("n"), e2.message ?: e.message ?: "Command failed", true) }
                return null
            }
        }
        return applySlashResult(line, r)
    }

    private suspend fun applySlashResult(line: String, r: JsonObject): String? {
        r.sn("notice")?.let { n -> items.update { it + ChatItem.Notice(k("n"), n) } }
        r.sn("warning")?.let { n -> items.update { it + ChatItem.Notice(k("n"), n) } }
        when (r.s("type")) {
            "send", "skill" -> {
                val msg = r.s("message")
                if (msg.isNotBlank()) {
                    busy.value = true
                    try { rpc("prompt.submit", jsonOf("session_id" to runtimeSid, "text" to msg)) }
                    catch (e: Exception) { busy.value = false; items.update { it + ChatItem.Notice(k("n"), e.message ?: "Couldn't send", true) } }
                }
            }
            "prefill" -> return r.s("message")
            "alias" -> {
                val rest = line.removePrefix("/").split(Regex("\\s+"), limit = 2).getOrElse(1) { "" }
                items.update { l -> l.dropLast(1) }
                return runSlash("/" + r.s("target").removePrefix("/") + if (rest.isNotBlank()) " $rest" else "")
            }
            else -> {
                val out = r.s("output")
                if (out.isNotBlank() && out != "(no output)") items.update { it + ChatItem.Output(k("o"), line.substringBefore(' '), out.trimEnd()) }
                else items.update { it + ChatItem.Notice(k("n"), "Done") }
            }
        }
        return null
    }

    // ── edit / regenerate / session tools ───────────────────────────────────

    /** Rewrites a past message: history from that turn on is dropped and the new text runs instead. */
    suspend fun edit(target: ChatItem.User, newText: String) {
        if (runtimeSid.isBlank()) return
        if (busy.value) { interrupt(); repeat(30) { if (!busy.value) return@repeat; kotlinx.coroutines.delay(150) } }
        val idx = items.value.indexOfFirst { it.key == target.key }
        if (idx < 0) return
        val key = k("u")
        items.update { it.take(idx) + ChatItem.User(key, newText, newText, null, target.ordinal) }
        userSeen = target.ordinal + 1
        busy.value = true
        val params = buildMap<String, Any?> {
            put("session_id", runtimeSid); put("text", newText); put("confirm_truncate", true)
            if (target.rowId != null) put("truncate_before_row_id", target.rowId) else put("truncate_before_user_ordinal", target.ordinal)
        }
        var lastErr: Exception? = null
        for (attempt in 0 until 12) {
            try {
                val r = rpc("prompt.submit", jsonOf(*params.toList().toTypedArray()))
                val row = r.l("user_row_id")
                if (row > 0) items.update { l -> l.map { if (it is ChatItem.User && it.key == key) it.copy(rowId = row) else it } }
                return
            } catch (e: Exception) {
                lastErr = e
                if (e.message?.contains("busy", true) == true) { kotlinx.coroutines.delay(400); continue }
                break
            }
        }
        busy.value = false
        items.update { it + ChatItem.Notice(k("n"), "Couldn't edit: " + (lastErr?.message ?: "unknown error"), true) }
    }


    suspend fun rename(t: String) {
        rpc("session.title", jsonOf("session_id" to runtimeSid, "title" to t))
        title.value = t
    }

    suspend fun compress(focus: String? = null): String {
        val r = rpc("session.compress", jsonOf("session_id" to runtimeSid, "focus_topic" to focus?.ifBlank { null }))
        val msg = r.sn("message") ?: if (r.b("compressed")) "Context compressed" else "Nothing to compress yet"
        items.update { it + ChatItem.Notice(k("n"), msg) }
        return msg
    }

    suspend fun setSessionFlag(key: String, value: String): String {
        // without a live session the server would write the global config instead, and the next read
        // (session-scoped) would flip the switch straight back — so make sure this chat exists first
        if (runtimeSid.isBlank()) newChat()
        if (key == "fast") store.set(store.fastPref, "fast_pref", value != "normal")
        val before = if (key == "fast") fast.value else null
        if (key == "fast") fast.value = value != "normal"   // optimistic, reverted on failure
        try {
            val r = rpc("config.set", jsonOf("session_id" to runtimeSid, "key" to key, "value" to value))
            val v = r.sn("value") ?: value
            when (key) { "reasoning" -> reasoning.value = v; "fast" -> fast.value = v != "normal" }
            return v
        } catch (e: Exception) {
            before?.let { fast.value = it }
            val m = e.message.orEmpty()
            if (key == "fast" && m.contains("not available", true)) throw IllegalStateException("This model has no faster tier to switch to — it already runs at its only speed.")
            throw e
        }
    }

    /** Pulls this chat's thinking level and speed so the picker opens on the truth. */
    suspend fun loadRunSettings() {
        val sid = runtimeSid
        fun params(key: String) = if (sid.isBlank()) jsonOf("key" to key) else jsonOf("session_id" to sid, "key" to key)
        runCatching { rpc("config.get", params("reasoning")).sn("value")?.let { reasoning.value = it } }
        // speed comes from session.info frames (and is re-applied once the agent is built), not a read-back here:
        // reading it mid-build answers "normal" and would flip your switch off
    }
    val reasoning = MutableStateFlow("")
    val fast = MutableStateFlow(false)

    /**
     * session.info carries `fast` (does the priority tier actually apply) and `service_tier`.
     * Mid-turn info frames can omit or blank the tier, so only a definite answer changes the toggle.
     */
    private fun fastFromInfo(pl: JsonObject): Boolean? {
        val t = pl.sn("service_tier")?.trim()?.lowercase()
        if (!t.isNullOrEmpty()) return t == "priority" || t == "ultrafast" || t == "fast"
        // no tier named in this frame: keep what we know rather than flipping off
        return if (t == null) (pl["fast"] as? JsonPrimitive)?.booleanOrNull?.takeIf { it } else null
    }

    suspend fun subagents(): List<JsonObject> =
        if (runtimeSid.isBlank()) emptyList() else rpc("subagent.list", jsonOf("session_id" to runtimeSid)).a("subagents").objs()
    suspend fun subagentTail(id: String): String = rpc("subagent.tail", jsonOf("session_id" to runtimeSid, "subagent_id" to id)).s("text")
    suspend fun stopSubagent(id: String) { rpc("subagent.interrupt", jsonOf("session_id" to runtimeSid, "subagent_id" to id)) }
    suspend fun steerSubagent(id: String, text: String) { rpc("subagent.steer", jsonOf("session_id" to runtimeSid, "subagent_id" to id, "text" to text)) }

    /** A side question that doesn't enter the main conversation. */
    suspend fun btw(text: String) {
        rpc("prompt.btw", jsonOf("session_id" to runtimeSid, "text" to text))
    }

    val sessionId get() = runtimeSid

    suspend fun interrupt(): Boolean {
        status.value = "Stopping…"
        val ok = runCatching { rpc("session.interrupt", jsonOf("session_id" to runtimeSid)) }.isSuccess
        // if the server never sends message.complete (e.g. it was already idle), don't leave Stop stuck on
        scope.launch {
            delay(6_000)
            if (busy.value && status.value == "Stopping…") { busy.value = false; status.value = ""; items.update { l -> l.map { if (it is ChatItem.Assistant && it.streaming) it.copy(streaming = false) else if (it is ChatItem.Tool && !it.done) it.copy(done = true) else it } } }
        }
        return ok
    }

    fun answer(ask: ServerAsk, result: JsonObject) {
        val idStr = (ask.id as? JsonPrimitive)?.content.orEmpty()
        if (idStr.startsWith("resume-approval:")) {
            asks.update { l -> l.filterNot { it.id == ask.id } }
            scope.launch { runCatching { rpc("approval.respond", buildJsonObject {
                put("session_id", runtimeSid); put("choice", result.s("choice").ifBlank { "deny" })
                idStr.removePrefix("resume-approval:").takeIf { it.isNotBlank() }?.let { put("request_id", it) }
            }) } }
            return
        }
        val frame = buildJsonObject { put("jsonrpc", "2.0"); put("id", ask.id); put("result", result) }
        ws?.send(frame.toString())
        asks.update { l -> l.filterNot { it.id == ask.id } }
    }


    // ── approvals and vault asks ─────────────────────────────────────────────

    private val acked = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** The approval card is on screen: tell Hermes, which starts its timeout clock from now (not from when it asked). */
    fun ackApproval(ask: ServerAsk) {
        if (ask.method != "approval") return
        val rid = ask.params.sn("request_id")?.takeIf { it.isNotBlank() }
            ?: (ask.id as? JsonPrimitive)?.content?.removePrefix("resume-approval:")?.takeIf { it.isNotBlank() } ?: return
        if (!acked.add(rid)) return
        val sid = runtimeSid
        scope.launch { runCatching { rpc("approval.received", jsonOf("session_id" to sid, "request_id" to rid)) } }
    }

    // ── todos, subagents, goals ──────────────────────────────────────────────

    val todos = MutableStateFlow<List<Todo>>(emptyList())
    private var todoRev = -1L
    private fun applyTodos(arr: JsonArray, rev: Long) {
        if (rev in 1 until todoRev) return   // an older snapshot arriving late
        todoRev = rev
        todos.value = arr.mapIndexedNotNull { i, e ->
            val o = e as? JsonObject ?: return@mapIndexedNotNull (e as? JsonPrimitive)?.contentOrNull?.let { Todo("$i", it, "pending") }
            val text = o.sn("content") ?: o.sn("text") ?: o.sn("title") ?: o.sn("task") ?: return@mapIndexedNotNull null
            Todo(o.sn("id") ?: "$i", text, (o.sn("status") ?: if (o.b("done") || o.b("completed")) "completed" else "pending").lowercase())
        }
    }

    /** Live delegated children of this chat, by id, fed by subagent.* events. */
    val subagents = MutableStateFlow<Map<String, Subagent>>(emptyMap())
    private fun onSubagent(type: String, pl: JsonObject) {
        val id = pl.sn("subagent_id")?.takeIf { it.isNotBlank() } ?: ("task-" + pl.l("task_index"))
        val st = pl.sn("status")?.takeIf { it.isNotBlank() } ?: when (type) {
            "subagent.spawn_requested" -> "queued"
            "subagent.complete" -> "completed"
            else -> "running"
        }
        subagents.update { m ->
            val old = m[id]
            m + (id to Subagent(
                id = id, goal = pl.sn("goal")?.takeIf { it.isNotBlank() } ?: old?.goal.orEmpty(), status = st,
                model = pl.sn("model") ?: old?.model.orEmpty(), depth = pl["depth"]?.let { pl.l("depth").toInt() } ?: old?.depth ?: 0,
                toolCount = pl["tool_count"]?.let { pl.l("tool_count").toInt() } ?: old?.toolCount ?: 0,
                lastTool = (if (type == "subagent.tool") pl.sn("tool_name") ?: pl.sn("name") ?: pl.sn("text") else null) ?: old?.lastTool.orEmpty(),
                summary = pl.sn("summary") ?: pl.sn("output_tail") ?: old?.summary.orEmpty(),
                startedAt = old?.startedAt?.takeIf { it > 0 } ?: now(),
                endedAt = if (type == "subagent.complete") now() else old?.endedAt ?: 0,
                inTok = pl["input_tokens"]?.let { pl.l("input_tokens") } ?: old?.inTok ?: 0,
                outTok = pl["output_tokens"]?.let { pl.l("output_tokens") } ?: old?.outTok ?: 0,
            ))
        }
    }
    /** One read when you open a chat that already has children running; events take over from there. */
    suspend fun seedSubagents() {
        if (runtimeSid.isBlank()) return
        val r = rpc("subagent.list", jsonOf("session_id" to runtimeSid))
        val live = r.a("subagents").objs().associate { o ->
            val id = o.s("subagent_id")
            id to Subagent(id, o.s("goal"), o.sn("status") ?: "running", o.s("model"), o.l("depth").toInt(), o.l("tool_count").toInt(), o.s("last_tool"),
                startedAt = serverTime(o["started_at"]) ?: now())
        }
        subagents.update { m -> m.filterValues { it.done } + live }
    }
    suspend fun delegationStatus(): JsonObject = rpc("delegation.status", JsonObject(emptyMap()))
    suspend fun pauseDelegation(paused: Boolean): Boolean = rpc("delegation.pause", jsonOf("paused" to paused)).b("paused")

    /** Goal / loop / heartbeat snapshot for this chat (null when none of them is set). */
    val control = MutableStateFlow<JsonObject?>(null)
    suspend fun loadControl() {
        if (runtimeSid.isBlank()) return
        control.value = rpc("session.control.read", jsonOf("session_id" to runtimeSid)).o("control")
    }
    /** goal.pause/resume/clear/unwait, loop.pause/resume/stop, heartbeat.pause/resume/clear, subgoal.* */
    suspend fun controlAction(action: String, text: String? = null, index: Int? = null): String? {
        val r = rpc("session.control", buildJsonObject {
            put("session_id", runtimeSid); put("action", action)
            if (text != null || index != null) put("args", buildJsonObject { text?.let { put("text", it) }; index?.let { put("index", it) } })
        })
        r.o("control")?.let { control.value = it }
        return r.o("dispatch")?.let { d -> d.sn("notice") ?: d.sn("message") ?: d.sn("output") }?.takeIf { it.isNotBlank() }
    }

    suspend fun contextBreakdown(): JsonObject = rpc("session.context_breakdown", jsonOf("session_id" to runtimeSid))

    // ── checkpoints ──────────────────────────────────────────────────────────

    suspend fun checkpoints(): JsonObject = rpc("rollback.list", jsonOf("session_id" to runtimeSid))
    suspend fun checkpointDiff(hash: String): JsonObject = rpc("rollback.diff", jsonOf("session_id" to runtimeSid, "hash" to hash))
    suspend fun restoreCheckpoint(hash: String, file: String? = null): JsonObject {
        val r = rpc("rollback.restore", jsonOf("session_id" to runtimeSid, "hash" to hash, "file_path" to file?.ifBlank { null }))
        if (!r.b("success")) throw IllegalStateException(r.sn("error") ?: r.sn("reason") ?: "Couldn't restore that checkpoint")
        // a full restore also rewinds the conversation: reload it so the transcript matches the files
        if (r.l("history_removed") > 0 && storedSid.isNotBlank()) runCatching { val keep = storedSid; val s = resumeRpc(keep); if (storedSid == keep) applySnapshot(s) }
        return r
    }

    // ── branch, undo, retry, redirect ────────────────────────────────────────

    /** Fork this chat into a new one that shares its history so far, and switch to it. */
    suspend fun branch(name: String? = null) {
        if (runtimeSid.isBlank()) return
        loadingSession.value = true
        try {
            val r = rpc("session.branch", jsonOf("session_id" to runtimeSid, "name" to name?.ifBlank { null }, "idempotency_key" to java.util.UUID.randomUUID().toString()))
            val wasMine = store.isMine(storedSid)
            todos.value = emptyList(); subagents.value = emptyMap(); control.value = null
            storedSid = r.s("stored_session_id")
            if (wasMine) store.markMine(storedSid)
            title.value = r.sn("title")?.ifBlank { null } ?: name?.ifBlank { null } ?: "Branch"
            runCatching { if (isActive) store.lastChatTitle = title.value }
            applySnapshot(r)
            items.update { it + ChatItem.Notice(k("n"), "Branched from “${r.sn("parent")?.take(40) ?: "the original"}”") }
        } finally { loadingSession.value = false }
    }

    /** Drop the last turn you sent (and its reply) from this chat. */
    suspend fun undo(): Int {
        if (runtimeSid.isBlank()) return 0
        if (busy.value) throw IllegalStateException("Stop the reply first, then undo")
        val removed = rpc("session.undo", jsonOf("session_id" to runtimeSid, "intent" to "undo")).l("removed").toInt()
        if (removed > 0) dropLastTurn()
        return removed
    }

    private fun dropLastTurn(): ChatItem.User? {
        val l = items.value
        val i = l.indexOfLast { it is ChatItem.User }
        if (i < 0) return null
        val u = l[i] as ChatItem.User
        items.value = l.take(i)
        userSeen = u.ordinal.coerceAtLeast(0)
        return u
    }

    /** Runs the last message again for a fresh answer: session.undo(retry) then the same text, falling back to an in-place edit. */
    suspend fun regenerate() {
        val last = items.value.filterIsInstance<ChatItem.User>().lastOrNull() ?: return
        if (busy.value) { edit(last, last.raw); return }
        val removed = try { rpc("session.undo", jsonOf("session_id" to runtimeSid, "intent" to "retry")).l("removed") } catch (e: RpcError) { -1L }
        if (removed <= 0) { edit(last, last.raw); return }
        dropLastTurn()
        val key = k("u")
        val ord = userSeen++
        items.update { it + last.copy(key = key, rowId = null, ordinal = ord, reactions = emptyList()) }
        busy.value = true
        try {
            val r = rpc("prompt.submit", jsonOf("session_id" to runtimeSid, "text" to last.raw))
            r.l("user_row_id").takeIf { it > 0 }?.let { row -> items.update { l -> l.map { if (it is ChatItem.User && it.key == key) it.copy(rowId = row) else it } } }
        } catch (e: Exception) {
            busy.value = false
            items.update { it + ChatItem.Notice(k("n"), e.message ?: "Couldn't retry", true) }
        }
    }

    /** Replace what the running turn is doing with this instead (unlike steer, which adds to it). */
    suspend fun redirect(text: String) {
        val r = rpc("session.redirect", jsonOf("session_id" to runtimeSid, "text" to text))
        when (r.s("status")) {
            "rejected" -> throw IllegalStateException("Hermes couldn't redirect this turn. Queue it instead.")
            "queued" -> items.update { it + ChatItem.Notice(k("n"), "↪ Redirect queued: " + text.take(120)) }
            else -> items.update { it + ChatItem.Notice(k("n"), "↪ Redirected: " + text.take(120)) }
        }
    }

    // ── background tasks ─────────────────────────────────────────────────────

    private val backgroundTasks = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** A finished /background task: (question, answer), for the notification. */
    val backgroundDone = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    /** Runs a task on a fresh agent that outlives this turn; its answer lands here and as a notification. */
    suspend fun runInBackground(text: String) {
        if (runtimeSid.isBlank()) newChat()
        val id = rpc("prompt.background", jsonOf("session_id" to runtimeSid, "text" to text)).s("task_id")
        if (id.isNotBlank()) backgroundTasks += id
        items.update { it + ChatItem.Notice(k("n"), "Running in the background: " + text.take(100)) }
    }

    // ── out-of-band notices and list refreshes ───────────────────────────────

    val toasts = kotlinx.coroutines.flow.MutableSharedFlow<HermesToast>(extraBufferCapacity = 8)
    val clearedToasts = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Bumped on sessions.changed / cron.changed so lists refetch instead of polling. */
    val sessionsChanged = MutableStateFlow(0)
    val cronChanged = MutableStateFlow(0)

    // ── settings Hermes keeps globally ───────────────────────────────────────

    /** Whether Hermes reads your reactions on the next turn (config display.message_reactions). */
    suspend fun reactionsVisible(): Boolean =
        rpc("config.get", jsonOf("key" to "full")).o("config")?.o("display")?.let { (it["message_reactions"] as? JsonPrimitive)?.booleanOrNull } ?: false
    suspend fun setReactionsVisible(on: Boolean): Boolean =
        rpc("config.set", jsonOf("key" to "display.message_reactions", "value" to on)).let { r -> (r["value"] as? JsonPrimitive)?.booleanOrNull ?: on }

    fun reset() { disconnect(); botProfile.value = null; runtimeSid = ""; storedSid = ""; items.value = emptyList(); title.value = "New chat"
        todos.value = emptyList(); subagents.value = emptyMap(); control.value = null; asks.value = emptyList() }
}

private const val AUTH_ERR = "Sign-in expired"
