package dev.hark.hermes.ui.screens

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.serialization.json.JsonObject

// ── Channels ──────────────────────────────────────────────────────────────────
@Composable
fun ChannelsScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val profile by app.store.profile.collectAsState()
    val data = rememberLoad(profile) { app.api.obj("/api/messaging/platforms") }
    var cfg by remember { mutableStateOf<JsonObject?>(null) }
    Page("Channels", subtitle = "Where Hermes talks to you", onBack = { nav.popBackStack() }, onRefresh = { data.reload() }, refreshing = data.loading && data.data != null,
        actions = { TextButton({ scope.act("Restarting gateway…", after = { data.reload() }) { app.api.post("/api/gateway/restart", profile = false) } }) { Text("Restart gateway", color = p.accent) } }) {
        loadState(data) { d ->
            val list = d.a("platforms").objs().sortedWith(compareByDescending<JsonObject> { it.b("enabled") }.thenByDescending { it.b("configured") })
            items(list, key = { it.s("id") }) { pl ->
                var on by remember(pl.s("id"), pl.b("enabled")) { mutableStateOf(pl.b("enabled")) }
                val st = pl.s("state")
                HCard(onClick = { cfg = pl }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(when { !pl.b("enabled") -> p.faint; st == "connected" -> p.good; st in listOf("error", "fatal") -> p.bad; else -> p.warn })
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pl.s("name"), style = MaterialTheme.typography.titleSmall, color = p.ink)
                            Text(pl.sn("error_message") ?: (if (!pl.b("configured")) "Not configured" else st.ifBlank { if (pl.b("enabled")) "Enabled" else "Disabled" }),
                                style = MaterialTheme.typography.bodySmall, color = if (pl.sn("error_message") != null) p.bad else p.muted, maxLines = 1)
                        }
                        Toggle(on) { v -> on = v; scope.act(if (v) "Enabled. Restart the gateway to connect." else "Disabled") { app.api.put("/api/messaging/platforms/${Api.enc(pl.s("id"))}", jsonOf("enabled" to v)) } }
                    }
                }
            }
        }
    }
    cfg?.let { pl ->
        val vals = remember(pl) { mutableStateMapOf<String, String>() }
        var advanced by remember { mutableStateOf(false) }
        FormSheet(pl.s("name"), { cfg = null }, onConfirm = {
            cfg = null
            val env = vals.filterValues { it.isNotBlank() }
            scope.act("Saved. Restart the gateway to apply.", after = { data.reload() }) { app.api.put("/api/messaging/platforms/${Api.enc(pl.s("id"))}", jsonOf("env" to env)) }
        }) {
            if (pl.s("description").isNotBlank()) Text(pl.s("description"), color = p.muted, style = MaterialTheme.typography.bodySmall)
            val vars = pl.a("env_vars").objs()
            vars.filter { advanced || !it.b("advanced") }.forEach { v ->
                val k = v.s("key")
                Field(
                    (v.sn("prompt") ?: k) + if (v.b("required")) " *" else "",
                    vals[k] ?: "", { vals[k] = it }, secret = v.b("is_password"),
                )
                Text(if (v.b("is_set")) "Set: ${v.sn("redacted_value") ?: "••••"} — leave blank to keep" else v.s("help").ifBlank { v.s("description") },
                    style = MaterialTheme.typography.bodySmall, color = p.faint)
            }
            if (vars.any { it.b("advanced") }) TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Show advanced", color = p.accent) }
            SoftButton("Test connection", Icons.Outlined.NetworkCheck) {
                scope.act { val r = app.api.post("/api/messaging/platforms/${Api.enc(pl.s("id"))}/test").objOrNull(); toastLater2(r.sn("message") ?: r.sn("state") ?: if (r.b("connected")) "Connected" else "Not connected") }
            }
        }
    }
}

private fun toastLater2(m: String) { kotlinx.coroutines.MainScope().toast(m) }

// ── Pairing ───────────────────────────────────────────────────────────────────
@Composable
fun PairingScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val data = rememberLoad(poll = 10000) { app.api.obj("/api/pairing", profile = false) }
    Page("Pairing", subtitle = "Approve who can message your agent", onBack = { nav.popBackStack() }, onRefresh = { data.reload() }, refreshing = data.loading && data.data != null) {
        loadState(data) { d ->
            val pending = d.a("pending").objs(); val approved = d.a("approved").objs()
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Pending (${pending.size})", Modifier.weight(1f))
                    if (pending.isNotEmpty()) TextButton({ scope.act("Cleared", after = { data.reload() }) { app.api.post("/api/pairing/clear-pending", profile = false) } }) { Text("Clear all", color = p.bad) }
                }
            }
            if (pending.isEmpty()) item { EmptyCard(Icons.Outlined.HowToReg, "No pending requests", "New users who message your bot will show up here.") }
            items(pending) { u ->
                HCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(u.sn("user_name") ?: u.s("user_id"), style = MaterialTheme.typography.titleSmall, color = p.ink)
                            Text("${u.s("platform")} · code ${u.s("code").ifBlank { u.s("request_id") }} · ${u.l("age_minutes")}m ago", style = MaterialTheme.typography.bodySmall, color = p.muted)
                        }
                        SoftButton("Approve", primary = true) {
                            scope.act("Approved", after = { data.reload() }) { app.api.post("/api/pairing/approve", jsonOf("platform" to u.s("platform"), "code" to u.s("code").ifBlank { u.s("request_id") }), profile = false) }
                        }
                    }
                }
            }
            item { SectionLabel("Approved (${approved.size})") }
            if (approved.isNotEmpty()) item {
                HCard(padding = 8.dp) {
                    approved.forEach { u ->
                        ListRow(u.sn("user_name") ?: u.s("user_id"), "${u.s("platform")} · ${u.s("user_id")}", sourceIcon(u.s("platform")), trailing = {
                            TextButton({ scope.act("Revoked", after = { data.reload() }) { app.api.post("/api/pairing/revoke", jsonOf("platform" to u.s("platform"), "user_id" to u.s("user_id")), profile = false) } }) { Text("Revoke", color = p.bad) }
                        })
                    }
                }
            }
        }
    }
}

// ── MCP ───────────────────────────────────────────────────────────────────────
@Composable
fun McpScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val profile by app.store.profile.collectAsState()
    val data = rememberLoad(profile) { app.api.obj("/api/mcp/servers").a("servers").objs() }
    val catalog = rememberLoad(profile) { runCatching { app.api.get("/api/mcp/catalog").let { it.objOrNull()?.a("entries")?.takeIf { a -> a.isNotEmpty() } ?: it.objOrNull()?.a("catalog") ?: it.arrOrEmpty() }.objs() }.getOrDefault(emptyList()) }
    var add by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<String?>(null) }
    Page("MCP servers", onBack = { nav.popBackStack() }, onRefresh = { data.reload() }, refreshing = data.loading && data.data != null,
        fab = { ExtendedFloatingActionButton(onClick = { add = true }, containerColor = p.accent, contentColor = p.accentInk, shape = RoundedCornerShape(20.dp), icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add") }) }) {
        loadState(data) { list ->
            if (list.isEmpty()) item { EmptyCard(Icons.Outlined.Cable, "No MCP servers", "Add one, or install from the catalog.") }
            items(list, key = { it.s("name") }) { s ->
                var on by remember(s.s("name"), s.b("enabled")) { mutableStateOf(s.b("enabled")) }
                HCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.s("name"), style = MaterialTheme.typography.titleSmall, color = p.ink)
                            Text(s.sn("url") ?: (s.s("command") + " " + s.a("args").strs().joinToString(" ")), style = MaterialTheme.typography.bodySmall, color = p.muted, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Pill(s.s("transport"), p.muted); Spacer(Modifier.width(8.dp))
                        Toggle(on) { v -> on = v; scope.act("Takes effect after a gateway restart") { app.api.put("/api/mcp/servers/${Api.enc(s.s("name"))}/enabled", jsonOf("enabled" to v)) } }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("Test", Icons.Outlined.NetworkCheck) {
                            scope.act {
                                val r = app.api.post("/api/mcp/servers/${Api.enc(s.s("name"))}/test").objOrNull()
                                toastLater2(if (r.b("ok")) "OK · ${r.a("tools").size} tools" else (r.sn("error") ?: "Test failed"))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton({ del = s.s("name") }) { Icon(Icons.Outlined.DeleteOutline, null, tint = p.bad) }
                    }
                }
            }
        }
        val cat = catalog.data.orEmpty()
        if (cat.isNotEmpty()) {
            item { SectionLabel("Catalog") }
            item {
                HCard(padding = 8.dp) {
                    cat.forEach { e ->
                        ListRow(e.sn("name") ?: e.s("id"), e.s("description"), Icons.Outlined.Inventory2, trailing = {
                            TextButton({ scope.act("Installed", after = { data.reload() }) { app.api.post("/api/mcp/catalog/install", jsonOf("name" to (e.sn("id") ?: e.s("name")))) } }) { Text("Install", color = p.accent) }
                        })
                    }
                }
            }
        }
    }
    if (add) {
        var name by remember { mutableStateOf("") }
        var http by remember { mutableStateOf(true) }
        var url by remember { mutableStateOf("") }
        var cmd by remember { mutableStateOf("") }
        var args by remember { mutableStateOf("") }
        var env by remember { mutableStateOf("") }
        var bearer by remember { mutableStateOf("") }
        FormSheet("Add MCP server", { add = false }, confirm = "Add", onConfirm = {
            add = false
            val body = if (http) jsonOf("name" to name, "url" to url, "bearer_token" to bearer.ifBlank { null })
            else jsonOf("name" to name, "command" to cmd, "args" to args.split(' ').filter { it.isNotBlank() },
                "env" to env.lines().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() })
            scope.act("Added", after = { data.reload() }) { app.api.post("/api/mcp/servers", body) }
        }) {
            Field("Name", name, { name = it })
            ChipRow(listOf("http" to "HTTP / SSE", "stdio" to "stdio"), if (http) "http" else "stdio") { http = it == "http" }
            if (http) { Field("URL", url, { url = it }); Field("Bearer token (optional)", bearer, { bearer = it }, secret = true) }
            else { Field("Command", cmd, { cmd = it }, mono = true); Field("Args", args, { args = it }, mono = true); Field("Env (KEY=VALUE per line)", env, { env = it }, singleLine = false, minLines = 2, mono = true) }
        }
    }
    del?.let { n -> ConfirmDialog("Remove $n?", "The server is deleted from config.yaml.", "Remove", true, { del = null }) {
        scope.act("Removed", after = { data.reload() }) { app.api.delete("/api/mcp/servers/${Api.enc(n)}") }
    } }
}

// ── Webhooks ──────────────────────────────────────────────────────────────────
@Composable
fun WebhooksScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val data = rememberLoad { app.api.obj("/api/webhooks", profile = false) }
    var add by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf<JsonObject?>(null) }
    var del by remember { mutableStateOf<String?>(null) }
    Page("Webhooks", onBack = { nav.popBackStack() }, onRefresh = { data.reload() }, refreshing = data.loading && data.data != null,
        fab = { ExtendedFloatingActionButton(onClick = { add = true }, containerColor = p.accent, contentColor = p.accentInk, shape = RoundedCornerShape(20.dp), icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New") }) }) {
        loadState(data) { d ->
            if (d["enabled"] != null && !d.b("enabled") || d["platform_enabled"] != null && !d.b("platform_enabled"))
                item { Text("The webhook platform is off. Enable it under Channels first.", color = p.warn, style = MaterialTheme.typography.bodyMedium) }
            val list = (d.a("routes").takeIf { it.isNotEmpty() } ?: d.a("subscriptions").takeIf { it.isNotEmpty() } ?: d.a("webhooks")).objs()
            if (list.isEmpty()) item { EmptyCard(Icons.Outlined.Webhook, "No webhooks", "Create one to let outside events trigger your agent.") }
            items(list, key = { it.s("name") }) { w ->
                var on by remember(w.s("name"), w.b("enabled")) { mutableStateOf(w.b("enabled")) }
                HCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.s("name"), style = MaterialTheme.typography.titleSmall, color = p.ink)
                            Text("${w.a("events").strs().joinToString(", ").ifBlank { "all events" }} → ${w.s("deliver").ifBlank { "local" }}", style = MaterialTheme.typography.bodySmall, color = p.muted)
                        }
                        Toggle(on) { v -> on = v; scope.act { app.api.put("/api/webhooks/${Api.enc(w.s("name"))}/enabled", jsonOf("enabled" to v), profile = false) } }
                    }
                    if (w.s("url").isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(w.s("url"), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = p.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton({ clip.setText(AnnotatedString(w.s("url"))) }) { Icon(Icons.Outlined.ContentCopy, null, tint = p.muted) }
                            IconButton({ del = w.s("name") }) { Icon(Icons.Outlined.DeleteOutline, null, tint = p.bad) }
                        }
                    }
                }
            }
        }
    }
    if (add) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var events by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        var deliver by remember { mutableStateOf("local") }
        FormSheet("New webhook", { add = false }, confirm = "Create", onConfirm = {
            add = false
            scope.act(after = { data.reload() }) {
                val r = app.api.post("/api/webhooks", jsonOf("name" to name, "description" to desc, "events" to events.split(',').map { it.trim() }.filter { it.isNotBlank() }, "prompt" to prompt, "deliver" to deliver), profile = false).objOrNull()
                secret = r
            }
        }) {
            Field("Name", name, { name = it.lowercase().replace(' ', '-') })
            Field("Description", desc, { desc = it })
            Field("Events (comma separated)", events, { events = it })
            Field("Agent prompt", prompt, { prompt = it }, singleLine = false, minLines = 3)
            Field("Deliver to", deliver, { deliver = it })
        }
    }
    secret?.let { r ->
        AlertDialog({ secret = null }, containerColor = p.sheet, shape = RoundedCornerShape(26.dp), title = { Text("Webhook created") },
            text = { Column { Text("Copy the secret now; it won't be shown again.", color = p.muted); Spacer(Modifier.height(8.dp)); CodeBlock("URL: ${r.s("url")}\nSecret: ${r.s("secret")}", 200.dp) } },
            confirmButton = { TextButton({ clip.setText(AnnotatedString(r.s("secret"))); secret = null }) { Text("Copy secret", color = p.accent) } })
    }
    del?.let { n -> ConfirmDialog("Delete $n?", "Incoming events for this route will be rejected.", "Delete", true, { del = null }) {
        scope.act("Deleted", after = { data.reload() }) { app.api.delete("/api/webhooks/${Api.enc(n)}", profile = false) }
    } }
}
