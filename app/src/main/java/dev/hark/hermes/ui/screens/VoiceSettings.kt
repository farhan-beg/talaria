package dev.hark.hermes.ui.screens

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.hark.hermes.app
import dev.hark.hermes.data.*
import dev.hark.hermes.ui.*
import kotlinx.serialization.json.*
import java.util.Locale

/** Per-provider fields Hermes reads under tts.<provider>.*, the same set Hermes Desktop's Voice settings expose. */
private val TTS_FIELDS: Map<String, List<String>> = mapOf(
    "edge" to listOf("voice", "speed"),
    "openai" to listOf("model", "voice"),
    "elevenlabs" to listOf("voice_id", "model_id"),
    "xai" to listOf("voice_id", "language", "speed"),
    "minimax" to listOf("model", "voice_id"),
    "mistral" to listOf("model", "voice_id"),
    "gemini" to listOf("model", "voice"),
    "deepinfra" to listOf("model", "voice"),
    "neutts" to listOf("model", "device"),
    "kittentts" to listOf("model", "voice"),
    "piper" to listOf("voice"),
)
private val PROVIDER_LABEL = mapOf(
    "edge" to "Edge (free)", "openai" to "OpenAI", "elevenlabs" to "ElevenLabs", "xai" to "xAI", "minimax" to "MiniMax",
    "mistral" to "Mistral", "gemini" to "Gemini", "deepinfra" to "DeepInfra", "neutts" to "NeuTTS (local)",
    "kittentts" to "KittenTTS (local)", "piper" to "Piper (local)",
)
/** Env vars a provider's key lives in are matched by these name fragments in the profile's .env catalog. */
private val KEY_HINT = mapOf("openai" to "OPENAI", "elevenlabs" to "ELEVENLABS", "xai" to "XAI", "minimax" to "MINIMAX",
    "mistral" to "MISTRAL", "gemini" to "GEMINI", "deepinfra" to "DEEPINFRA")
/** Suggestions only; any value the provider accepts can be typed. */
private val SUGGEST: Map<String, List<String>> = mapOf(
    "edge.voice" to listOf("en-US-AriaNeural", "en-US-JennyNeural", "en-US-GuyNeural", "en-GB-SoniaNeural", "en-GB-RyanNeural",
        "en-IN-NeerjaNeural", "en-IN-PrabhatNeural", "hi-IN-SwaraNeural", "hi-IN-MadhurNeural", "en-AU-NatashaNeural"),
    "openai.model" to listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd"),
    "openai.voice" to listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse"),
    "elevenlabs.model_id" to listOf("eleven_multilingual_v2", "eleven_flash_v2_5", "eleven_turbo_v2_5"),
    "gemini.model" to listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts"),
    "gemini.voice" to listOf("Kore", "Puck", "Charon", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr"),
    "minimax.model" to listOf("speech-02-hd", "speech-02-turbo"),
    "edge.speed" to listOf("0.8", "1.0", "1.2", "1.5"),
    "xai.speed" to listOf("0.8", "1.0", "1.2", "1.5"),
)
private val NUMERIC = setOf("speed", "sample_rate", "bit_rate", "optimize_streaming_latency")
private val FIELD_LABEL = mapOf("voice" to "Voice", "voice_id" to "Voice", "model" to "Model", "model_id" to "Model",
    "speed" to "Speed", "language" to "Language", "device" to "Device")

private fun JsonObject.path(vararg keys: String): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) cur = (cur as? JsonObject)?.get(k)
    return cur
}
private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

@Composable
fun VoiceSettingsScreen(nav: NavHostController) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by app.store.profile.collectAsState()
    val hermesVoice by app.store.hermesVoice.collectAsState()

    // ── server side ──
    val cfg = rememberLoad(profile) { app.api.obj("/api/config") }
    val schema = rememberLoad(profile) { runCatching { app.api.obj("/api/config/schema").o("fields") }.getOrNull() ?: JsonObject(emptyMap()) }
    val env = rememberLoad(profile) { runCatching { app.api.obj("/api/env") }.getOrNull() ?: JsonObject(emptyMap()) }
    var provider by remember { mutableStateOf<String?>(null) }
    val draft = remember { mutableStateMapOf<String, String>() }   // "provider.field" -> value
    var dirty by remember { mutableStateOf(false) }
    var elVoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var editKey by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(cfg.data) {
        val c = cfg.data ?: return@LaunchedEffect
        if (!dirty) {
            provider = c.path("tts", "provider").text().ifBlank { "edge" }
            draft.clear()
            TTS_FIELDS.forEach { (prov, fields) -> fields.forEach { f -> c.path("tts", prov, f)?.text()?.takeIf { it.isNotBlank() }?.let { draft["$prov.$f"] = it } } }
        }
    }
    LaunchedEffect(provider) {
        if (provider == "elevenlabs" && elVoices.isEmpty()) runCatching {
            val r = app.api.obj("/api/audio/elevenlabs/voices")
            if (r.b("available")) elVoices = r.a("voices").objs().map { it.s("voice_id") to it.s("label").ifBlank { it.s("name") } }
        }
    }
    val speaker = remember { Speaker(ctx) { true } }
    val phoneSpeaker = remember { Speaker(ctx) { false } }
    DisposableEffect(Unit) { onDispose { speaker.shutdown(); phoneSpeaker.shutdown() } }

    suspend fun save() {
        val prov = provider ?: "edge"
        val block = buildJsonObject {
            put("provider", prov)
            TTS_FIELDS.forEach { (pv, fields) ->
                val vals = fields.mapNotNull { f -> draft["$pv.$f"]?.trim()?.takeIf { it.isNotEmpty() }?.let { f to it } }
                if (vals.isNotEmpty()) put(pv, buildJsonObject {
                    vals.forEach { (f, v) -> if (f in NUMERIC && v.toDoubleOrNull() != null) put(f, v.toDouble()) else put(f, v) }
                })
            }
        }
        app.api.put("/api/config", buildJsonObject { put("config", buildJsonObject { put("tts", block) }) })
        dirty = false
    }

    // ── phone side ──
    val phoneVoice by app.store.phoneVoice.collectAsState()
    val phoneRate by app.store.phoneRate.collectAsState()
    val phonePitch by app.store.phonePitch.collectAsState()
    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var engine by remember { mutableStateOf("") }
    var showAllLangs by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        var t: TextToSpeech? = null
        t = TextToSpeech(ctx.applicationContext) { ok ->
            if (ok == TextToSpeech.SUCCESS) {
                voices = runCatching { t?.voices?.toList().orEmpty() }.getOrDefault(emptyList())
                    .filter { !it.isNetworkConnectionRequired || it.features?.contains("notInstalled") != true }
                    .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
                engine = t?.defaultEngine.orEmpty()
            }
        }
        onDispose { t?.shutdown() }
    }

    Page("Voice", subtitle = "How voice mode speaks replies", onBack = { nav.popBackStack() },
        onRefresh = { cfg.reload(); env.reload() }, refreshing = cfg.loading && cfg.data != null) {

        item { SectionLabel("Who speaks") }
        item {
            HCard(padding = 8.dp) {
                ListRow("Phone voice", "Your phone's speech engine. Free, works offline", Icons.Outlined.PhoneAndroid,
                    trailing = { RadioButton(!hermesVoice, { app.store.set(app.store.hermesVoice, "hermes_voice", false) }) },
                    onClick = { app.store.set(app.store.hermesVoice, "hermes_voice", false) })
                ListRow("Hermes voice", "The TTS provider set up on your Hermes server, the same voice as desktop", Icons.Outlined.Dns,
                    trailing = { RadioButton(hermesVoice, { app.store.set(app.store.hermesVoice, "hermes_voice", true) }) },
                    onClick = { app.store.set(app.store.hermesVoice, "hermes_voice", true) })
            }
        }

        // ── Hermes ──
        item { SectionLabel("Hermes voice" + if (profile.isNotBlank()) " · $profile" else "") }
        loadState(cfg) { _ ->
            item("hv-provider") {
                HCard {
                    Text("Provider", color = p.ink, style = MaterialTheme.typography.titleSmall)
                    Text("Edge is free and needs no key. The others use your own API key.", color = p.muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    val opts = schema.data?.o("tts.provider")?.a("options")?.strs()?.filter { it.isNotBlank() }?.ifEmpty { null } ?: TTS_FIELDS.keys.toList()
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        opts.forEach { o ->
                            val on = provider == o
                            Text(PROVIDER_LABEL[o] ?: o, color = if (on) p.ink else p.muted, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accentSoft else p.card)
                                    .clickable { provider = o; dirty = true }.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            val prov = provider ?: "edge"
            item("hv-fields-$prov") {
                HCard {
                    Text((PROVIDER_LABEL[prov] ?: prov) + " settings", color = p.ink, style = MaterialTheme.typography.titleSmall)
                    val fields = TTS_FIELDS[prov] ?: emptyList()
                    if (fields.isEmpty()) Text("This provider is configured on the server only.", color = p.muted, style = MaterialTheme.typography.bodySmall)
                    fields.forEach { f ->
                        val k = "$prov.$f"
                        Spacer(Modifier.height(10.dp))
                        val schemaOpts = schema.data?.o("tts.$k")?.a("options")?.strs()?.filter { it.isNotBlank() }.orEmpty()
                        val sugg: List<Pair<String, String>> = when {
                            k == "elevenlabs.voice_id" && elVoices.isNotEmpty() -> elVoices
                            schemaOpts.isNotEmpty() -> schemaOpts.map { it to it }
                            else -> SUGGEST[k].orEmpty().map { it to it }
                        }
                        Field(FIELD_LABEL[f] ?: f, draft[k].orEmpty(), { draft[k] = it; dirty = true }, mono = f != "speed")
                        if (sugg.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                sugg.forEach { (v, label) ->
                                    val on = draft[k] == v
                                    Text(label, color = if (on) p.ink else p.muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(50)).background(if (on) p.accentSoft else Color.Transparent)
                                            .clickable { draft[k] = v; dirty = true }.padding(horizontal = 10.dp, vertical = 6.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(if (dirty) "Save" else "Saved", Icons.Outlined.Check, primary = dirty) { if (dirty) scope.act("Voice saved on Hermes", after = { cfg.reload() }) { save() } }
                        SoftButton(if (testing) "Playing…" else "Test voice", Icons.Outlined.PlayArrow) {
                            if (testing) { speaker.stop(); testing = false }
                            else scope.act {
                                if (dirty) save()
                                testing = true
                                speaker.speak("Hi, this is Hermes. This is how I'll sound in voice mode.") { testing = false }
                            }
                        }
                    }
                    speaker.serverError?.let { Text("Hermes couldn't speak: " + it.take(160), color = p.bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            KEY_HINT[prov]?.let { hint ->
                item("hv-keys-$prov") {
                    val keys = env.data?.entries?.mapNotNull { (k, v) -> (v as? JsonObject)?.takeIf { k.contains(hint) }?.let { k to it } }.orEmpty()
                    HCard(padding = 8.dp) {
                        Text("API key", color = p.ink, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp, top = 8.dp))
                        if (keys.isEmpty()) Text("Add the ${PROVIDER_LABEL[prov] ?: prov} key under More › API keys.", color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                        keys.sortedByDescending { it.second.b("is_set") }.take(4).forEach { (k, v) ->
                            ListRow(k, if (v.b("is_set")) (v.sn("redacted_value") ?: "Set") else "Not set — tap to add",
                                if (v.b("is_set")) Icons.Outlined.Key else Icons.Outlined.KeyOff, if (v.b("is_set")) p.good else p.warn, onClick = { editKey = k })
                        }
                    }
                }
            }
        }

        // ── phone ──
        item { SectionLabel("Phone voice") }
        item {
            HCard {
                Text("Voice", color = p.ink, style = MaterialTheme.typography.titleSmall)
                Text(if (engine.isNotBlank()) "From $engine. Install more voices in Android's text-to-speech settings." else "Your phone's text-to-speech engine",
                    color = p.muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                val lang = Locale.getDefault().language
                val shown = voices.filter { showAllLangs || it.locale.language == lang || it.locale.language == "en" }
                val rows = listOf<Pair<String, String>>("" to "System default") + shown.map { it.name to voiceLabel(it) }
                Column(Modifier.heightIn(max = 320.dp).verticalScrollCompat()) {
                    rows.forEach { (name, label) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            app.store.set(app.store.phoneVoice, "phone_voice", name)
                            phoneSpeaker.speak("This is how I'll sound.") {}
                        }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(phoneVoice == name, null)
                            Spacer(Modifier.width(6.dp))
                            Text(label, color = p.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                TextButton({ showAllLangs = !showAllLangs }) { Text(if (showAllLangs) "Show my languages only" else "Show all languages", color = p.accent) }
                Spacer(Modifier.height(6.dp))
                Text("Speed  %.1f×".format(phoneRate.toFloatOrNull() ?: 1f), color = p.ink, style = MaterialTheme.typography.bodyMedium)
                Slider(phoneRate.toFloatOrNull() ?: 1f, { app.store.set(app.store.phoneRate, "phone_rate", "%.1f".format(Locale.US, it)) }, valueRange = 0.5f..2.5f, steps = 19)
                Text("Pitch  %.1f".format(phonePitch.toFloatOrNull() ?: 1f), color = p.ink, style = MaterialTheme.typography.bodyMedium)
                Slider(phonePitch.toFloatOrNull() ?: 1f, { app.store.set(app.store.phonePitch, "phone_pitch", "%.1f".format(Locale.US, it)) }, valueRange = 0.5f..2.0f, steps = 14)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("Test voice", Icons.Outlined.PlayArrow) { phoneSpeaker.speak("Hi, this is Hermes. This is how I'll sound in voice mode.") {} }
                    SoftButton("Android settings", Icons.Outlined.Settings) {
                        runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
        }
    }

    editKey?.let { k ->
        var value by remember(k) { mutableStateOf("") }
        FormSheet(k, { editKey = null }, onConfirm = {
            editKey = null
            scope.act("Key saved", after = { env.reload() }) { app.api.put("/api/env", jsonOf("key" to k, "value" to value)) }
        }) {
            Field("Value", value, { value = it }, secret = true)
            env.data?.o(k)?.sn("url")?.let { Text("Get one at $it", color = p.accent, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun voiceLabel(v: android.speech.tts.Voice): String {
    val loc = v.locale.getDisplayName(Locale.getDefault())
    val tag = v.name.substringAfterLast('-').takeIf { it.length in 2..14 && it != v.locale.country.lowercase() }.orEmpty()
    val net = if (v.isNetworkConnectionRequired) " · online" else ""
    return "$loc" + (if (tag.isNotBlank()) " · $tag" else "") + net
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))
