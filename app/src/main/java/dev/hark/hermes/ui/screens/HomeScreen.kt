package dev.hark.hermes.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

@Composable
fun HomeScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val profile0 by app.store.profile.collectAsState()
    val server by app.store.activeId.collectAsState()
    val auth by app.store.auth.collectAsState()
    // reload everything when you switch servers, not just profiles
    val profile = profile0 + "@" + server
    val status = rememberLoad(profile, poll = 8000) { app.api.obj("/api/status", profile = false) }
    val bgSessions by app.store.showBackground.collectAsState()
    val hideCli by app.store.hideCli.collectAsState()
    val changed = rememberSessionsChanged()
    val sessions = rememberLoad(profile, bgSessions, hideCli, changed) { app.api.obj(sessionsUrl(24, bgSessions)) }
    val visible = rememberSessionFilter()
    val model = rememberLoad(profile) { runCatching { app.api.obj("/api/model/info") }.getOrNull() }
    val sys = rememberLoad(server, poll = 15000) { runCatching { app.api.obj("/api/system/stats", profile = false) }.getOrNull() }
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greet = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

    Page(
        title = greet, subtitle = auth.label,
        refreshing = status.loading && status.data != null,
        onRefresh = { status.reload(); sessions.reload(); model.reload(); sys.reload() },
        actions = {
            ServerSwitcher(nav)
            IconButton({ nav.go("settings") }) { Icon(Icons.Outlined.AccountCircle, "Settings", tint = p.ink) }
        },
    ) {
        loadState(status) { st ->
            item {
                // hero
                val running = st.b("gateway_running")
                Column(Modifier.fillMaxWidth().clip(CardShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(p.heroA, p.heroB))).border(1.dp, p.line, CardShape).padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (running) androidx.compose.ui.graphics.Color.White else p.bad, pulse = running)
                        Spacer(Modifier.width(8.dp))
                        Text(if (running) "Gateway online" else "Gateway stopped", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Text("v${st.s("version")}", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(model.data?.s("model")?.ifBlank { null } ?: "Hermes Agent", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall)
                    Text(model.data?.s("provider")?.let { "via $it" } ?: "", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Color.White).clickable { nav.go("chat") }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoAwesome, null, tint = androidx.compose.ui.graphics.Color(0xFF0D1222), modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("New chat", color = androidx.compose.ui.graphics.Color(0xFF0D1222), style = MaterialTheme.typography.labelLarge)
                        }
                        Row(Modifier.clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f)).clickable {
                            scope.act(if (running) "Restarting gateway…" else "Starting gateway…", after = { status.reload() }) {
                                app.api.post(if (running) "/api/gateway/restart" else "/api/gateway/start", profile = false)
                            }
                        }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (running) Icons.Outlined.RestartAlt else Icons.Outlined.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (running) "Restart" else "Start", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            // resource banner
            val mem = st.o("memory"); val disk = st.o("disk")
            val warn = listOfNotNull(
                disk?.takeIf { it.s("pressure") in listOf("elevated", "critical") }?.let { "Disk is nearly full (${it.l("free_mb")} MB free)" },
                mem?.takeIf { it.b("last_boot_suspected_oom") }?.let { "Your agent restarted unexpectedly, most likely out of memory" },
                mem?.takeIf { it.s("pressure") in listOf("elevated", "critical") }?.let { "Your agent is almost out of memory" },
            ).firstOrNull()
            if (warn != null) item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.warn.copy(alpha = 0.14f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = p.warn); Spacer(Modifier.width(10.dp))
                    Text(warn, color = p.ink, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Active now", st.l("active_sessions").toString(), Modifier.weight(1f), "sessions, last 5 min", Icons.Outlined.Bolt)
                    val plats = st.o("gateway_platforms")
                    val connected = plats?.values?.count { (it as? JsonObject)?.s("state") == "connected" } ?: 0
                    StatTile("Channels", "$connected/${plats?.size ?: 0}", Modifier.weight(1f), "connected", Icons.Outlined.Hub)
                }
            }
            val plats = st.o("gateway_platforms")
            if (plats != null && plats.isNotEmpty()) item {
                HCard {
                    Text("Platforms", style = MaterialTheme.typography.titleSmall, color = p.ink)
                    Spacer(Modifier.height(6.dp))
                    plats.forEach { (name, v) ->
                        val o = v as? JsonObject
                        val state = o.s("state")
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dot(when (state) { "connected" -> p.good; "error", "fatal" -> p.bad; else -> p.warn })
                            Spacer(Modifier.width(10.dp))
                            Text(name.replaceFirstChar { it.uppercase() }, color = p.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(o.sn("error_message") ?: state, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        }
        sys.data?.let { s ->
            item {
                HCard(onClick = { nav.go("system") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Host", style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.weight(1f))
                        Text(s.s("hostname"), style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                    Spacer(Modifier.height(4.dp))
                    if (s.o("memory") != null || s["cpu_percent"] != null) {
                        if (s["cpu_percent"] != null) Meter("CPU", s.d("cpu_percent"), "${s.d("cpu_percent").toInt()}% of ${s.l("cpu_count")} cores")
                        s.o("memory")?.let { Meter("Memory", it.d("percent"), "${humanBytes(it.l("used"))} / ${humanBytes(it.l("total"))}") }
                        s.o("disk")?.let { Meter("Disk", it.d("percent"), "${humanBytes(it.l("free"))} free") }
                    } else Text("${s.s("os")} ${s.s("arch")} · ${s.l("cpu_count")} cores", color = p.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Recent sessions", Modifier.weight(1f))
                TextButton({ nav.go("sessions") }) { Text("See all", color = p.accent) }
            }
        }
        loadState(sessions) { d ->
            val list = d.a("sessions").objs().filter(visible).sortedByDescending { it.b("pinned") }.take(6)
            if (list.isEmpty()) item { EmptyCard(Icons.Outlined.ChatBubbleOutline, "No sessions yet", "Start a chat and it'll show up here.") }
            else item {
                HCard(padding = 8.dp) { list.forEach { SessionRow(it, onChanged = { sessions.reload() }) { nav.go("session/${it.s("id")}") } } }
            }
        }
    }
}

private val SUBAGENT_SOURCES = setOf("subagent", "kanban", "delegate", "delegation", "worker")
// api_server is Hermes' OpenAI-compatible endpoint: other programs (or Hermes calling itself), never this app
private val AUTOMATION_SOURCES = setOf("cron", "tool", "api", "api_server", "acp", "oneshot", "recovered")
/** Terminal sessions; Hermes also starts these itself with `hermes chat -q`, so they're hidden unless you opt in. */
private val CLI_SOURCES = setOf("cli")

/**
 * Where a session really came from. Hermes keeps `source` as live routing state, so opening a cron run or a
 * helper's session from a chat client flips it (to "tui", "desktop", ...); `created_source` is stamped once
 * at creation and never changes, so it decides.
 */
private fun JsonObject.origins(): Set<String> = setOfNotNull(sn("created_source")?.lowercase(), sn("source")?.lowercase()).filter { it.isNotBlank() }.toSet()
private val CRON_RUN_ID = Regex("^cron_.+_\\d{8}_\\d{6}$")

/** Sessions spawned by delegate_task / kanban workers rather than by you. */
fun JsonObject.isSubagent(): Boolean {
    val id = sn("session_id") ?: s("id")
    return origins().any { it in SUBAGENT_SOURCES } || id.startsWith("delegate_") || id.startsWith("subagent_") || id.startsWith("kanban_") ||
        sn("_delegate_from") != null || sn("delegate_from") != null || (o("model_config")?.sn("_delegate_from") != null)
}
fun JsonObject.isAutomation(): Boolean {
    val id = sn("session_id") ?: s("id")
    val o = origins()
    return o.any { it in AUTOMATION_SOURCES } || (app.store.hideCli.value && o.any { it in CLI_SOURCES }) || id.startsWith("cron_") || CRON_RUN_ID.matches(id)
}

/**
 * Session list URL. Unless you opted in, the server drops background runs itself. The exclusion list
 * deliberately leaves out "subagent": per Hermes' subagent_listing_scope, naming other sources without it
 * keeps delegate children hidden even when the server has sessions.show_subagents turned on.
 */
private val HIDDEN_SOURCES = listOf("acp", "api_server", "cron", "kanban", "oneshot", "tool", "recovered")
private fun hiddenSources() = HIDDEN_SOURCES + if (app.store.hideCli.value) CLI_SOURCES else emptySet()
fun sessionsUrl(limit: Int, background: Boolean = app.store.showBackground.value, archived: Boolean = false): String =
    // rows whose live source flipped slip past the server's filter and are dropped on the phone, so ask for extra
    (if (background || archived) "/api/sessions?limit=$limit&offset=0&order=recent"
    else "/api/sessions?limit=${(limit * 2).coerceAtMost(100)}&offset=0&order=recent&exclude_sources=" + hiddenSources().joinToString(",")) +
        if (archived) "&archived=only" else ""

/** The user's session-visibility settings, as a predicate. */
@Composable
fun rememberSessionFilter(): (JsonObject) -> Boolean {
    val bg by app.store.showBackground.collectAsState()
    val empty by app.store.hideEmpty.collectAsState()
    val cli by app.store.hideCli.collectAsState()
    return remember(bg, empty, cli) {
        { s -> (bg || (!s.isSubagent() && !s.isAutomation())) && !(empty && s.l("message_count") == 0L && !s.b("is_active")) }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SessionRow(s: JsonObject, onChanged: (() -> Unit)? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val title = s.sn("title") ?: s.sn("preview") ?: "Untitled"
    val mineIds by app.store.mineSessions.collectAsState()
    val mine = listOfNotNull(s.sn("id"), s.sn("session_id"), s.sn("_lineage_root_id")).any { it in mineIds }
    val id = s.sn("id") ?: s.s("session_id")
    val profile = s.sn("profile")?.takeIf { it.isNotBlank() && it != "default" }
    val pinned = s.b("pinned"); val archived = s.b("archived"); val hidden = s.b("hidden")
    var menu by remember { mutableStateOf(false) }
    // long-press: pin, archive or hide, the same switches Hermes Desktop has (PATCH /api/sessions/{id})
    fun set(field: String, v: Boolean, done: String) { menu = false; scope.act(done, after = { onChanged?.invoke() }) { app.api.patch("/api/sessions/${Api.enc(id)}", jsonOf(field to v)) } }
    Box(Modifier.combinedClickable(onClick = onClick, onLongClick = { if (onChanged != null) menu = true })) {
        ListRow(
            title = title,
            subtitle = listOfNotNull(if (mine) "Talaria" else s.sn("source"), s.sn("model"), "${s.l("message_count")} msgs", relTime(s.d("last_active"))).joinToString(" · "),
            icon = if (mine) Icons.Outlined.PhoneAndroid else sourceIcon(s.s("source")),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    profile?.let { Pill(it, p.muted) }
                    if (archived) Icon(Icons.Outlined.Archive, "Archived", tint = p.faint, modifier = Modifier.size(15.dp))
                    if (hidden) Icon(Icons.Outlined.VisibilityOff, "Hidden", tint = p.faint, modifier = Modifier.size(15.dp))
                    if (pinned) Icon(Icons.Outlined.PushPin, "Pinned", tint = p.accent, modifier = Modifier.size(15.dp))
                    if (s.b("is_active")) Dot(p.good, pulse = true)
                }
            },
            onClick = null,
        )
        DropdownMenu(menu, { menu = false }, containerColor = p.sheet) {
            DropdownMenuItem({ Text(if (pinned) "Unpin" else "Pin") }, { set("pinned", !pinned, if (pinned) "Unpinned" else "Pinned") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) })
            DropdownMenuItem({ Text(if (archived) "Unarchive" else "Archive") }, { set("archived", !archived, if (archived) "Back in your chats" else "Archived") }, leadingIcon = { Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) })
            DropdownMenuItem({ Text(if (hidden) "Unhide" else "Hide") }, { set("hidden", !hidden, if (hidden) "Unhidden" else "Hidden from your lists") }, leadingIcon = { Icon(if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null) })
        }
    }
}

/** Ticks when Hermes says its session list moved (sessions.changed), settled so a burst reloads once. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun rememberSessionsChanged(): Int {
    val g by app.activeGateway.collectAsState()
    val f = remember(g) { g.sessionsChanged.debounce(700) }
    return f.collectAsState(g.sessionsChanged.value).value
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun rememberCronChanged(): Int {
    val g by app.activeGateway.collectAsState()
    val f = remember(g) { g.cronChanged.debounce(700) }
    return f.collectAsState(g.cronChanged.value).value
}

fun sourceIcon(src: String) = when (src.lowercase()) {
    "telegram" -> Icons.Outlined.Send
    "discord" -> Icons.Outlined.Forum
    "slack" -> Icons.Outlined.Tag
    "cron" -> Icons.Outlined.Schedule
    "whatsapp", "signal", "sms" -> Icons.Outlined.Sms
    "email" -> Icons.Outlined.Email
    "api", "acp", "tool" -> Icons.Outlined.Code
    else -> Icons.Outlined.Terminal
}


/** Quick switch between your saved Hermes servers. */
@Composable
fun ServerSwitcher(nav: NavHostController) {
    val p = LocalPalette.current
    val servers by app.store.servers.collectAsState()
    val active by app.store.activeId.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(Icons.Outlined.Dns, "Servers", tint = p.ink) }
        DropdownMenu(open, { open = false }, containerColor = p.sheet) {
            servers.forEach { sv ->
                DropdownMenuItem(
                    { Column { Text(sv.auth.label, color = p.ink); Text(sv.auth.host, color = p.faint, style = MaterialTheme.typography.bodySmall, maxLines = 1) } },
                    { open = false; if (sv.id != active) switchServer(sv.id) },
                    leadingIcon = { Icon(if (sv.id == active) Icons.Outlined.CheckCircle else Icons.Outlined.Dns, null, tint = if (sv.id == active) p.good else p.muted) },
                )
            }
            HorizontalDivider(color = p.line)
            DropdownMenuItem({ Text("Add server") }, { open = false; app.store.addServer() }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
            DropdownMenuItem({ Text("Manage servers") }, { open = false; nav.go("settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) })
        }
    }
}
