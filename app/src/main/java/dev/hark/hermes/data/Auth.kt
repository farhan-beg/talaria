package dev.hark.hermes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * In-app sign-in, no browser. Uses the dashboard's username/password provider
 * (POST /auth/password-login). The session cookies it returns are the same provider-minted
 * tokens the native bearer path verifies, so the app keeps them as Bearer + refresh tokens
 * and rotates them through /auth/native/refresh.
 */
class NativeAuth(private val api: Api, private val store: Store) {
    private val json = "application/json".toMediaType()

    /** Password-capable providers the server offers (e.g. "basic"). */
    suspend fun passwordProviders(baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${baseUrl.trimEnd('/')}/api/auth/providers").get().build()
        try {
            api.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyList()
                val o = Jsonx.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                o["providers"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
                    .filter { it["supports_password"]?.jsonPrimitive?.booleanOrNull == true }
                    .map { it["name"]!!.jsonPrimitive.content }
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun signIn(baseUrl: String, provider: String, username: String, password: String) = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val body = buildJsonObject {
            put("provider", provider); put("username", username); put("password", password); put("next", "/")
        }.toString().toRequestBody(json)
        Diag.i("auth", "Signing in to ${base.substringAfter("://")} with provider $provider")
        val req = Request.Builder().url("$base/auth/password-login").post(body).build()
        var at = ""; var rt = ""
        api.http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) Diag.w("auth", "Sign-in failed: HTTP ${r.code} ${Api.errorDetail(txt) ?: ""}")
            if (!r.isSuccessful) throw java.io.IOException(
                when (r.code) {
                    401 -> "Wrong username or password."
                    404 -> "This server has no username/password sign-in enabled."
                    429 -> "Too many attempts. Wait a minute and try again."
                    else -> Api.errorDetail(txt) ?: "Sign-in failed (${r.code})"
                })
            for (h in r.headers("Set-Cookie")) {
                val pair = h.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim().removeSurrounding("\"")
                if (value.isEmpty()) continue
                when {
                    name.endsWith("hermes_session_at") -> at = value
                    name.endsWith("hermes_session_rt") -> rt = value
                }
            }
        }
        if (at.isEmpty()) throw java.io.IOException("Signed in, but the server returned no session token. Is a proxy stripping Set-Cookie headers?")
        // Confirm the token works as a bearer and learn its expiry.
        val meReq = Request.Builder().url("$base/api/auth/me").header("Authorization", "Bearer $at").get().build()
        val me = api.http.newCall(meReq).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw java.io.IOException("Server rejected the session (${r.code}): ${Api.errorDetail(txt) ?: txt.take(120)}")
            Jsonx.parseToJsonElement(txt).jsonObject
        }
        Diag.i("auth", "Signed in as ${me["user_id"]?.jsonPrimitive?.contentOrNull ?: username} via ${me["provider"]?.jsonPrimitive?.contentOrNull ?: provider}; token expires_at=${me["expires_at"]}; refresh token ${if (rt.isNotBlank()) "present" else "MISSING"}")
        store.update {
            it.copy(
                baseUrl = base, accessToken = at, refreshToken = rt,
                expiresAt = me["expires_at"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                provider = me["provider"]?.jsonPrimitive?.contentOrNull ?: provider,
                userId = me["user_id"]?.jsonPrimitive?.contentOrNull ?: username,
                authRequired = true,
            )
        }
    }
}
