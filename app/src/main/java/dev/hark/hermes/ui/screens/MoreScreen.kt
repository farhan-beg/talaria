package dev.hark.hermes.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.coroutines.launch

private data class Dest(val route: String, val title: String, val sub: String, val icon: ImageVector, val tint: Long)

private val groups = listOf(
    "Agent" to listOf(
        Dest("models", "Model", "Main and auxiliary models", Icons.Outlined.Memory, 0xFF0F5C58),
        Dest("skills", "Skills & tools", "Toggle, browse the hub", Icons.Outlined.Extension, 0xFF7A5AF8),
        Dest("bots", "Bots", "Named agents, each with a forever-chat", Icons.Outlined.SmartToy, 0xFF8B5CF6),
        Dest("profiles", "Profiles", "Isolated Hermes instances", Icons.Outlined.People, 0xFF2E7DD7),
        Dest("memory", "Memory", "Providers and built-in stores", Icons.Outlined.Psychology, 0xFFC2410C),
        Dest("files", "Files", "Browse, preview, upload, download", Icons.Outlined.FolderOpen, 0xFFCA8A04),
        Dest("voice", "Voice", "Phone or Hermes voice, provider, speed", Icons.Outlined.RecordVoiceOver, 0xFF0D9488),
    ),
    "Insights" to listOf(
        Dest("analytics", "Analytics", "Tokens, cost, models", Icons.Outlined.Insights, 0xFF16A34A),
        Dest("logs", "Logs", "Agent, gateway, errors", Icons.Outlined.Article, 0xFF64748B),
    ),
    "Connections" to listOf(
        Dest("channels", "Channels", "Telegram, Discord, Slack…", Icons.Outlined.Hub, 0xFF0EA5E9),
        Dest("pairing", "Pairing", "Approve messaging users", Icons.Outlined.HowToReg, 0xFFDB2777),
        Dest("mcp", "MCP servers", "Tools from MCP", Icons.Outlined.Cable, 0xFF9333EA),
        Dest("webhooks", "Webhooks", "Event subscriptions", Icons.Outlined.Webhook, 0xFFCA8A04),
    ),
    "Admin" to listOf(
        Dest("keys", "API keys", "Credentials in .env", Icons.Outlined.Key, 0xFFB45309),
        Dest("config", "Config", "Edit config.yaml", Icons.Outlined.Tune, 0xFF475569),
        Dest("system", "System", "Host, gateway, operations", Icons.Outlined.Dns, 0xFF0F766E),
        Dest("settings", "App settings", "Theme, profile, account", Icons.Outlined.Settings, 0xFF6B7280),
    ),
)

@Composable
fun MoreScreen(nav: NavHostController) {
    val p = LocalPalette.current
    Page("Control center", subtitle = "Everything your dashboard can do") {
        groups.forEach { (label, dests) ->
            item(label) { SectionLabel(label) }
            item("$label-grid") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    dests.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { d -> Tile(d, Modifier.weight(1f)) { nav.go(d.route) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(d: Dest, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val tint = Color(d.tint)
    Column(modifier.height(118.dp).clip(CardShape).background(p.card).border(1.dp, p.line.copy(alpha = 0.6f), CardShape).clickable(onClick = onClick).padding(16.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(d.icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(d.title, style = MaterialTheme.typography.titleSmall, color = p.ink)
        Text(d.sub, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1)
    }
}

@Composable
fun SettingsScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val theme by app.store.theme.collectAsState()
    val profile by app.store.profile.collectAsState()
    val auth by app.store.auth.collectAsState()
    val profiles = rememberLoad { app.api.obj("/api/profiles", profile = false).a("profiles").objs() }
    val me = rememberLoad { runCatching { app.api.obj("/api/auth/me", profile = false) }.getOrNull() }
    var signOut by remember { mutableStateOf(false) }

    Page("Settings", onBack = { nav.popBackStack() }) {
        item {
            HCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Person, null, tint = p.accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(me.data?.sn("display_name") ?: me.data?.sn("email") ?: auth.userId.take(18).ifBlank { "Signed in" }, style = MaterialTheme.typography.titleMedium, color = p.ink)
                        Text("via ${auth.provider.ifBlank { "dashboard" }} · ${auth.label}", style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                }
            }
        }
        if (app.store.insecure || app.store.servers.value.any { dev.hark.hermes.data.isInsecureUrl(it.auth.baseUrl) }) item {
            HCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LockOpen, null, tint = p.bad); Spacer(Modifier.width(10.dp))
                    Text(if (app.store.insecure) "This phone couldn't open encrypted storage, so your sign-in is kept in plain app storage. Signing out and back in, or clearing the app's storage, usually fixes it."
                        else "A saved server uses plain http over the internet. Switch it to https, or a LAN or Tailscale address.",
                        color = p.ink, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SectionLabel("Servers") }
        item { ServersCard() }
        item { SectionLabel("Theme") }
        item { ThemePicker() }
        item { ChipRow(listOf("system" to "System", "light" to "Light", "dark" to "Dark", "amoled" to "Pure black"), theme) { app.store.setTheme(it) } }
        item { SectionLabel("Feel") }
        item {
            val glass by app.store.glass.collectAsState()
            val motion by app.store.motion.collectAsState()
            val ambient by app.store.ambient.collectAsState()
            HCard(padding = 8.dp) {
                SettingToggle("Liquid glass", "Frosted surfaces with a lit edge", Icons.Outlined.BlurOn, glass) { app.store.set(app.store.glass, "glass", it) }
                SettingToggle("Fluid motion", "Springy taps and floating entrances", Icons.Outlined.Animation, motion) { app.store.set(app.store.motion, "motion", it) }
                SettingToggle("Ambient light", "Slowly drifting glow behind the app", Icons.Outlined.WbTwilight, ambient) { app.store.set(app.store.ambient, "ambient", it) }
            }
        }
        item { SectionLabel("For nerds") }
        item {
            val nerd by app.store.nerd.collectAsState()
            HCard(padding = 8.dp) {
                val mdIn by app.store.markdownInput.collectAsState()
                SettingToggle("Markdown in my messages", "Format bar and live styling while typing; your sent messages render bold, lists, code and more", Icons.Outlined.TextFormat, mdIn) { app.store.set(app.store.markdownInput, "markdown_input", it) }
                val interimOn by app.store.interimInReply.collectAsState()
                SettingToggle("Full reply", "Words Hermes writes between tool calls show in the reply, not tucked into \"Worked for\"", Icons.Outlined.Notes, interimOn) { app.store.set(app.store.interimInReply, "interim_in_reply", it) }
                SettingToggle("Stats for nerds", "Tokens/sec, time to first token, output tokens and total time under each reply", Icons.Outlined.Speed, nerd) { app.store.set(app.store.nerd, "nerd_stats", it) }
                ReactionsSetting()
            }
        }
        item { SectionLabel("Sessions") }
        item {
            val bg by app.store.showBackground.collectAsState()
            val empty by app.store.hideEmpty.collectAsState()
            val cli by app.store.hideCli.collectAsState()
            HCard(padding = 8.dp) {
                SettingToggle("Show subagent & cron sessions", "Off keeps recents to your own chats. API sessions are always background.", Icons.Outlined.AccountTree, bg) { app.store.set(app.store.showBackground, "show_background_sessions", it) }
                SettingToggle("Treat CLI sessions as background", "Hermes starts these itself with hermes chat -q. Turn off if you chat from the terminal.", Icons.Outlined.Terminal, cli) { app.store.set(app.store.hideCli, "hide_cli_sessions", it) }
                SettingToggle("Hide empty sessions", "Sessions with no messages", Icons.Outlined.HideSource, empty) { app.store.set(app.store.hideEmpty, "hide_empty", it) }
            }
        }
        item { SectionLabel("Managed profile") }
        item {
            Text("Config, keys, skills, MCP, models and chat follow this profile.", color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
        }
        loadState(profiles) { list ->
            item {
                ChipRow(listOf("" to "Dashboard default") + list.map { it.s("name") to (it.sn("display_name") ?: it.s("name")) }, profile) {
                    app.store.setProfile(it); app.gateway.reset()
                }
            }
        }
        item { SectionLabel("Account") }
        item {
            HCard(padding = 8.dp) {
                ListRow("Sign out", "Forget tokens on this phone", Icons.Outlined.Logout, p.bad, onClick = { signOut = true })
                ListRow("Add server", "Connect another Hermes dashboard", Icons.Outlined.Add, onClick = { app.store.addServer() })
            }
        }
        item { Text("Talaria 1.14.0  ·  for Hermes Agent", color = p.faint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }
    }
    if (signOut) ConfirmDialog("Sign out?", "You'll need to sign in with your dashboard OAuth again.", "Sign out", danger = true, { signOut = false }) {
        app.dropGateway(app.store.activeId.value); app.store.signOut()
    }
}


/** Hermes' own display.message_reactions: whether the agent reads your reactions on its next turn. Lives on the server, so it covers every client. */
@Composable
private fun ReactionsSetting() {
    val scope = rememberCoroutineScope()
    val g by app.activeGateway.collectAsState()
    val conn by g.conn.collectAsState()
    var on by remember(g) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(g, conn) { if (conn == Conn.Ready) on = runCatching { g.reactionsVisible() }.getOrNull() }
    SettingToggle("Hermes sees my reactions", if (on == null) "Checking your Hermes…" else "Your ❤️ 👍 👎 reach Hermes on its next turn. Applies to every app on this Hermes", Icons.Outlined.AddReaction, on == true) { v ->
        if (on == null) return@SettingToggle
        val before = on; on = v
        scope.launch { try { on = g.setReactionsVisible(v) } catch (e: Exception) { on = before; toast(errText(e)) } }
    }
}

@Composable
private fun SettingToggle(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(title, sub, icon, trailing = { Toggle(checked, onChange) }, onClick = { onChange(!checked) })
}

@Composable
private fun ThemePicker() {
    val p = LocalPalette.current
    val current by app.store.palette.collectAsState()
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        items(ThemePresets.size) { i ->
            val t = ThemePresets[i]
            val sel = t.id == current
            val ring by androidx.compose.animation.animateColorAsState(if (sel) p.accent else androidx.compose.ui.graphics.Color.Transparent, label = "ring")
            Column(
                Modifier.width(96.dp).press { app.store.set(app.store.palette, "palette", t.id) }
                    .border(2.dp, ring, RoundedCornerShape(24.dp)).padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(20.dp))
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(t.swatch[0], t.swatch[1]))),
                ) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(26.dp).clip(CircleShape).background(t.swatch[2]).sheen(CircleShape))
                    Box(Modifier.align(Alignment.TopStart).padding(10.dp).width(38.dp).height(8.dp).clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f)))
                    Box(Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 24.dp).width(24.dp).height(8.dp).clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f)))
                }
                Spacer(Modifier.height(6.dp))
                Text(t.name, style = MaterialTheme.typography.labelLarge, color = if (sel) p.ink else p.muted)
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}


/** Every saved server: tap to switch, rename to something you'll recognise, or remove. */
@Composable
private fun ServersCard() {
    val p = LocalPalette.current
    val servers by app.store.servers.collectAsState()
    val active by app.store.activeId.collectAsState()
    var renaming by remember { mutableStateOf<dev.hark.hermes.data.Store.Server?>(null) }
    var removing by remember { mutableStateOf<dev.hark.hermes.data.Store.Server?>(null) }
    HCard(padding = 8.dp) {
        servers.forEach { sv ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { if (sv.id != active) switchServer(sv.id) }.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (sv.id == active) Icons.Outlined.CheckCircle else Icons.Outlined.Dns, null, tint = if (sv.id == active) p.good else p.muted)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(sv.auth.label, color = p.ink, style = MaterialTheme.typography.titleSmall)
                    Text(sv.auth.host + if (!sv.auth.isSignedIn) " · signed out" else "", color = p.faint, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Options", tint = p.faint) }
                    DropdownMenu(menu, { menu = false }, containerColor = p.sheet) {
                        DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = sv }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                        DropdownMenuItem({ Text("Remove", color = p.bad) }, { menu = false; removing = sv }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = p.bad) })
                    }
                }
            }
        }
    }
    renaming?.let { sv ->
        var name by remember(sv.id) { mutableStateOf(sv.auth.name) }
        AlertDialog({ renaming = null }, containerColor = p.sheet,
            title = { Text("Name this server") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(sv.auth.host) }, shape = RoundedCornerShape(14.dp)) },
            confirmButton = { TextButton({ app.store.renameServer(sv.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } })
    }
    removing?.let { sv ->
        AlertDialog({ removing = null }, containerColor = p.sheet,
            title = { Text("Remove ${sv.auth.label}?") },
            text = { Text("Its sign-in is forgotten on this phone. Nothing changes on the server.") },
            confirmButton = { TextButton({ val wasActive = sv.id == active; app.dropGateway(sv.id); app.store.removeServer(sv.id); if (wasActive && app.store.auth.value.isSignedIn) app.gatewayFor(app.store.activeId.value).connect(); removing = null }) { Text("Remove", color = p.bad) } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } })
    }
}
