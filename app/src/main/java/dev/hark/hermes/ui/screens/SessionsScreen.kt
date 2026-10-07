package dev.hark.hermes.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


@Composable
fun SessionsScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val profile by app.store.profile.collectAsState()
    var filter by remember { mutableStateOf("chats") }
    var q by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(40) }
    var prune by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    val visible = rememberSessionFilter()
    LaunchedEffect(q) { delay(350); debounced = q }

    val stats = rememberLoad(profile) { runCatching { app.api.obj("/api/sessions/stats") }.getOrNull() }
    val bg by app.store.showBackground.collectAsState()
    // the Automation/Subagents chips are an explicit ask, so they fetch everything
    val wantAll = bg || filter == "automation" || filter == "subagents" || filter == "all"
    val data = rememberLoad(profile, limit, debounced, wantAll) {
        if (debounced.isNotBlank()) app.api.obj("/api/sessions/search?q=${Api.enc(debounced)}").a("results").objs()
        else app.api.obj(sessionsUrl(limit, wantAll)).a("sessions").objs()
    }

    Page("Sessions", refreshing = data.loading && data.data != null, onRefresh = { data.reload(); stats.reload() },
        actions = {
            IconButton({ showHidden = !showHidden }) {
                Icon(if (showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (showHidden) "Hide hidden sessions" else "Show hidden sessions", tint = if (showHidden) p.accent else p.ink)
            }
            IconButton({ prune = true }) { Icon(Icons.Outlined.CleaningServices, "Prune", tint = p.ink) } }) {
        stats.data?.let { s ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Sessions", s.l("total").toString(), Modifier.weight(1f))
                    StatTile("Messages", humanTokens(s.l("messages")), Modifier.weight(1f))
                    StatTile("Archived", s.l("archived").toString(), Modifier.weight(1f))
                }
            }
        }
        item { SearchField(q, { q = it }, "Search every message") }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { app.store.set(app.store.showBackground, "show_background_sessions", !bg) }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountTree, null, tint = p.muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                Text("Show subagent & cron sessions", color = p.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(bg, { app.store.set(app.store.showBackground, "show_background_sessions", it) })
            }
        }
        if (debounced.isBlank()) item { ChipRow(listOf("chats" to "Chats", "automation" to "Automation", "subagents" to "Subagents", "all" to "All"), filter) { filter = it } }
        loadState(data) { list ->
            val byKind = if (debounced.isNotBlank()) list else list.filter {
                when (filter) {
                    "chats" -> !it.isAutomation() && !it.isSubagent()
                    "automation" -> it.isAutomation()
                    "subagents" -> it.isSubagent()
                    else -> true
                }
            }
            // the Subagents chip is an explicit ask, so it ignores the hide setting
            val shown = if (showHidden || filter == "subagents" || filter == "automation" || debounced.isNotBlank()) byKind else byKind.filter(visible)
            val hiddenCount = byKind.size - shown.size
            if (hiddenCount > 0) item {
                Text("$hiddenCount hidden by your settings · Show", color = p.muted, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.press { showHidden = true }.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            if (shown.isEmpty()) item { EmptyCard(Icons.Outlined.SearchOff, "Nothing here", if (debounced.isBlank()) "No sessions match this filter." else "No messages match “$debounced”.") }
            else item {
                HCard(padding = 8.dp) {
                    shown.forEach { s ->
                        val id = s.sn("session_id") ?: s.s("id")
                        if (debounced.isNotBlank()) ListRow(s.sn("title") ?: id.take(12), s.s("snippet").replace(">>>", "").replace("<<<", ""), Icons.Outlined.FormatQuote) { nav.go("session/$id") }
                        else SessionRow(s) { nav.go("session/$id") }
                    }
                }
            }
            if (debounced.isBlank() && list.size >= limit) item {
                TextButton({ limit += 40 }, Modifier.fillMaxWidth()) { Text("Load more", color = p.accent) }
            }
        }
    }

    if (prune) {
        var days by remember { mutableStateOf("30") }
        FormSheet("Prune old sessions", { prune = false }, confirm = "Prune", onConfirm = {
            prune = false
            scope.act("Pruned", after = { data.reload(); stats.reload() }) { app.api.post("/api/sessions/prune", jsonOf("older_than_days" to (days.toIntOrNull() ?: 30))) }
        }) {
            Text("Deletes ended sessions older than this many days.", color = p.muted)
            Field("Days", days, { days = it.filter(Char::isDigit) })
        }
    }
}

@Composable
fun SessionDetailScreen(nav: NavHostController, id: String) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val meta = rememberLoad(id) { app.api.obj("/api/sessions/${Api.enc(id)}") }
    val msgs = rememberLoad(id) { app.api.obj("/api/sessions/${Api.enc(id)}/messages?limit=500&order=latest").a("messages").objs() }
    var rename by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    val m = meta.data
    val title = m?.sn("title") ?: "Session"

    Page(title, subtitle = m?.let { listOfNotNull(it.sn("source"), it.sn("model"), relTime(it.d("last_active"))).joinToString(" · ") },
        onBack = { nav.popBackStack() }, refreshing = msgs.loading && msgs.data != null, onRefresh = { meta.reload(); msgs.reload() },
        actions = {
            IconButton({ rename = true }) { Icon(Icons.Outlined.Edit, "Rename", tint = p.ink) }
            IconButton({
                scope.act {
                    val ex = app.api.get("/api/sessions/${Api.enc(id)}/export")
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, ex.toString()), "Export session"))
                }
            }) { Icon(Icons.Outlined.IosShare, "Export", tint = p.ink) }
            IconButton({ del = true }) { Icon(Icons.Outlined.DeleteOutline, "Delete", tint = p.bad) }
        }) {
        m?.let { s ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Messages", s.l("message_count").toString(), Modifier.weight(1f))
                    StatTile("Tools", s.l("tool_call_count").toString(), Modifier.weight(1f))
                    StatTile("Tokens", humanTokens(s.l("input_tokens") + s.l("output_tokens")), Modifier.weight(1f))
                }
            }
            item {
                SoftButton("Continue in chat", Icons.Outlined.ChatBubbleOutline, Modifier.fillMaxWidth(), primary = true) {
                    ChatNav.pendingResume.value = id to s.sn("title")
                    nav.go("chat")
                }
            }
        }
        loadState(msgs) { list ->
            items(list.filter { it.s("role") != "system" }) { MessageCard(it) }
        }
    }

    if (rename) {
        var t by remember { mutableStateOf(m?.s("title") ?: "") }
        FormSheet("Rename session", { rename = false }, onConfirm = {
            rename = false
            scope.act("Renamed", after = { meta.reload() }) { app.api.patch("/api/sessions/${Api.enc(id)}", jsonOf("title" to t.ifBlank { null })) }
        }) { Field("Title", t, { t = it }) }
    }
    if (del) ConfirmDialog("Delete session?", "This removes the session and its whole message history.", "Delete", danger = true, { del = false }) {
        scope.act("Deleted", after = { nav.popBackStack() }) { app.api.delete("/api/sessions/${Api.enc(id)}") }
    }
}

@Composable
private fun MessageCard(m: JsonObject) {
    val p = LocalPalette.current
    val role = m.s("role")
    val content = (m["content"] as? JsonPrimitive)?.content ?: m["content"]?.toString().orEmpty()
    val (label, color) = when (role) {
        "user" -> "You" to p.ink
        "assistant" -> "Hermes" to p.accent
        "tool" -> (m.sn("tool_name") ?: "Tool") to p.warn
        else -> role to p.muted
    }
    HCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(label, color)
            Spacer(Modifier.weight(1f))
            if (m.d("timestamp") > 0) Text(relTime(m.d("timestamp")), color = p.faint, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))
        when {
            role == "tool" -> CodeBlock(content.take(4000), 220.dp)
            content.isNotBlank() -> Markdown(content.take(20000))
        }
        m.a("tool_calls").objs().forEach { tc ->
            val f = tc.o("function")
            Spacer(Modifier.height(6.dp))
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(p.cardAlt).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Build, null, tint = p.muted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                Text(f.s("name"), fontFamily = Mono, color = p.ink, style = MaterialTheme.typography.labelMedium)
                Text("  " + f.s("arguments").take(80), color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}
