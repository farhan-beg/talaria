package dev.hark.hermes.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AuthState(
    val baseUrl: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Double = 0.0,
    val provider: String = "",
    val userId: String = "",
    val authRequired: Boolean = true,
) {
    val isConfigured get() = baseUrl.isNotBlank()
    val isSignedIn get() = isConfigured && (!authRequired || accessToken.isNotBlank())
}

/** Encrypted persistence for the server URL and bearer tokens. */
class Store(context: Context) {
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "hermes_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences("hermes_plain", Context.MODE_PRIVATE)
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
    )

    fun update(block: (AuthState) -> AuthState) {
        val n = block(_auth.value)
        prefs.edit()
            .putString("base", n.baseUrl).putString("at", n.accessToken).putString("rt", n.refreshToken)
            .putLong("exp2", n.expiresAt.toLong()).putString("prov", n.provider).putString("uid", n.userId)
            .putBoolean("authreq", n.authRequired).apply()
        _auth.value = n
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
    val nerd = flag("nerd_stats", false)
    fun set(flow: MutableStateFlow<String>, key: String, v: String) { prefs.edit().putString(key, v).apply(); flow.value = v }
    fun set(flow: MutableStateFlow<Boolean>, key: String, v: Boolean) { prefs.edit().putBoolean(key, v).apply(); flow.value = v }

    fun signOut() = update { it.copy(accessToken = "", refreshToken = "", expiresAt = 0.0, userId = "") }
    fun forget() { update { AuthState() }; setProfile("") }
}
