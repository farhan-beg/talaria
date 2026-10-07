package dev.hark.hermes.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.StateFlow

data class AuthState(
    val baseUrl: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Double = 0.0,
    val provider: String = "",
    val userId: String = "",
    val authRequired: Boolean = true,
    /** Your name for this server ("Home lab"); falls back to its host. */
    val name: String = "",
) {
    val host get() = baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
    val label get() = name.ifBlank { host.ifBlank { "New server" } }
    val isConfigured get() = baseUrl.isNotBlank()
    val isSignedIn get() = isConfigured && (!authRequired || accessToken.isNotBlank())
}

/** Encrypted persistence for the server URL and bearer tokens. */
class Store(context: Context) {
    /** True when the Android keystore couldn't open encrypted storage and tokens sit in plain app storage. */
    var insecure = false
        private set

    private fun openEncrypted(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, "hermes_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val prefs: SharedPreferences = try {
        openEncrypted(context)
    } catch (e: Exception) {
        // usually a keystore key lost after a restore or OS update: the old file can't be read, so start
        // it fresh (you sign in again) rather than dropping tokens into plain storage
        try {
            context.deleteSharedPreferences("hermes_secure")
            openEncrypted(context)
        } catch (e2: Exception) {
            android.util.Log.w("Talaria", "Encrypted storage unavailable; using plain app storage")
            insecure = true
            context.getSharedPreferences("hermes_plain", Context.MODE_PRIVATE)
        }
    }

    private val _auth = MutableStateFlow(load())
    val auth: StateFlow<AuthState> = _auth

    private val _profile = MutableStateFlow(prefs.getString("profile", "") ?: "")
    val profile: StateFlow<String> = _profile

    private val _theme = MutableStateFlow(prefs.getString("theme", "system") ?: "system")
    val theme: StateFlow<String> = _theme

    private fun load() = AuthState(
        baseUrl = prefs.getString("base", "") ?: "",
        accessToken = prefs.getString("at", "") ?: "",
        refreshToken = prefs.getString("rt", "") ?: "",
        expiresAt = try { prefs.getLong("exp2", 0L).toDouble() } catch (e: Exception) { 0.0 },
        provider = prefs.getString("prov", "") ?: "",
        userId = prefs.getString("uid", "") ?: "",
        authRequired = prefs.getBoolean("authreq", true),
        name = prefs.getString("name", "") ?: "",
    )

    fun update(block: (AuthState) -> AuthState) {
        val n = block(_auth.value)
        prefs.edit()
            .putString("base", n.baseUrl).putString("at", n.accessToken).putString("rt", n.refreshToken)
            .putLong("exp2", n.expiresAt.toLong()).putString("prov", n.provider).putString("uid", n.userId)
            .putBoolean("authreq", n.authRequired).putString("name", n.name).apply()
        _auth.value = n
        saveActiveIntoList()
    }

    // ── several servers ─────────────────────────────────────────────────────
    data class Server(val id: String, val auth: AuthState)

    private fun encode(a: AuthState) = kotlinx.serialization.json.buildJsonObject {
        put("base", kotlinx.serialization.json.JsonPrimitive(a.baseUrl)); put("at", kotlinx.serialization.json.JsonPrimitive(a.accessToken))
        put("rt", kotlinx.serialization.json.JsonPrimitive(a.refreshToken)); put("exp", kotlinx.serialization.json.JsonPrimitive(a.expiresAt))
        put("prov", kotlinx.serialization.json.JsonPrimitive(a.provider)); put("uid", kotlinx.serialization.json.JsonPrimitive(a.userId))
        put("authreq", kotlinx.serialization.json.JsonPrimitive(a.authRequired)); put("name", kotlinx.serialization.json.JsonPrimitive(a.name))
    }
    private fun decode(o: kotlinx.serialization.json.JsonObject): AuthState {
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        return AuthState(str("base"), str("at"), str("rt"), str("exp").toDoubleOrNull() ?: 0.0, str("prov"), str("uid"), str("authreq") != "false", str("name"))
    }
    private fun readServers(): List<Server> = runCatching {
        Jsonx.parseToJsonElement(prefs.getString("servers", "[]") ?: "[]").jsonArray.mapNotNull { e ->
            val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            Server((o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null, decode(o["auth"] as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null))
        }
    }.getOrDefault(emptyList())
    private fun writeServers(l: List<Server>) {
        prefs.edit().putString("servers", kotlinx.serialization.json.JsonArray(l.map { s ->
            kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive(s.id)); put("auth", encode(s.auth)) }
        }).toString()).apply()
        _servers.value = l
    }
    private val _servers = MutableStateFlow(readServers())
    val servers: StateFlow<List<Server>> = _servers
    private val _activeId = MutableStateFlow(prefs.getString("active_server", "") ?: "")
    val activeId: StateFlow<String> = _activeId

    init {
        // first run after the update: adopt the server you were already signed in to
        if (_servers.value.isEmpty() && _auth.value.isConfigured) {
            val id = java.util.UUID.randomUUID().toString().take(8)
            prefs.edit().putString("active_server", id).apply(); _activeId.value = id
            writeServers(listOf(Server(id, _auth.value)))
        }
    }

    private fun saveActiveIntoList() {
        val a = _auth.value
        if (!a.isConfigured) return
        var id = _activeId.value
        if (id.isBlank()) { id = java.util.UUID.randomUUID().toString().take(8); prefs.edit().putString("active_server", id).apply(); _activeId.value = id }
        val l = _servers.value
        writeServers(if (l.any { it.id == id }) l.map { if (it.id == id) Server(id, a) else it } else l + Server(id, a))
    }

    /** Makes another saved server the one the app talks to. */
    fun switchTo(id: String) {
        val s = _servers.value.firstOrNull { it.id == id } ?: return
        prefs.edit().putString("active_server", id).apply(); _activeId.value = id
        setProfile("")
        update { s.auth }
    }

    /** Opens the connect screen for a new server; the current one stays saved. */
    fun addServer() {
        prefs.edit().putString("active_server", "").apply(); _activeId.value = ""
        setProfile("")
        val n = AuthState()
        prefs.edit().putString("base", "").putString("at", "").putString("rt", "").putString("name", "").apply()
        _auth.value = n
    }

    fun renameServer(id: String, name: String) {
        if (id == _activeId.value) update { it.copy(name = name.trim()) }
        else writeServers(_servers.value.map { if (it.id == id) Server(id, it.auth.copy(name = name.trim())) else it })
    }

    fun removeServer(id: String) {
        writeServers(_servers.value.filterNot { it.id == id })
        if (id == _activeId.value) {
            val next = _servers.value.firstOrNull()
            if (next != null) switchTo(next.id) else { prefs.edit().putString("active_server", "").apply(); _activeId.value = ""; update { AuthState() }; setProfile("") }
        }
    }

    fun setProfile(p: String) { prefs.edit().putString("profile", p).apply(); _profile.value = p }
    fun setTheme(t: String) { prefs.edit().putString("theme", t).apply(); _theme.value = t }

    // look & feel
    private fun str(k: String, d: String) = MutableStateFlow(prefs.getString(k, d) ?: d)
    private fun flag(k: String, d: Boolean) = MutableStateFlow(prefs.getBoolean(k, d))
    val palette = str("palette", "talaria")
    val glass = flag("glass", true)
    val motion = flag("motion", true)
    val ambient = flag("ambient", true)
    // session visibility
    val hideSubagents = flag("hide_subagents", true)
    val hideAutomation = flag("hide_automation", false)
    val hideEmpty = flag("hide_empty", true)
    /** Off by default: recents show only your own chats, not subagent or cron runs. */
    val showBackground = flag("show_background_sessions", false)
    /** On by default: `hermes chat -q` runs Hermes starts itself (source cli) count as background work. */
    val hideCli = flag("hide_cli_sessions", true)
    val nerd = flag("nerd_stats", false)
    /** Your fast-mode choice, re-applied to each chat (the server scopes it per session). */
    val fastPref = flag("fast_pref", false)
    /** Voice mode reads replies with the TTS voice configured on Hermes instead of the phone's engine. */
    val hermesVoice = flag("hermes_voice", false)
    /** Markdown in your own messages: live styling + format bar in the composer, rendered in sent bubbles. */
    val markdownInput = flag("markdown_input", true)
    /** Phone TTS: voice name ("" = engine default), speech rate and pitch (1.0 = normal). */
    val phoneVoice = str("phone_voice", "")
    val phoneRate = str("phone_rate", "1.0")
    val phonePitch = str("phone_pitch", "1.0")
    /** Sessions started on this phone. Hermes stamps them "tui" (the protocol we speak), so the label lives here. */
    val mineSessions = MutableStateFlow(prefs.getStringSet("mine_sessions", emptySet())!!.toSet())
    fun isMine(id: String) = id.isNotBlank() && id in mineSessions.value
    fun markMine(id: String) {
        if (id.isBlank() || id in mineSessions.value) return
        val next = (mineSessions.value + id).let { if (it.size > 3000) it.drop(it.size - 3000).toSet() else it }
        prefs.edit().putStringSet("mine_sessions", next).apply(); mineSessions.value = next
    }
    var askedNotif: Boolean
        get() = prefs.getBoolean("asked_notif", false)
        set(v) { prefs.edit().putBoolean("asked_notif", v).apply() }
    // saved snippets for the attach sheet
    val snippets = MutableStateFlow(runCatching { Jsonx.parseToJsonElement(prefs.getString("snippets", "[]") ?: "[]").jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()))
    fun saveSnippets(l: List<String>) { prefs.edit().putString("snippets", kotlinx.serialization.json.JsonArray(l.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()).apply(); snippets.value = l }
    fun set(flow: MutableStateFlow<String>, key: String, v: String) { prefs.edit().putString(key, v).apply(); flow.value = v }
    fun set(flow: MutableStateFlow<Boolean>, key: String, v: Boolean) { prefs.edit().putBoolean(key, v).apply(); flow.value = v }

    fun signOut() = update { it.copy(accessToken = "", refreshToken = "", expiresAt = 0.0, userId = "") }
    fun forget() { removeServer(_activeId.value) }
}
