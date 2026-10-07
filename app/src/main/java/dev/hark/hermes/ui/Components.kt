package dev.hark.hermes.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import dev.hark.hermes.data.ApiException
import dev.hark.hermes.data.AuthExpired
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// ── liquid glass + motion ─────────────────────────────────────────────────────
/**
 * Frosted "liquid glass" surface: a translucent fill, a soft top-down sheen, and a
 * bright rim along the upper edge that fades out toward the bottom, the way light
 * catches the edge of a glass pebble. Falls back to a flat card when glass is off.
 */
fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape, fill: Color? = null): Modifier = composed {
    val p = LocalPalette.current
    val look = LocalLook.current
    val base = fill ?: p.card
    if (!look.glass) return@composed this.clip(shape).background(base).border(1.dp, p.line, shape)
    val hi = if (p.dark) Color.White else Color.White
    this.clip(shape).background(base)
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(hi.copy(alpha = if (p.dark) 0.075f else 0.55f), hi.copy(alpha = 0f)), endY = 260f))
        .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(hi.copy(alpha = if (p.dark) 0.20f else 0.95f), p.line.copy(alpha = p.line.alpha * 0.6f), p.line)), shape)
}

/** A highlight that sits on top of an accent-filled pill so it reads as glass, not paint. */
fun Modifier.sheen(shape: androidx.compose.ui.graphics.Shape): Modifier = composed {
    if (!LocalLook.current.glass) return@composed this
    this.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0f))), shape)
}

/** Click with a springy squish instead of a ripple. */
fun Modifier.press(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val motion = LocalLook.current.motion
    val scale by animateFloatAsState(if (pressed && motion) 0.955f else 1f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "press")
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

/** Float in on first appearance: rises a little, fades up, settles with a spring. */
fun Modifier.entrance(delayMs: Int = 0): Modifier = composed {
    if (!LocalLook.current.motion) return@composed this
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        a.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow))
    }
    this.graphicsLayer {
        val v = a.value
        alpha = v.coerceIn(0f, 1f); translationY = (1f - v) * 46f
        val sc = 0.965f + 0.035f * v; scaleX = sc; scaleY = sc
    }
}

// ── global toast ──────────────────────────────────────────────────────────────
object Toaster { val host = SnackbarHostState() }

fun CoroutineScope.toast(msg: String) = launch { Toaster.host.currentSnackbarData?.dismiss(); Toaster.host.showSnackbar(msg) }

fun errText(e: Throwable): String = when (e) {
    is AuthExpired -> "Session expired. Sign in again."
    is ApiException -> e.message ?: "Request failed"
    is java.net.UnknownHostException -> "Can't reach the server"
    is java.net.ConnectException -> "Connection refused"
    is java.net.SocketTimeoutException -> "The server took too long"
    else -> e.message ?: e.javaClass.simpleName
}

/** Run a mutating call with toast feedback, then optionally reload. */
fun CoroutineScope.act(done: String? = null, after: (() -> Unit)? = null, block: suspend () -> Unit) = launch {
    try { block(); done?.let { toast(it) }; after?.invoke() } catch (e: Exception) { toast(errText(e)) }
}

// ── loading helper ────────────────────────────────────────────────────────────
class Load<T>(val data: T?, val error: String?, val loading: Boolean, val reload: () -> Unit)

@Composable
fun <T> rememberLoad(vararg keys: Any?, poll: Long = 0, fetch: suspend () -> T): Load<T> {
    var data by remember(*keys) { mutableStateOf<T?>(null) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var loading by remember(*keys) { mutableStateOf(true) }
    var tick by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, tick) {
        loading = true
        try { data = fetch(); error = null } catch (e: Exception) { error = errText(e) }
        loading = false
        if (poll > 0) while (true) {
            delay(poll)
            try { data = fetch(); error = null } catch (_: Exception) {}
        }
    }
    return Load(data, error, loading, reload = { tick++ })
}

// ── page scaffold ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val p = LocalPalette.current
    val topInset = LocalTopInset.current
    Box(Modifier.fillMaxSize()) {
        val list: @Composable () -> Unit = {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + topInset, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("__header") {
                    Column(Modifier.padding(top = if (onBack != null) 0.dp else 22.dp, bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (onBack != null) {
                                IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = p.ink)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            actions()
                        }
                        Text(title, style = MaterialTheme.typography.headlineMedium, color = p.ink)
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                content()
            }
        }
        if (onRefresh != null) PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize().statusBarsPadding()) { list() }
        else Box(Modifier.fillMaxSize().statusBarsPadding()) { list() }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp)) { fab() }
    }
}

fun <T> LazyListScope.loadState(l: Load<T>, body: LazyListScope.(T) -> Unit) {
    val d = l.data
    when {
        d != null -> body(d)
        l.error != null -> item { ErrorCard(l.error, l.reload) }
        else -> item { LoadingCard() }
    }
}

@Composable
fun HCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth().entrance()
            .then(if (onClick != null) Modifier.press(onClick = onClick) else Modifier)
            .glass(CardShape)
            .padding(padding),
        content = content,
    )
}
typealias Dp = androidx.compose.ui.unit.Dp

@Composable
fun LoadingCard() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp), color = LocalPalette.current.accent)
    }
}

@Composable
fun ErrorCard(msg: String, retry: () -> Unit) {
    val p = LocalPalette.current
    HCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, null, tint = p.bad)
            Spacer(Modifier.width(10.dp))
            Text(msg, color = p.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = retry) { Text("Retry") }
        }
    }
}

@Composable
fun EmptyCard(icon: ImageVector, title: String, body: String) {
    val p = LocalPalette.current
    HCard(padding = 28.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(p.accentSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = p.accent)
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = p.ink)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalPalette.current.muted,
        modifier = modifier.padding(start = 4.dp, top = 10.dp))
}

@Composable
fun Pill(text: String, color: Color = LocalPalette.current.accent, filled: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, maxLines = 1,
        color = if (filled) Color.White else color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (filled) color else color.copy(alpha = 0.13f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
fun Dot(color: Color, pulse: Boolean = false) {
    val a by if (pulse) rememberInfiniteTransition(label = "p").animateFloat(0.35f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a") else remember { mutableFloatStateOf(1f) }
    Box(Modifier.size(9.dp).clip(CircleShape).background(color.copy(alpha = a)))
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, icon: ImageVector? = null) {
    val p = LocalPalette.current
    Column(modifier.entrance().glass(CardShape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = p.accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
            Text(label, style = MaterialTheme.typography.labelMedium, color = p.muted, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1)
    }
}

@Composable
fun ListRow(
    title: String, subtitle: String? = null, icon: ImageVector? = null, iconTint: Color? = null,
    trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.press(onClick = onClick) else Modifier).clip(RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background((iconTint ?: p.accent).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint ?: p.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
fun KV(k: String, v: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(k, color = p.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
        Text(v, color = p.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Meter(label: String, percent: Double, detail: String) {
    val p = LocalPalette.current
    val c = when { percent >= 90 -> p.bad; percent >= 75 -> p.warn; else -> p.accent }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row { Text(label, style = MaterialTheme.typography.labelLarge, color = p.ink, modifier = Modifier.weight(1f)); Text(detail, style = MaterialTheme.typography.bodySmall, color = p.muted) }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
            color = c, trackColor = p.cardAlt, drawStopIndicator = {},
        )
    }
}

@Composable
fun SoftButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, danger: Boolean = false, primary: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bg = when { primary -> p.accent; danger -> p.bad.copy(alpha = 0.12f); else -> p.cardAlt }
    val fg = when { primary -> p.accentInk; danger -> p.bad; else -> p.ink }
    Row(
        modifier.press(onClick = onClick).clip(RoundedCornerShape(16.dp)).background(bg)
            .then(if (primary) Modifier.sheen(RoundedCornerShape(16.dp)) else Modifier).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val sel = id == selected
            val bg by animateColorAsState(if (sel) p.accent else p.card, spring(stiffness = Spring.StiffnessMediumLow), label = "chip")
            val fg by animateColorAsState(if (sel) p.accentInk else p.ink, spring(stiffness = Spring.StiffnessMediumLow), label = "chipfg")
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg,
                modifier = Modifier.press { onSelect(id) }.glass(RoundedCornerShape(50), bg)
                    .padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    val p = LocalPalette.current
    OutlinedTextField(
        value, onChange, placeholder = { Text(hint, color = p.faint) }, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = p.muted) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Outlined.Close, null, tint = p.muted) } },
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = p.card, focusedContainerColor = p.card, unfocusedBorderColor = p.line),
    )
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, secret: Boolean = false, singleLine: Boolean = true, minLines: Int = 1, mono: Boolean = false) {
    val p = LocalPalette.current
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = singleLine, minLines = minLines,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        textStyle = if (mono) LocalTextStyle.current.copy(fontFamily = Mono, fontSize = 13.sp) else LocalTextStyle.current,
        shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = p.card, focusedContainerColor = p.card, unfocusedBorderColor = p.line),
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String = "Confirm", danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.sheet, shape = RoundedCornerShape(26.dp),
        title = { Text(title) }, text = { Text(body, color = p.muted) },
        confirmButton = { TextButton({ onConfirm(); onDismiss() }) { Text(confirm, color = if (danger) p.bad else p.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = p.muted) } },
    )
}

@Composable
fun FormSheet(title: String, onDismiss: () -> Unit, confirm: String = "Save", onConfirm: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.sheet, shape = RoundedCornerShape(26.dp),
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, color = p.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = p.muted) } },
    )
}

@Composable
fun CodeBlock(text: String, maxHeight: Dp = 420.dp, lang: String = "", header: Boolean = false) {
    val p = LocalPalette.current
    if (header) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.code)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(lang.ifBlank { "code" }, color = p.faint, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.weight(1f))
                CopyButton(text); ShareButton(text)
            }
            SelectionContainer {
                Box(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Text(text, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, color = p.ink)
                }
            }
        }
        return
    }
    SelectionContainer {
        Box(Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(14.dp)).background(p.code)
            .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(text, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, color = p.ink)
        }
    }
}

// ── tiny markdown renderer ────────────────────────────────────────────────────
@Composable
fun Markdown(text: String, color: Color = LocalPalette.current.ink, modifier: Modifier = Modifier, body: TextStyle = MaterialTheme.typography.bodyLarge) {
    val p = LocalPalette.current
    val blocks = remember(text) { mdBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Md.Code -> if (b.lang in COPY_LANGS) CopyCard(b.text.trimEnd(), b.lang) else CodeBlock(b.text.trimEnd(), 360.dp, b.lang, header = true)
                is Md.Table -> MdTable(b, color)
                is Md.Heading -> Text(inline(b.text, p), style = when (b.level) { 1 -> MaterialTheme.typography.titleLarge; 2 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.titleSmall }, color = color)
                is Md.Bullet -> Row(Modifier.padding(start = (b.indent * 14).dp), verticalAlignment = Alignment.Top) {
                    when (b.check) {
                        null -> Text(if (b.marker.isEmpty()) (if (b.indent % 2 == 0) "•" else "◦") else b.marker, color = p.muted, style = body, modifier = Modifier.width(if (b.marker.length > 2) 30.dp else 18.dp))
                        else -> Icon(if (b.check) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, tint = if (b.check) p.accent else p.muted, modifier = Modifier.padding(top = 3.dp, end = 6.dp).size(18.dp))
                    }
                    Text(inline(b.text, p), color = if (b.check == true) p.muted else color, style = body.copy(textDecoration = if (b.check == true) androidx.compose.ui.text.style.TextDecoration.LineThrough else null))
                }
                is Md.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(p.line)); Spacer(Modifier.width(10.dp))
                    Markdown(b.text, p.muted, body = body)
                }
                is Md.Para -> Text(inline(b.text, p), color = color, style = body)
                Md.Rule -> HorizontalDivider(color = p.line)
                is Md.Img -> dev.hark.hermes.ui.screens.ChatImage(b.src)
                is Md.FileRef -> dev.hark.hermes.ui.screens.FileChips(listOf(b.path))
            }
        }
    }
}

private sealed interface Md {
    data class Code(val text: String, val lang: String = "") : Md
    data class Table(val head: List<String>, val rows: List<List<String>>) : Md
    data class Heading(val level: Int, val text: String) : Md
    data class Bullet(val marker: String, val text: String, val indent: Int, val check: Boolean? = null) : Md
    data class Quote(val text: String) : Md
    data class Para(val text: String) : Md
    data class Img(val src: String) : Md
    data class FileRef(val path: String) : Md
    data object Rule : Md
}

private fun mdBlocks(src: String): List<Md> {
    val out = mutableListOf<Md>()
    val lines = src.replace("\r", "").split('\n')
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Md.Para(para.toString().trim()); para.clear() }
    while (i < lines.size) {
        val raw = lines[i]; val l = raw.trimStart()
        when {
            l.startsWith("```") || l.startsWith("~~~") -> {
                flush(); val fence = l.take(3); val lang = l.drop(3).trim().substringBefore(' ').lowercase(); val sb = StringBuilder(); i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) { sb.append(lines[i]).append('\n'); i++ }
                out += Md.Code(sb.toString(), lang)
            }
            Regex("^#{1,6} ").containsMatchIn(l) -> { flush(); val n = l.takeWhile { it == '#' }.length; out += Md.Heading(n, l.drop(n).trim().trimEnd('#').trim()) }
            l.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) || l.replace(" ", "").matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> { flush(); out += Md.Rule }
            para.isNotEmpty() && Regex("^(=+|-+)\\s*$").matches(l) -> {   // setext heading
                val t = para.toString().trim(); para.clear(); out += Md.Heading(if (l.startsWith("=")) 1 else 2, t)
            }
            Regex("^[-*+] \\[[ xX]\\] ").containsMatchIn(l) -> { flush(); out += Md.Bullet("", l.drop(6), (raw.length - l.length) / 2, l[3] != ' ') }
            Regex("^[-*+] ").containsMatchIn(l) -> { flush(); out += Md.Bullet("", l.drop(2), (raw.length - l.length) / 2) }
            Regex("^\\d+[.)] ").containsMatchIn(l) -> { flush(); val m = l.substringBefore(' '); out += Md.Bullet(m, l.substringAfter(' '), (raw.length - l.length) / 2) }
            l.startsWith(">") -> {
                flush(); val sb = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) { sb.append(lines[i].trimStart().removePrefix(">").removePrefix(" ")).append('\n'); i++ }
                i--; out += Md.Quote(sb.toString().trimEnd())
            }
            Regex("^!\\[[^\\]]*\\]\\(([^)\\s]+)[^)]*\\)$").matches(l.trim()) -> { flush(); out += Md.Img(Regex("\\(([^)\\s]+)").find(l)!!.groupValues[1]) }
            l.trim().startsWith("MEDIA:") -> {
                flush(); val src = l.trim().removePrefix("MEDIA:").trim().trim('`', '"', '\'')
                if (src.isNotBlank()) out += if (dev.hark.hermes.ui.screens.isImageName(src) || src.startsWith("http")) Md.Img(src) else Md.FileRef(src)
            }
            l.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^\\|?\\s*:?-{2,}.*")) -> {
                flush()
                fun cells(r: String) = r.trim().trim('|').split('|').map { it.trim() }
                val head = cells(l); i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += cells(lines[i]); i++ }
                i--
                out += Md.Table(head, rows)
            }
            l.isBlank() -> flush()
            para.isEmpty() && raw.startsWith("  ") && out.lastOrNull() is Md.Bullet && lines.getOrNull(i - 1)?.isNotBlank() == true -> {
                val b = out.removeAt(out.size - 1) as Md.Bullet; out += b.copy(text = b.text + "\n" + l)
            }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(raw) }
        }
        i++
    }
    flush()
    return out
}

private val FILE_AT = Regex("""/(?:[\w.\-@+~]+/)+[\w.\-@+~]+\.[A-Za-z0-9]{1,8}(?![\w/])""")
private fun isFilePath(t: String) = (t.startsWith("/") || t.startsWith("~/")) && !t.contains(' ') && FILE_AT.matches(t.replaceFirst("~", "/~"))

/** Web links open in the browser; paths on Hermes' machine open in the in-app file viewer. */
private inline fun androidx.compose.ui.text.AnnotatedString.Builder.linked(target: String, p: Palette, mono: Boolean = false, body: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit) {
    val style = androidx.compose.ui.text.TextLinkStyles(SpanStyle(color = p.accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontFamily = if (mono) Mono else null, background = if (mono) p.code else Color.Unspecified))
    val ann: androidx.compose.ui.text.LinkAnnotation = when {
        target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:") -> androidx.compose.ui.text.LinkAnnotation.Url(target, style)
        else -> androidx.compose.ui.text.LinkAnnotation.Clickable(target, style) { dev.hark.hermes.ui.screens.FileOpen.open(target.removePrefix("file://")) }
    }
    withLink(ann) { body() }
}

private fun inline(s: String, p: Palette): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s[i] == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!|~<>" -> { append(s[i + 1]); i += 2 }
            s.startsWith("***", i) && s.indexOf("***", i + 3) > i + 3 -> { val e = s.indexOf("***", i + 3); withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)) { append(inline(s.substring(i + 3, e), p)) }; i = e + 3 }
            s.startsWith("~~", i) && s.indexOf("~~", i + 2) > i + 2 -> { val e = s.indexOf("~~", i + 2); withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) { append(inline(s.substring(i + 2, e), p)) }; i = e + 2 }
            s.startsWith("__", i) && (i == 0 || !s[i - 1].isLetterOrDigit()) && s.indexOf("__", i + 2) > i + 2 -> { val e = s.indexOf("__", i + 2); withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(s.substring(i + 2, e), p)) }; i = e + 2 }
            s[i] == '<' && Regex("^<(https?://[^>\\s]+)>").find(s.substring(i)) != null -> { val m = Regex("^<(https?://[^>\\s]+)>").find(s.substring(i))!!; val u = m.groupValues[1]; linked(u, p) { append(u) }; i += m.value.length }
            s.startsWith("**", i) && s.indexOf("**", i + 2) > i -> { val e = s.indexOf("**", i + 2); withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(s.substring(i + 2, e), p)) }; i = e + 2 }
            s[i] == '`' && s.indexOf('`', i + 1) > i -> {
                val e = s.indexOf('`', i + 1); val code = s.substring(i + 1, e)
                if (isFilePath(code)) linked(code, p, mono = true) { append(" $code ") }
                else withStyle(SpanStyle(fontFamily = Mono, background = p.code, fontSize = 13.5.sp)) { append(" $code ") }
                i = e + 1
            }
            (s.startsWith("http://", i) || s.startsWith("https://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                var e = i; while (e < s.length && !s[e].isWhitespace() && s[e] != ')' && s[e] != '>' && s[e] != '"') e++
                while (e > i && s[e - 1] in ".,;:!?'") e--
                val url = s.substring(i, e); linked(url, p) { append(url) }; i = e
            }
            s[i] == '/' && (i == 0 || s[i - 1].isWhitespace() || s[i - 1] == '(') && FILE_AT.matchAt(s, i) != null -> {
                val m = FILE_AT.matchAt(s, i)!!; linked(m.value, p, mono = true) { append(m.value) }; i += m.value.length
            }
            (s[i] == '*' || s[i] == '_') && i + 1 < s.length && s[i + 1] != ' ' && s.indexOf(s[i], i + 1) > i + 1 && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                val e = s.indexOf(s[i], i + 1); withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, e)) }; i = e + 1
            }
            s[i] == '[' && s.indexOf("](", i) > i && s.indexOf(')', s.indexOf("](", i)) > 0 -> {
                val mid = s.indexOf("](", i); val end = s.indexOf(')', mid)
                val target = s.substring(mid + 2, end).trim().substringBefore(' ')
                linked(target, p) { append(s.substring(i + 1, mid)) }
                i = end + 1
            }
            else -> { append(s[i]); i++ }
        }
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.accentInk, uncheckedTrackColor = p.cardAlt, uncheckedBorderColor = p.line))
}



/** Fence languages Hermes can use to hand you a ready-to-send piece of text instead of code. */
val COPY_LANGS = setOf("copy", "template", "text-template", "email", "message", "draft", "reply", "prompt", "tweet", "post")

@Composable
fun CopyButton(text: String) {
    val p = LocalPalette.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) { kotlinx.coroutines.delay(1400); done = false } }
    Row(Modifier.clip(RoundedCornerShape(50)).clickable {
        clip.setText(androidx.compose.ui.text.AnnotatedString(text)); done = true
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Outlined.Check else Icons.Outlined.ContentCopy, "Copy", tint = if (done) p.good else p.faint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (done) "Copied" else "Copy", color = if (done) p.good else p.faint, fontSize = 11.sp)
    }
}

@Composable
fun ShareButton(text: String) {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Icon(Icons.Outlined.Share, "Share", tint = p.faint, modifier = Modifier.size(30.dp).clip(CircleShape).clickable {
        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), null))
    }.padding(7.dp))
}

/** A ready-to-send draft (```email, ```message, ```copy …): reads like text, one tap to copy or share. */
@Composable
fun CopyCard(text: String, lang: String) {
    val p = LocalPalette.current
    val subject = text.lineSequence().firstOrNull()?.takeIf { it.startsWith("Subject:", true) }?.substringAfter(':')?.trim()
    val title = subject ?: when (lang) { "email" -> "Email"; "message", "reply" -> "Message"; "prompt" -> "Prompt"; "tweet", "post" -> "Post"; "draft" -> "Draft"; else -> "Ready to copy" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).border(1.dp, p.line, RoundedCornerShape(18.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Description, null, tint = p.accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
            Text(title, color = p.ink, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            CopyButton(text); ShareButton(text)
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer { Text(text, color = p.ink, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun MdTable(t: Md.Table, color: Color) {
    val p = LocalPalette.current
    val cols = maxOf(t.head.size, t.rows.maxOfOrNull { it.size } ?: 0)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, p.line, RoundedCornerShape(14.dp)).horizontalScroll(rememberScrollState())) {
        Column {
            Row(Modifier.background(p.code)) { (0 until cols).forEach { c -> Text(t.head.getOrElse(c) { "" }, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 90.dp, max = 220.dp).padding(10.dp)) } }
            t.rows.forEachIndexed { n, r ->
                if (n > 0) HorizontalDivider(color = p.line)
                Row { (0 until cols).forEach { c -> Text(inline(r.getOrElse(c) { "" }, p), color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 90.dp, max = 220.dp).padding(10.dp)) } }
            }
        }
    }
}


// ── live markdown styling for the composer ────────────────────────────────────
/** Styles markdown as you type while keeping every character (markers are dimmed), so offsets map 1:1. */
class MarkdownInputTransformation(private val p: Palette) : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: AnnotatedString) = androidx.compose.ui.text.input.TransformedText(mdHighlight(text.text, p), androidx.compose.ui.text.input.OffsetMapping.Identity)
}

private val HL_RULES: List<Pair<Regex, (Palette) -> SpanStyle>> = listOf(
    Regex("\\*\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*\\*") to { _ -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic) },
    Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*") to { _ -> SpanStyle(fontWeight = FontWeight.Bold) },
    Regex("(?<![\\w*])\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*(?![\\w*])") to { _ -> SpanStyle(fontStyle = FontStyle.Italic) },
    Regex("(?<!\\w)_(?=\\S)(.+?)(?<=\\S)_(?!\\w)") to { _ -> SpanStyle(fontStyle = FontStyle.Italic) },
    Regex("~~(?=\\S)(.+?)(?<=\\S)~~") to { _ -> SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough) },
    Regex("\\[([^\\]\\n]+)\\]\\([^)\\s]+\\)") to { pp -> SpanStyle(color = pp.accent) },
)

fun mdHighlight(src: String, p: Palette): AnnotatedString = buildAnnotatedString {
    append(src)
    val dim = SpanStyle(color = p.faint)
    // fenced blocks first, then mask them so inline rules skip their contents
    val masked = StringBuilder(src)
    Regex("(?ms)^\\s*(```|~~~).*?(^\\s*\\1\\s*$|\\z)").findAll(src).forEach { m ->
        addStyle(SpanStyle(fontFamily = Mono, background = p.code), m.range.first, m.range.last + 1)
        for (k in m.range) masked.setCharAt(k, ' ')
    }
    Regex("`[^`\\n]+`").findAll(masked).forEach { m ->
        addStyle(SpanStyle(fontFamily = Mono, background = p.code), m.range.first, m.range.last + 1)
        addStyle(dim, m.range.first, m.range.first + 1); addStyle(dim, m.range.last, m.range.last + 1)
        for (k in m.range) masked.setCharAt(k, ' ')
    }
    var off = 0
    masked.toString().split('\n').forEach { line ->
        Regex("^(#{1,6}) ").find(line)?.let { h ->
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = when (h.groupValues[1].length) { 1 -> 1.25.em; 2 -> 1.15.em; else -> 1.05.em }), off, off + line.length)
            addStyle(dim, off, off + h.value.length)
        }
        Regex("^\\s*(>+|[-*+] (\\[[ xX]\\] )?|\\d+[.)] )").find(line)?.let { m -> addStyle(SpanStyle(color = p.accent, fontWeight = FontWeight.SemiBold), off + m.range.first, off + m.range.last + 1) }
        if (line.trimStart().startsWith(">")) addStyle(SpanStyle(color = p.muted, fontStyle = FontStyle.Italic), off, off + line.length)
        off += line.length + 1
    }
    HL_RULES.forEach { (re, st) ->
        re.findAll(masked).forEach { m ->
            val g = m.groups[1]!!.range
            addStyle(st(p), g.first, g.last + 1)
            addStyle(dim, m.range.first, g.first); addStyle(dim, g.last + 1, m.range.last + 1)
        }
    }
}

/** Wraps the selection (or the cursor) in [before]/[after]; with no selection the cursor lands between them. */
fun androidx.compose.ui.text.input.TextFieldValue.wrap(before: String, after: String = before): androidx.compose.ui.text.input.TextFieldValue {
    val a = selection.min; val b = selection.max
    val sel = text.substring(a, b)
    if (sel.startsWith(before) && sel.endsWith(after) && sel.length >= before.length + after.length) {
        val inner = sel.substring(before.length, sel.length - after.length)
        return copy(text.replaceRange(a, b, inner), TextRange(a, a + inner.length))
    }
    val t = text.replaceRange(a, b, before + sel + after)
    return copy(t, if (a == b) TextRange(a + before.length) else TextRange(a + before.length, a + before.length + sel.length))
}

/** Toggles a line prefix ("- ", "1. ", "> ", "# ") on every line the selection touches. */
fun androidx.compose.ui.text.input.TextFieldValue.linePrefix(prefix: String): androidx.compose.ui.text.input.TextFieldValue {
    val start = text.lastIndexOf('\n', (selection.min - 1).coerceAtLeast(0)).let { if (selection.min == 0) 0 else it + 1 }.coerceAtLeast(0)
    val end = text.indexOf('\n', selection.max).let { if (it < 0) text.length else it }
    val lines = text.substring(start, end).split('\n')
    val numbered = prefix == "1. "
    val has = lines.all { if (numbered) Regex("^\\d+\\. ").containsMatchIn(it) else it.startsWith(prefix) }
    val out = lines.mapIndexed { n, l ->
        if (has) (if (numbered) l.replaceFirst(Regex("^\\d+\\. "), "") else l.removePrefix(prefix))
        else (if (numbered) "${n + 1}. " else prefix) + l
    }.joinToString("\n")
    val t = text.replaceRange(start, end, out)
    return copy(t, TextRange(start + out.length))
}
