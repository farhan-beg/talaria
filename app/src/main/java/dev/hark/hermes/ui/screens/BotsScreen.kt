package dev.hark.hermes.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Bot Mode ──────────────────────────────────────────────────────────────────

/** A bot's face: its photo when it has one, else Desktop's shape in its colour with two eyes. */
@Composable
fun BotAvatar(bot: Bot?, size: Dp = 44.dp, name: String = bot?.name.orEmpty(), working: Boolean = bot?.working == true) {
    val p = LocalPalette.current
    val rev by Bots.avatarRev.collectAsState()
    var photo by remember(bot?.name, bot?.metaRev, rev) { mutableStateOf(bot?.let { Bots.cachedAvatar(it) }) }
    LaunchedEffect(bot?.name, bot?.metaRev, rev) { if (bot != null && bot.photo && photo == null) photo = Bots.avatar(app.gateway, bot) }
    val color = Color(Bots.colorOf(bot, name))
    Box(Modifier.size(size)) {
        val img = photo
        if (img != null) Image(img.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
        else Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width; val h = this.size.height
            when (bot?.shape ?: "circle") {
                "squircle" -> drawRoundRect(color, Offset(w * .075f, h * .075f), Size(w * .85f, h * .85f), CornerRadius(w * .28f))
                "pill" -> drawRoundRect(color, Offset(w * .05f, h * .18f), Size(w * .9f, h * .64f), CornerRadius(h * .32f))
                "hexagon" -> drawPath(Path().apply {
                    moveTo(w * .5f, h * .09f); lineTo(w * .86f, h * .29f); lineTo(w * .86f, h * .71f); lineTo(w * .5f, h * .91f); lineTo(w * .14f, h * .71f); lineTo(w * .14f, h * .29f); close() }, color)
                "triangle" -> drawPath(Path().apply { moveTo(w * .5f, h * .12f); lineTo(w * .92f, h * .86f); lineTo(w * .08f, h * .86f); close() }, color)
                "drop" -> drawPath(Path().apply {
                    moveTo(w * .5f, h * .07f); cubicTo(w * .5f, h * .07f, w * .15f, h * .5f, w * .15f, h * .67f)
                    cubicTo(w * .15f, h * .87f, w * .32f, h * .95f, w * .5f, h * .95f); cubicTo(w * .68f, h * .95f, w * .85f, h * .87f, w * .85f, h * .67f)
                    cubicTo(w * .85f, h * .5f, w * .5f, h * .07f, w * .5f, h * .07f); close() }, color)
                else -> drawCircle(color, w * .44f)
            }
            val lum = (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue)
            val eye = if (lum < 0.5) Color(0xF2E8DCC3) else Color(0xD9000000)
            val ey = if (bot?.shape == "triangle") h * .6f else h * .48f
            drawCircle(eye, w * .065f, Offset(w * .4f, ey)); drawCircle(eye, w * .065f, Offset(w * .6f, ey))
        }
        if (working) Box(Modifier.align(Alignment.BottomEnd).size(size * 0.28f).clip(CircleShape).background(p.bg).padding(2.dp).clip(CircleShape).background(p.good))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BotsScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val g = app.gateway
    val conn by g.conn.collectAsState()
    LaunchedEffect(Unit) { g.connect() }
    var q by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Bot?>(null) }
    var actionsFor by remember { mutableStateOf<Bot?>(null) }
    var deleting by remember { mutableStateOf<Bot?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    val roster = rememberLoad(conn == Conn.Ready, poll = 10_000) {
        if (conn != Conn.Ready) throw java.io.IOException("Connecting to Hermes…")
        try { Bots.list(g) } catch (e: java.io.IOException) {
            if (e.message.orEmpty().contains("method", true) || e.message.orEmpty().contains("unknown", true))
                throw java.io.IOException("This server's Hermes doesn't have Bot Mode yet. Update Hermes to use bots.")
            throw e
        }
    }
    fun open(b: Bot) {
        if (opening != null) return
        opening = b.name
        scope.launch {
            try { g.openBot(b.name, b.chatId); nav.go("chat") } catch (e: Exception) { toast(errText(e)) } finally { opening = null }
        }
    }

    Page("Bots", subtitle = "Each bot has its own memory, skills and one forever-chat", onBack = { nav.popBackStack() },
        onRefresh = { roster.reload() }, refreshing = roster.loading && roster.data != null,
        fab = { ExtendedFloatingActionButton(onClick = { creating = true }, containerColor = p.accent, contentColor = p.accentInk, shape = RoundedCornerShape(20.dp),
            icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New bot") }) }) {
        loadState(roster) { all ->
            val anyHidden = all.any { it.hidden }
            item { SearchField(q, { q = it }, "Search bots") }
            if (anyHidden) item { ChipRow(listOf("shown" to "Visible", "all" to "Include hidden"), if (showHidden) "all" else "shown") { showHidden = it == "all" } }
            val shown = all.filter { (showHidden || !it.hidden) && (q.isBlank() || listOf(it.display, it.name, it.description).any { s -> s.contains(q, true) }) }
                .sortedWith(compareByDescending<Bot> { it.pinned }.thenByDescending { it.lastActive })
            if (shown.isEmpty()) item { EmptyCard(Icons.Outlined.SmartToy, if (q.isBlank()) "No bots yet" else "No match", if (q.isBlank()) "Tap New bot to make one. It gets its own memory, skills and chat." else "Try another name.") }
            val groups = shown.groupBy { if (it.pinned) "Pinned" else it.section ?: "" }
            val order = listOf("Pinned") + groups.keys.filter { it != "Pinned" && it.isNotBlank() }.sorted() + listOf("")
            order.filter { it in groups }.forEach { label ->
                if (groups.size > 1 || label.isNotBlank()) item("sec-$label") { SectionLabel(label.ifBlank { "Bots" }) }
                items(groups.getValue(label), key = { "bot-" + it.name }) { b ->
                    HCard(padding = 14.dp, modifier = Modifier.combinedClickable(onClick = { open(b) }, onLongClick = { actionsFor = b })) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BotAvatar(b, 48.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(b.display, style = MaterialTheme.typography.titleMedium, color = if (b.hidden) p.muted else p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    Spacer(Modifier.width(6.dp))
                                    Text("@" + b.handle, style = MaterialTheme.typography.bodySmall, color = p.faint, maxLines = 1)
                                    if (b.pinned) { Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.PushPin, null, tint = p.faint, modifier = Modifier.size(13.dp)) }
                                }
                                val line = b.preview.ifBlank { b.description }.ifBlank { if (b.chatId == null) "Say hello to start its chat" else "" }
                                if (line.isNotBlank()) Text(line.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                if (opening == b.name) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = p.accent)
                                else if (b.working) Pill("working", p.good)
                                else if (b.lastActive > 0) Text(relTime(b.lastActive), style = MaterialTheme.typography.labelSmall, color = p.faint)
                            }
                        }
                    }
                }
            }
            item { Text("Long-press a bot to pin, hide, edit or delete it. Bots can message each other with @name.", color = p.faint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 60.dp)) }
        }
    }

    actionsFor?.let { b ->
        AlertDialog(onDismissRequest = { actionsFor = null }, containerColor = p.sheet, shape = RoundedCornerShape(26.dp),
            title = { Row(verticalAlignment = Alignment.CenterVertically) { BotAvatar(b, 36.dp); Spacer(Modifier.width(10.dp)); Text(b.display) } },
            text = {
                Column {
                    fun meta(done: String, changes: Map<String, Any?>) { actionsFor = null; scope.act(done, after = { roster.reload() }) { Bots.updateMeta(g, b, changes) } }
                    ListRow("Open chat", null, Icons.Outlined.Forum, onClick = { actionsFor = null; open(b) })
                    ListRow(if (b.pinned) "Unpin" else "Pin to top", null, Icons.Outlined.PushPin, onClick = { meta(if (b.pinned) "Unpinned" else "Pinned", mapOf("pinned" to !b.pinned)) })
                    ListRow(if (b.hidden) "Show in the list" else "Hide", null, if (b.hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, onClick = { meta(if (b.hidden) "Shown" else "Hidden", mapOf("hidden" to !b.hidden)) })
                    ListRow("Edit", "Name, look, mission, personality", Icons.Outlined.Edit, onClick = { actionsFor = null; editing = b })
                    if (!b.isDefault) ListRow("Delete", null, Icons.Outlined.DeleteOutline, iconTint = p.bad, onClick = { actionsFor = null; deleting = b })
                }
            },
            confirmButton = { TextButton({ actionsFor = null }) { Text("Close", color = p.muted) } })
    }
    if (creating) CreateBotSheet(roster.data.orEmpty(), onDismiss = { creating = false }) { name, hello ->
        creating = false
        scope.launch {
            try {
                val fresh = Bots.list(g).firstOrNull { it.name == name }
                g.openBot(name, fresh?.chatId)
                nav.go("chat")
                if (hello) g.send("Hey, tell me about yourself!")
            } catch (e: Exception) { toast(errText(e)) }
            roster.reload()
        }
    }
    editing?.let { b -> EditBotSheet(b, onDismiss = { editing = null }) { editing = null; roster.reload() } }
    deleting?.let { b -> ConfirmDialog("Delete ${b.display}?", "Its profile, memory, skills and chats are removed from the host. This can't be undone.", "Delete", true, { deleting = null }) {
        deleting = null
        scope.act("Deleted ${b.display}", after = { roster.reload() }) {
            if (g.botProfile.value == b.name) g.newChat()
            app.api.delete("/api/profiles/${Api.enc(b.name)}", profile = false)
        }
    } }
}

@Composable
private fun LookPicker(color: String, shape: String, name: String, onColor: (String) -> Unit, onShape: (String) -> Unit) {
    val p = LocalPalette.current
    Text("Colour", color = p.muted, style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Bots.COLORS.take(7).forEach { c ->
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(Bots.parseColor(c)!!))
                .then(if (c.equals(color, true)) Modifier.border(3.dp, p.ink, CircleShape) else Modifier).clickable { onColor(c) })
        }
    }
    Text("Shape", color = p.muted, style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Bots.SHAPES.forEach { s ->
            val look = Bot(name, false, name, "", "", color, s, false, false, null, false, false, null, "", 0.0, false, kotlinx.serialization.json.JsonObject(emptyMap()), 0)
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (s == shape) p.accentSoft else Color.Transparent).clickable { onShape(s) }.padding(4.dp)) { BotAvatar(look, 34.dp) }
        }
    }
}

@Composable
private fun CreateBotSheet(existing: List<Bot>, onDismiss: () -> Unit, onCreated: (String, Boolean) -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var soul by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(Bots.COLORS.random()) }
    var shape by remember { mutableStateOf("circle") }
    var hello by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    FormSheet("New bot", { if (!busy) onDismiss() }, confirm = if (busy) "Creating…" else "Create", onConfirm = {
        if (busy) return@FormSheet
        if (title.isBlank()) { scope.toast("Give your bot a name"); return@FormSheet }
        busy = true
        scope.launch {
            try { onCreated(Bots.create(app.gateway, title.trim(), desc.trim(), soul, color, shape, existing.map { it.name }.toSet()), hello) }
            catch (e: Exception) { toast(errText(e)); busy = false }
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(Bot(Bots.slug(title), false, title, "", "", color, shape, false, false, null, false, false, null, "", 0.0, false, kotlinx.serialization.json.JsonObject(emptyMap()), 0), 52.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title.ifBlank { "Your new bot" }, color = p.ink, style = MaterialTheme.typography.titleMedium)
                Text("@" + Bots.slug(title), color = p.faint, style = MaterialTheme.typography.bodySmall)
            }
        }
        Field("Name", title, { title = it.take(40) })
        Field("What it does", desc, { desc = it }, singleLine = false, minLines = 2)
        LookPicker(color, shape, Bots.slug(title), { color = it }, { shape = it })
        Field("Personality (optional SOUL.md)", soul, { soul = it }, singleLine = false, minLines = 3)
        Text("It starts with the main profile's model, keys and settings.", color = p.faint, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Say hello right away", color = p.ink, modifier = Modifier.weight(1f)); Toggle(hello) { hello = it } }
    }
}

@Composable
private fun EditBotSheet(b: Bot, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val g = app.gateway
    var title by remember { mutableStateOf(b.display) }
    var desc by remember { mutableStateOf(b.description) }
    var color by remember { mutableStateOf(b.color ?: String.format("#%06X", Bots.colorOf(b) and 0xFFFFFF)) }
    var shape by remember { mutableStateOf(b.shape) }
    var section by remember { mutableStateOf(b.section.orEmpty()) }
    var cur by remember { mutableStateOf(b) }
    var busy by remember { mutableStateOf(false) }
    val described = rememberLoad(b.name) { Bots.describe(g, b.name) }
    var soul by remember(described.data) { mutableStateOf(described.data?.s("soul")) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val jpeg = withContext(Dispatchers.IO) { squareJpeg(ctx, uri) }
                cur = Bots.setPhoto(g, cur, jpeg); toast("Photo updated")
            } catch (e: Exception) { toast(errText(e)) } finally { busy = false }
        }
    }
    FormSheet("Edit ${b.display}", { if (!busy) onDismiss() }, confirm = if (busy) "Saving…" else "Save", onConfirm = {
        if (busy) return@FormSheet
        busy = true
        scope.launch {
            try {
                Bots.updateMeta(g, cur, mapOf("title" to title.trim().ifBlank { null }, "description" to desc.trim(), "color" to color, "shape" to shape,
                    "sectionName" to section.trim().ifBlank { null }, "sectionId" to section.trim().ifBlank { null }?.let { Bots.slug(it) }))
                val s = soul
                if (s != null && s != described.data?.s("soul")) Bots.saveSoul(g, b.name, s)
                runCatching { if (desc.trim() != b.description) g.rpc("profiles.configure", jsonOf("name" to b.name, "description" to desc.trim())) }
                toast("Saved"); onSaved()
            } catch (e: Exception) { toast(errText(e)); busy = false }
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(cur.copy(color = color, shape = shape), 56.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                SoftButton(if (cur.photo) "Change photo" else "Use a photo", Icons.Outlined.Image) {
                    pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (cur.photo) TextButton({ scope.launch { busy = true; try { cur = Bots.setPhoto(g, cur, null) } catch (e: Exception) { toast(errText(e)) } finally { busy = false } } }) { Text("Remove photo", color = p.bad) }
            }
        }
        Field("Name", title, { title = it.take(40) })
        Text("@" + b.handle + " stays the same, so teammates can still reach it.", color = p.faint, style = MaterialTheme.typography.bodySmall)
        Field("What it does", desc, { desc = it }, singleLine = false, minLines = 2)
        if (!cur.photo) LookPicker(color, shape, b.name, { color = it }, { shape = it })
        Field("Section (optional)", section, { section = it.take(30) })
        if (soul == null) LoadingCard() else Field("Personality (SOUL.md)", soul.orEmpty(), { soul = it }, singleLine = false, minLines = 6, mono = true)
        described.data?.o("model")?.let { m -> if (m.s("default").isNotBlank()) Text("Model: " + m.s("default") + (m.sn("provider")?.let { " · $it" } ?: ""), color = p.faint, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Centre-crops a picked image to a 512 px square JPEG, well under the server's 2 MB cap. */
private fun squareJpeg(ctx: android.content.Context, uri: android.net.Uri): ByteArray {
    val src = ctx.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) } ?: throw java.io.IOException("Couldn't read that image")
    val side = minOf(src.width, src.height)
    val crop = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    val out = android.graphics.Bitmap.createScaledBitmap(crop, 512, 512, true)
    return java.io.ByteArrayOutputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it); it.toByteArray() }
}
