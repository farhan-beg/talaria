package dev.hark.hermes.data

import android.content.Context
import dev.hark.hermes.HermesApp

/**
 * Remembers each reply's timing on the phone, so "stats for nerds" survive reloads,
 * reconnects and reopening a chat (the server's history carries text only).
 */
object StatsCache {
    private const val MAX = 600
    private val prefs by lazy { HermesApp.instance.getSharedPreferences("turn_stats", Context.MODE_PRIVATE) }
    private fun id(text: String) = text.trim().hashCode().toString(36) + ":" + text.trim().length

    fun save(a: ChatItem.Assistant) {
        if (a.firstMs <= 0 || a.text.isBlank()) return
        runCatching {
            val e = prefs.edit().putString(id(a.text), "${a.startMs},${a.firstMs},${a.endMs},${a.outTokens},${a.chars},${System.currentTimeMillis()}")
            e.apply()
            if (prefs.all.size > MAX) {
                val old = prefs.all.entries.sortedBy { (it.value as? String)?.split(',')?.getOrNull(5)?.toLongOrNull() ?: 0L }.take(prefs.all.size - MAX)
                prefs.edit().apply { old.forEach { remove(it.key) } }.apply()
            }
        }
    }

    fun restore(a: ChatItem.Assistant): ChatItem.Assistant = runCatching {
        // short lines repeat across turns ("Let me check."), so their timings can't be trusted
        if (a.text.trim().length < 48) return a
        val v = prefs.getString(id(a.text), null)?.split(',') ?: return a
        if (v[2].toLong() - v[0].toLong() !in 0..6 * 3600_000L) return a
        a.copy(startMs = v[0].toLong(), firstMs = v[1].toLong(), endMs = v[2].toLong(), outTokens = v[3].toLong(), chars = v[4].toInt().coerceAtLeast(a.text.length))
    }.getOrDefault(a)
}
