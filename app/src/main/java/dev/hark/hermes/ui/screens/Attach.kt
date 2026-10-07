package dev.hark.hermes.ui.screens

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hark.hermes.app
import dev.hark.hermes.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

// ── media helpers ────────────────────────────────────────────────────────────

internal fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

internal fun hasMediaAccess(ctx: Context): Boolean {
    fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return ok(mediaPermission()) || (Build.VERSION.SDK_INT >= 34 && ok(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
}

/** Most recent photos on the device, newest first. */
internal fun recentPhotos(ctx: Context, limit: Int = 30): List<Uri> {
    val out = mutableListOf<Uri>()
    val proj = arrayOf(MediaStore.Images.Media._ID)
    runCatching {
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext() && out.size < limit) out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
        }
    }
    return out
}

internal fun thumbnail(ctx: Context, uri: Uri, px: Int): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
    else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
    }
}.getOrNull()

/**
 * Photos go up at a sensible size: long edge capped at 2048 px, EXIF rotation applied, re-encoded as JPEG.
 * Small images and GIFs pass through untouched.
 */
internal fun prepareImage(ctx: Context, uri: Uri, bytes: ByteArray, name: String, mime: String): Triple<ByteArray, String, String> {
    if (mime == "image/gif" || (bytes.size < 1_200_000 && (mime == "image/jpeg" || mime == "image/png"))) return Triple(bytes, name, mime)
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return Triple(bytes, name, mime)
        val long = maxOf(bmp.width, bmp.height)
        if (long > 2048) { val k = 2048f / long; bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) }
        val rot = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                when (android.media.ExifInterface(s).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 86, out)
        Triple(out.toByteArray(), name.substringBeforeLast('.') + ".jpg", "image/jpeg")
    }.getOrDefault(Triple(bytes, name, mime))
}

@Composable
internal fun UriThumb(uri: Uri, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { thumbnail(ctx, uri, px * 2) } }
    Box(modifier.size(size).background(p.accentSoft), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Icon(Icons.Outlined.Image, null, tint = p.faint, modifier = Modifier.size(size / 3))
    }
}

// ── the sheet ───────────────────────────────────────────────────────────────

/**
 * ChatGPT-style attach sheet: a strip of recent photos up top, then big actions
 * (camera, photos, files, paste) and your saved snippets.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AttachSheet(
    draft: String,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onPaste: () -> Unit,
    onRecent: (List<Uri>) -> Unit,
    onAskMedia: () -> Unit,
    onInsert: (String) -> Unit,
    mediaVersion: Int,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val snippets by app.store.snippets.collectAsStateWithLifecycle()
    val access = remember(mediaVersion) { hasMediaAccess(ctx) }
    val recent by produceState(emptyList<Uri>(), access, mediaVersion) { value = if (access) withContext(Dispatchers.IO) { recentPhotos(ctx) } else emptyList() }
    val selected = remember { mutableStateListOf<Uri>() }
    var adding by remember { mutableStateOf(false) }
    var newSnippet by remember { mutableStateOf(draft) }
    val clipHas = remember {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm?.hasPrimaryClip() == true
    }

    ModalBottomSheet(onDismiss, containerColor = p.sheet) {
        // recent photos
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Tile(Icons.Outlined.PhotoCamera, "Camera", Modifier.size(96.dp), onCamera)
            }
            if (!access) item {
                Tile(Icons.Outlined.PhotoLibrary, "Show recent photos", Modifier.size(width = 132.dp, height = 96.dp), onAskMedia)
            }
            items(recent, key = { it.toString() }) { uri ->
                val sel = uri in selected
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(18.dp)).clickable { if (sel) selected.remove(uri) else if (selected.size < 10) selected.add(uri) }) {
                    UriThumb(uri, 96.dp)
                    if (sel) Box(Modifier.matchParentSize().background(p.accent.copy(alpha = 0.25f)))
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                        .background(if (sel) p.accent else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f))
                        .border(1.5.dp, androidx.compose.ui.graphics.Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        if (sel) Text("${selected.indexOf(uri) + 1}", color = p.accentInk, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        AnimatedVisibility(selected.isNotEmpty()) {
            Button({ onRecent(selected.toList()); onDismiss() }, Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.accentInk)) {
                Text(if (selected.size == 1) "Add photo" else "Add ${selected.size} photos")
            }
        }

        // actions
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action(Icons.Outlined.PhotoLibrary, "Photos", Modifier.weight(1f)) { onPhotos(); onDismiss() }
            Action(Icons.Outlined.AttachFile, "Files", Modifier.weight(1f)) { onFiles(); onDismiss() }
            Action(Icons.Outlined.ContentPaste, "Paste", Modifier.weight(1f), enabled = clipHas) { onPaste(); onDismiss() }
        }

        // snippets
        Row(Modifier.padding(start = 22.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Saved snippets", color = p.faint, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton({ newSnippet = draft; adding = true }) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("New", color = p.accent)
            }
        }
        if (snippets.isEmpty()) Text("Save prompts you reuse and drop them in with one tap.", color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 22.dp))
        Column(Modifier.padding(horizontal = 12.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            snippets.forEachIndexed { i, sn ->
                var menu by remember(sn) { mutableStateOf(false) }
                Box {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = { onInsert(sn); onDismiss() }, onLongClick = { menu = true })
                        .padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ShortText, null, tint = p.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                        Text(sn, color = p.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(menu, { menu = false }, containerColor = p.sheet) {
                        DropdownMenuItem({ Text("Delete") }, { menu = false; app.store.saveSnippets(snippets.toMutableList().also { it.removeAt(i) }) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (adding) AlertDialog({ adding = false }, containerColor = p.sheet,
        title = { Text("New snippet", color = p.ink) },
        text = { OutlinedTextField(newSnippet, { newSnippet = it }, shape = RoundedCornerShape(16.dp), maxLines = 8, placeholder = { Text("e.g. Review this for bugs and suggest fixes") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ if (newSnippet.isNotBlank()) app.store.saveSnippets(listOf(newSnippet.trim()) + snippets.filter { it != newSnippet.trim() }); adding = false }, enabled = newSnippet.isNotBlank()) { Text("Save", color = p.accent) } },
        dismissButton = { TextButton({ adding = false }) { Text("Cancel", color = p.muted) } })
}

@Composable
private fun Tile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(p.accentSoft).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = p.ink, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = p.ink, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(modifier.height(78.dp).clip(RoundedCornerShape(18.dp)).background(p.accentSoft.copy(alpha = if (enabled) 0.6f else 0.25f))
        .clickable(enabled = enabled, onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = if (enabled) p.ink else p.faint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (enabled) p.ink else p.faint, style = MaterialTheme.typography.labelMedium)
    }
}
