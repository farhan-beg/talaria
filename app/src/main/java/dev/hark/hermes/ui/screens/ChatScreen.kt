@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.hark.hermes.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

object ChatNav { val pendingResume = MutableStateFlow<Pair<String, String?>?>(null) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val g = app.gateway
    val scope = rememberCoroutineScope()
    val items by g.items.collectAsStateWithLifecycle()
    val busy by g.busy.collectAsStateWithLifecycle()
    val status by g.status.collectAsStateWithLifecycle()
    val title by g.title.collectAsStateWithLifecycle()
    val model by g.model.collectAsStateWithLifecycle()
    val conn by g.conn.collectAsStateWithLifecycle()
    val connErr by g.connError.collectAsStateWithLifecycle()
    val asks by g.asks.collectAsStateWithLifecycle()
    val loadingSession by g.loadingSession.collectAsStateWithLifecycle()
    val usage by g.usage.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChatItem.User?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var showSubs by remember { mutableStateOf(false) }
    var askBtw by remember { mutableStateOf(false) }
    val yolo by g.yolo.collectAsStateWithLifecycle()
    val attachments by g.attachments.collectAsStateWithLifecycle()
    var uploading by remember { mutableStateOf(0) }
    var hints by remember { mutableStateOf<List<SlashHint>>(emptyList()) }
    var replaceFrom by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            uploading++
            scope.launch {
                try {
                    val (bytes, name, mime) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readUri(ctx, uri) }
                    if (bytes.size > 25 * 1024 * 1024) toast("$name is over 25 MB") else g.attach(bytes, name, mime)
                } catch (e: Exception) { toast(errText(e)) } finally { uploading-- }
            }
        }
    }
    var voiceMode by remember { mutableStateOf(false) }
    val dict = remember { Dictation(ctx) }
    DisposableEffect(Unit) { onDispose { dict.cancel(); dict.destroy() } }
    var dictBase by remember { mutableStateOf("") }
    LaunchedEffect(dict.partial) { if (dict.listening && dict.partial.isNotBlank()) input = (dictBase + " " + dict.partial).trim() }
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterGrant?.invoke() else scope.toast("Microphone permission is needed for voice")
        afterGrant = null
    }
    fun withMic(f: () -> Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) f()
        else { afterGrant = f; micPerm.launch(android.Manifest.permission.RECORD_AUDIO) }
    }
    fun toggleDictation() {
        if (!dict.available) { scope.toast("No speech recognizer on this phone"); return }
        if (dict.listening) dict.stop() else withMic {
            dictBase = input
            dict.onFinal = { t -> if (t.isNotBlank()) input = (dictBase + " " + t).trim() }
            dict.onError = { }
            dict.start()
        }
    }
    // slash suggestions follow the typed command token
    LaunchedEffect(input) {
        val token = input.substringAfterLast(' ')
        if (!input.startsWith("/") || (input.contains(' ') && !token.startsWith("/") && input.count { it == ' ' } > 1)) { hints = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(120)
        runCatching { g.completeSlash(input) }.onSuccess { (h, from) -> hints = h.take(8); replaceFrom = from }.onFailure { hints = emptyList() }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { g.connect() }
    // the chat page can already be alive in the pager, so watch for resumes instead of reading once
    LaunchedEffect(Unit) {
        ChatNav.pendingResume.collect { pr ->
            if (pr != null) {
                ChatNav.pendingResume.value = null
                try { g.resume(pr.first, pr.second) } catch (e: Exception) { toast(errText(e)) }
            }
        }
    }
    LaunchedEffect(items.size, items.lastOrNull()) { if (items.isNotEmpty()) listState.animateScrollToItem(items.size) }

    val topInset = LocalTopInset.current
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Spacer(Modifier.height(topInset))
        // slim status strip: title + connection on the left, new chat on the right
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(when (conn) { Conn.Ready -> p.good; Conn.Connecting -> p.warn; Conn.Failed -> p.bad; else -> p.faint }, pulse = conn == Conn.Connecting)
                    Spacer(Modifier.width(6.dp))
                    Row(Modifier.weight(1f, fill = false).clip(RoundedCornerShape(8.dp)).clickable(enabled = conn == Conn.Ready) { showModels = true }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (conn) { Conn.Ready -> model.ifBlank { "Pick a model" }; Conn.Connecting -> "Connecting…"; Conn.Failed -> connErr ?: "Disconnected"; else -> "Offline" },
                            style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (conn == Conn.Ready) Icon(Icons.Outlined.ExpandMore, "Change model", tint = p.faint, modifier = Modifier.size(16.dp))
                    }
                    usage?.let { u -> if (u.l("total") > 0) Text("  ·  ${humanTokens(u.l("total"))} tok", style = MaterialTheme.typography.bodySmall, color = p.faint) }
                }
            }
            val yoloBg by animateColorAsState(if (yolo) p.warn.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent, label = "yolo")
            Box(Modifier.size(40.dp).clip(CircleShape).background(yoloBg).clickable {
                scope.launch {
                    try { g.setYolo(!yolo); toast(if (g.yolo.value) "YOLO on: actions run without asking" else "YOLO off: Hermes will ask first") }
                    catch (e: Exception) { toast(errText(e)) }
                }
            }, contentAlignment = Alignment.Center) {
                Icon(if (yolo) Icons.Filled.Bolt else Icons.Outlined.Bolt, "YOLO mode", tint = if (yolo) p.warn else p.muted, modifier = Modifier.size(21.dp))
            }
            IconButton({ showHistory = true }) { Icon(Icons.Outlined.Forum, "Conversations", tint = p.muted, modifier = Modifier.size(21.dp)) }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = p.muted, modifier = Modifier.size(21.dp)) }
                DropdownMenu(menu, { menu = false }, containerColor = p.sheet) {
                    val ready = conn == Conn.Ready && g.sessionId.isNotBlank()
                    fun go(f: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) { menu = false; scope.launch { try { f() } catch (e: Exception) { toast(errText(e)) } } }
                    DropdownMenuItem({ Text("Rename chat") }, { menu = false; renaming = true }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, enabled = ready)
                    DropdownMenuItem({ Text("Side question (btw)") }, { menu = false; askBtw = true }, leadingIcon = { Icon(Icons.Outlined.QuestionAnswer, null) }, enabled = ready)
                    DropdownMenuItem({ Text("Subagents") }, { menu = false; showSubs = true }, leadingIcon = { Icon(Icons.Outlined.AccountTree, null) }, enabled = ready)
                    DropdownMenuItem({ Text("Compress context") }, { go { toast(g.compress()) } }, leadingIcon = { Icon(Icons.Outlined.Compress, null) }, enabled = ready && !busy)
                    DropdownMenuItem({ Text("Undo last turn") }, { go { g.runSlash("/undo")?.let { input = it } } }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Undo, null) }, enabled = ready && !busy)
                    DropdownMenuItem({ Text("Branch this chat") }, { go { g.runSlash("/branch")?.let { input = it } } }, leadingIcon = { Icon(Icons.Outlined.CallSplit, null) }, enabled = ready && !busy)
                    HorizontalDivider(color = p.line)
                    DropdownMenuItem({ Text("Fast mode") }, { go { g.setSessionFlag("fast", "toggle"); toast("Fast mode toggled") } }, leadingIcon = { Icon(Icons.Outlined.Speed, null) }, enabled = ready)
                    listOf("low", "medium", "high").forEach { lvl ->
                        DropdownMenuItem({ Text("Reasoning: $lvl") }, { go { g.setSessionFlag("reasoning", lvl); toast("Reasoning set to $lvl") } }, leadingIcon = { Icon(Icons.Outlined.Psychology, null) }, enabled = ready)
                    }
                }
            }
            Box(Modifier.size(40.dp).press { scope.launch { try { g.newChat() } catch (e: Exception) { toast(errText(e)) } } }.glass(CircleShape, p.accentSoft),
                contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, "New chat", tint = p.ink, modifier = Modifier.size(22.dp)) }
        }
        if (conn == Conn.Failed || conn == Conn.Idle) {
            Row(Modifier.padding(horizontal = 18.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.bad.copy(alpha = 0.1f)).clickable {
                g.connect()
                if (g.storedSid.isNotBlank()) scope.launch { runCatching { g.resume(g.storedSid, title) } }
            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, null, tint = p.bad, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Not connected. Tap to reconnect.", color = p.ink, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Box(Modifier.weight(1f)) {
            if (items.isEmpty() && !loadingSession) EmptyChat { input = it }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val lastA = items.indexOfLast { it is ChatItem.Assistant }
                val lastU = items.indexOfLast { it is ChatItem.User }
                itemsIndexed(items, key = { _, it -> it.key }) { i, it ->
                    ChatRow(it, canRegen = i == lastA && i > lastU && !busy,
                        onEdit = { u -> editing = u },
                        onRegen = { scope.launch { g.regenerate() } })
                }
                if (busy) item("__typing") { Typing(status) }
                item("__end") { Spacer(Modifier.height(4.dp)) }
            }
            if (loadingSession) LoadingCard()
        }

        asks.firstOrNull()?.let { AskCard(it) }

        // slash command suggestions
        AnimatedVisibility(hints.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(horizontal = 14.dp).fillMaxWidth().heightIn(max = 280.dp)
                .glass(RoundedCornerShape(20.dp), if (p.dark) p.sheet.copy(alpha = 0.97f) else androidx.compose.ui.graphics.Color.White)
                .verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                hints.forEach { h ->
                    Row(Modifier.fillMaxWidth().clickable {
                        input = input.take(replaceFrom.coerceIn(0, input.length)) + h.text.let { if (replaceFrom > 0 && it.startsWith("/") && input.getOrNull(replaceFrom - 1) == '/') it.drop(1) else it } + " "
                        hints = emptyList()
                    }.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (h.skill) Icons.Outlined.AutoAwesome else Icons.Outlined.Terminal, null, tint = p.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(h.display, color = p.ink, fontFamily = Mono, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        if (h.meta.isNotBlank()) {
                            Spacer(Modifier.width(10.dp))
                            Text(h.meta, color = p.faint, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // staged attachments
        if (attachments.isNotEmpty() || uploading > 0) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                attachments.forEach { a ->
                    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(p.accentSoft).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (a.kind) { "image" -> Icons.Outlined.Image; "pdf" -> Icons.Outlined.PictureAsPdf; else -> Icons.Outlined.Description }, null, tint = p.ink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(a.name + if (a.pages > 0) " · ${a.pages}p" else "", color = p.ink, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        Box(Modifier.size(26.dp).clip(CircleShape).clickable { scope.launch { g.detach(a) } }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Close, "Remove", tint = p.muted, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                if (uploading > 0) Row(Modifier.clip(RoundedCornerShape(14.dp)).background(p.accentSoft).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = p.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("Uploading…", color = p.muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // composer — a floating glass pill with a bright send key
        Row(
            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp, top = 8.dp).navigationBarsPadding()
                .shadow(20.dp, RoundedCornerShape(26.dp), ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.3f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.3f))
                .glass(RoundedCornerShape(26.dp), if (p.dark) p.sheet.copy(alpha = 0.96f) else androidx.compose.ui.graphics.Color.White)
                .padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { picker.launch("*/*") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AttachFile, "Attach", tint = p.muted, modifier = Modifier.size(22.dp))
            }
            Box(Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (input.isEmpty()) Text("Message or /command", color = p.faint, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp))
                androidx.compose.foundation.text.BasicTextField(
                    input, { input = it }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink, fontSize = 17.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(p.ink), maxLines = 6, modifier = Modifier.fillMaxWidth(),
                )
            }
            val canSend = (input.isNotBlank() || attachments.isNotEmpty()) && uploading == 0
            val micPulse by rememberInfiniteTransition(label = "mic").animateFloat(1f, 1.18f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "mp")
            Box(Modifier.size(48.dp).graphicsLayer { val k = if (dict.listening) micPulse * (1f + dict.level * 0.15f) else 1f; scaleX = k; scaleY = k }
                .clip(CircleShape).background(if (dict.listening) p.bad.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { toggleDictation() }, contentAlignment = Alignment.Center) {
                Icon(if (dict.listening) Icons.Outlined.MicNone else Icons.Outlined.Mic, "Dictate", tint = if (dict.listening) p.bad else p.muted, modifier = Modifier.size(22.dp))
            }
            val btn by animateColorAsState(p.accent, label = "send")
            Box(
                Modifier.size(48.dp).press {
                        if (busy && !canSend) scope.launch { g.interrupt() }
                        else if (!canSend) withMic { if (dict.listening) dict.cancel(); voiceMode = true }
                        else {
                            val t = input.trim(); input = ""; hints = emptyList()
                            scope.launch {
                                if (t.startsWith("/") && attachments.isEmpty()) {
                                    try { g.runSlash(t)?.let { input = it } } catch (e: Exception) { toast(errText(e)) }
                                } else g.send(t)
                            }
                        }
                    }.clip(CircleShape).background(btn).sheen(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(when { canSend -> 1; busy -> 0; else -> 2 }, transitionSpec = { (fadeIn(tween(160)) + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.6f)) }, label = "key") { k ->
                    Icon(when (k) { 0 -> Icons.Outlined.Stop; 1 -> Icons.AutoMirrored.Outlined.Send; else -> Icons.Outlined.GraphicEq },
                        when (k) { 0 -> "Stop"; 1 -> "Send"; else -> "Voice mode" }, tint = p.accentInk, modifier = Modifier.size(22.dp))
                }
            }
        }
    }

    if (voiceMode) VoiceMode { voiceMode = false }
    editing?.let { u ->
        TextPrompt("Edit message", u.raw, "Send", note = "Everything after this message is replaced.", onDismiss = { editing = null }) { t ->
            editing = null; scope.launch { g.edit(u, t) }
        }
    }
    if (renaming) TextPrompt("Rename chat", title, "Save", onDismiss = { renaming = false }) { t ->
        renaming = false; scope.launch { try { g.rename(t) } catch (e: Exception) { toast(errText(e)) } }
    }
    if (askBtw) TextPrompt("Side question", "", "Ask", note = "Answered from this chat's context without adding to it.", onDismiss = { askBtw = false }) { t ->
        askBtw = false; scope.launch { try { g.btw(t) } catch (e: Exception) { toast(errText(e)) } }
    }
    if (showSubs) SubagentSheet { showSubs = false }
    if (showModels) ModelSheet { showModels = false }

    if (showHistory) {
        ModalBottomSheet({ showHistory = false }, containerColor = p.sheet) {
            val sessions = rememberLoad { app.api.obj(sessionsUrl(50)) }
            val visible = rememberSessionFilter()
            Text("Conversations", style = MaterialTheme.typography.titleLarge, color = p.ink, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                loadState(sessions) { d ->
                    items(d.a("sessions").objs().filter(visible), key = { it.s("id") }) { s ->
                        SessionRow(s) {
                            showHistory = false
                            scope.launch { try { g.resume(s.s("id"), s.sn("title")) } catch (e: Exception) { toast(errText(e)) } }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EmptyChat(pick: (String) -> Unit) {
    val p = LocalPalette.current
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val greet = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Up late?" }
    val ideas = listOf("Summarize what you did today", "Check my server's disk and memory", "Draft a cron job that sends me news at 9am")
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(greet, style = MaterialTheme.typography.displaySmall, color = p.ink, modifier = Modifier.padding(horizontal = 6.dp))
        Text("Where should Hermes fly next?", style = MaterialTheme.typography.displaySmall, color = p.faint, modifier = Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(24.dp))
        ideas.forEach { text ->
            Text(text, color = p.ink, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 5.dp).entrance(120 + 70 * ideas.indexOf(text)).press { pick(text) }.glass(RoundedCornerShape(26.dp)).padding(horizontal = 20.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem, canRegen: Boolean = false, onEdit: (ChatItem.User) -> Unit = {}, onRegen: () -> Unit = {}) {
    val p = LocalPalette.current
    val clip = LocalClipboardManager.current
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            var open by remember { mutableStateOf(false) }
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            Box(Modifier.padding(start = 48.dp)) {
                Text(item.text, color = p.userInk, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
                    modifier = Modifier.entrance().clip(RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp))
                        .combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); open = true })
                        .glass(RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp), p.userBubble).padding(horizontal = 20.dp, vertical = 15.dp))
                DropdownMenu(open, { open = false }, containerColor = p.sheet) {
                    if (item.ordinal >= 0) DropdownMenuItem({ Text("Edit") }, { open = false; onEdit(item) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                    DropdownMenuItem({ Text("Copy") }, { open = false; clip.setText(AnnotatedString(item.raw)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                }
            }
        }
        is ChatItem.Assistant -> Column(Modifier.padding(end = 36.dp).entrance().animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium))
            .glass(RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp)).padding(horizontal = 20.dp, vertical = 16.dp)) {
            if (item.reasoning.isNotBlank()) {
                var open by remember { mutableStateOf(false) }
                Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Psychology, null, tint = p.muted, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (item.streaming && item.text.isBlank()) "Thinking…" else "Thought process", color = p.muted, style = MaterialTheme.typography.labelMedium)
                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = p.muted, modifier = Modifier.size(16.dp))
                }
                AnimatedVisibility(open) {
                    Text(item.reasoning, color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 22.dp, bottom = 6.dp))
                }
            }
            if (item.text.isNotBlank()) {
                SelectionContainer { Markdown(item.text) }
                if (!item.streaming) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ContentCopy, "Copy", tint = p.faint, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { clip.setText(AnnotatedString(item.text)) }.padding(6.dp))
                    if (canRegen) Icon(Icons.Outlined.Refresh, "Regenerate", tint = p.faint, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onRegen() }.padding(6.dp))
                }
            }
            val nerd by app.store.nerd.collectAsStateWithLifecycle()
            if (nerd && item.firstMs > 0) NerdStats(item)
        }
        is ChatItem.Tool -> {
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().entrance().press { open = !open }.glass(RoundedCornerShape(22.dp))
                .animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)).padding(horizontal = 16.dp, vertical = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.done) Icon(Icons.Outlined.CheckCircle, null, tint = p.good, modifier = Modifier.size(18.dp))
                    else CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = p.ink)
                    Spacer(Modifier.width(10.dp))
                    Text(item.name, color = p.ink, style = MaterialTheme.typography.labelLarge, fontFamily = Mono)
                    if (item.preview.isNotBlank()) Text("  " + item.preview, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    if (item.duration > 0) Text(String.format("%.1fs", item.duration), color = p.faint, style = MaterialTheme.typography.labelSmall)
                }
                AnimatedVisibility(open && (item.summary.isNotBlank() || item.preview.isNotBlank())) {
                    Column(Modifier.padding(top = 8.dp)) {
                        if (item.preview.isNotBlank()) CodeBlock(item.preview, 160.dp)
                        if (item.summary.isNotBlank()) Text(item.summary, color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        is ChatItem.Output -> Column(Modifier.fillMaxWidth().entrance().clip(RoundedCornerShape(16.dp)).background(p.accentSoft.copy(alpha = 0.6f)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, null, tint = p.accent, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                Text(item.command, color = p.accent, fontFamily = Mono, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer { Text(item.text, color = p.ink, fontFamily = Mono, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp)) }
        }
        is ChatItem.Notice -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(item.text, color = if (item.error) p.bad else p.muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clip(RoundedCornerShape(50)).background((if (item.error) p.bad else p.muted).copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun Typing(status: String) {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "t")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.padding(end = 4.dp).size(7.dp).clip(CircleShape).background(p.accent.copy(alpha = a)))
        }
        if (status.isNotBlank()) Text(" $status", color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AskCard(ask: ServerAsk) {
    val p = LocalPalette.current
    val g = app.gateway
    val pr = ask.params
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(CardShape).background(p.card)
        .border(1.5.dp, p.warn.copy(alpha = 0.6f), CardShape).padding(16.dp)) {
        when (ask.method) {
            "approval" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, null, tint = p.warn); Spacer(Modifier.width(8.dp))
                    Text("Approve this command?", style = MaterialTheme.typography.titleSmall, color = p.ink)
                }
                if (pr.s("description").isNotBlank()) Text(pr.s("description"), color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(8.dp)); CodeBlock(pr.s("command"), 160.dp); Spacer(Modifier.height(10.dp))
                val choices = pr.a("choices").strs().ifEmpty { listOf("once", "session", "always", "deny") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    choices.forEach { c ->
                        SoftButton(when (c) { "once" -> "Allow once"; "session" -> "This session"; "always" -> "Always"; "deny" -> "Deny"; else -> c },
                            primary = c == "once", danger = c == "deny") { g.answer(ask, jsonOf("choice" to c)) }
                    }
                }
            }
            "clarify" -> {
                val qs = pr.a("questions").objs()
                val answers = remember(ask) { mutableStateMapOf<String, String>() }
                Text("Hermes has a question", style = MaterialTheme.typography.titleSmall, color = p.ink)
                qs.forEach { q ->
                    Spacer(Modifier.height(8.dp))
                    Text(q.s("question"), color = p.ink, style = MaterialTheme.typography.bodyMedium)
                    val ch = q.a("choices").strs()
                    if (ch.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        ChipRow(ch.map { it to it }, answers[q.s("qid")] ?: "") { answers[q.s("qid")] = it }
                    }
                    Spacer(Modifier.height(6.dp))
                    Field("Answer", answers[q.s("qid")] ?: "", { answers[q.s("qid")] = it })
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("Send", primary = true) { g.answer(ask, jsonOf("answers" to qs.associate { it.s("qid") to answers[it.s("qid")] })) }
                    SoftButton("Skip") { g.answer(ask, JsonObject(emptyMap())) }
                }
            }
            else -> {
                var v by remember(ask) { mutableStateOf("") }
                val label = when (ask.method) { "sudo" -> "Sudo password"; "secret" -> pr.s("prompt").ifBlank { pr.s("env_var") }; else -> ask.method }
                Text(label, style = MaterialTheme.typography.titleSmall, color = p.ink)
                if (ask.method == "sudo") CodeBlock(pr.s("command"), 120.dp)
                Spacer(Modifier.height(8.dp))
                Field("Value", v, { v = it }, secret = ask.method in listOf("sudo", "secret", "vault.unlock_prompt"))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("Submit", primary = true) { g.answer(ask, jsonOf("value" to v)) }
                    SoftButton("Decline") { g.answer(ask, jsonOf("value" to "")) }
                }
            }
        }
    }
}


/** Optional "stats for nerds" strip under a reply: speed, latency, size, time. */
@Composable
private fun NerdStats(a: ChatItem.Assistant) {
    val p = LocalPalette.current
    // tick while streaming so the live rate keeps moving between deltas
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (a.streaming) LaunchedEffect(a.key) { while (true) { kotlinx.coroutines.delay(250); now = System.currentTimeMillis() } }
    val tps = a.tps(now)
    val ttft = if (a.startMs > 0) (a.firstMs - a.startMs) / 1000.0 else 0.0
    val total = ((if (a.endMs > 0) a.endMs else now) - (if (a.startMs > 0) a.startMs else a.firstMs)) / 1000.0
    val tok = (if (a.exact) "" else "~") + humanTokens(a.tokens)
    val speedColor by animateColorAsState(when { tps >= 60 -> p.good; tps >= 20 -> p.accent; tps > 0 -> p.warn; else -> p.faint }, label = "tps")
    Row(
        Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(p.accentSoft.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Bolt, null, tint = speedColor, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        val stat = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono, letterSpacing = 0.sp)
        Text(if (tps > 0) "%.1f tok/s".format(tps) else "— tok/s", color = speedColor, style = stat)
        Text("  ·  $tok tok  ·  TTFT %.2fs  ·  %.1fs".format(ttft, total), color = p.muted, style = stat, maxLines = 1)
    }
}


/** Reads a picked file's bytes, display name and mime type. */
internal fun readUri(ctx: android.content.Context, uri: android.net.Uri): Triple<ByteArray, String, String> {
    val cr = ctx.contentResolver
    var name = uri.lastPathSegment ?: "file"
    cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.let { name = it }
    }
    val mime = cr.getType(uri) ?: "application/octet-stream"
    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    return Triple(bytes, name, mime)
}

/** Switch the model for this chat only, grouped by provider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(onClose: () -> Unit) {
    val p = LocalPalette.current
    val g = app.gateway
    val scope = rememberCoroutineScope()
    val current by g.model.collectAsStateWithLifecycle()
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var switching by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { try { data = g.modelOptions() } catch (e: Exception) { err = errText(e) } }
    ModalBottomSheet(onClose, containerColor = p.sheet) {
        Text("Model for this chat", style = MaterialTheme.typography.titleLarge, color = p.ink, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Only this conversation changes. Your default stays the same.", style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search models") }, singleLine = true, shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        val rows = data?.a("providers")?.objs().orEmpty().mapNotNull { row ->
            val models = row.a("models").strs().filter { query.isBlank() || it.contains(query, true) || row.s("name").contains(query, true) }
            if (models.isEmpty()) null else row to models
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            when {
                err != null -> item { Text(err!!, color = p.bad, modifier = Modifier.padding(12.dp)) }
                data == null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) } }
                rows.isEmpty() -> item { Text("No models match.", color = p.muted, modifier = Modifier.padding(12.dp)) }
            }
            rows.forEach { (row, models) ->
                item(row.s("slug") + "_h") {
                    Text(row.s("name").ifBlank { row.s("slug") }, color = p.faint, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 4.dp))
                }
                items(models, key = { row.s("slug") + "/" + it }) { m ->
                    val sel = m == current || m.substringAfterLast('/') == current.substringAfterLast('/')
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (sel) p.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(enabled = switching == null) {
                            switching = m
                            scope.launch {
                                try { g.setModel(m, row.s("slug")); onClose() } catch (e: Exception) { toast(errText(e)) } finally { switching = null }
                            }
                        }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m, color = p.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            switching == m -> CircularProgressIndicator(Modifier.size(16.dp), color = p.accent, strokeWidth = 2.dp)
                            sel -> Icon(Icons.Outlined.Check, null, tint = p.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}


@Composable
private fun TextPrompt(title: String, initial: String, confirm: String, note: String? = null, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val p = LocalPalette.current
    var t by remember { mutableStateOf(initial) }
    AlertDialog(onDismiss, containerColor = p.sheet,
        title = { Text(title, color = p.ink) },
        text = {
            Column {
                note?.let { Text(it, color = p.muted, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(10.dp)) }
                OutlinedTextField(t, { t = it }, shape = RoundedCornerShape(16.dp), maxLines = 8, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ if (t.isNotBlank()) onDone(t.trim()) }, enabled = t.isNotBlank()) { Text(confirm, color = p.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = p.muted) } })
}

/** Live delegate_task children of this chat: watch, steer or stop them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubagentSheet(onClose: () -> Unit) {
    val p = LocalPalette.current
    val g = app.gateway
    val scope = rememberCoroutineScope()
    var subs by remember { mutableStateOf<List<JsonObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var openId by remember { mutableStateOf<String?>(null) }
    var tail by remember { mutableStateOf("") }
    var steer by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            try { subs = g.subagents(); err = null } catch (e: Exception) { err = errText(e) }
            openId?.let { id -> runCatching { tail = g.subagentTail(id) } }
            kotlinx.coroutines.delay(2500)
        }
    }
    ModalBottomSheet(onClose, containerColor = p.sheet) {
        Text("Subagents", style = MaterialTheme.typography.titleLarge, color = p.ink, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                err != null -> item { Text(err!!, color = p.bad) }
                subs == null -> item { Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) } }
                subs!!.isEmpty() -> item { Text("No subagents running in this chat.", color = p.muted, modifier = Modifier.padding(8.dp)) }
            }
            items(subs.orEmpty(), key = { it.s("subagent_id") }) { sa ->
                val id = sa.s("subagent_id")
                val isOpen = openId == id
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.accentSoft.copy(alpha = 0.5f))
                    .clickable { openId = if (isOpen) null else id; tail = "" ; if (!isOpen) scope.launch { runCatching { tail = g.subagentTail(id) } } }.padding(14.dp)) {
                    Text(sa.s("goal").ifBlank { id }, color = p.ink, style = MaterialTheme.typography.bodyMedium, maxLines = if (isOpen) 6 else 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(sa.s("model"), "depth ${sa.l("depth")}").filter { it.isNotBlank() }.joinToString(" · "), color = p.faint, style = MaterialTheme.typography.labelSmall)
                    AnimatedVisibility(isOpen) {
                        Column(Modifier.padding(top = 10.dp)) {
                            Text(tail.takeLast(3000).ifBlank { "No output yet." }, color = p.muted, fontFamily = Mono, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState(), reverseScrolling = true))
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(steer, { steer = it }, placeholder = { Text("Steer it…") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                                IconButton({ val t = steer; steer = ""; scope.launch { try { g.steerSubagent(id, t); toast("Sent") } catch (e: Exception) { toast(errText(e)) } } }, enabled = steer.isNotBlank()) {
                                    Icon(Icons.AutoMirrored.Outlined.Send, "Steer", tint = p.accent)
                                }
                                IconButton({ scope.launch { try { g.stopSubagent(id); toast("Stopping") } catch (e: Exception) { toast(errText(e)) } } }) {
                                    Icon(Icons.Outlined.StopCircle, "Stop", tint = p.bad)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
