package dev.hark.hermes.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own diagnostic log: connection, auth and request events plus crashes, kept on the phone so a user
 * can send it with a problem report. Tokens, passwords and cookies are scrubbed before anything is written.
 */
object Diag {
    enum class Level(val tag: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }
    data class Line(val at: Long, val level: Level, val area: String, val msg: String) {
        fun format(): String = "${ts.get()!!.format(Date(at))} ${level.tag}/$area: $msg"
    }

    private val ts = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }
    private const val MAX_LINES = 3000
    private const val MAX_FILE = 768L * 1024
    private val ring = ArrayDeque<Line>(MAX_LINES)
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines
    private var dir: File? = null
    private var publishPending = false

    /** Verbose: also records every REST call, not just failures. Off by default. */
    @Volatile var verbose = false

    fun init(ctx: Context, verbose: Boolean) {
        this.verbose = verbose
        val d = File(ctx.filesDir, "diag").apply { mkdirs() }
        dir = d
        // carry the last session's tail over so a report after a restart still has the lead-up
        runCatching {
            val f = File(d, "talaria.log")
            if (f.exists()) f.readLines().takeLast(400).forEach { ring.addLast(Line(0, Level.DEBUG, "prev", it)) }
        }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            runCatching {
                val trace = ex.stackTraceToString()
                log(Level.ERROR, "crash", "Uncaught on ${t.name}: ${ex.javaClass.name}: ${ex.message}")
                File(d, "last-crash.txt").writeText("${ts.get()!!.format(Date())} on ${t.name}\n${scrub(trace)}")
            }
            prev?.uncaughtException(t, ex)
        }
        i("app", "Started ${appVersion(ctx)} on Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})")
    }

    fun d(area: String, msg: String) { if (verbose) log(Level.DEBUG, area, msg) }
    fun i(area: String, msg: String) = log(Level.INFO, area, msg)
    fun w(area: String, msg: String) = log(Level.WARN, area, msg)
    fun e(area: String, msg: String, t: Throwable? = null) =
        log(Level.ERROR, area, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}")

    @Synchronized
    fun log(level: Level, area: String, raw: String) {
        val line = Line(System.currentTimeMillis(), level, area, scrub(raw))
        if (ring.size >= MAX_LINES) ring.removeFirst()
        ring.addLast(line)
        android.util.Log.println(when (level) { Level.DEBUG -> 3; Level.INFO -> 4; Level.WARN -> 5; Level.ERROR -> 6 }, "Talaria/$area", line.msg)
        runCatching {
            val f = File(dir ?: return@runCatching, "talaria.log")
            if (f.length() > MAX_FILE) { File(f.parentFile, "talaria.1.log").delete(); f.renameTo(File(f.parentFile, "talaria.1.log")) }
            f.appendText(line.format() + "\n")
        }
        _lines.value = ring.toList()
    }

    @Synchronized fun clear() {
        ring.clear(); _lines.value = emptyList()
        dir?.let { File(it, "talaria.log").delete(); File(it, "talaria.1.log").delete() }
        i("app", "Log cleared")
    }

    /** The crash from the last run, once; null when the app closed normally. */
    fun takeLastCrash(): String? {
        val f = File(dir ?: return null, "last-crash.txt")
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }

    private val secrets = listOf(
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{6,}") to "$1•••",
        Regex("(?i)([?&](?:token|ticket|internal|code|code_verifier|access_token|refresh_token)=)[^&\\s\"']+") to "$1•••",
        Regex("(?i)(\"(?:access_token|refresh_token|password|token|ticket|code_verifier|secret|api_key)\"\\s*:\\s*\")[^\"]*\"") to "$1•••\"",
        Regex("(?i)(hermes_session_(?:at|rt)=)[^;\\s]+") to "$1•••",
        Regex("(?i)\\b(sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9]{12,}|xox[abp]-[A-Za-z0-9-]{10,})") to "•••",
    )
    fun scrub(s: String): String = secrets.fold(s) { acc, (re, rep) -> re.replace(acc, rep) }

    fun appVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "Talaria ${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
    }.getOrDefault("Talaria")

    /** Header for a report: app, device, network, server (host only) and connection state. No tokens. */
    fun environment(ctx: Context, store: Store, extra: Map<String, String> = emptyMap()): String = buildString {
        val a = store.auth.value
        appendLine(appVersion(ctx))
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Network: ${networkSummary(ctx)}")
        appendLine("Server: ${a.host.ifBlank { "none" }} · auth ${if (a.authRequired) a.provider.ifBlank { "?" } else "off"} · refresh token ${if (a.refreshToken.isNotBlank()) "yes" else "no"}")
        if (a.expiresAt > 0) {
            val left = (a.expiresAt - System.currentTimeMillis() / 1000.0).toLong()
            appendLine("Access token ${if (left > 0) "expires in ${left / 60} min" else "expired ${-left / 60} min ago"}")
        }
        appendLine("Saved servers: ${store.servers.value.size}")
        extra.forEach { (k, v) -> appendLine("$k: $v") }
    }

    fun networkSummary(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        val kinds = buildList {
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) add("mobile")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) add("VPN (Tailscale or other)")
        }
        // a VPN like Tailscale is the active network itself; the underlying link is on another network
        val under = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter { !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) }
        val base = when {
            under.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) } -> "Wi-Fi"
            under.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) } -> "mobile"
            else -> null
        }
        (kinds.joinToString(" + ").ifBlank { "unknown" }) + (if (base != null && base !in kinds) " over $base" else "")
    }.getOrDefault("unknown")

    /** The log as text, newest last, capped so an email body or attachment stays reasonable. */
    fun dump(maxLines: Int = MAX_LINES): String = synchronized(this) { ring.toList() }.takeLast(maxLines).joinToString("\n") { if (it.at == 0L) it.msg else it.format() }

    /** Writes the report (environment + description + log) to a shareable file. */
    fun writeReportFile(ctx: Context, body: String): File {
        val d = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val f = File(d, "talaria-report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
        f.writeText(body)
        return f
    }
}

/** Where problem reports go. Email always works; the direct endpoint is used when the build sets one. */
object Reports {
    /** Your support inbox. Change it in app/build.gradle.kts (REPORT_EMAIL). */
    val email: String get() = dev.hark.hermes.BuildConfig.REPORT_EMAIL
    /** Optional HTTPS form endpoint (Formspree, Web3Forms, your own) that forwards reports to your inbox. */
    val endpoint: String get() = dev.hark.hermes.BuildConfig.REPORT_URL
    val repo: String get() = dev.hark.hermes.BuildConfig.REPORT_REPO

    data class Draft(val kind: String, val summary: String, val details: String, val contact: String, val includeLog: Boolean, val crash: String? = null)

    fun compose(ctx: Context, store: Store, d: Draft): String = buildString {
        appendLine("Talaria problem report · ${d.kind}")
        appendLine("=".repeat(40))
        appendLine(d.summary.trim())
        if (d.details.isNotBlank()) { appendLine(); appendLine(d.details.trim()) }
        if (d.contact.isNotBlank()) { appendLine(); appendLine("Reply to: ${d.contact.trim()}") }
        appendLine(); appendLine("── Environment ──")
        append(Diag.environment(ctx, store))
        d.crash?.let { appendLine(); appendLine("── Crash ──"); appendLine(it) }
        if (d.includeLog) { appendLine(); appendLine("── App log ──"); appendLine(Diag.dump()) }
    }

    /** Posts to the configured endpoint. JSON fields work with Formspree and Web3Forms (access key in the URL's ?access_key=). */
    suspend fun sendDirect(api: Api, ctx: Context, store: Store, d: Draft): Unit = withContext(Dispatchers.IO) {
        val url = endpoint
        require(url.startsWith("https://")) { "No report endpoint is set in this build" }
        val key = Regex("[?&]access_key=([^&]+)").find(url)?.groupValues?.get(1)
        val body = buildJsonObject {
            key?.let { put("access_key", it) }
            put("subject", "Talaria: ${d.kind} · ${d.summary.take(80)}")
            put("from_name", "Talaria app")
            if (d.contact.contains('@')) { put("email", d.contact.trim()); put("replyto", d.contact.trim()) }
            put("message", compose(ctx, store, d))
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url.substringBefore("?access_key=").substringBefore("&access_key="))
            .header("Accept", "application/json").post(body).build()
        api.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Report service answered ${r.code}")
        }
        Diag.i("report", "Sent report directly (${d.kind})")
    }
}
