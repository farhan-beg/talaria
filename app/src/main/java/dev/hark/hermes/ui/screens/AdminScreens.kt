package dev.hark.hermes.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.serialization.json.JsonObject

// ── API keys ──────────────────────────────────────────────────────────────────
@Composable
fun KeysScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val profile by app.store.profile.collectAsState()
    val data = rememberLoad(profile) { app.api.obj("/api/env") }
    var q by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Pair<String, JsonObject>?>(null) }
    var del by remember { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf(false) }
    Page("API keys", subtitle = "Stored in the profile's .env", onBack = { nav.popBackStack() }, onRefresh = { data.reload() }, refreshing = data.loading && data.data != null,
        actions = { IconButton({ add = true }) { Icon(Icons.Outlined.Add, "Add", tint = p.ink) } }) {
        item { SearchField(q, { q = it }, "Search keys") }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Show advanced", color = p.muted, modifier = Modifier.weight(1f)); Toggle(advanced) { advanced = it } } }
        loadState(data) { d ->
            val entries = d.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
                .filter { (k, v) -> (advanced || !v.b("advanced") || v.b("is_set")) && (q.isBlank() || k.contains(q, true) || v.s("description").contains(q, true)) }
            entries.groupBy { it.second.s("category").ifBlank { "Other" } }.toSortedMap().forEach { (cat, list) ->
                item(cat) { SectionLabel(cat) }
                item("$cat-list") {
                    HCard(padding = 8.dp) {
                        list.sortedByDescending { it.second.b("is_set") }.forEach { (k, v) ->
                            ListRow(k, if (v.b("is_set")) (v.sn("redacted_value") ?: "set") else v.s("description"),
                                if (v.b("is_set")) Icons.Outlined.Key else Icons.Outlined.KeyOff, if (v.b("is_set")) p.good else p.faint,
                                trailing = { if (v.b("is_set")) IconButton({ del = k }) { Icon(Icons.Outlined.Close, null, tint = p.muted) } },
                                onClick = { edit = k to v })
                        }
                    }
                }
            }
        }
    }
    if (edit != null || add) {
        val (k0, v) = edit ?: ("" to null)
        var key by remember { mutableStateOf(k0) }
        var value by remember { mutableStateOf("") }
        FormSheet(if (add) "Add variable" else k0, { edit = null; add = false }, onConfirm = {
            edit = null; add = false
            scope.act("Saved", after = { data.reload() }) { app.api.put("/api/env", jsonOf("key" to key, "value" to value)) }
        }) {
            if (v != null && v.s("description").isNotBlank()) Text(v.s("description"), color = p.muted, style = MaterialTheme.typography.bodySmall)
            if (add) Field("Name", key, { key = it.uppercase().replace(' ', '_') }, mono = true)
            Field("Value", value, { value = it }, secret = v?.b("is_password") ?: true)
            v?.sn("url")?.let { Text("Get one at $it", color = p.accent, style = MaterialTheme.typography.bodySmall) }
        }
    }
    del?.let { k -> ConfirmDialog("Remove $k?", "It's deleted from .env. Running sessions keep the old value until restarted.", "Remove", true, { del = null }) {
        scope.act("Removed", after = { data.reload() }) { app.api.delete("/api/env", jsonOf("key" to k)) }
    } }
}

// ── Config ────────────────────────────────────────────────────────────────────
@Composable
fun ConfigScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val profile by app.store.profile.collectAsState()
    val raw = rememberLoad(profile) { app.api.obj("/api/config/raw") }
    var text by remember(raw.data) { mutableStateOf(raw.data?.s("yaml") ?: "") }
    val dirty = raw.data != null && text != raw.data?.s("yaml")
    Page("Config", subtitle = raw.data?.sn("path") ?: "config.yaml", onBack = { nav.popBackStack() },
        actions = {
            TextButton({ scope.act("Saved. Applies to new sessions.", after = { raw.reload() }) { app.api.put("/api/config/raw", jsonOf("yaml_text" to text)) } }, enabled = dirty) {
                Text("Save", color = if (dirty) p.accent else p.faint)
            }
        }) {
        loadState(raw) {
            item {
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 500.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = Mono, fontSize = androidx.compose.ui.unit.TextUnit(12.5f, androidx.compose.ui.unit.TextUnitType.Sp), color = p.ink),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = p.card, focusedContainerColor = p.card, unfocusedBorderColor = p.line))
            }
            item { Text("Changes take effect on the next session or gateway restart.", color = p.muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ── System ────────────────────────────────────────────────────────────────────
@Composable
fun SystemScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val stats = rememberLoad(poll = 10000) { app.api.obj("/api/system/stats", profile = false) }
    val status = rememberLoad(poll = 10000) { app.api.obj("/api/status", profile = false) }
    val update = rememberLoad { runCatching { app.api.obj("/api/hermes/update/check", profile = false) }.getOrNull() }
    val portal = rememberLoad { runCatching { app.api.obj("/api/portal", profile = false) }.getOrNull() }
    val curator = rememberLoad { runCatching { app.api.obj("/api/curator", profile = false) }.getOrNull() }
    val pool = rememberLoad { runCatching { app.api.obj("/api/credentials/pool", profile = false).a("providers").objs() }.getOrDefault(emptyList()) }
    val ckpt = rememberLoad { runCatching { app.api.obj("/api/ops/checkpoints", profile = false) }.getOrNull() }
    var action by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }

    fun run(path: String, label: String, body: JsonObject? = null) = scope.act {
        val r = app.api.post(path, body, profile = false).objOrNull()
        if (r?.b("ok") == false && r.sn("error") != null) throw ApiException(400, r.s("error"))
        action = r.sn("name") ?: label
    }

    Page("System", onBack = { nav.popBackStack() }, onRefresh = { stats.reload(); status.reload(); update.reload() }, refreshing = stats.loading && stats.data != null) {
        loadState(stats) { s ->
            item {
                HCard {
                    Text(s.s("hostname"), style = MaterialTheme.typography.titleMedium, color = p.ink)
                    Text("${s.s("os")} ${s.s("os_release")} · ${s.s("arch")} · Python ${s.s("python_version")}", style = MaterialTheme.typography.bodySmall, color = p.muted)
                    Spacer(Modifier.height(8.dp))
                    if (s["cpu_percent"] != null) Meter("CPU", s.d("cpu_percent"), "${s.d("cpu_percent").toInt()}% · ${s.l("cpu_count")} cores")
                    s.o("memory")?.let { Meter("Memory", it.d("percent"), "${humanBytes(it.l("used"))} / ${humanBytes(it.l("total"))}") }
                    s.o("disk")?.let { Meter("Disk", it.d("percent"), "${humanBytes(it.l("used"))} / ${humanBytes(it.l("total"))}") }
                    if (s.l("uptime_seconds") > 0) KV("Uptime", "${s.l("uptime_seconds") / 86400}d ${(s.l("uptime_seconds") % 86400) / 3600}h")
                    s.a("load_avg").let { if (it.isNotEmpty()) KV("Load", it.joinToString("  ") { x -> String.format("%.2f", x.toString().toDoubleOrNull() ?: 0.0) }) }
                }
            }
        }
        update.data?.let { u ->
            item {
                HCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Hermes ${u.s("current_version")}", style = MaterialTheme.typography.titleSmall, color = p.ink)
                            Text(u.sn("message") ?: when { u.b("update_available") -> "${u.l("behind")} commits behind"; else -> "Up to date" }, style = MaterialTheme.typography.bodySmall, color = p.muted)
                        }
                        if (u.b("update_available") && u.b("can_apply")) SoftButton("Update", Icons.Outlined.SystemUpdate, primary = true) {
                            confirm = Triple("Update Hermes?", "Runs hermes update in the background (${u.l("behind")} commits).") { run("/api/hermes/update", "update") }
                        } else if (u.b("update_available")) Text(u.s("update_command"), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = p.ink)
                        else Pill("latest", p.good)
                    }
                }
            }
        }
        status.data?.let { st ->
            item { SectionLabel("Gateway") }
            item {
                HCard {
                    val running = st.b("gateway_running")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (running) p.good else p.bad, running); Spacer(Modifier.width(8.dp))
                        Text(if (running) "Running · PID ${st.s("gateway_pid")}" else "Stopped" + (st.sn("gateway_exit_reason")?.let { " · $it" } ?: ""), color = p.ink, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("Start", Icons.Outlined.PlayArrow, Modifier.weight(1f)) { run("/api/gateway/start", "gateway-start") }
                        SoftButton("Restart", Icons.Outlined.RestartAlt, Modifier.weight(1f)) { run("/api/gateway/restart", "gateway-restart") }
                        SoftButton("Stop", Icons.Outlined.Stop, Modifier.weight(1f), danger = true) { confirm = Triple("Stop the gateway?", "Messaging channels disconnect until you start it again.") { run("/api/gateway/stop", "gateway-stop") } }
                    }
                }
            }
        }
        item { SectionLabel("Operations") }
        item {
            HCard(padding = 8.dp) {
                ListRow("Doctor", "Diagnose your install", Icons.Outlined.MedicalServices, onClick = { run("/api/ops/doctor", "doctor") })
                ListRow("Security audit", "Scan for risky settings", Icons.Outlined.Security, onClick = { run("/api/ops/security-audit", "security-audit") })
                ListRow("Back up", "Create a backup archive on the host", Icons.Outlined.Backup, onClick = { run("/api/ops/backup", "backup", JsonObject(emptyMap())) })
                ListRow("Prompt size", "System-prompt size breakdown", Icons.Outlined.Straighten, onClick = { run("/api/ops/prompt-size", "prompt-size") })
                ListRow("Migrate config", "Clean up retired settings", Icons.Outlined.AutoFixHigh, onClick = { run("/api/ops/config-migrate", "config-migrate") })
                ListRow("Support dump", "Generate a debug bundle", Icons.Outlined.BugReport, onClick = { run("/api/ops/dump", "dump") })
            }
        }
        curator.data?.let { c ->
            item { SectionLabel("Skill curator") }
            item {
                HCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (c.b("paused")) "Paused" else if (c.b("enabled")) "Active" else "Disabled", style = MaterialTheme.typography.titleSmall, color = p.ink)
                            Text("Every ${c.s("interval_hours").ifBlank { "?" }}h · last run ${isoRel(c.sn("last_run_at"))}", style = MaterialTheme.typography.bodySmall, color = p.muted)
                        }
                        SoftButton(if (c.b("paused")) "Resume" else "Pause") { scope.act(after = { curator.reload() }) { app.api.put("/api/curator/paused", jsonOf("paused" to !c.b("paused")), profile = false) } }
                        Spacer(Modifier.width(8.dp))
                        SoftButton("Run") { scope.act("Curator started", after = { curator.reload() }) { app.api.post("/api/curator/run", profile = false) } }
                    }
                }
            }
        }
        portal.data?.let { pt ->
            item { SectionLabel("Nous Portal") }
            item {
                HCard {
                    KV("Logged in", if (pt.b("logged_in") || pt.b("authenticated")) "Yes" else "No")
                    pt.sn("provider")?.let { KV("Provider", it) }
                    pt.sn("inference_provider")?.let { KV("Inference", it) }
                    pt.a("features").objs().take(12).forEach { f -> KV(f.s("name").ifBlank { f.s("tool") }, f.s("route").ifBlank { f.s("via").ifBlank { if (f.b("enabled")) "portal" else "local" } }) }
                }
            }
        }
        val pools = pool.data.orEmpty()
        if (pools.isNotEmpty()) {
            item { SectionLabel("Credential pool") }
            item {
                HCard(padding = 8.dp) {
                    pools.forEach { pr -> pr.a("entries").objs().forEach { e ->
                        ListRow("${pr.s("provider")} · ${e.sn("label") ?: "#${e.s("index")}"}", "${e.s("token_preview")} · ${e.l("request_count")} requests · ${e.sn("last_status") ?: "unused"}", Icons.Outlined.VpnKey, trailing = {
                            IconButton({ confirm = Triple("Remove key?", "Removes this key from the ${pr.s("provider")} rotation.") { scope.act("Removed", after = { pool.reload() }) { app.api.delete("/api/credentials/pool/${Api.enc(pr.s("provider"))}/${e.l("index")}", profile = false) } } }) {
                                Icon(Icons.Outlined.DeleteOutline, null, tint = p.bad)
                            }
                        })
                    } }
                }
            }
        }
        ckpt.data?.let { c ->
            item { SectionLabel("Checkpoints") }
            item {
                HCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.a("sessions").size} sessions · ${humanBytes(c.l("total_bytes"))}", color = p.ink, modifier = Modifier.weight(1f))
                        SoftButton("Prune") { scope.act("Pruned", after = { ckpt.reload() }) { app.api.post("/api/ops/checkpoints/prune", JsonObject(emptyMap()), profile = false) } }
                    }
                }
            }
        }
    }
    action?.let { ActionLogSheet(it) { action = null; status.reload() } }
    confirm?.let { (t, b, f) -> ConfirmDialog(t, b, "Continue", false, { confirm = null }) { f() } }
}
