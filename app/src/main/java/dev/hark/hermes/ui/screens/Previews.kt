package dev.hark.hermes.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.webkit.WebView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.hark.hermes.ui.*
import dev.hark.hermes.data.humanBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

// ── what kind of preview a file gets ─────────────────────────────────────────

internal val VIDEO_EXT = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "ts")
internal val AUDIO_EXT = setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "opus", "flac", "amr", "mid", "midi")
internal val WEB_EXT = setOf("html", "htm", "xhtml", "svg")
internal val ZIP_EXT = setOf("zip", "jar", "epub", "aar", "cbz", "whl")
internal val FONT_EXT = setOf("ttf", "otf")

private fun cacheFile(ctx: android.content.Context, name: String, bytes: ByteArray): File {
    val dir = File(ctx.cacheDir, "preview").apply { mkdirs() }
    return File(dir, name.ifBlank { "file" }.replace('/', '_')).apply { writeBytes(bytes) }
}

/** Whether [RichPreview] has a viewer for this file. */
internal fun hasRichPreview(name: String, mime: String): Boolean {
    val ext = extOf(name)
    return ext in VIDEO_EXT || mime.startsWith("video/") || ext in AUDIO_EXT || mime.startsWith("audio/") || ext in setOf("docx", "pptx", "xlsx", "odt", "ods", "odp", "apk") ||
        ext in WEB_EXT || mime == "text/html" || mime == "image/svg+xml" || ext in FONT_EXT || ext in ZIP_EXT || mime == "application/zip" || (ext == "gif" && Build.VERSION.SDK_INT >= 28)
}

/** Picks a preview by extension and mime type. */
@Composable
internal fun RichPreview(name: String, bytes: ByteArray, mime: String, parent: FileReq) {
    val ext = extOf(name)
    when {
        ext in VIDEO_EXT || mime.startsWith("video/") -> VideoPreview(name, bytes)
        ext in AUDIO_EXT || mime.startsWith("audio/") -> AudioPreview(name, bytes)
        ext == "docx" -> DocText(remember(bytes) { docxText(bytes) })
        ext == "pptx" -> Slides(remember(bytes) { pptxSlides(bytes) })
        ext == "xlsx" -> SheetTabs(bytes)
        ext in setOf("odt", "ods", "odp") -> DocText(remember(bytes) { odfText(bytes) })
        ext in WEB_EXT || mime == "text/html" || mime == "image/svg+xml" -> WebPreview(bytes, if (ext == "svg" || mime == "image/svg+xml") "image/svg+xml" else "text/html")
        ext == "apk" -> ApkPreview(name, bytes)
        ext in FONT_EXT -> FontPreview(name, bytes)
        ext in ZIP_EXT || mime == "application/zip" -> ZipPreview(bytes, parent)
        ext == "gif" && Build.VERSION.SDK_INT >= 28 -> AnimatedImage(name, bytes)
    }
}

// ── media ───────────────────────────────────────────────────────────────────

@Composable
private fun VideoPreview(name: String, bytes: ByteArray) {
    val ctx = LocalContext.current
    val f = remember(bytes) { cacheFile(ctx, name, bytes) }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
        AndroidView({ c ->
            VideoView(c).apply {
                val mc = MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                setVideoPath(f.absolutePath)
                setOnPreparedListener { start(); mc.show(2500) }
            }
        }, Modifier.fillMaxWidth(), onRelease = { it.stopPlayback() })
    }
}

private fun clock(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
private fun AudioPreview(name: String, bytes: ByteArray) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val f = remember(bytes) { cacheFile(ctx, name, bytes) }
    var err by remember { mutableStateOf<String?>(null) }
    val mp = remember(f) { runCatching { MediaPlayer().apply { setDataSource(f.absolutePath); prepare() } }.onFailure { err = "Can't play this audio here" }.getOrNull() }
    DisposableEffect(mp) { onDispose { mp?.release() } }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    val dur = mp?.duration?.coerceAtLeast(1) ?: 1
    LaunchedEffect(mp, playing) { while (playing && mp != null) { pos = mp.currentPosition; if (!mp.isPlaying) playing = false; delay(200) } }
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(32.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.GraphicEq, null, tint = p.accent, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(name, color = p.ink, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (err != null || mp == null) { Text(err ?: "", color = p.muted, modifier = Modifier.padding(top = 8.dp)); return }
        Spacer(Modifier.height(16.dp))
        Slider(pos.toFloat(), { pos = it.toInt(); mp.seekTo(pos) }, valueRange = 0f..dur.toFloat(),
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
        Row(Modifier.fillMaxWidth()) {
            Text(clock(pos), color = p.muted, style = MaterialTheme.typography.labelSmall); Spacer(Modifier.weight(1f))
            Text(clock(dur), color = p.muted, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            IconButton({ mp.seekTo((mp.currentPosition - 10_000).coerceAtLeast(0)); pos = mp.currentPosition }) { Icon(Icons.Outlined.Replay10, "Back 10s", tint = p.ink) }
            Box(Modifier.size(64.dp).clip(CircleShape).background(p.accent).clickable {
                if (mp.isPlaying) { mp.pause(); playing = false } else { mp.start(); playing = true }
            }, contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, tint = p.accentInk, modifier = Modifier.size(32.dp))
            }
            IconButton({ mp.seekTo((mp.currentPosition + 10_000).coerceAtMost(dur)); pos = mp.currentPosition }) { Icon(Icons.Outlined.Forward10, "Forward 10s", tint = p.ink) }
        }
        if (Build.VERSION.SDK_INT >= 23) TextButton({
            speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; 2f -> 0.75f; else -> 1f }
            runCatching { val was = mp.isPlaying; mp.playbackParams = mp.playbackParams.setSpeed(speed); if (!was) mp.pause() }
        }) { Text("${speed}×", color = p.muted) }
    }
}

@Composable
private fun AnimatedImage(name: String, bytes: ByteArray) {
    val ctx = LocalContext.current
    val f = remember(bytes) { cacheFile(ctx, name, bytes) }
    AndroidView({ c ->
        android.widget.ImageView(c).apply {
            if (Build.VERSION.SDK_INT >= 28) runCatching {
                val d = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(f))
                setImageDrawable(d); (d as? android.graphics.drawable.AnimatedImageDrawable)?.start()
            }
        }
    }, Modifier.fillMaxSize())
}

/** HEIC, AVIF and other formats BitmapFactory can't read but the platform decoder can. */
internal fun decodeAny(bytes: ByteArray): Bitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: if (Build.VERSION.SDK_INT >= 28) runCatching {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, _, _ -> d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }
        }.getOrNull() else null

// ── web ─────────────────────────────────────────────────────────────────────

/** Rendered offline with scripts and network off: a preview, not a browser. */
@Composable
private fun WebPreview(bytes: ByteArray, mime: String) {
    val html = remember(bytes) { String(bytes, Charsets.UTF_8) }
    AndroidView({ c ->
        WebView(c).apply {
            settings.javaScriptEnabled = false
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.builtInZoomControls = true; settings.displayZoomControls = false
            settings.loadWithOverviewMode = true; settings.useWideViewPort = true
            setBackgroundColor(android.graphics.Color.WHITE)
            if (mime == "image/svg+xml") loadDataWithBaseURL(null, "<html><body style='margin:0;display:flex;justify-content:center'><img style='max-width:100%' src='data:image/svg+xml;base64," +
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) + "'/></body></html>", "text/html", "utf-8", null)
            else loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }, Modifier.fillMaxSize(), onRelease = { it.destroy() })
}

// ── office documents ────────────────────────────────────────────────────────

private fun zipEntries(bytes: ByteArray, want: (String) -> Boolean): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory && want(e.name)) out[e.name] = z.readBytes() }
    }
    return out
}

private fun parser(b: ByteArray): XmlPullParser = android.util.Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); setInput(ByteArrayInputStream(b), "UTF-8")
}

/** A paragraph of a document: its text and whether it's a heading. */
internal data class Para(val text: String, val heading: Int = 0, val bullet: Boolean = false)

internal fun docxText(bytes: ByteArray): List<Para> = runCatching {
    val doc = zipEntries(bytes) { it == "word/document.xml" }["word/document.xml"] ?: return emptyList()
    val xp = parser(doc); val out = ArrayList<Para>(); val sb = StringBuilder(); var h = 0; var bullet = false; var inT = false
    while (xp.next() != XmlPullParser.END_DOCUMENT) {
        when (xp.eventType) {
            XmlPullParser.START_TAG -> when (xp.name) {
                "w:p" -> { sb.setLength(0); h = 0; bullet = false }
                "w:pStyle" -> xp.getAttributeValue(null, "w:val")?.let { v ->
                    val m = Regex("(?i)heading\\s*(\\d)").find(v); if (m != null) h = m.groupValues[1].toInt() else if (v.equals("Title", true)) h = 1 }
                "w:numPr" -> bullet = true
                "w:t" -> inT = true
                "w:tab" -> sb.append('\t'); "w:br" -> sb.append('\n')
            }
            XmlPullParser.TEXT -> if (inT) sb.append(xp.text)
            XmlPullParser.END_TAG -> when (xp.name) { "w:t" -> inT = false; "w:p" -> out.add(Para(sb.toString(), h, bullet)) }
        }
    }
    out
}.getOrDefault(emptyList())

internal fun odfText(bytes: ByteArray): List<Para> = runCatching {
    val doc = zipEntries(bytes) { it == "content.xml" }["content.xml"] ?: return emptyList()
    val xp = parser(doc); val out = ArrayList<Para>(); val sb = StringBuilder(); var depth = 0; var h = 0
    while (xp.next() != XmlPullParser.END_DOCUMENT) {
        when (xp.eventType) {
            XmlPullParser.START_TAG -> when (xp.name) {
                "text:p", "text:h" -> { if (depth == 0) { sb.setLength(0); h = if (xp.name == "text:h") (xp.getAttributeValue(null, "text:outline-level")?.toIntOrNull() ?: 1) else 0 }; depth++ }
                "text:tab" -> sb.append('\t'); "text:line-break" -> sb.append('\n'); "text:s" -> sb.append(' ')
            }
            XmlPullParser.TEXT -> if (depth > 0) sb.append(xp.text)
            XmlPullParser.END_TAG -> if (xp.name == "text:p" || xp.name == "text:h") { depth--; if (depth == 0) out.add(Para(sb.toString(), h)) }
        }
    }
    out
}.getOrDefault(emptyList())

internal fun pptxSlides(bytes: ByteArray): List<List<String>> = runCatching {
    val files = zipEntries(bytes) { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
    files.entries.sortedBy { Regex("\\d+").findAll(it.key).last().value.toInt() }.map { (_, b) ->
        val xp = parser(b); val paras = ArrayList<String>(); val sb = StringBuilder(); var inT = false
        while (xp.next() != XmlPullParser.END_DOCUMENT) {
            when (xp.eventType) {
                XmlPullParser.START_TAG -> when (xp.name) { "a:p" -> sb.setLength(0); "a:t" -> inT = true }
                XmlPullParser.TEXT -> if (inT) sb.append(xp.text)
                XmlPullParser.END_TAG -> when (xp.name) { "a:t" -> inT = false; "a:p" -> if (sb.isNotBlank()) paras.add(sb.toString()) }
            }
        }
        paras
    }
}.getOrDefault(emptyList())

/** Every sheet of a workbook as rows of cell text, in workbook order. */
internal fun xlsxSheets(bytes: ByteArray): List<Pair<String, List<List<String>>>> = runCatching {
    val files = zipEntries(bytes) { it.startsWith("xl/") && it.endsWith(".xml") || it == "xl/_rels/workbook.xml.rels" }
    val shared = ArrayList<String>()
    files["xl/sharedStrings.xml"]?.let { b ->
        val xp = parser(b); val sb = StringBuilder(); var inT = false
        while (xp.next() != XmlPullParser.END_DOCUMENT) when (xp.eventType) {
            XmlPullParser.START_TAG -> when (xp.name) { "si" -> sb.setLength(0); "t" -> inT = true }
            XmlPullParser.TEXT -> if (inT) sb.append(xp.text)
            XmlPullParser.END_TAG -> when (xp.name) { "t" -> inT = false; "si" -> shared.add(sb.toString()) }
        }
    }
    val rels = HashMap<String, String>()
    files["xl/_rels/workbook.xml.rels"]?.let { b -> val xp = parser(b)
        while (xp.next() != XmlPullParser.END_DOCUMENT) if (xp.eventType == XmlPullParser.START_TAG && xp.name == "Relationship")
            rels[xp.getAttributeValue(null, "Id")] = xp.getAttributeValue(null, "Target").removePrefix("/xl/").removePrefix("/") }
    val sheets = ArrayList<Pair<String, String>>()
    files["xl/workbook.xml"]?.let { b -> val xp = parser(b)
        while (xp.next() != XmlPullParser.END_DOCUMENT) if (xp.eventType == XmlPullParser.START_TAG && xp.name == "sheet")
            sheets.add(xp.getAttributeValue(null, "name") to ("xl/" + (rels[xp.getAttributeValue(null, "r:id")] ?: ""))) }
    if (sheets.isEmpty()) files.keys.filter { it.startsWith("xl/worksheets/sheet") }.sorted().forEach { sheets.add(it.substringAfterLast('/').removeSuffix(".xml") to it) }
    fun colIdx(ref: String): Int { var n = 0; for (ch in ref) { if (!ch.isLetter()) break; n = n * 26 + (ch.uppercaseChar() - 'A' + 1) }; return n - 1 }
    sheets.mapNotNull { (title, path) ->
        val b = files[path] ?: return@mapNotNull null
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); var col = 0; var type = ""; val v = StringBuilder(); var inV = false
        val xp = parser(b)
        while (xp.next() != XmlPullParser.END_DOCUMENT && rows.size < 20_000) when (xp.eventType) {
            XmlPullParser.START_TAG -> when (xp.name) {
                "row" -> { row = ArrayList(); xp.getAttributeValue(null, "r")?.toIntOrNull()?.let { while (rows.size < it - 1 && rows.size < 20_000) rows.add(emptyList()) } }
                "c" -> { col = xp.getAttributeValue(null, "r")?.let { colIdx(it) } ?: row.size; type = xp.getAttributeValue(null, "t") ?: ""; v.setLength(0) }
                "v", "t" -> inV = true
            }
            XmlPullParser.TEXT -> if (inV) v.append(xp.text)
            XmlPullParser.END_TAG -> when (xp.name) {
                "v", "t" -> inV = false
                "c" -> { while (row.size < col) row.add(""); val raw = v.toString()
                    row.add(when (type) { "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1) ?: ""; "b" -> if (raw == "1") "TRUE" else "FALSE"
                        else -> raw.toDoubleOrNull()?.let { d -> if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else raw } ?: raw }) }
                "row" -> rows.add(row)
            }
        }
        title to rows.filter { r -> r.any { it.isNotBlank() } }
    }
}.getOrDefault(emptyList())

@Composable
private fun DocText(paras: List<Para>) {
    val p = LocalPalette.current
    if (paras.none { it.text.isNotBlank() }) { Text("No text in this document", color = p.muted); return }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            items(paras.size) { i ->
                val para = paras[i]
                when {
                    para.text.isBlank() -> Spacer(Modifier.height(8.dp))
                    para.heading > 0 -> Text(para.text, color = p.ink, fontWeight = FontWeight.SemiBold,
                        fontSize = when (para.heading) { 1 -> 22.sp; 2 -> 19.sp; else -> 17.sp }, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    para.bullet -> Row(Modifier.padding(vertical = 2.dp)) { Text("•  ", color = p.muted); Text(para.text, color = p.ink, style = MaterialTheme.typography.bodyMedium) }
                    else -> Text(para.text, color = p.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun Slides(slides: List<List<String>>) {
    val p = LocalPalette.current
    if (slides.isEmpty()) { Text("No slides with text", color = p.muted); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(slides.size) { i ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(16.dp)) {
                Text("Slide ${i + 1}", color = p.faint, style = MaterialTheme.typography.labelSmall)
                slides[i].forEachIndexed { k, t ->
                    Text(t, color = p.ink, style = if (k == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = if (k == 0) 6.dp else 4.dp))
                }
                if (slides[i].isEmpty()) Text("(no text on this slide)", color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun SheetTabs(bytes: ByteArray) {
    val p = LocalPalette.current
    val sheets by produceState<List<Pair<String, List<List<String>>>>?>(null, bytes) { value = withContext(Dispatchers.Default) { xlsxSheets(bytes) } }
    val s = sheets ?: run { CircularProgressIndicator(color = p.accent); return }
    if (s.isEmpty()) { Text("Couldn't read this workbook", color = p.muted); return }
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        if (s.size > 1) Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { ChipRow(s.indices.map { it.toString() to s[it].first }, tab.toString()) { tab = it.toInt() } }
        key(tab) { Box(Modifier.weight(1f)) { TableView(s[tab].second) } }
    }
}

// ── archives, apps, fonts ───────────────────────────────────────────────────

@Composable
private fun ZipPreview(bytes: ByteArray, parent: FileReq) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val entries by produceState<List<Pair<String, Long>>?>(null, bytes) {
        value = withContext(Dispatchers.IO) { runCatching {
            val out = ArrayList<Pair<String, Long>>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (out.size < 5000) { val e = z.nextEntry ?: break; if (!e.isDirectory) { var n = 0L; val buf = ByteArray(32 * 1024); while (true) { val r = z.read(buf); if (r < 0) break; n += r }; out.add(e.name to n) } } }
            out.toList()
        }.getOrDefault(emptyList()) }
    }
    val list = entries ?: run { CircularProgressIndicator(color = p.accent); return }
    Column(Modifier.fillMaxSize()) {
        Text("${list.size} files · ${humanBytes(list.sumOf { it.second })} unpacked", color = p.faint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp, 10.dp))
        HorizontalDivider(color = p.line)
        LazyColumn(Modifier.weight(1f)) {
            items(list.size) { i ->
                val (path, size) = list[i]
                Row(Modifier.fillMaxWidth().clickable {
                    // open the entry in the same viewer; back returns to this archive
                    val data = ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.firstOrNull { it.name == path }?.let { z.readBytes() } } ?: return@clickable
                    val f = cacheFile(ctx, path.substringAfterLast('/'), data)
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    FileOpen.request.value = FileReq(path, false, uri.toString(), parent)
                }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(path, false), null, tint = p.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(path.substringAfterLast('/'), color = p.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (path.contains('/')) Text(path.substringBeforeLast('/'), color = p.faint, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(humanBytes(size), color = p.muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ApkPreview(name: String, bytes: ByteArray) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val info = remember(bytes) {
        runCatching {
            val f = cacheFile(ctx, name, bytes); val pm = ctx.packageManager
            val pi = pm.getPackageArchiveInfo(f.absolutePath, 0)!!
            pi.applicationInfo!!.sourceDir = f.absolutePath; pi.applicationInfo!!.publicSourceDir = f.absolutePath
            val icon = pi.applicationInfo!!.loadIcon(pm)
            val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { b -> val c = android.graphics.Canvas(b); icon.setBounds(0, 0, 192, 192); icon.draw(c) }
            Triple(pi.applicationInfo!!.loadLabel(pm).toString(), "${pi.packageName}\nVersion ${pi.versionName ?: "?"} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})", bmp)
        }.getOrNull()
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        if (info == null) { Icon(Icons.Outlined.Android, null, tint = p.accent, modifier = Modifier.size(64.dp)); Text("Android app", color = p.ink); return }
        Image(info.third.asImageBitmap(), null, Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)))
        Spacer(Modifier.height(14.dp))
        Text(info.first, color = p.ink, style = MaterialTheme.typography.titleLarge)
        Text(info.second, color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        Text(humanBytes(bytes.size.toLong()), color = p.faint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FontPreview(name: String, bytes: ByteArray) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val fam = remember(bytes) { runCatching { FontFamily(android.graphics.Typeface.createFromFile(cacheFile(ctx, name, bytes))) }.getOrNull() }
    if (fam == null) { Text("Can't load this font", color = p.muted); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(48, 32, 22, 16, 12).forEach { s ->
            Text(if (s >= 32) "Aa Bb Cc 123" else "The quick brown fox jumps over the lazy dog.", fontFamily = fam, fontSize = s.sp, color = p.ink, lineHeight = (s * 1.25).sp)
        }
        Text("ABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz\n0123456789 !@#\$%&*()", fontFamily = fam, fontSize = 18.sp, color = p.muted, lineHeight = 26.sp)
    }
}
