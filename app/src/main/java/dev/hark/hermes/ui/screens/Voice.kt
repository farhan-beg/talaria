package dev.hark.hermes.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hark.hermes.app
import dev.hark.hermes.data.ChatItem
import dev.hark.hermes.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import java.util.Locale

/** On-device speech recognition with live partial results and a mic level for visuals. */
class Dictation(ctx: Context) {
    private val rec: SpeechRecognizer? = if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null
    val available get() = rec != null
    var listening by mutableStateOf(false); private set
    var partial by mutableStateOf("")
    var level by mutableFloatStateOf(0f); private set
    var onFinal: (String) -> Unit = {}
    var onError: (Int) -> Unit = {}

    init {
        rec?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onError(error: Int) { listening = false; level = 0f; onError(error) }
            override fun onResults(results: Bundle?) {
                listening = false; level = 0f
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                partial = ""
                onFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) partial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun start() {
        val r = rec ?: return
        partial = ""
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        listening = true
        r.startListening(i)
    }
    fun stop() { rec?.stopListening() }
    fun cancel() { rec?.cancel(); listening = false; level = 0f }
    fun destroy() { rec?.destroy() }
}

/**
 * Text-to-speech that reports when it finishes. Speaks with the phone's TTS engine, or, when [hermes] is on,
 * with the voice configured on the Hermes server (tts: in config.yaml, via /api/audio/speak), falling back
 * to the phone if the server can't synthesize.
 */
class Speaker(private val ctx: Context, private val hermes: () -> Boolean = { false }) {
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var job: kotlinx.coroutines.Job? = null
    private var player: android.media.MediaPlayer? = null
    /** Set when the server voice failed, so the UI can say why it sounds different. */
    var serverError by mutableStateOf<String?>(null); private set
    private var ready = false
    var speaking by mutableStateOf(false); private set
    private var done: () -> Unit = {}
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { ready = it == TextToSpeech.SUCCESS }.also { t ->
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true }
            override fun onDone(id: String?) { if (id == "last") { speaking = false; done() } }
            @Deprecated("old api") override fun onError(id: String?) { speaking = false; done() }
            override fun onStop(id: String?, interrupted: Boolean) { speaking = false }
        })
    }
    fun speak(text: String, then: () -> Unit) {
        done = then
        val clean = speakable(text)
        if (clean.isBlank()) { then(); return }
        if (hermes()) { speakHermes(clean, then); return }
        speakPhone(clean)
    }

    private fun speakPhone(clean: String) {
        if (!ready) { speaking = false; done(); return }
        tts.language = Locale.getDefault()
        val want = app.store.phoneVoice.value
        if (want.isNotBlank()) runCatching { tts.voices?.firstOrNull { it.name == want }?.let { tts.voice = it } }
        tts.setSpeechRate(app.store.phoneRate.value.toFloatOrNull()?.coerceIn(0.25f, 3f) ?: 1f)
        tts.setPitch(app.store.phonePitch.value.toFloatOrNull()?.coerceIn(0.25f, 3f) ?: 1f)
        // split long replies so the engine never hits its input limit
        val chunks = clean.chunked(3500)
        chunks.forEachIndexed { i, c ->
            tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == chunks.lastIndex) "last" else "p$i")
        }
        speaking = true
    }
    /** Sentence-sized pieces so the first audio starts quickly; the next piece is synthesized while one plays. */
    private fun pieces(t: String, max: Int = 450): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder()
        Regex("[^.!?…]+[.!?…]*\\s*").findAll(t).map { it.value }.forEach { s ->
            if (cur.length + s.length > max && cur.isNotEmpty()) { out += cur.toString().trim(); cur.clear() }
            if (s.length > max) s.chunked(max).forEach { out += it.trim() } else cur.append(s)
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.filter { it.isNotBlank() }
    }

    private suspend fun synth(text: String): java.io.File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val r = app.api.post("/api/audio/speak", kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive(text)) })
        val url = ((r as? kotlinx.serialization.json.JsonObject)?.get("data_url") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val comma = url.indexOf(',')
        if (!url.startsWith("data:") || comma < 0) throw java.io.IOException("Hermes returned no audio")
        val mime = url.substring(5, comma).substringBefore(';')
        val ext = when { "wav" in mime -> ".wav"; "ogg" in mime || "opus" in mime -> ".ogg"; "flac" in mime -> ".flac"; else -> ".mp3" }
        java.io.File.createTempFile("hermes-tts", ext, ctx.cacheDir).also { it.writeBytes(java.util.Base64.getDecoder().decode(url.substring(comma + 1))) }
    }

    private suspend fun play(f: java.io.File) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
        val mp = android.media.MediaPlayer()
        player = mp
        fun finish() { runCatching { mp.release() }; if (player === mp) player = null; f.delete(); if (c.isActive) c.resumeWith(Result.success(Unit)) }
        mp.setOnCompletionListener { finish() }
        mp.setOnErrorListener { _, _, _ -> finish(); true }
        c.invokeOnCancellation { runCatching { mp.stop() }; runCatching { mp.release() }; f.delete() }
        try { mp.setDataSource(f.absolutePath); mp.setOnPreparedListener { it.start() }; mp.prepareAsync() } catch (e: Exception) { finish() }
    }

    private fun speakHermes(clean: String, then: () -> Unit) {
        job?.cancel()
        speaking = true
        job = io.launch {
            val parts = pieces(clean)
            var next: kotlinx.coroutines.Deferred<java.io.File>? = async { synth(parts[0]) }
            for (i in parts.indices) {
                val f = try { next!!.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    // server voice unavailable: say the rest with the phone's voice
                    serverError = e.message ?: "Hermes voice unavailable"
                    speakPhone(parts.drop(i).joinToString(" ")); return@launch
                }
                serverError = null
                next = if (i + 1 < parts.size) async { synth(parts[i + 1]) } else null
                play(f)
            }
            speaking = false; then()
        }
    }

    fun stop() { job?.cancel(); job = null; runCatching { player?.stop() }; tts.stop(); speaking = false }
    fun shutdown() { stop(); tts.shutdown(); io.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
}

/** Strips markdown and code so replies read naturally out loud. */
fun speakable(md: String): String = md
    .replace(Regex("```[\\s\\S]*?```"), " I've put the code in the chat. ")
    .replace(Regex("`([^`]*)`"), "$1")
    .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("(?m)^#{1,6}\\s*"), "")
    .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
    .replace(Regex("[*_~>|]"), "")
    .replace(Regex("https?://\\S+"), "link")
    .replace(Regex("\\s+"), " ").trim()

private enum class Phase { Listening, Thinking, Speaking, Paused }

/**
 * Hands-free voice mode: listen, send, read the reply aloud, listen again.
 * Everything speech-related runs on the phone; Hermes only sees text.
 */
@Composable
fun VoiceMode(onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val g = app.gateway
    val scope = rememberCoroutineScope()
    val dict = remember { Dictation(ctx) }
    val hermesVoice by app.store.hermesVoice.collectAsStateWithLifecycle()
    val speaker = remember { Speaker(ctx) { app.store.hermesVoice.value } }
    val busy by g.busy.collectAsStateWithLifecycle()
    val items by g.items.collectAsStateWithLifecycle()
    var phase by remember { mutableStateOf(Phase.Listening) }
    var heard by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var sentAt by remember { mutableIntStateOf(-1) }

    fun listen() { heard = ""; phase = Phase.Listening; dict.start() }

    DisposableEffect(Unit) {
        dict.onFinal = { t ->
            if (t.isBlank()) { if (phase == Phase.Listening) listen() }
            else {
                heard = t; phase = Phase.Thinking; sentAt = g.items.value.size
                scope.launch { g.send(t) }
            }
        }
        dict.onError = { code ->
            // silence or no match: just keep listening; anything else pauses so it doesn't spin
            if (phase == Phase.Listening) {
                if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) scope.launch { delay(250); listen() }
                else phase = Phase.Paused
            }
        }
        if (dict.available) listen() else phase = Phase.Paused
        onDispose { dict.cancel(); dict.destroy(); speaker.stop(); speaker.shutdown() }
    }

    // live caption of the reply while it streams
    val latest = items.drop(sentAt.coerceAtLeast(0)).filterIsInstance<ChatItem.Assistant>().lastOrNull()
    LaunchedEffect(latest?.text) { if (phase == Phase.Thinking) reply = latest?.text.orEmpty() }
    // the turn ended: read every reply from this turn, then listen again
    LaunchedEffect(busy) {
        if (!busy && phase == Phase.Thinking && sentAt >= 0) {
            delay(150)
            val said = g.items.value.drop(sentAt).filterIsInstance<ChatItem.Assistant>().joinToString("\n") { it.text }
            reply = said
            phase = Phase.Speaking
            speaker.speak(said.ifBlank { "Done." }) { scope.launch { delay(200); if (phase == Phase.Speaking) listen() } }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(p.bgTop, p.bg, p.bgBottom)))) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when (phase) { Phase.Listening -> "Listening"; Phase.Thinking -> "Thinking"; Phase.Speaking -> "Speaking"; Phase.Paused -> if (dict.available) "Paused" else "Speech recognition isn't available" },
                    color = p.muted, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 24.dp),
                )
                Spacer(Modifier.height(10.dp))
                // which voice reads replies: the phone's TTS engine (free, offline) or the one set up on Hermes
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "Phone voice", true to "Hermes voice").forEach { (h, label) ->
                        val on = hermesVoice == h
                        Text(label, color = if (on) p.ink else p.muted, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clip(CircleShape).background(if (on) p.accentSoft else Color.Transparent)
                                .clickable { app.store.set(app.store.hermesVoice, "hermes_voice", h) }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                speaker.serverError?.let { if (hermesVoice) Text("Hermes voice failed, using the phone's: " + it.take(80), color = p.warn, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.weight(1f))
                Orb(phase, dict.level, Modifier.size(240.dp).clip(CircleShape).clickable {
                    when (phase) {
                        Phase.Speaking -> { speaker.stop(); listen() }        // tap to cut in
                        Phase.Listening -> dict.stop()                        // tap to send now
                        Phase.Paused -> listen()
                        Phase.Thinking -> scope.launch { g.interrupt() }
                    }
                })
                Spacer(Modifier.height(36.dp))
                val caption = when (phase) { Phase.Listening -> dict.partial.ifBlank { heard }; Phase.Thinking, Phase.Speaking -> reply.ifBlank { heard }; Phase.Paused -> "Tap the orb to talk" }
                Text(speakable(caption).takeLast(280), color = p.ink, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 27.sp),
                    maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundKey(if (phase == Phase.Paused) Icons.Outlined.Mic else Icons.Outlined.MicOff, if (phase == Phase.Paused) "Resume" else "Pause") {
                        if (phase == Phase.Paused) listen() else { dict.cancel(); speaker.stop(); phase = Phase.Paused }
                    }
                    RoundKey(Icons.Outlined.Close, "End", danger = true) { onClose() }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun RoundKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(if (danger) p.bad.copy(alpha = 0.18f) else p.accentSoft).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (danger) p.bad else p.ink, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = p.muted, style = MaterialTheme.typography.labelMedium)
    }
}

/** A liquid orb: breathes while idle, swells with your voice, ripples while speaking. */
@Composable
private fun Orb(phase: Phase, level: Float, modifier: Modifier) {
    val p = LocalPalette.current
    val inf = rememberInfiniteTransition(label = "orb")
    val t by inf.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(if (phase == Phase.Thinking) 1400 else 3200, easing = LinearEasing)), label = "t")
    val lv by animateFloatAsState(if (phase == Phase.Listening) level else 0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "lv")
    val core by animateColorAsState(when (phase) { Phase.Speaking -> p.accent; Phase.Thinking -> p.accent.copy(alpha = 0.75f); Phase.Paused -> p.muted.copy(alpha = 0.5f); else -> p.accent }, label = "c")
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val base = size.minDimension * 0.30f
        val speak = if (phase == Phase.Speaking) (kotlin.math.sin(t * 3) * 0.5f + 0.5f) * 0.10f else 0f
        val breathe = kotlin.math.sin(t) * 0.03f
        val r = base * (1f + breathe + lv * 0.35f + speak)
        // halo rings
        for (i in 3 downTo 1) {
            val rr = r * (1f + i * 0.16f + lv * 0.12f * i)
            drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.16f / i), Color.Transparent), c, rr), rr, c)
        }
        // body with a drifting highlight
        val hl = Offset(c.x + kotlin.math.cos(t) * r * 0.25f, c.y + kotlin.math.sin(t) * r * 0.25f - r * 0.2f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), core, core.copy(alpha = 0.85f)), hl, r * 1.4f), r, c)
        if (phase == Phase.Thinking) {
            val sweep = 90f
            drawArc(Color.White.copy(alpha = 0.6f), Math.toDegrees(t.toDouble()).toFloat(), sweep, false,
                topLeft = Offset(c.x - r * 1.12f, c.y - r * 1.12f), size = androidx.compose.ui.geometry.Size(r * 2.24f, r * 2.24f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}
