package dev.hark.hermes.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    val sessions = rememberLoad(profile, bgSessions) { app.api.obj(sessionsUrl(24, bgSessions)) }
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
            val list = d.a("sessions").objs().filter(visible).take(6)
            if (list.isEmpty()) item { EmptyCard(Icons.Outlined.ChatBubbleOutline, "No sessions yet", "Start a chat and it'll show up here.") }
            else item {
                HCard(padding = 8.dp) { list.forEach { SessionRow(it) { nav.go("session/${it.s("id")}") } } }
            }
        }
    }
}

private val SUBAGENT_SOURCES = setOf("subagent", "kanban", "delegate", "delegation", "worker")
private val AUTOMATION_SOURCES = setOf("cron", "tool", "api", "acp", "oneshot", "recovered")

/** Sessions spawned by delegate_task / kanban workers rather than by you. */
fun JsonObject.isSubagent(): Boolean {
    val src = s("source").lowercase()
    val id = sn("session_id") ?: s("id")
    return src in SUBAGENT_SOURCES || id.startsWith("delegate_") || id.startsWith("subagent_") || id.startsWith("kanban_") ||
        sn("_delegate_from") != null || sn("delegate_from") != null
}
fun JsonObject.isAutomation() = s("source").lowercase() in AUTOMATION_SOURCES || (sn("session_id") ?: s("id")).startsWith("cron_")

/**
 * Session list URL. Unless you opted in, the server drops background runs itself. The exclusion list
 * deliberately leaves out "subagent": per Hermes' subagent_listing_scope, naming other sources without it
 * keeps delegate children hidden even when the server has sessions.show_subagents turned on.
 */
private val HIDDEN_SOURCES = listOf("acp", "cron", "kanban", "oneshot", "tool", "recovered")
fun sessionsUrl(limit: Int, background: Boolean = app.store.showBackground.value): String =
    "/api/sessions?limit=$limit&offset=0&order=recent" + if (background) "" else "&exclude_sources=" + HIDDEN_SOURCES.joinToString(",")

/** The user's session-visibility settings, as a predicate. */
@Composable
fun rememberSessionFilter(): (JsonObject) -> Boolean {
    val bg by app.store.showBackground.collectAsState()
    val empty by app.store.hideEmpty.collectAsState()
    return remember(bg, empty) {
        { s -> (bg || (!s.isSubagent() && !s.isAutomation())) && !(empty && s.l("message_count") == 0L && !s.b("is_active")) }
    }
}

@Composable
fun SessionRow(s: JsonObject, onClick: () -> Unit) {
    val p = LocalPalette.current
    val title = s.sn("title") ?: s.sn("preview") ?: "Untitled"
    ListRow(
        title = title,
        subtitle = listOfNotNull(s.sn("source"), s.sn("model"), "${s.l("message_count")} msgs", relTime(s.d("last_active"))).joinToString(" · "),
        icon = sourceIcon(s.s("source")),
        trailing = { if (s.b("is_active")) Dot(p.good, pulse = true) },
        onClick = onClick,
    )
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
            DropdownMenuItem({ Text("Add server") }, { open = false; app.gateway.reset(); app.store.addServer() }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
            DropdownMenuItem({ Text("Manage servers") }, { open = false; nav.go("settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) })
        }
    }
}
