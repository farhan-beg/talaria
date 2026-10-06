package dev.hark.hermes.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.serialization.json.JsonObject

@Composable
fun AnalyticsScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val profile by app.store.profile.collectAsState()
    var days by remember { mutableStateOf("30") }
    val data = rememberLoad(profile, days) { app.api.obj("/api/analytics/usage?days=$days") }
    Page("Analytics", onBack = { nav.popBackStack() }, refreshing = data.loading && data.data != null, onRefresh = { data.reload() }) {
        item { ChipRow(listOf("7" to "7 days", "30" to "30 days", "90" to "90 days"), days) { days = it } }
        loadState(data) { d ->
            val t = d.o("totals")
            val cost = t.d("total_actual_cost").takeIf { it > 0 } ?: t.d("total_estimated_cost")
            val inp = t.l("total_input"); val out = t.l("total_output"); val cache = t.l("total_cache_read")
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Tokens", humanTokens(inp + out), Modifier.weight(1f), "${humanTokens(inp)} in · ${humanTokens(out)} out", Icons.Outlined.DataUsage)
                    StatTile("Cost", String.format("$%.2f", cost), Modifier.weight(1f), if (t.d("total_actual_cost") > 0) "actual" else "estimated", Icons.Outlined.Payments)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val sess = t.l("total_sessions")
                    StatTile("Sessions", sess.toString(), Modifier.weight(1f), String.format("%.1f / day", sess / (days.toDoubleOrNull() ?: 30.0)), Icons.Outlined.Forum)
                    val hit = if (inp + cache > 0) cache * 100.0 / (inp + cache) else 0.0
                    StatTile("Cache hits", String.format("%.0f%%", hit), Modifier.weight(1f), "${humanTokens(cache)} cached", Icons.Outlined.Cached)
                }
            }
            val daily = d.a("daily").objs()
            if (daily.isNotEmpty()) item {
                HCard {
                    Text("Daily tokens", style = MaterialTheme.typography.titleSmall, color = p.ink)
                    Row(Modifier.padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(p.accent); Text(" input   ", style = MaterialTheme.typography.labelSmall, color = p.muted)
                        Dot(p.warn); Text(" output", style = MaterialTheme.typography.labelSmall, color = p.muted)
                    }
                    BarChart(daily, p.accent, p.warn, p.cardAlt)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(daily.first().s("day").takeLast(5), style = MaterialTheme.typography.labelSmall, color = p.faint, modifier = Modifier.weight(1f))
                        Text(daily.last().s("day").takeLast(5), style = MaterialTheme.typography.labelSmall, color = p.faint)
                    }
                }
            }
            val models = d.a("by_model").objs()
            if (models.isNotEmpty()) {
                item { SectionLabel("By model") }
                item {
                    HCard(padding = 10.dp) {
                        val max = models.maxOf { it.l("input_tokens") + it.l("output_tokens") }.coerceAtLeast(1)
                        models.sortedByDescending { it.l("input_tokens") + it.l("output_tokens") }.forEach { m ->
                            val tot = m.l("input_tokens") + m.l("output_tokens")
                            Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
                                Row {
                                    Text(m.s("model"), style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(String.format("$%.2f", m.d("estimated_cost")), style = MaterialTheme.typography.labelLarge, color = p.ink)
                                }
                                Text("${m.l("sessions")} sessions · ${humanTokens(tot)} tokens · ${m.l("api_calls")} calls", style = MaterialTheme.typography.bodySmall, color = p.muted)
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator({ tot.toFloat() / max }, Modifier.fillMaxWidth().height(6.dp), color = p.accent, trackColor = p.cardAlt, drawStopIndicator = {})
                            }
                        }
                    }
                }
            }
            val skills = d.o("skills")?.a("top_skills")?.objs().orEmpty()
            if (skills.isNotEmpty()) {
                item { SectionLabel("Top skills") }
                item {
                    HCard(padding = 8.dp) {
                        skills.take(10).forEach { s -> ListRow(s.s("skill").ifBlank { s.s("name") }, s.entries.filter { it.key != "skill" && it.key != "name" }.take(3).joinToString(" · ") { "${it.key.replace('_', ' ')} ${it.value.toString().trim('"')}" }, Icons.Outlined.Extension) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarChart(daily: List<JsonObject>, a: Color, b: Color, track: Color) {
    val max = daily.maxOf { it.l("input_tokens") + it.l("output_tokens") }.coerceAtLeast(1).toFloat()
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val n = daily.size
        val gap = if (n > 40) 2f else 5f
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        daily.forEachIndexed { i, d ->
            val x = i * (w + gap)
            val hi = d.l("input_tokens") / max * size.height
            val ho = d.l("output_tokens") / max * size.height
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2.5f))
            drawRoundRect(a, Offset(x, size.height - hi - ho), Size(w, hi + ho), CornerRadius(w / 2.5f))
            if (ho > 0) drawRoundRect(b, Offset(x, size.height - ho), Size(w, ho), CornerRadius(w / 2.5f))
        }
    }
}

@Composable
fun LogsScreen(nav: NavHostController) {
    val p = LocalPalette.current
    var file by remember { mutableStateOf("agent") }
    var level by remember { mutableStateOf("ALL") }
    var comp by remember { mutableStateOf("all") }
    var lines by remember { mutableStateOf("200") }
    var live by remember { mutableStateOf(false) }
    val data = rememberLoad(file, level, comp, lines, live, poll = if (live) 5000 else 0) {
        app.api.obj("/api/logs?file=$file&lines=$lines&level=$level&component=$comp", profile = false).a("lines").strs()
    }
    Page("Logs", onBack = { nav.popBackStack() }, refreshing = data.loading && data.data != null, onRefresh = { data.reload() },
        actions = {
            Text("Live", color = p.muted, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(6.dp))
            Toggle(live) { live = it }
        }) {
        item { ChipRow(listOf("agent" to "Agent", "gateway" to "Gateway", "errors" to "Errors"), file) { file = it } }
        item { ChipRow(listOf("ALL", "DEBUG", "INFO", "WARNING", "ERROR").map { it to it.lowercase().replaceFirstChar(Char::uppercase) }, level) { level = it } }
        item { ChipRow(listOf("all", "gateway", "agent", "tools", "cli", "cron").map { it to it.replaceFirstChar(Char::uppercase) }, comp) { comp = it } }
        item { ChipRow(listOf("50", "100", "200", "500").map { it to "$it lines" }, lines) { lines = it } }
        loadState(data) { list ->
            if (list.isEmpty()) item { EmptyCard(Icons.Outlined.Article, "No log lines", "Nothing matches these filters.") }
            else item {
                HCard(padding = 12.dp) {
                    list.takeLast(500).forEach { l ->
                        val c = when { "ERROR" in l || "CRITICAL" in l -> p.bad; "WARNING" in l -> p.warn; "DEBUG" in l -> p.faint; else -> p.ink }
                        Text(l, color = c, fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }
    }
}
