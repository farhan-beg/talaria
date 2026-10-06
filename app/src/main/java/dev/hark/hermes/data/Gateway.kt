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

sealed interface ChatItem {
    val key: String
    data class User(override val key: String, val text: String, val raw: String = text, val rowId: Long? = null, val ordinal: Int = -1) : ChatItem
    data class Assistant(
        override val key: String, val text: String, val reasoning: String = "", val streaming: Boolean = false,
        // nerd stats: wall-clock marks (ms) and how much streamed
        val startMs: Long = 0, val firstMs: Long = 0, val endMs: Long = 0, val chars: Int = 0, val outTokens: Long = 0,
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
    data class Tool(override val key: String, val name: String, val preview: String, val done: Boolean, val summary: String = "", val duration: Double = 0.0) : ChatItem
    data class Notice(override val key: String, val text: String, val error: Boolean = false) : ChatItem
    /** Output of a slash command, shown as a terminal-style card. */
    data class Output(override val key: String, val command: String, val text: String) : ChatItem
}

/** A file staged into the session before the next prompt. Images ride the turn; files become @file refs. */
data class Attachment(val name: String, val kind: String, val path: String = "", val ref: String = "", val pages: Int = 0)

data class SlashHint(val text: String, val display: String, val meta: String, val skill: Boolean)

data class ServerAsk(val id: JsonElement, val method: String, val params: JsonObject)

enum class Conn { Idle, Connecting, Ready, Failed }

/** JSON-RPC client for the dashboard's /api/ws gateway — the same transport Hermes Desktop uses for native chat. */
class Gateway(private val api: Api, private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var readyGate = CompletableDeferred<Unit>()

    val conn = MutableStateFlow(Conn.Idle)
    val connError = MutableStateFlow<String?>(null)
    val items = MutableStateFlow<List<ChatItem>>(emptyList())
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val title = MutableStateFlow("New chat")
    val model = MutableStateFlow("")
    val usage = MutableStateFlow<JsonObject?>(null)
    val asks = MutableStateFlow<List<ServerAsk>>(emptyList())
    val loadingSession = MutableStateFlow(false)
    val yolo = MutableStateFlow(false)
    val attachments = MutableStateFlow<List<Attachment>>(emptyList())

    @Volatile var runtimeSid: String = ""; private set
    @Volatile var storedSid: String = ""; private set
    private var seq = 0L
    private var userSeen = 0
    private fun k(p: String) = "$p-${seq++}"

    fun connect() {
        if (conn.value == Conn.Connecting || conn.value == Conn.Ready) return
        conn.value = Conn.Connecting
        connError.value = null
        readyGate = CompletableDeferred()
        scope.launch {
            val token = try { api.accessToken() } catch (e: Exception) { "" }
            val req = Request.Builder().url(api.wsUrl("/api/ws", token)).build()
            ws = api.http.newWebSocket(req, Listener())
        }
    }

    fun disconnect() {
        ws?.close(1000, "bye"); ws = null
        conn.value = Conn.Idle
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            conn.value = Conn.Ready
            readyGate.complete(Unit)
            scope.launch { runCatching { rpc("client.capabilities", jsonOf("server_requests" to true)) } }
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            text.split('\n').filter { it.isNotBlank() }.forEach { line ->
                runCatching { handle(Jsonx.parseToJsonElement(line).jsonObject) }
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { dropped(if (code == 4401) "Sign-in expired" else null) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            dropped(if (response?.code == 401 || response?.code == 403) "Sign-in expired" else (t.message ?: "Connection lost"))
        }
    }

    private fun dropped(err: String?) {
        if (ws == null && conn.value == Conn.Idle) return
        ws = null
        conn.value = if (err != null) Conn.Failed else Conn.Idle
        connError.value = err
        if (!readyGate.isCompleted) readyGate.completeExceptionally(java.io.IOException(err ?: "closed"))
        pending.values.forEach { it.completeExceptionally(java.io.IOException(err ?: "Connection closed")) }
        pending.clear()
        busy.value = false
        status.value = ""
    }

    private suspend fun ensureReady() {
        if (conn.value != Conn.Ready) connect()
        withTimeout(20_000) { readyGate.await() }
    }

    suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        ensureReady()
        val id = "m${ids.getAndIncrement()}"
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        val frame = jsonOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)
        if (ws?.send(frame.toString()) != true) { pending.remove(id); throw java.io.IOException("Not connected") }
        return withTimeout(120_000) { d.await() }
    }

    private fun handle(m: JsonObject) {
        val method = m.s("method")
        when {
            method == "event" -> onEvent(m.o("params") ?: return)
            method.isNotBlank() && m["id"] != null -> {
                val p = m.o("params") ?: JsonObject(emptyMap())
                asks.update { it + ServerAsk(m["id"]!!, method, p) }
            }
            m["id"] != null -> {
                val id = m.s("id")
                val d = pending.remove(id) ?: return
                val err = m.o("error")
                if (err != null) d.completeExceptionally(java.io.IOException(err.s("message").ifBlank { "Request failed" }))
                else d.complete(m.o("result") ?: JsonObject(emptyMap()))
            }
        }
    }

    private fun mine(sid: String) = sid.isBlank() || sid == runtimeSid

    private fun onEvent(p: JsonObject) {
        val type = p.s("type")
        val sid = p.s("session_id")
        val pl = p.o("payload") ?: JsonObject(emptyMap())
        when (type) {
            "gateway.ready" -> {}
            "request.cancel" -> { val id = pl.s("id"); asks.update { l -> l.filterNot { (it.id as? JsonPrimitive)?.content == id } } }
            "skin.changed" -> {}
            else -> if (mine(sid)) onSessionEvent(type, pl)
        }
    }

    private fun lastStreaming(): Int = items.value.indexOfLast { it is ChatItem.Assistant && it.streaming }

    private var turnStart = 0L
    private var usageOutAtStart = 0L
    private fun outOf(u: JsonObject?): Long = u?.let { maxOf(it.l("output"), it.l("output_tokens"), it.l("completion_tokens")) } ?: 0L
    private fun now() = System.currentTimeMillis()
    private fun ChatItem.Assistant.ended() = if (streaming || endMs == 0L) copy(streaming = false, endMs = now()) else copy(streaming = false)
    private fun ChatItem.Assistant.got(t: String) = copy(firstMs = if (firstMs == 0L && t.isNotEmpty()) now() else firstMs, chars = chars + t.length)

    private fun ensureAssistant(): Int {
        val i = lastStreaming()
        if (i >= 0) return i
        items.update { it + ChatItem.Assistant(k("a"), "", streaming = true, startMs = if (turnStart > 0) turnStart else now()) }
        return items.value.lastIndex
    }

    private fun mutate(i: Int, f: (ChatItem) -> ChatItem) = items.update { l -> l.toMutableList().also { if (i in it.indices) it[i] = f(it[i]) } }

    private fun onSessionEvent(type: String, pl: JsonObject) {
        when (type) {
            "btw.complete" -> items.update { it + ChatItem.Output(k("o"), "btw · " + pl.s("question").take(60), pl.s("text")) }
            "message.start" -> { busy.value = true; turnStart = now(); usageOutAtStart = outOf(usage.value); ensureAssistant() }
            "message.delta" -> { val i = ensureAssistant(); val t = pl.s("text"); mutate(i) { (it as ChatItem.Assistant).got(t).copy(text = it.text + t) } }
            "reasoning.delta", "thinking.delta" -> { val i = ensureAssistant(); val t = pl.s("text"); mutate(i) { (it as ChatItem.Assistant).got(t).copy(reasoning = it.reasoning + t) } }
            "message.interim" -> {
                val t = pl.s("text")
                val i = lastStreaming()
                if (i >= 0) mutate(i) { a -> (a as ChatItem.Assistant).ended().let { it.copy(text = it.text.ifBlank { t }) } }
                else if (t.isNotBlank()) items.update { it + ChatItem.Assistant(k("a"), t) }
            }
            "tool.generating" -> status.value = "Preparing ${pl.s("name")}…"
            "tool.start" -> {
                val i = lastStreaming()
                if (i >= 0) mutate(i) { (it as ChatItem.Assistant).ended() }
                items.update { l ->
                    l.filterNot { it is ChatItem.Assistant && it.text.isBlank() && it.reasoning.isBlank() } +
                        ChatItem.Tool("t-" + pl.s("tool_id"), pl.s("name"), pl.s("context").ifBlank { pl.s("preview").ifBlank { pl.s("args_text") } }, false)
                }
                status.value = ""
            }
            "tool.complete" -> {
                val key = "t-" + pl.s("tool_id")
                items.update { l -> l.map { if (it is ChatItem.Tool && it.key == key) it.copy(done = true, summary = pl.s("summary"), duration = pl.d("duration_s")) else it } }
            }
            "status.update" -> status.value = pl.s("text")
            "session.usage" -> usage.value = pl.o("usage")
            "session.title" -> title.value = pl.s("title").ifBlank { title.value }
            "session.info" -> { pl.sn("model")?.let { model.value = it }; pl["yolo"]?.let { yolo.value = pl.b("yolo") } }
            "message.complete" -> {
                val text = pl["text"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
                val i = lastStreaming()
                val reused = pl.b("response_reused")
                pl.o("usage")?.let { usage.value = it }
                val turnOut = (outOf(usage.value) - usageOutAtStart).coerceAtLeast(0)
                if (i >= 0) mutate(i) { a -> (a as ChatItem.Assistant).ended().let { it.copy(text = if (it.text.isBlank() && !reused) text else it.text) } }
                // credit the turn's exact output tokens to its last reply (single-reply turns are exact)
                if (turnOut > 0) {
                    val j = items.value.indexOfLast { it is ChatItem.Assistant }
                    if (j >= 0) mutate(j) { a -> (a as ChatItem.Assistant).let { if (it.startMs >= turnStart && it.firstMs > 0) it.copy(outTokens = turnOut) else it } }
                }
                else if (text.isNotBlank() && !reused && items.value.lastOrNull().let { it !is ChatItem.Assistant || it.text != text }) {
                    items.update { it + ChatItem.Assistant(k("a"), text) }
                }
                items.update { l -> l.filterNot { it is ChatItem.Assistant && it.text.isBlank() && it.reasoning.isBlank() } }
                val st = pl.s("status")
                if (st == "error") items.update { it + ChatItem.Notice(k("n"), pl.sn("error") ?: pl.sn("failure_reason") ?: "The turn failed", true) }
                if (st == "interrupted") items.update { it + ChatItem.Notice(k("n"), "Stopped") }
                pl.sn("warning")?.let { w -> items.update { it + ChatItem.Notice(k("n"), w) } }
                pl.o("usage")?.let { usage.value = it }
                busy.value = false
                status.value = ""
            }
            "error" -> { items.update { it + ChatItem.Notice(k("n"), pl.s("message"), true) }; busy.value = false; status.value = "" }
        }
    }

    // ── public chat API ──────────────────────────────────────────────────────

    private fun applySnapshot(r: JsonObject) {
        runtimeSid = r.s("session_id")
        storedSid = r.sn("stored_session_id") ?: storedSid
        r.o("info")?.let { info -> info.sn("model")?.let { model.value = it }; info["yolo"]?.let { yolo.value = info.b("yolo") } }
        val list = mutableListOf<ChatItem>()
        var ord = 0
        r.a("messages").objs().forEach { m ->
            val txt = m.s("text").ifBlank { (m["content"] as? JsonPrimitive)?.contentOrNull ?: "" }
            when (m.s("role")) {
                "user" -> {
                    val o = ord++
                    if (m.s("display_kind") != "hidden" && txt.isNotBlank()) list += ChatItem.User(k("u"), txt, txt, m["row_id"]?.let { m.l("row_id") }?.takeIf { it > 0 }, o)
                }
                "assistant" -> if (txt.isNotBlank()) list += ChatItem.Assistant(k("a"), txt, m.s("reasoning"))
                "tool" -> list += ChatItem.Tool(k("t"), m.s("name").ifBlank { "tool" }, m.s("context"), true)
                "system" -> {}
            }
        }
        items.value = list
        userSeen = ord
    }

    suspend fun newChat() {
        loadingSession.value = true
        try {
            items.value = emptyList(); title.value = "New chat"; usage.value = null; busy.value = false; attachments.value = emptyList()
            val p = store.profile.value
            val r = rpc("session.create", jsonOf("cols" to 80, "profile" to p.ifBlank { null }))
            storedSid = r.s("stored_session_id")
            applySnapshot(r)
        } finally { loadingSession.value = false }
    }

    suspend fun resume(stored: String, t: String?) {
        loadingSession.value = true
        try {
            items.value = emptyList(); title.value = t?.ifBlank { null } ?: "Chat"; usage.value = null; busy.value = false; attachments.value = emptyList()
            storedSid = stored
            val r = rpc("session.resume", jsonOf("session_id" to stored, "cols" to 80, "inline_images" to false))
            applySnapshot(r)
        } finally { loadingSession.value = false }
    }

    suspend fun send(text: String) {
        if (runtimeSid.isBlank()) newChat()
        val staged = attachments.value
        attachments.value = emptyList()
        val refs = staged.filter { it.ref.isNotBlank() }.joinToString(" ") { it.ref }
        val body = listOf(refs, text).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "See attached." }
        val shown = (staged.map { "\uD83D\uDCCE " + it.name } + listOf(text).filter { it.isNotBlank() }).joinToString("\n")
        val wasBusy = busy.value
        val key = k("u")
        val ord = userSeen++
        items.update { it + ChatItem.User(key, shown.ifBlank { body }, body, null, ord) }
        busy.value = true
        try {
            val r = rpc("prompt.submit", jsonOf("session_id" to runtimeSid, "text" to body))
            val row = r.l("user_row_id")
            if (row > 0) items.update { l -> l.map { if (it is ChatItem.User && it.key == key) it.copy(rowId = row) else it } }
        } catch (e: Exception) {
            if (wasBusy) { items.update { it + ChatItem.Notice(k("n"), e.message ?: "Couldn't send", true) }; return }
            busy.value = false
            items.update { it + ChatItem.Notice(k("n"), e.message ?: "Couldn't send", true) }
        }
    }

    // ── attachments ──────────────────────────────────────────────────────────

    suspend fun attach(bytes: ByteArray, name: String, mime: String) {
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
        attachments.update { it + a }
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

    suspend fun setModel(modelId: String, provider: String) {
        if (runtimeSid.isBlank()) newChat()
        val v = buildString { append(modelId); if (provider.isNotBlank()) append(" --provider ").append(provider); append(" --session") }
        rpc("config.set", jsonOf("key" to "model", "session_id" to runtimeSid, "value" to v))
        model.value = modelId
        items.update { it + ChatItem.Notice(k("n"), "Switched to $modelId") }
    }

    suspend fun setYolo(on: Boolean) {
        if (runtimeSid.isBlank()) newChat()
        val r = rpc("config.set", jsonOf("key" to "yolo", "session_id" to runtimeSid, "value" to if (on) "on" else "off"))
        yolo.value = r.s("value").let { if (it.isBlank()) on else it == "1" || it == "on" || it == "true" }
    }

    // ── slash commands ───────────────────────────────────────────────────────

    /** Completions for the text being typed; second value is where the replacement starts. */
    suspend fun completeSlash(text: String): Pair<List<SlashHint>, Int> {
        val r = rpc("complete.slash", jsonOf("text" to text, "session_id" to runtimeSid.ifBlank { null }, "profile" to store.profile.value.ifBlank { null }))
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

    /** Runs the last message again for a fresh answer. */
    suspend fun regenerate() {
        val last = items.value.filterIsInstance<ChatItem.User>().lastOrNull() ?: return
        edit(last, last.raw)
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

    suspend fun setSessionFlag(key: String, value: String) {
        rpc("config.set", jsonOf("session_id" to runtimeSid, "key" to key, "value" to value))
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

    suspend fun interrupt() { runCatching { rpc("session.interrupt", jsonOf("session_id" to runtimeSid)) } }

    fun answer(ask: ServerAsk, result: JsonObject) {
        val frame = buildJsonObject { put("jsonrpc", "2.0"); put("id", ask.id); put("result", result) }
        ws?.send(frame.toString())
        asks.update { l -> l.filterNot { it.id == ask.id } }
    }

    fun reset() { disconnect(); runtimeSid = ""; storedSid = ""; items.value = emptyList(); title.value = "New chat" }
}
