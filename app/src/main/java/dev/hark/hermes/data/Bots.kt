package dev.hark.hermes.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Bot Mode: every Hermes profile is a named bot with ONE forever-chat, the session titled exactly
 * "Bot Chat" on that profile. The server resolves it by title on every `profiles.list`, so the app
 * never stores a session pointer. Look (title, colour, shape, pin, hide, section) lives in the
 * profile's `ui_meta["hermes-bots"]`, the same place Hermes Desktop keeps it, so both stay in sync.
 */
data class Bot(
    val name: String,
    val isDefault: Boolean,
    /** Friendly name: ui_meta title, then the profile's display_name, then the slug. */
    val display: String,
    val description: String,
    val model: String,
    val color: String?,
    val shape: String,
    val pinned: Boolean,
    val hidden: Boolean,
    val section: String?,
    val hasAvatar: Boolean,
    val photo: Boolean,
    /** The forever-chat's live id (compression tip), when it exists yet. */
    val chatId: String?,
    val preview: String,
    /** Unix seconds. */
    val lastActive: Double,
    /** A kanban/tool worker heartbeat in the last two minutes. */
    val working: Boolean,
    val meta: JsonObject,
    val metaRev: Int,
) {
    val handle get() = if (name.equals("default", true)) "hermes" else name
}

object Bots {
    const val CHAT_TITLE = "Bot Chat"
    const val META_KEY = "hermes-bots"
    val SHAPES = listOf("circle", "squircle", "pill", "hexagon", "triangle", "drop")
    val COLORS = listOf("#8B5CF6", "#F0C46C", "#5EE6D0", "#FF9466", "#9BE8A8", "#FFA8CC", "#60A5FA", "#F87171", "#A3A3A3")

    /** Whether the server injects the teammate-messaging protocol itself (newer Hermes). */
    @Volatile var serverProtocol = true
        private set

    /** Latest roster per gateway, so the chat header and teammate bubbles can find a bot's look. */
    val roster = MutableStateFlow<Map<String, Bot>>(emptyMap())

    private val avatars = ConcurrentHashMap<String, Bitmap?>()
    val avatarRev = MutableStateFlow(0)

    fun parse(r: JsonObject): Bot {
        val meta = r.o("ui_meta")?.o(META_KEY) ?: JsonObject(emptyMap())
        val canon = r.o("canonical_session")
        val worker = r.o("worker_session")
        val name = r.s("name")
        val now = System.currentTimeMillis() / 1000.0
        val last = maxOf(canon.d("last_active"), r.o("last_session").d("last_active"))
        return Bot(
            name = name,
            isDefault = r.b("is_default"),
            display = meta.sn("title") ?: r.sn("display_name") ?: if (r.b("is_default")) "Hermes" else name,
            description = meta.sn("description") ?: r.s("description"),
            model = r.s("model"),
            color = meta.sn("color"),
            shape = meta.sn("shape") ?: "circle",
            pinned = meta.b("pinned"),
            hidden = meta.b("hidden"),
            section = meta.sn("sectionName"),
            hasAvatar = r.b("has_avatar"),
            photo = r.b("has_avatar") && meta.s("imageKind") != "shape",
            chatId = canon?.let { it.sn("resolved_id") ?: it.sn("id") },
            preview = canon.sn("preview") ?: "",
            lastActive = last,
            working = worker != null && now - worker.d("last_active") < 120,
            meta = meta,
            metaRev = r.o("ui_meta_revisions")?.l(META_KEY)?.toInt() ?: 0,
        )
    }

    suspend fun list(g: Gateway): List<Bot> {
        val r = g.rpc("profiles.list", jsonOf("include_sessions" to true))
        serverProtocol = r.b("bot_mode_protocol")
        val bots = r.a("profiles").objs().map(::parse)
        roster.value = bots.associateBy { it.name } + bots.associateBy { it.handle }
        return bots
    }

    fun find(handleOrName: String): Bot? = roster.value[handleOrName] ?: roster.value.values.firstOrNull { it.name.equals(handleOrName, true) }

    fun cachedAvatar(b: Bot): Bitmap? = avatars[b.name + ":" + b.metaRev]

    suspend fun avatar(g: Gateway, b: Bot): Bitmap? {
        if (!b.photo) return null
        val key = b.name + ":" + b.metaRev
        if (avatars.containsKey(key)) return avatars[key]
        val bmp = runCatching {
            val r = g.rpc("profiles.get_asset", jsonOf("name" to b.name, "asset" to "avatar"))
            if (!r.b("found")) null else {
                val bytes = android.util.Base64.decode(r.s("data").substringAfter("base64,"), android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
        avatars[key] = bmp
        return bmp
    }

    /** Merge [changes] into the bot's look and write it back with a compare-and-swap; one retry on a race. */
    suspend fun updateMeta(g: Gateway, b: Bot, changes: Map<String, Any?>): Bot {
        var cur = b
        repeat(2) { attempt ->
            val merged = buildJsonObject {
                cur.meta.forEach { (k, v) -> if (k !in changes) put(k, v) }
                changes.forEach { (k, v) -> if (v != null) put(k, toJson(v)) }
            }
            val r = g.rpc("profiles.configure", jsonOf("name" to cur.name, "ui_meta" to jsonOf(META_KEY to merged),
                "ui_meta_expected_revisions" to jsonOf(META_KEY to cur.metaRev)))
            val applied = r.o("applied")
            if (applied == null || applied.b("ui_meta")) {
                val rev = applied?.o("ui_meta_revisions")?.l(META_KEY)?.toInt() ?: (cur.metaRev + 1)
                val next = parseLocal(cur, merged, rev)
                roster.update { m -> m + (next.name to next) + (next.handle to next) }
                return next
            }
            if (attempt == 0) cur = list(g).firstOrNull { it.name == b.name } ?: throw java.io.IOException("Bot not found")
        }
        throw java.io.IOException("Someone else changed this bot at the same time. Try again.")
    }

    private fun parseLocal(b: Bot, meta: JsonObject, rev: Int) = b.copy(
        display = meta.sn("title") ?: b.display, description = meta.sn("description") ?: b.description,
        color = meta.sn("color"), shape = meta.sn("shape") ?: "circle", pinned = meta.b("pinned"), hidden = meta.b("hidden"),
        section = meta.sn("sectionName"), photo = b.hasAvatar && meta.s("imageKind") != "shape", meta = meta, metaRev = rev)

    /** A short profile id from a friendly name: "Research Bot" → "research-bot". */
    fun slug(title: String): String =
        title.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifBlank { "bot" }

    suspend fun create(g: Gateway, title: String, description: String, soul: String, color: String, shape: String, taken: Set<String>): String {
        var name = slug(title)
        if (name in taken || name == "default") { var i = 2; while ("$name-$i" in taken) i++; name = "$name-$i" }
        g.rpc("profiles.create", jsonOf("name" to name, "description" to description, "clone_from" to "default",
            "soul" to composeSoul(name, title, description, soul, taken)))
        runCatching {
            g.rpc("profiles.configure", jsonOf("name" to name, "ui_meta" to jsonOf(META_KEY to jsonOf(
                "title" to title, "description" to description, "color" to color, "shape" to shape,
                "imageKind" to "shape", "created" to System.currentTimeMillis()))))
        }
        return name
    }

    suspend fun setPhoto(g: Gateway, b: Bot, jpeg: ByteArray?): Bot {
        if (jpeg == null) g.rpc("profiles.set_asset", jsonOf("name" to b.name, "asset" to "avatar", "clear" to true))
        else g.rpc("profiles.set_asset", jsonOf("name" to b.name, "asset" to "avatar",
            "data" to "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)))
        avatars.keys.filter { it.startsWith(b.name + ":") }.forEach { avatars.remove(it) }
        val next = updateMeta(g, b.copy(hasAvatar = jpeg != null), mapOf("imageKind" to if (jpeg == null) "shape" else "photo", "custom" to true))
        avatarRev.update { it + 1 }
        return next
    }

    suspend fun describe(g: Gateway, name: String): JsonObject = g.rpc("profiles.describe", jsonOf("name" to name))
    suspend fun saveSoul(g: Gateway, name: String, soul: String) { g.rpc("profiles.configure", jsonOf("name" to name, "soul" to soul)) }

    /** Desktop's SOUL for a new bot. Older servers don't inject the teammate protocol, so it rides in the SOUL there. */
    private fun composeSoul(name: String, title: String, description: String, custom: String, taken: Set<String>): String {
        val identity = custom.trim().ifBlank {
            listOfNotNull("# $title", "", description.takeIf { it.isNotBlank() }?.let { "**Mission:** $it" }, "",
                "You are $title, a persistent named agent (profile `$name`) on this machine.",
                "You keep your own memory, skills, and conversation history across sessions.").joinToString("\n")
        }
        if (serverProtocol) return identity
        val handle = name
        val mates = taken.filter { it != name }.ifEmpty { listOf("(none yet)") }.joinToString("\n") { if (it.startsWith("(")) "- $it" else "- `$it`" }
        return identity + "\n\n" + """
            ## Messaging other agents

            You work alongside other named agents. Every agent (including you) has
            ONE canonical conversation titled "Bot Chat". Agent-to-agent messages are delivered straight
            into it, like a DM. To message a teammate, run:

            ```
            hermes -p <agent-name> chat --in ~ -c "Bot Chat" --create-if-missing -Q -q "Message from 🤖 $handle (@$handle): your message"
            ```

            Run the send with background=true and notify_on_complete=true on the terminal tool, then finish
            your turn. The reply arrives later as a background process notification. Never block waiting for it.

            If a message in YOUR chat starts with "Message from 🤖 <name>", it is a teammate messaging you,
            not the user. Answer it directly.

            When the user writes @<agent-name> or says "ask <name> to ...", that is a handoff: message that
            agent, wait for the reply, and report back. Run `hermes profile list` for the live teammate list.
            Teammates when you were created:
        """.trimIndent() + "\n" + mates
    }

    /** "Message from 🤖 Name (@handle): text" → (handle, display, text). */
    private val DM = Regex("^Message from \\uD83E\\uDD16 (.+?) \\(@([A-Za-z0-9_.-]+)\\):\\s*", RegexOption.DOT_MATCHES_ALL)
    fun teammateMessage(text: String): Triple<String, String, String>? =
        DM.find(text)?.let { m -> Triple(m.groupValues[2], m.groupValues[1], text.substring(m.range.last + 1)) }

    /** Desktop's colour fallback: a stable hue from the profile name; the main profile is violet. */
    fun colorOf(b: Bot?, name: String = b?.name.orEmpty()): Long {
        parseColor(b?.color)?.let { return it }
        if (name.isBlank() || name == "default") return 0xFF8B5CF6
        var h = 0L
        for (c in name) h = (h * 31 + c.code) and 0xFFFFFFFFL
        return hsl((h % 360).toFloat(), 0.68f, 0.58f)
    }

    fun parseColor(s: String?): Long? {
        val v = s?.trim().orEmpty()
        if (v.startsWith("#")) return runCatching {
            val hex = v.drop(1).let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }.take(6)
            0xFF000000 or hex.toLong(16)
        }.getOrNull()
        Regex("hsl\\(\\s*([\\d.]+)[ ,]+([\\d.]+)%[ ,]+([\\d.]+)%").find(v)?.let { m ->
            return hsl(m.groupValues[1].toFloat(), m.groupValues[2].toFloat() / 100f, m.groupValues[3].toFloat() / 100f)
        }
        return null
    }

    private fun hsl(h: Float, s: Float, l: Float): Long {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when ((h / 60).toInt() % 6) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
        fun ch(v: Float) = ((v + m) * 255).toInt().coerceIn(0, 255).toLong()
        return 0xFF000000 or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
