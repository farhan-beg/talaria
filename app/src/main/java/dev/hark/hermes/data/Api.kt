package dev.hark.hermes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)
class AuthExpired : IOException("Session expired. Sign in again.")

/** REST client for the Hermes dashboard API, authenticated with the RFC 8252 native bearer token. */
class Api(private val store: Store) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val JSONT = "application/json".toMediaType()
    private val refreshLock = Mutex()
    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expired: SharedFlow<Unit> = _expired
    @Volatile var lastAuthError: String? = null

    val base get() = store.auth.value.baseUrl.trimEnd('/')

    fun normalize(url: String): String {
        var u = url.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    /** Public probe: no auth. */
    suspend fun probe(baseUrl: String): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${baseUrl.trimEnd('/')}/api/status").get().build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "Server answered ${r.code}")
            try { Jsonx.parseToJsonElement(body).jsonObject } catch (e: Exception) {
                throw ApiException(r.code, "That URL isn't a Hermes dashboard")
            }
        }
    }

    suspend fun exchangeCode(baseUrl: String, code: String, verifier: String): JsonObject =
        withContext(Dispatchers.IO) {
            val body = jsonOf("code" to code, "code_verifier" to verifier).toString().toRequestBody(JSONT)
            val req = Request.Builder().url("${baseUrl.trimEnd('/')}/auth/native/token").post(body).build()
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException(r.code, errorDetail(txt) ?: "Sign-in failed (${r.code})")
                Jsonx.parseToJsonElement(txt).jsonObject
            }
        }

    private suspend fun refresh(staleToken: String): Boolean = refreshLock.withLock {
        val a = store.auth.value
        if (a.accessToken != staleToken && a.accessToken.isNotBlank()) return true // someone else refreshed
        if (a.refreshToken.isBlank()) return false
        withContext(Dispatchers.IO) {
            try {
                val body = jsonOf("refresh_token" to a.refreshToken, "provider" to a.provider).toString().toRequestBody(JSONT)
                val req = Request.Builder().url("$base/auth/native/refresh").post(body).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext false
                    val o = Jsonx.parseToJsonElement(r.body!!.string()).jsonObject
                    store.update {
                        it.copy(
                            accessToken = o.s("access_token"),
                            refreshToken = o.sn("refresh_token") ?: it.refreshToken,
                            expiresAt = o.d("expires_at"),
                        )
                    }
                    true
                }
            } catch (e: Exception) { false }
        }
    }

    suspend fun accessToken(): String {
        val a = store.auth.value
        if (a.refreshToken.isNotBlank() && a.expiresAt > 0 && a.expiresAt - 60 < System.currentTimeMillis() / 1000.0) {
            refresh(a.accessToken)
        }
        return store.auth.value.accessToken
    }

    private fun withProfile(path: String, profile: Boolean): String {
        val p = store.profile.value
        if (!profile || p.isBlank() || path.contains("profile=")) return path
        return path + (if (path.contains('?')) "&" else "?") + "profile=" + enc(p)
    }

    suspend fun call(method: String, path: String, body: JsonElement? = null, profile: Boolean = true): JsonElement =
        withContext(Dispatchers.IO) {
            var token = accessToken()
            var attempt = 0
            while (true) {
                val rb = when {
                    body != null -> body.toString().toRequestBody(JSONT)
                    method == "GET" -> null
                    else -> "{}".toRequestBody(JSONT)
                }
                val req = Request.Builder().url(base + withProfile(path, profile)).method(method, rb).apply {
                    if (token.isNotBlank()) header("Authorization", "Bearer $token")
                    header("Accept", "application/json")
                }.build()
                val resp = http.newCall(req).execute()
                resp.use { r ->
                    val txt = r.body?.string().orEmpty()
                    if (r.code == 401 && store.auth.value.authRequired) {
                        if (attempt == 0 && refresh(token)) { token = store.auth.value.accessToken; attempt++; return@use null }
                        lastAuthError = "$path → 401 ${errorDetail(txt) ?: ""}".trim()
                        _expired.tryEmit(Unit)
                        throw AuthExpired()
                    }
                    if (!r.isSuccessful) throw ApiException(r.code, errorDetail(txt) ?: "Request failed (${r.code})")
                    if (txt.isBlank()) JsonObject(emptyMap()) else try { Jsonx.parseToJsonElement(txt) } catch (e: Exception) { JsonPrimitive(txt) }
                }?.let { return@withContext it }
            }
            @Suppress("UNREACHABLE_CODE")
            JsonNull as JsonElement
        }

    suspend fun get(path: String, profile: Boolean = true) = call("GET", path, null, profile)
    suspend fun post(path: String, body: JsonElement? = null, profile: Boolean = true) = call("POST", path, body, profile)
    suspend fun put(path: String, body: JsonElement? = null, profile: Boolean = true) = call("PUT", path, body, profile)
    suspend fun patch(path: String, body: JsonElement? = null, profile: Boolean = true) = call("PATCH", path, body, profile)
    suspend fun delete(path: String, body: JsonElement? = null, profile: Boolean = true) = call("DELETE", path, body, profile)

    suspend fun obj(path: String, profile: Boolean = true): JsonObject = get(path, profile).objOrNull() ?: JsonObject(emptyMap())
    suspend fun arr(path: String, profile: Boolean = true): JsonArray = get(path, profile).arrOrEmpty()

    fun wsUrl(path: String, token: String, extra: String = ""): String {
        val b = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val q = buildString {
            if (token.isNotBlank()) append("token=").append(enc(token))
            if (extra.isNotBlank()) { if (isNotEmpty()) append('&'); append(extra) }
        }
        return "$b$path" + if (q.isNotEmpty()) "?$q" else ""
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
        fun errorDetail(txt: String): String? = try {
            val o = Jsonx.parseToJsonElement(txt).jsonObject
            val d = o["detail"]
            when (d) {
                is JsonPrimitive -> d.content
                is JsonObject -> d.s("message").ifBlank { d.s("error").ifBlank { d.toString() } }
                is JsonArray -> d.objs().joinToString("; ") { it.s("msg") }
                else -> o.sn("error") ?: o.sn("message")
            }
        } catch (e: Exception) { txt.take(200).ifBlank { null } }
    }
}
