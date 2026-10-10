package dev.hark.hermes.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A crash from the last run, handed from the launch prompt to the report form. */
object CrashBus { var pending: String? = null }

private val kinds = listOf("connection" to "Connection", "bug" to "Something broke", "crash" to "Crash", "idea" to "Idea")

/**
 * Report a problem: a short form that bundles the app's diagnostic log, the phone and network, and (optionally)
 * Hermes' own error log and a screenshot, then sends it to the developer's inbox: directly when the build has a
 * report endpoint, otherwise through the phone's email app. A GitHub issue is offered too.
 */
@Composable
fun ReportScreen(nav: NavHostController, preset: String = "") {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val crash = remember { CrashBus.pending.also { CrashBus.pending = null } }
    val g by app.activeGateway.collectAsState()
    val connErr by g.connError.collectAsState()
    var kind by rememberSaveable { mutableStateOf(preset.ifBlank { if (crash != null) "crash" else if (connErr != null) "connection" else "bug" }) }
    var summary by rememberSaveable { mutableStateOf(when { crash != null -> "Talaria closed unexpectedly"; preset == "connection" && connErr != null -> "Chat won't connect: $connErr"; else -> "" }) }
    var details by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf(app.store.reportContact.value) }
    var includeLog by rememberSaveable { mutableStateOf(true) }
    var includeServer by rememberSaveable { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { shot = it }

    suspend fun draft(): Reports.Draft {
        var extra = details
        if (includeServer) {
            val errs = runCatching { app.api.obj("/api/logs?file=errors&lines=80", profile = false).a("lines").strs() }.getOrNull()
            if (!errs.isNullOrEmpty()) extra += "\n\n── Hermes error log (last ${errs.size}) ──\n" + Diag.scrub(errs.joinToString("\n"))
        }
        app.store.set(app.store.reportContact, "report_contact", contact.trim())
        return Reports.Draft(kinds.first { it.first == kind }.second, summary.ifBlank { "(no summary)" }, extra, contact, includeLog, crash)
    }

    fun viaEmail() = scope.launch {
        busy = true
        try {
            val d = draft()
            val full = Reports.compose(ctx, app.store, d)
            val file = withContext(Dispatchers.IO) { Diag.writeReportFile(ctx, full) }
            val auth = ctx.packageName + ".files"
            val uris = arrayListOf(androidx.core.content.FileProvider.getUriForFile(ctx, auth, file))
            shot?.let { s -> withContext(Dispatchers.IO) { copyToShared(ctx, s) }?.let { uris += androidx.core.content.FileProvider.getUriForFile(ctx, auth, it) } }
            val short = Reports.compose(ctx, app.store, d.copy(includeLog = false)) + if (d.includeLog) "\n(Full app log attached.)" else ""
            val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(Reports.email))
                putExtra(Intent.EXTRA_SUBJECT, "Talaria: ${d.kind} · ${d.summary.take(80)}")
                putExtra(Intent.EXTRA_TEXT, short)
                if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) else putExtra(Intent.EXTRA_STREAM, uris[0])
                clipData = android.content.ClipData.newRawUri("report", uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Diag.i("report", "Opening email app for a ${d.kind} report")
            ctx.startActivity(Intent.createChooser(send, "Send report with…").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast(errText(e)) }
        busy = false
    }

    fun direct() = scope.launch {
        busy = true
        try { Reports.sendDirect(app.api, ctx, app.store, draft()); toast("Report sent. Thank you!"); nav.popBackStack() }
        catch (e: Exception) { Diag.e("report", "Direct send failed", e); toast("Couldn't send directly (${errText(e)}). Try email.") }
        busy = false
    }

    fun viaGithub() = scope.launch {
        val d = draft()
        val body = buildString {
            appendLine(d.summary); if (d.details.isNotBlank()) { appendLine(); appendLine(d.details.take(2500)) }
            appendLine(); appendLine("**Environment**"); appendLine("```"); append(Diag.environment(ctx, app.store)); appendLine("```")
            if (d.includeLog) { appendLine("<details><summary>App log (last 60 lines)</summary>\n\n```"); appendLine(Diag.dump(60)); appendLine("```\n</details>") }
            d.crash?.let { appendLine("<details><summary>Crash</summary>\n\n```"); appendLine(it.take(3000)); appendLine("```\n</details>") }
        }.take(7000)
        val url = "https://github.com/${Reports.repo}/issues/new?title=" + Uri.encode("[${d.kind}] ${d.summary.take(100)}") + "&body=" + Uri.encode(body)
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("No browser to open GitHub") }
    }

    Page("Report a problem", subtitle = "Goes straight to the developer, with what's needed to fix it", onBack = { nav.popBackStack() }) {
        item { ChipRow(kinds, kind) { kind = it } }
        item {
            val colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = p.card, focusedContainerColor = p.card, unfocusedBorderColor = p.line, focusedBorderColor = p.ink.copy(alpha = 0.5f), focusedLabelColor = p.ink, cursorColor = p.ink)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(summary, { summary = it }, label = { Text("What happened?") }, singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(), colors = colors,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                OutlinedTextField(details, { details = it }, label = { Text("Steps, what you expected (optional)") }, minLines = 4, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(), colors = colors,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                OutlinedTextField(contact, { contact = it }, label = { Text("Your email or Telegram, for a reply (optional)") }, singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(), colors = colors)
            }
        }
        if (crash != null) item {
            HCard(padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.BugReport, null, tint = p.bad); Spacer(Modifier.width(8.dp)); Text("The crash details are included", color = p.ink, style = MaterialTheme.typography.titleSmall) }
                Text(crash.lineSequence().drop(1).take(3).joinToString("\n"), color = p.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            HCard(padding = 8.dp) {
                ListRow("Include app log", "Connection, sign-in and request events. Tokens and passwords are removed.", Icons.Outlined.Article, trailing = { Toggle(includeLog) { includeLog = it } }, onClick = { includeLog = !includeLog })
                ListRow("Include Hermes error log", "Last 80 lines from your server's errors.log", Icons.Outlined.Dns, trailing = { Toggle(includeServer) { includeServer = it } }, onClick = { includeServer = !includeServer })
                ListRow(if (shot == null) "Add a screenshot" else "Screenshot attached", if (shot == null) "Email only" else "Tap to remove", Icons.Outlined.Image,
                    onClick = { if (shot == null) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) else shot = null })
                ListRow("Preview what's sent", null, Icons.Outlined.Visibility, onClick = { preview = true })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val canSend = summary.isNotBlank() && !busy
                if (Reports.endpoint.startsWith("https://")) {
                    Button({ direct() }, enabled = canSend, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.accentInk)) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = p.accentInk) else { Icon(Icons.Outlined.Send, null); Spacer(Modifier.width(8.dp)); Text("Send report") }
                    }
                }
                val emailPrimary = !Reports.endpoint.startsWith("https://")
                if (emailPrimary) Button({ viaEmail() }, enabled = canSend, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.accentInk)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = p.accentInk) else { Icon(Icons.Outlined.Email, null); Spacer(Modifier.width(8.dp)); Text("Send by email") }
                } else OutlinedButton({ viaEmail() }, enabled = canSend, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Outlined.Email, null, tint = p.ink); Spacer(Modifier.width(8.dp)); Text("Send by email instead", color = p.ink)
                }
                OutlinedButton({ viaGithub() }, enabled = canSend, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Outlined.Code, null, tint = p.ink); Spacer(Modifier.width(8.dp)); Text("Open a GitHub issue", color = p.ink)
                }
                Text("Reports go to ${Reports.email}. Nothing is sent until you tap a button.", color = p.faint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }

    if (preview) {
        var text by remember { mutableStateOf("Building…") }
        LaunchedEffect(Unit) { text = Reports.compose(ctx, app.store, draft()) }
        AlertDialog({ preview = false }, containerColor = p.sheet, shape = RoundedCornerShape(26.dp),
            title = { Text("What's sent") },
            text = {
                Box(Modifier.heightIn(max = 460.dp).verticalScrollCompat()) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp, color = p.ink)
                }
            },
            confirmButton = { TextButton({ preview = false }) { Text("Done") } })
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

private fun copyToShared(ctx: android.content.Context, uri: Uri): File? = runCatching {
    val d = File(ctx.cacheDir, "shared").apply { mkdirs() }
    val f = File(d, "talaria-screenshot-${System.currentTimeMillis()}.jpg")
    ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: return null
    f
}.getOrNull()

/** The app's own log: what the phone saw while talking to Hermes. Filter, search, copy, share, or report from it. */
@Composable
fun AppLogScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val all by Diag.lines.collectAsState()
    var level by rememberSaveable { mutableStateOf("all") }
    var q by rememberSaveable { mutableStateOf("") }
    val verbose by app.store.verboseLog.collectAsState()
    var clear by remember { mutableStateOf(false) }
    val shown = remember(all, level, q) {
        all.asReversed().filter { l ->
            (level == "all" || (level == "problems" && l.level >= Diag.Level.WARN) || l.area == level) &&
                (q.isBlank() || l.msg.contains(q, true) || l.area.contains(q, true))
        }.take(1500)
    }
    Page("App diagnostics", subtitle = "What this phone saw while talking to Hermes", onBack = { nav.popBackStack() }, actions = {
        IconButton({ clip.setText(AnnotatedString(Diag.environment(ctx, app.store) + "\n" + Diag.dump())); scope.toast("Log copied") }) { Icon(Icons.Outlined.ContentCopy, "Copy", tint = p.ink) }
        IconButton({
            runCatching {
                val f = Diag.writeReportFile(ctx, Diag.environment(ctx, app.store) + "\n" + Diag.dump())
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share log"))
            }.onFailure { scope.toast(errText(it)) }
        }) { Icon(Icons.Outlined.Share, "Share", tint = p.ink) }
        IconButton({ clear = true }) { Icon(Icons.Outlined.DeleteSweep, "Clear", tint = p.ink) }
    }) {
        item {
            HCard(padding = 8.dp) {
                ListRow("Report a problem", "Send this log to the developer", Icons.Outlined.BugReport, p.accent, onClick = { nav.go("report") })
                ListRow("Detailed logging", "Also record every request, not only failures", Icons.Outlined.Tune, trailing = { Toggle(verbose) { app.store.set(app.store.verboseLog, "verbose_log", it); Diag.verbose = it } }, onClick = { val v = !verbose; app.store.set(app.store.verboseLog, "verbose_log", v); Diag.verbose = v })
            }
        }
        item {
            HCard(padding = 14.dp) {
                Text(Diag.environment(ctx, app.store).trim(), color = p.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        item { ChipRow(listOf("all" to "All", "problems" to "Problems", "ws" to "Chat link", "auth" to "Sign-in", "http" to "Requests", "net" to "Network", "crash" to "Crashes"), level) { level = it } }
        item {
            OutlinedTextField(q, { q = it }, placeholder = { Text("Search the log", color = p.faint) }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null, tint = p.muted) },
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = p.card, focusedContainerColor = p.card, unfocusedBorderColor = p.line, focusedBorderColor = p.ink.copy(alpha = 0.5f), cursorColor = p.ink))
        }
        if (shown.isEmpty()) item { EmptyCard(Icons.Outlined.Article, "Nothing here", "No log lines match.") }
        else items(shown.size, key = { it }) { i ->
            val l = shown[i]
            val c = when (l.level) { Diag.Level.ERROR -> p.bad; Diag.Level.WARN -> p.warn; Diag.Level.DEBUG -> p.faint; else -> p.ink }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { clip.setText(AnnotatedString(l.format())); scope.toast("Line copied") }.padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text(if (l.at == 0L) "prev" else l.format().substring(6, 14), color = p.faint, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.width(58.dp))
                Text("${l.area}  ${l.msg}", color = c, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
    if (clear) ConfirmDialog("Clear the log?", "The app's diagnostic log on this phone is erased.", "Clear", danger = true, { clear = false }) { Diag.clear(); clear = false }
}
