package dev.hark.hermes.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun CronScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val profile by app.store.profile.collectAsState()
    val cronMoved = rememberCronChanged()
    val jobs = rememberLoad(profile, cronMoved) { app.api.arr("/api/cron/jobs").objs() }
    val targets = rememberLoad(profile) { runCatching { app.api.obj("/api/cron/delivery-targets").a("targets").objs() }.getOrDefault(emptyList()) }
    var edit by remember { mutableStateOf<JsonObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JsonObject?>(null) }
    var runsFor by remember { mutableStateOf<JsonObject?>(null) }

    Page("Automations", subtitle = "Scheduled prompts your agent runs on its own",
        refreshing = jobs.loading && jobs.data != null, onRefresh = { jobs.reload() },
        fab = {
            ExtendedFloatingActionButton(onClick = { creating = true }, containerColor = p.accent, contentColor = p.accentInk, shape = RoundedCornerShape(20.dp),
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New") })
        }) {
        loadState(jobs) { list ->
            if (list.isEmpty()) item { EmptyCard(Icons.Outlined.Schedule, "No automations yet", "Create one to have Hermes run a prompt on a schedule.") }
            items(list, key = { it.s("id") }) { j ->
                val paused = !j.b("enabled") || j.s("state") == "paused"
                val err = j.sn("last_error") ?: j.o("last_fire_error")?.sn("detail")
                HCard(onClick = { runsFor = j }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(j.sn("name") ?: j.s("prompt").take(40).ifBlank { j.s("id") }, style = MaterialTheme.typography.titleMedium, color = p.ink,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            err != null -> Pill("error", p.bad)
                            paused -> Pill("paused", p.muted)
                            else -> Pill("active", p.good)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Repeat, null, tint = p.accent, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp))
                        Text(j.sn("schedule_display") ?: j.o("schedule")?.sn("display") ?: j.o("schedule")?.s("expr") ?: "", color = p.ink, style = MaterialTheme.typography.bodyMedium, fontFamily = Mono)
                        Spacer(Modifier.width(10.dp))
                        Text("→ ${j.sn("deliver") ?: "local"}", color = p.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (j.s("prompt").isNotBlank()) Text(j.s("prompt"), color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("Next ${if (paused) "—" else isoFuture(j.sn("next_run_at"))}", color = p.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text("Last ${isoRel(j.sn("last_run_at"))}", color = p.muted, style = MaterialTheme.typography.labelMedium)
                    }
                    if (err != null) Text(err, color = p.bad, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val id = Api.enc(j.s("id"))
                        SoftButton(if (paused) "Resume" else "Pause", if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause) {
                            scope.act(if (paused) "Resumed" else "Paused", after = { jobs.reload() }) { app.api.post("/api/cron/jobs/$id/${if (paused) "resume" else "pause"}") }
                        }
                        SoftButton("Run now", Icons.Outlined.Bolt) { scope.act("Triggered", after = { jobs.reload() }) { app.api.post("/api/cron/jobs/$id/trigger") } }
                        Spacer(Modifier.weight(1f))
                        IconButton({ edit = j }) { Icon(Icons.Outlined.Edit, "Edit", tint = p.muted) }
                        IconButton({ deleting = j }) { Icon(Icons.Outlined.DeleteOutline, "Delete", tint = p.bad) }
                    }
                }
            }
        }
    }

    if (creating || edit != null) {
        val j = edit
        var name by remember { mutableStateOf(j?.s("name") ?: "") }
        var prompt by remember { mutableStateOf(j?.s("prompt") ?: "") }
        var sched by remember { mutableStateOf(j?.o("schedule")?.sn("expr") ?: j?.sn("schedule_display") ?: "0 9 * * *") }
        var deliver by remember { mutableStateOf(j?.sn("deliver") ?: "local") }
        FormSheet(if (j == null) "New automation" else "Edit automation", { creating = false; edit = null }, onConfirm = {
            val body = jsonOf("name" to name.ifBlank { null }, "prompt" to prompt, "schedule" to sched, "deliver" to deliver)
            creating = false; val e = edit; edit = null
            scope.act(if (e == null) "Created" else "Saved", after = { jobs.reload() }) {
                if (e == null) app.api.post("/api/cron/jobs", body) else app.api.put("/api/cron/jobs/${Api.enc(e.s("id"))}", body)
            }
        }) {
            Field("Name (optional)", name, { name = it })
            Field("Prompt", prompt, { prompt = it }, singleLine = false, minLines = 3)
            Field("Schedule", sched, { sched = it }, mono = true)
            ChipRow(listOf("0 9 * * *" to "Daily 9am", "0 * * * *" to "Hourly", "0 9 * * 1" to "Mondays", "*/15 * * * *" to "Every 15m", "every 30m" to "every 30m"), sched) { sched = it }
            Text("Deliver to", color = p.muted, style = MaterialTheme.typography.labelMedium)
            val ts = targets.data.orEmpty().map { it.s("id") to it.s("name") }.ifEmpty { listOf("local" to "Local") }
            ChipRow(ts, deliver) { deliver = it }
        }
    }
    deleting?.let { j ->
        ConfirmDialog("Delete automation?", "“${j.sn("name") ?: j.s("id")}” will stop running and be removed.", "Delete", danger = true, { deleting = null }) {
            scope.act("Deleted", after = { jobs.reload() }) { app.api.delete("/api/cron/jobs/${Api.enc(j.s("id"))}") }
        }
    }
    runsFor?.let { j -> RunsSheet(j) { runsFor = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunsSheet(j: JsonObject, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val runs = rememberLoad(j.s("id")) { app.api.get("/api/cron/jobs/${Api.enc(j.s("id"))}/runs?limit=20").let { it.objOrNull()?.a("runs") ?: it.arrOrEmpty() }.objs() }
    ModalBottomSheet(onDismiss, containerColor = p.sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
            Text(j.sn("name") ?: "Automation", style = MaterialTheme.typography.titleLarge, color = p.ink)
            Spacer(Modifier.height(8.dp))
            if (j.s("prompt").isNotBlank()) CodeBlock(j.s("prompt"), 160.dp)
            Spacer(Modifier.height(12.dp))
            SectionLabel("Recent runs")
            when {
                runs.data == null && runs.error == null -> LoadingCard()
                runs.data.isNullOrEmpty() -> Text("No runs recorded yet.", color = p.muted, modifier = Modifier.padding(8.dp))
                else -> runs.data!!.forEach { r ->
                    val ok = r.s("status").ifBlank { r.s("last_status") }
                    ListRow(
                        title = isoRel(r.sn("started_at") ?: r.sn("at") ?: r.sn("run_at")),
                        subtitle = r.sn("error") ?: r.sn("summary") ?: r.sn("output")?.take(120) ?: ok,
                        icon = if (ok.contains("err") || ok.contains("fail")) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircleOutline,
                        iconTint = if (ok.contains("err") || ok.contains("fail")) p.bad else p.good,
                    )
                }
            }
        }
    }
}
