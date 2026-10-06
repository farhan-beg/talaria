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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

// ── Skills & toolsets ─────────────────────────────────────────────────────────
@Composable
fun SkillsScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val profile by app.store.profile.collectAsState()
    var tab by remember { mutableStateOf("skills") }
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }
    val skills = rememberLoad(profile) { app.api.arr("/api/skills").objs() }
    val toolsets = rememberLoad(profile) { app.api.arr("/api/tools/toolsets").objs() }
    var hubQ by remember { mutableStateOf("") }
    var hubDeb by remember { mutableStateOf("") }
    LaunchedEffect(hubQ) { delay(450); hubDeb = hubQ }
    val hub = rememberLoad(hubDeb, profile) { if (hubDeb.isBlank()) emptyList() else app.api.obj("/api/skills/hub/search?q=${Api.enc(hubDeb)}&source=all&limit=25").a("results").objs() }
    var action by remember { mutableStateOf<String?>(null) }

    Page("Skills & tools", onBack = { nav.popBackStack() }, onRefresh = { skills.reload(); toolsets.reload() }, refreshing = skills.loading && skills.data != null) {
        item { ChipRow(listOf("skills" to "Installed", "toolsets" to "Toolsets", "hub" to "Browse hub"), tab) { tab = it } }
        when (tab) {
            "skills" -> {
                item { SearchField(q, { q = it }, "Filter skills") }
                loadState(skills) { list ->
                    val cats = list.map { it.s("category").ifBlank { "General" } }.distinct().sorted()
                    item { ChipRow(listOf("" to "All (${list.size})") + cats.map { it to it }, cat) { cat = it } }
                    val shown = list.filter {
                        (cat.isBlank() || it.s("category").ifBlank { "General" } == cat) &&
                            (q.isBlank() || it.s("name").contains(q, true) || it.s("description").contains(q, true))
                    }
                    item {
                        HCard(padding = 8.dp) {
                            shown.forEach { s ->
                                var on by remember(s.s("name"), s.b("enabled")) { mutableStateOf(s.b("enabled")) }
                                ListRow(s.s("name"), s.s("description"), trailing = {
                                    Toggle(on) { v ->
                                        on = v
                                        scope.act(after = null) { app.api.put("/api/skills/toggle", jsonOf("name" to s.s("name"), "enabled" to v, "profile" to profile.ifBlank { null })) }
                                    }
                                })
                            }
                            if (shown.isEmpty()) Text("No skills match.", color = p.muted, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }
            "toolsets" -> loadState(toolsets) { list ->
                items(list, key = { it.s("name") }) { t ->
                    var on by remember(t.s("name"), t.b("enabled")) { mutableStateOf(t.b("enabled")) }
                    HCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.s("label").ifBlank { t.s("name") }, style = MaterialTheme.typography.titleSmall, color = p.ink)
                                Text(t.s("description"), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 2)
                            }
                            Toggle(on) { v -> on = v; scope.act { app.api.put("/api/tools/toolsets/${Api.enc(t.s("name"))}", jsonOf("enabled" to v, "profile" to profile.ifBlank { null })) } }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!t.b("configured")) Pill("needs setup", p.warn)
                            Text(t.a("tools").strs().joinToString(", "), style = MaterialTheme.typography.bodySmall, color = p.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            else -> {
                item { SearchField(hubQ, { hubQ = it }, "Search the skill hub") }
                item { SoftButton("Update all installed skills", Icons.Outlined.Update, Modifier.fillMaxWidth()) {
                    scope.act { val r = app.api.post("/api/skills/hub/update", jsonOf("profile" to profile.ifBlank { null })).objOrNull(); action = r.sn("name") ?: "skills-update" }
                } }
                if (hubDeb.isBlank()) item { EmptyCard(Icons.Outlined.TravelExplore, "Find new skills", "Search across every hub source, then install with one tap.") }
                else loadState(hub) { list ->
                    if (list.isEmpty()) item { EmptyCard(Icons.Outlined.SearchOff, "No results", "Try a different search.") }
                    items(list, key = { it.s("identifier") }) { r ->
                        HCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.s("name"), style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.weight(1f))
                                Pill(r.s("source")); Spacer(Modifier.width(6.dp))
                                if (r.s("trust_level").isNotBlank()) Pill(r.s("trust_level"), if (r.s("trust_level") in listOf("official", "trusted")) p.good else p.muted)
                            }
                            Text(r.s("description"), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
                            Spacer(Modifier.height(8.dp))
                            SoftButton("Install", Icons.Outlined.Download, primary = true) {
                                scope.act { val res = app.api.post("/api/skills/hub/install", jsonOf("identifier" to r.s("identifier"), "profile" to profile.ifBlank { null })).objOrNull(); action = res.sn("name") ?: "skills-install" }
                            }
                        }
                    }
                }
            }
        }
    }
    action?.let { ActionLogSheet(it) { action = null; skills.reload() } }
}

/** Streams a background action's log via /api/actions/{name}/status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionLogSheet(name: String, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val st = rememberLoad(name, poll = 1500) { app.api.obj("/api/actions/${Api.enc(name)}/status?lines=300", profile = false) }
    ModalBottomSheet(onDismiss, containerColor = p.sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleLarge, color = p.ink, modifier = Modifier.weight(1f))
                val d = st.data
                when {
                    d == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    d.b("running") -> Pill("running", p.accent)
                    d.l("exit_code") == 0L -> Pill("done", p.good)
                    else -> Pill("exit ${d.s("exit_code")}", p.bad)
                }
            }
            Spacer(Modifier.height(10.dp))
            CodeBlock(st.data?.a("lines")?.strs()?.joinToString("\n")?.ifBlank { "Waiting for output…" } ?: (st.error ?: "Starting…"), 460.dp)
        }
    }
}

// ── Model ─────────────────────────────────────────────────────────────────────
@Composable
fun ModelsScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val profile by app.store.profile.collectAsState()
    val info = rememberLoad(profile) { app.api.obj("/api/model/info") }
    val opts = rememberLoad(profile) { app.api.obj("/api/model/options") }
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }

    Page("Model", onBack = { nav.popBackStack() }, onRefresh = { info.reload(); opts.reload() }, refreshing = info.loading && info.data != null) {
        loadState(info) { m ->
            item {
                HCard {
                    Text("Current", style = MaterialTheme.typography.labelMedium, color = p.muted)
                    Text(m.s("model").ifBlank { "Not set" }, style = MaterialTheme.typography.headlineSmall, color = p.ink)
                    Text("via ${m.s("provider")}", style = MaterialTheme.typography.bodyMedium, color = p.muted)
                    Spacer(Modifier.height(10.dp))
                    val caps = m.o("capabilities")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (caps.b("supports_tools")) Pill("tools")
                        if (caps.b("supports_vision")) Pill("vision")
                        if (caps.b("supports_reasoning")) Pill("reasoning")
                        if (m.l("effective_context_length") > 0) Pill("${humanTokens(m.l("effective_context_length"))} ctx", p.muted)
                    }
                }
            }
        }
        item { SearchField(q, { q = it }, "Find a model") }
        loadState(opts) { o ->
            o.a("providers").objs().forEach { prov ->
                val slug = prov.s("slug")
                val models = prov.a("models").strs().filter { q.isBlank() || it.contains(q, true) }
                if (q.isNotBlank() && models.isEmpty()) return@forEach
                item(slug) {
                    HCard(padding = 10.dp, onClick = { open = if (open == slug) null else slug }) {
                        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(prov.s("name").ifBlank { slug }, style = MaterialTheme.typography.titleSmall, color = p.ink)
                                Text("${prov.a("models").size} models" + (prov.sn("warning")?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1)
                            }
                            if (prov.b("is_current")) Pill("current", p.good)
                            if (prov["authenticated"] != null && !prov.b("authenticated")) { Spacer(Modifier.width(6.dp)); Pill("no key", p.warn) }
                            Icon(if (open == slug || q.isNotBlank()) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = p.muted)
                        }
                        if (open == slug || q.isNotBlank()) models.take(80).forEach { mdl ->
                            val cur = info.data?.s("model") == mdl && prov.b("is_current")
                            ListRow(mdl, null, trailing = { if (cur) Icon(Icons.Outlined.Check, null, tint = p.accent) }, onClick = {
                                scope.act("Switched to $mdl", after = { info.reload(); opts.reload() }) {
                                    app.api.post("/api/model/set", jsonOf("scope" to "main", "provider" to slug, "model" to mdl, "confirm_expensive_model" to true))
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

// ── Profiles ──────────────────────────────────────────────────────────────────
@Composable
fun ProfilesScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val list = rememberLoad { app.api.obj("/api/profiles", profile = false).a("profiles").objs() }
    val active = rememberLoad { runCatching { app.api.obj("/api/profiles/active", profile = false) }.getOrNull() }
    var create by remember { mutableStateOf(false) }
    var soulFor by remember { mutableStateOf<String?>(null) }
    var del by remember { mutableStateOf<String?>(null) }
    Page("Profiles", onBack = { nav.popBackStack() }, onRefresh = { list.reload(); active.reload() }, refreshing = list.loading && list.data != null,
        fab = { ExtendedFloatingActionButton(onClick = { create = true }, containerColor = p.accent, contentColor = p.accentInk, shape = RoundedCornerShape(20.dp), icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New") }) }) {
        loadState(list) { ps ->
            items(ps, key = { it.s("name") }) { pr ->
                val name = pr.s("name")
                val isActive = active.data?.s("active") == name || active.data?.s("name") == name
                HCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pr.sn("display_name") ?: name, style = MaterialTheme.typography.titleMedium, color = p.ink, modifier = Modifier.weight(1f))
                        if (isActive) Pill("active", p.good)
                        if (pr.b("is_default")) { Spacer(Modifier.width(6.dp)); Pill("default", p.muted) }
                        if (pr.b("gateway_running")) { Spacer(Modifier.width(6.dp)); Dot(p.good, true) }
                    }
                    Text(listOfNotNull(pr.sn("model"), pr.sn("provider"), "${pr.l("skill_count")} skills").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = p.muted)
                    if (pr.s("description").isNotBlank()) Text(pr.s("description"), style = MaterialTheme.typography.bodyMedium, color = p.ink, modifier = Modifier.padding(top = 6.dp), maxLines = 3)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isActive) SoftButton("Set active", Icons.Outlined.Star) { scope.act("Active profile: $name", after = { active.reload() }) { app.api.post("/api/profiles/active", jsonOf("name" to name), profile = false) } }
                        SoftButton("Manage", Icons.Outlined.Tune) { app.store.setProfile(if (pr.b("is_default")) "" else name); app.gateway.reset(); toastLater("Now managing $name") }
                        SoftButton("SOUL", Icons.Outlined.Description) { soulFor = name }
                        Spacer(Modifier.weight(1f))
                        if (!pr.b("is_default")) IconButton({ del = name }) { Icon(Icons.Outlined.DeleteOutline, null, tint = p.bad) }
                    }
                }
            }
        }
    }
    if (create) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var clone by remember { mutableStateOf(true) }
        FormSheet("New profile", { create = false }, confirm = "Create", onConfirm = {
            create = false
            scope.act("Profile created", after = { list.reload() }) { app.api.post("/api/profiles", jsonOf("name" to name, "description" to desc.ifBlank { null }, "clone_from_default" to clone), profile = false) }
        }) {
            Field("Name", name, { name = it.lowercase().replace(' ', '-') })
            Field("Description", desc, { desc = it }, singleLine = false, minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Clone settings from default", color = p.ink, modifier = Modifier.weight(1f)); Toggle(clone) { clone = it } }
        }
    }
    soulFor?.let { n ->
        val soul = rememberLoad(n) { app.api.obj("/api/profiles/${Api.enc(n)}/soul", profile = false).s("content") }
        var text by remember(soul.data) { mutableStateOf(soul.data ?: "") }
        FormSheet("SOUL · $n", { soulFor = null }, onConfirm = {
            soulFor = null
            scope.act("SOUL saved") { app.api.put("/api/profiles/${Api.enc(n)}/soul", jsonOf("content" to text), profile = false) }
        }) {
            if (soul.data == null) LoadingCard() else Field("SOUL.md", text, { text = it }, singleLine = false, minLines = 10, mono = true)
        }
    }
    del?.let { n -> ConfirmDialog("Delete profile $n?", "Its config, skills, sessions and memory are removed from the host.", "Delete", true, { del = null }) {
        scope.act("Deleted", after = { list.reload() }) { app.api.delete("/api/profiles/${Api.enc(n)}", profile = false) }
    } }
}

private fun toastLater(msg: String) { kotlinx.coroutines.MainScope().toast(msg) }

// ── Memory ────────────────────────────────────────────────────────────────────
@Composable
fun MemoryScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val profile by app.store.profile.collectAsState()
    val mem = rememberLoad(profile) { app.api.obj("/api/memory") }
    var reset by remember { mutableStateOf<String?>(null) }
    Page("Memory", onBack = { nav.popBackStack() }, onRefresh = { mem.reload() }, refreshing = mem.loading && mem.data != null) {
        loadState(mem) { m ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("MEMORY.md", humanBytes(m.o("builtin_files").l("memory")), Modifier.weight(1f), icon = Icons.Outlined.Notes)
                    StatTile("USER.md", humanBytes(m.o("builtin_files").l("user")), Modifier.weight(1f), icon = Icons.Outlined.Person)
                }
            }
            item { SectionLabel("External provider") }
            item {
                HCard(padding = 8.dp) {
                    val active = m.s("active")
                    ListRow("Built-in only", "Just MEMORY.md and USER.md", Icons.Outlined.Inventory2, trailing = { if (active.isBlank()) Icon(Icons.Outlined.Check, null, tint = p.accent) }, onClick = {
                        scope.act("Using built-in memory", after = { mem.reload() }) { app.api.put("/api/memory/provider", jsonOf("provider" to "")) }
                    })
                    m.a("providers").objs().forEach { pr ->
                        ListRow(pr.s("name"), pr.s("description"), Icons.Outlined.Psychology,
                            iconTint = when (pr.s("status")) { "ready" -> p.good; "needs_config" -> p.warn; else -> p.faint },
                            trailing = { if (active == pr.s("name")) Icon(Icons.Outlined.Check, null, tint = p.accent) else if (pr.s("status") != "ready") Pill(pr.s("status").replace('_', ' '), p.muted) },
                            onClick = { scope.act("Memory provider: ${pr.s("name")}", after = { mem.reload() }) { app.api.put("/api/memory/provider", jsonOf("provider" to pr.s("name"))) } })
                    }
                }
            }
            item { SectionLabel("Reset") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("Reset MEMORY", danger = true, modifier = Modifier.weight(1f)) { reset = "memory" }
                    SoftButton("Reset USER", danger = true, modifier = Modifier.weight(1f)) { reset = "user" }
                    SoftButton("Both", danger = true, modifier = Modifier.weight(1f)) { reset = "all" }
                }
            }
        }
    }
    reset?.let { t -> ConfirmDialog("Reset memory?", "This wipes the built-in ${if (t == "all") "MEMORY.md and USER.md" else t.uppercase() + ".md"} store.", "Reset", true, { reset = null }) {
        scope.act("Memory reset", after = { mem.reload() }) { app.api.post("/api/memory/reset", jsonOf("target" to t)) }
    } }
}
