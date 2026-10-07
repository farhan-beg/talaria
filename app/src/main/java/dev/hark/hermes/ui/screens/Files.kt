package dev.hark.hermes.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

// ── opening files Hermes mentions ────────────────────────────────────────────

/** A file to show: either a path on Hermes' machine (from chat) or one in the Files browser. */
data class FileReq(val path: String, val managed: Boolean = false, val local: String = "")

object FileOpen {
    val request = MutableStateFlow<FileReq?>(null)
    fun open(path: String, managed: Boolean = false) { request.value = FileReq(path.trim(), managed) }
    /** Something you attached: preview from the phone copy when we still have it. */
    fun openAttachment(a: Attachment) { request.value = FileReq(a.path.lineSequence().firstOrNull().orEmpty().ifBlank { a.name }, false, a.thumb) }
}

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic")
private val TEXT_EXT = setOf("txt", "md", "markdown", "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "csv", "tsv", "log", "xml", "html", "htm", "css",
    "js", "ts", "tsx", "jsx", "py", "kt", "kts", "java", "go", "rs", "rb", "php", "c", "h", "cpp", "hpp", "cs", "swift", "sh", "bash", "zsh", "sql", "env", "gradle", "lua", "r", "dart", "vue", "svelte")

internal fun extOf(name: String) = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
internal fun isImageName(name: String) = extOf(name) in IMAGE_EXT

/** Absolute paths that look like files (have an extension) anywhere in a reply. */
private val PATH_RE = Regex("""(?<![\w/.:])((?:~|/)(?:[\w.\-@+~]+/)*[\w.\-@+~]+\.[A-Za-z0-9]{1,8})(?![\w/])""")
fun findPaths(text: String): List<String> {
    val noCode = text.replace(Regex("```[\\s\\S]*?```"), " ")
    return PATH_RE.findAll(noCode).map { it.groupValues[1] }.filterNot { it.startsWith("//") }.distinct().take(12).toList()
}

internal suspend fun loadFile(req: FileReq): Triple<ByteArray, String, String> {
    if (req.local.isNotBlank()) runCatching {
        return withContext(Dispatchers.IO) { val (b, n, m) = readUri(app, android.net.Uri.parse(req.local)); Triple(b, m, n) }
    }
    val enc = dev.hark.hermes.data.Api.enc(req.path)
    if (req.managed) return app.api.bytes("/api/files/download?path=$enc")
    val sid = app.gateway.storedSid
    return try {
        app.api.bytes("/api/fs/download?path=$enc" + if (sid.isNotBlank()) "&session_id=${dev.hark.hermes.data.Api.enc(sid)}" else "")
    } catch (e: ApiException) {
        if (sid.isNotBlank() && e.code in setOf(400, 404)) app.api.bytes("/api/fs/download?path=$enc") else throw e
    }
}

private fun cacheCopy(ctx: Context, name: String, bytes: ByteArray): android.net.Uri {
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    val f = File(dir, name.ifBlank { "file" }.replace('/', '_'))
    f.writeBytes(bytes)
    return androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
}

private fun mimeFor(name: String, given: String): String =
    given.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(name)) ?: "application/octet-stream"

internal fun shareFile(ctx: Context, name: String, bytes: ByteArray, mime: String) {
    val uri = cacheCopy(ctx, name, bytes)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mimeFor(name, mime)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share $name"))
}

internal fun openWith(ctx: Context, name: String, bytes: ByteArray, mime: String) {
    val uri = cacheCopy(ctx, name, bytes)
    try {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeFor(name, mime)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open $name"))
    } catch (e: Exception) { /* no app */ }
}

/** Saves into the phone's Downloads folder. Returns a short description of where it went. */
internal fun saveToDownloads(ctx: Context, name: String, bytes: ByteArray, mime: String): String {
    if (Build.VERSION.SDK_INT >= 29) {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeFor(name, mime))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Talaria")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("Couldn't save")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        return "Saved to Downloads/Talaria"
    }
    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
    File(dir, name).writeBytes(bytes)
    return "Saved to ${dir.name}"
}

// ── viewer ──────────────────────────────────────────────────────────────────

/** Mount once at the root: whatever asks to open a file gets this full-screen viewer. */
@Composable
fun FileViewerHost() {
    val req by FileOpen.request.collectAsStateWithLifecycle()
    req?.let { r -> FileViewer(r) { FileOpen.request.value = null } }
}

@Composable
private fun FileViewer(req: FileReq, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var data by remember(req) { mutableStateOf<Triple<ByteArray, String, String>?>(null) }
    var err by remember(req) { mutableStateOf<String?>(null) }
    LaunchedEffect(req) { try { data = loadFile(req) } catch (e: Exception) { err = errText(e) } }
    val name = data?.third?.ifBlank { null } ?: req.path.substringAfterLast('/')
    val ext = extOf(name)
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close", tint = p.ink) }
                Column(Modifier.weight(1f)) {
                    Text(name, color = p.ink, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(data?.let { humanBytes(it.first.size.toLong()) + " · " + req.path } ?: req.path, color = p.faint, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val d = data
                if (d != null) {
                    IconButton({ shareFile(ctx, name, d.first, d.second) }) { Icon(Icons.Outlined.Share, "Share", tint = p.ink) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = p.ink) }
                        DropdownMenu(menu, { menu = false }, containerColor = p.sheet) {
                            DropdownMenuItem({ Text("Open with…") }, { menu = false; openWith(ctx, name, d.first, d.second) }, leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) })
                            DropdownMenuItem({ Text("Save to Downloads") }, { menu = false; scope.launch { try { scope.toast(withContext(Dispatchers.IO) { saveToDownloads(ctx, name, d.first, d.second) }) } catch (e: Exception) { toast(errText(e)) } } }, leadingIcon = { Icon(Icons.Outlined.Download, null) })
                            DropdownMenuItem({ Text("Copy path") }, { menu = false; clip.setText(AnnotatedString(req.path)); scope.launch { toast("Path copied") } }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                        }
                    }
                }
            }
            HorizontalDivider(color = p.line)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val d = data
                when {
                    err != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = p.bad, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Couldn't open this file", color = p.ink, style = MaterialTheme.typography.titleSmall)
                        Text(err!!, color = p.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    d == null -> CircularProgressIndicator(color = p.accent)
                    ext in IMAGE_EXT || d.second.startsWith("image/") -> ZoomImage(d.first)
                    ext == "pdf" || d.second == "application/pdf" -> PdfPages(d.first)
                    ext in TEXT_EXT || d.second.startsWith("text/") || d.second.contains("json") || looksText(d.first) -> {
                        val txt = remember(d) { String(d.first.copyOf(minOf(d.first.size, 1_500_000)), Charsets.UTF_8) }
                        if (ext == "md" || ext == "markdown") Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) { SelectionContainer { Markdown(txt) } }
                        else SelectionContainer {
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(14.dp)) {
                                Text(txt, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp, color = p.ink)
                            }
                        }
                    }
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(iconFor(name, false), null, tint = p.accent, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(name, color = p.ink, style = MaterialTheme.typography.titleMedium)
                        Text(humanBytes(d.first.size.toLong()), color = p.muted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(18.dp))
                        Button({ openWith(ctx, name, d.first, d.second) }, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.accentInk), shape = RoundedCornerShape(16.dp)) { Text("Open with…") }
                    }
                }
            }
        }
    }
}

private fun looksText(b: ByteArray): Boolean {
    val n = minOf(b.size, 4096); if (n == 0) return true
    var bad = 0
    for (i in 0 until n) { val c = b[i].toInt() and 0xff; if (c == 0) return false; if (c < 9 || (c in 14..31)) bad++ }
    return bad * 20 < n
}

@Composable
private fun ZoomImage(bytes: ByteArray) {
    val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val st = rememberTransformableState { z, pan, _ -> scale = (scale * z).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan }
    if (bmp == null) Text("Can't preview this image", color = LocalPalette.current.muted)
    else Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().transformable(st)
        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
}

@Composable
private fun PdfPages(bytes: ByteArray) {
    val ctx = LocalContext.current
    val pages by produceState<List<Bitmap>?>(null, bytes) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(ctx.cacheDir, "view.pdf").apply { writeBytes(bytes) }
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        (0 until minOf(r.pageCount, 30)).map { i ->
                            r.openPage(i).use { pg ->
                                val w = 1200; val h = (w.toFloat() * pg.height / pg.width).toInt()
                                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                            }
                        }
                    }
                }
            }.getOrElse { emptyList() }
        }
    }
    val p = LocalPalette.current
    when {
        pages == null -> CircularProgressIndicator(color = p.accent)
        pages!!.isEmpty() -> Text("Can't preview this PDF", color = p.muted)
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            pages!!.forEach { Image(it.asImageBitmap(), null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth) }
        }
    }
}

internal fun iconFor(name: String, dir: Boolean): ImageVector = when {
    dir -> Icons.Outlined.Folder
    extOf(name) in IMAGE_EXT -> Icons.Outlined.Image
    extOf(name) == "pdf" -> Icons.Outlined.PictureAsPdf
    extOf(name) in setOf("mp3", "wav", "m4a", "ogg", "flac", "opus") -> Icons.Outlined.AudioFile
    extOf(name) in setOf("mp4", "mov", "webm", "mkv") -> Icons.Outlined.VideoFile
    extOf(name) in setOf("zip", "tar", "gz", "tgz", "7z", "rar") -> Icons.Outlined.FolderZip
    extOf(name) in setOf("csv", "tsv", "xlsx", "xls") -> Icons.Outlined.TableChart
    extOf(name) in TEXT_EXT -> Icons.Outlined.Description
    else -> Icons.Outlined.InsertDriveFile
}

// ── in-chat pieces ───────────────────────────────────────────────────────────

/** Tappable cards for files a reply mentions. */
@Composable
fun FileChips(paths: List<String>) {
    if (paths.isEmpty()) return
    val p = LocalPalette.current
    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        paths.forEach { path ->
            val name = path.substringAfterLast('/')
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(p.accentSoft.copy(alpha = 0.7f)).clickable { FileOpen.open(path) }
                .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(name, false), null, tint = p.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(name, color = p.ink, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            }
        }
    }
}

/** An image in a reply: a web URL or a file on Hermes' machine. Tap to open full screen. */
@Composable
fun ChatImage(src: String) {
    val p = LocalPalette.current
    val isWeb = src.startsWith("http://") || src.startsWith("https://")
    val bmp by produceState<Bitmap?>(null, src) {
        value = runCatching {
            val bytes = if (isWeb) app.api.fetchUrl(src) else loadFile(FileReq(src.removePrefix("file://"))).first
            withContext(Dispatchers.Default) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1; while (o.outWidth / (s * 2) >= 1400) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            }
        }.getOrNull()
    }
    val ctx = LocalContext.current
    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().heightIn(min = 120.dp, max = 360.dp).clip(RoundedCornerShape(16.dp)).background(p.accentSoft.copy(alpha = 0.5f))
        .clickable { if (isWeb) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(src))) } else FileOpen.open(src.removePrefix("file://")) },
        contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()) }
            ?: Icon(Icons.Outlined.Image, null, tint = p.faint, modifier = Modifier.size(32.dp))
    }
}

// ── Files screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val dir = rememberLoad(path) { app.api.obj("/api/files" + (path?.let { "?path=" + dev.hark.hermes.data.Api.enc(it) } ?: "")) }
    var confirmDel by remember { mutableStateOf<JsonObject?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    val cwd = dir.data?.s("path").orEmpty()
    val upload = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            scope.act("Uploaded", after = { dir.reload() }) {
                val (bytes, name, mime) = withContext(Dispatchers.IO) { readUri(ctx, uri) }
                val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
                app.api.post("/api/files/upload", jsonOf("path" to cwd.trimEnd('/') + "/" + name, "data_url" to "data:$mime;base64,$b64", "overwrite" to false))
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = path != null && dir.data?.sn("parent") != null) { path = dir.data?.sn("parent") }

    Page("Files", subtitle = cwd.ifBlank { null }, onBack = { nav.popBackStack() }, onRefresh = { dir.reload() }, refreshing = dir.loading && dir.data != null,
        actions = {
            if (dir.data?.b("can_change_path") == true) IconButton({ goTo = true }) { Icon(Icons.Outlined.DriveFileMove, "Go to path", tint = p.ink) }
        },
        fab = {
            var menu by remember { mutableStateOf(false) }
            Box {
                FloatingActionButton({ menu = true }, containerColor = p.accent, contentColor = p.accentInk, shape = CircleShape) { Icon(Icons.Outlined.Add, "Add") }
                DropdownMenu(menu, { menu = false }, containerColor = p.sheet) {
                    DropdownMenuItem({ Text("Upload from phone") }, { menu = false; upload.launch("*/*") }, leadingIcon = { Icon(Icons.Outlined.Upload, null) })
                    DropdownMenuItem({ Text("New folder") }, { menu = false; newFolder = true }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) })
                }
            }
        }) {
        item { SearchField(query, { query = it }, "Filter this folder") }
        loadState(dir) { d ->
            d.sn("parent")?.let { parent ->
                item("up") { ListRow("..", "Up one level", Icons.Outlined.DriveFolderUpload, onClick = { path = parent }) }
            }
            val entries = d.a("entries").objs().filter { query.isBlank() || it.s("name").contains(query, true) }
            if (entries.isEmpty()) item { Text(if (query.isBlank()) "This folder is empty" else "Nothing matches", color = p.muted, modifier = Modifier.padding(20.dp)) }
            items(entries, key = { it.s("path") }) { e ->
                val isDir = e.b("is_directory")
                var menu by remember { mutableStateOf(false) }
                Box {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .combinedClickable(onClick = { if (isDir) path = e.s("path") else FileOpen.open(e.s("path"), managed = true) }, onLongClick = { menu = true })
                        .padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (isDir) p.accentSoft else p.card), contentAlignment = Alignment.Center) {
                            Icon(iconFor(e.s("name"), isDir), null, tint = if (isDir) p.accent else p.muted, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.s("name"), color = p.ink, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val meta = listOfNotNull(
                                if (!isDir) e["size"]?.let { humanBytes(e.l("size")) } else null,
                                e["mtime"]?.let { runCatching { relTime(e.d("mtime")) }.getOrNull() },
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, color = p.faint, style = MaterialTheme.typography.labelSmall)
                        }
                        if (isDir) Icon(Icons.Outlined.ChevronRight, null, tint = p.faint)
                    }
                    DropdownMenu(menu, { menu = false }, containerColor = p.sheet) {
                        DropdownMenuItem({ Text("Copy path") }, { menu = false; clip.setText(AnnotatedString(e.s("path"))); scope.launch { toast("Path copied") } }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                        if (!isDir) DropdownMenuItem({ Text("Save to phone") }, { menu = false; scope.act {
                            val (b, mime, n) = loadFile(FileReq(e.s("path"), true)); scope.toast(withContext(Dispatchers.IO) { saveToDownloads(ctx, n.ifBlank { e.s("name") }, b, mime) })
                        } }, leadingIcon = { Icon(Icons.Outlined.Download, null) })
                        DropdownMenuItem({ Text("Delete", color = p.bad) }, { menu = false; confirmDel = e }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = p.bad) })
                    }
                }
            }
        }
    }

    confirmDel?.let { e ->
        ConfirmDialog("Delete ${e.s("name")}?", if (e.b("is_directory")) "This deletes the folder and everything inside it." else "This can't be undone.", "Delete", danger = true, onDismiss = { confirmDel = null }) {
            scope.act("Deleted", after = { dir.reload() }) { app.api.delete("/api/files", jsonOf("path" to e.s("path"), "recursive" to e.b("is_directory"))) }
        }
    }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        FormSheet("New folder", { newFolder = false }, "Create", onConfirm = {
            newFolder = false
            if (name.isNotBlank()) scope.act("Folder created", after = { dir.reload() }) { app.api.post("/api/files/mkdir", jsonOf("path" to cwd.trimEnd('/') + "/" + name.trim())) }
        }) { Field("Name", name, { name = it }) }
    }
    if (goTo) {
        var to by remember { mutableStateOf(cwd) }
        FormSheet("Go to folder", { goTo = false }, "Open", onConfirm = { goTo = false; if (to.isNotBlank()) path = to.trim() }) { Field("Path", to, { to = it }, mono = true) }
    }
}


/** What you attached, shown in your own message: photo thumbnails and file cards. */
@Composable
fun SentAttachments(files: List<Attachment>) {
    if (files.isEmpty()) return
    val p = LocalPalette.current
    val imgs = files.filter { it.kind == "image" }
    val docs = files - imgs.toSet()
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        if (imgs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val size = if (imgs.size == 1) 200.dp else 120.dp
            imgs.forEach { a ->
                Box(Modifier.size(size).clip(RoundedCornerShape(18.dp)).clickable { FileOpen.openAttachment(a) }) {
                    if (a.thumb.isNotBlank()) UriThumb(android.net.Uri.parse(a.thumb), size)
                    else Box(Modifier.fillMaxSize().background(p.accentSoft), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, null, tint = p.faint) }
                }
            }
        }
        docs.forEach { a ->
            Row(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp)).background(p.card).border(1.dp, p.line, RoundedCornerShape(16.dp))
                .clickable { FileOpen.openAttachment(a) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(iconFor(a.name, false), null, tint = p.accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(a.name, color = p.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when { a.pages > 0 -> "PDF · ${a.pages} pages"; extOf(a.name).isNotBlank() -> extOf(a.name).uppercase(); else -> "File" }, color = p.faint, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
