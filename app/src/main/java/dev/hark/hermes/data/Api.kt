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
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** For links in replies: no redirects, so a public URL can't bounce the phone onto your LAN. */
    private val publicHttp: OkHttpClient by lazy { http.newBuilder().followRedirects(false).followSslRedirects(false).build() }

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

    /** Raw bytes from an authenticated endpoint (file downloads). Returns bytes, mime type and the server's file name. */
    suspend fun bytes(path: String, maxBytes: Long = 60L * 1024 * 1024): Triple<ByteArray, String, String> = withContext(Dispatchers.IO) {
        var token = accessToken()
        repeat(2) { attempt ->
            val req = Request.Builder().url(base + withProfile(path, true)).get().apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.build()
            http.newCall(req).execute().use { r ->
                if (r.code == 401 && attempt == 0 && store.auth.value.authRequired && refresh(token)) { token = store.auth.value.accessToken; return@repeat }
                if (!r.isSuccessful) throw ApiException(r.code, errorDetail(r.body?.string().orEmpty()) ?: "Download failed (${r.code})")
                val len = r.body?.contentLength() ?: -1
                if (len > maxBytes) throw ApiException(413, "File is too large to open on the phone")
                val cd = r.header("Content-Disposition").orEmpty()
                val name = Regex("filename\\*=UTF-8''([^;]+)").find(cd)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: Regex("filename=\"?([^\";]+)").find(cd)?.groupValues?.get(1) ?: ""
                return@withContext Triple(readCapped(r.body!!, maxBytes, "File is too large to open on the phone"), r.header("Content-Type").orEmpty().substringBefore(';'), name)
            }
        }
        throw AuthExpired()
    }

    /** Plain GET of a public URL (images Hermes links to). */
    suspend fun fetchUrl(url: String, maxBytes: Long = 15L * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        publicHttp.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "Couldn't load image")
            if ((r.body?.contentLength() ?: 0) > maxBytes) throw ApiException(413, "Image too large")
            readCapped(r.body!!, maxBytes, "Image too large")
        }
    }

    suspend fun obj(path: String, profile: Boolean = true): JsonObject = get(path, profile).objOrNull() ?: JsonObject(emptyMap())
    suspend fun arr(path: String, profile: Boolean = true): JsonArray = get(path, profile).arrOrEmpty()

    /** A token for any saved server (one live socket each); the active server goes through the normal path. */
    suspend fun accessTokenFor(server: String): String {
        if (server.isBlank() || server == store.activeId.value) return accessToken()
        val a = store.serverAuth(server) ?: return ""
        if (a.refreshToken.isNotBlank() && a.expiresAt > 0 && a.expiresAt - 60 < System.currentTimeMillis() / 1000.0) refreshServer(server, a)
        return store.serverAuth(server)?.accessToken.orEmpty()
    }

    private suspend fun refreshServer(server: String, a: AuthState): Boolean = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                val body = jsonOf("refresh_token" to a.refreshToken, "provider" to a.provider).toString().toRequestBody(JSONT)
                val req = Request.Builder().url(a.baseUrl.trimEnd('/') + "/auth/native/refresh").post(body).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext false
                    val o = Jsonx.parseToJsonElement(r.body!!.string()).jsonObject
                    store.updateServer(server) { it.copy(accessToken = o.s("access_token"), refreshToken = o.sn("refresh_token") ?: it.refreshToken, expiresAt = o.d("expires_at")) }
                    true
                }
            } catch (e: Exception) { false }
        }
    }

    fun wsUrlFor(server: String, path: String, token: String): String {
        if (server.isBlank() || server == store.activeId.value) return wsUrl(path, token)
        val b = store.serverAuth(server)?.baseUrl.orEmpty().trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return "$b$path" + if (token.isNotBlank()) "?token=" + enc(token) else ""
    }

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


/** Reads a body but stops at [max] bytes, whatever Content-Length claimed (chunked replies have none). */
internal fun readCapped(body: okhttp3.ResponseBody, max: Long, tooBig: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    body.byteStream().use { input ->
        while (true) {
            val n = input.read(buf); if (n < 0) break
            if (out.size() + n > max) throw ApiException(413, tooBig)
            out.write(buf, 0, n)
        }
    }
    return out.toByteArray()
}

/** Loopback, LAN, link-local, CGNAT/Tailscale and .local/.ts.net names: places plain http is normal. */
fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase().substringBefore(':').trim('[', ']')
    if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".ts.net") || h.endsWith(".internal")) return true
    val v4 = h.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && h.count { c -> c == '.' } == 3 }
    if (v4 != null) {
        val (a, b) = v4[0] to v4[1]
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }
    return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
}

/** http:// to a public address: tokens and chats would cross the internet unencrypted. */
fun isInsecureUrl(url: String): Boolean = url.startsWith("http://") && !isPrivateHost(url.removePrefix("http://").substringBefore('/'))
