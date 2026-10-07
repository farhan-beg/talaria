package dev.hark.hermes.data

/** What the chat list renders: plain items, or a turn's behind-the-scenes work folded into one block. */
sealed interface Seg {
    val key: String
    data class Item(val item: ChatItem, val stats: TurnStats? = null) : Seg { override val key get() = item.key }
    data class Work(override val key: String, val steps: List<ChatItem>, val live: Boolean, val tailMs: Long = 0) : Seg {
        val toolCount get() = steps.count { it is ChatItem.Tool }
        val startMs get() = steps.minOfOrNull { when (it) { is ChatItem.Tool -> it.startMs; is ChatItem.Assistant -> it.startMs; else -> 0L }.takeIf { t -> t > 0 } ?: Long.MAX_VALUE }?.takeIf { it != Long.MAX_VALUE } ?: 0L
        // through the end of the answer, so "Worked for" covers the whole turn
        val endMs get() = maxOf(tailMs, steps.maxOfOrNull { when (it) { is ChatItem.Tool -> maxOf(it.endMs, it.startMs); is ChatItem.Assistant -> maxOf(it.endMs, it.firstMs); else -> 0L } } ?: 0L)
    }
}

/**
 * Folds every turn's reasoning, tool calls and interim notes into a single Work block, leaving only
 * the user's message, the final answer (with its reasoning moved into the block) and any notices.
 */
fun foldTurns(items: List<ChatItem>, busy: Boolean): List<Seg> {
    val out = mutableListOf<Seg>()
    var i = 0
    while (i < items.size) {
        if (items[i] is ChatItem.User) { out += Seg.Item(items[i]); i++; continue }
        var j = i
        while (j < items.size && items[j] !is ChatItem.User) j++
        val turn = items.subList(i, j)
        val isLast = j == items.size
        val live = isLast && busy
        // the answer is the last assistant text in the turn, unless a tool is still running after it
        val ansIdx = turn.indexOfLast { it is ChatItem.Assistant && it.text.isNotBlank() }
            .takeIf { idx -> idx >= 0 && turn.drop(idx + 1).none { it is ChatItem.Tool } } ?: -1
        val steps = mutableListOf<ChatItem>()
        var placed = false
        var tail = 0L
        val rows = mutableListOf<Seg>()
        turn.forEachIndexed { n, it ->
            when {
                n == ansIdx -> {
                    val a = it as ChatItem.Assistant
                    if (a.reasoning.isNotBlank()) steps += a.copy(key = a.key + "-r", text = "")
                    tail = maxOf(a.endMs, a.firstMs)
                    if (!placed && steps.isNotEmpty()) { rows += Seg.Work("w-" + steps.first().key, steps.toList(), live, tail); placed = true }
                    rows += Seg.Item(a.copy(reasoning = ""), TurnStats.of(turn.filterIsInstance<ChatItem.Assistant>()))
                }
                it is ChatItem.Tool || it is ChatItem.Assistant -> {
                    steps += it
                }
                else -> rows += Seg.Item(it)
            }
        }
        if (!placed && steps.isNotEmpty()) {
            // no answer yet: the block sits where the turn's work began
            rows.add(0, Seg.Work("w-" + steps.first().key, steps.toList(), live))
        } else if (placed) {
            // keep the block's steps in sync if more arrived after the answer (rare)
            val wi = rows.indexOfFirst { it is Seg.Work }
            if (wi >= 0) rows[wi] = Seg.Work((rows[wi] as Seg.Work).key, steps.toList(), live, tail)
        }
        out += rows
        i = j
    }
    return out
}

/** One plain-language line about what the agent is doing right now. */
fun liveActivity(items: List<ChatItem>, status: String): String {
    if (status.isNotBlank()) return status
    val last = items.lastOrNull { it !is ChatItem.Notice } ?: return "Working…"
    return when (last) {
        is ChatItem.Tool -> if (!last.done) "${friendlyTool(last.name)}${last.preview.takeIf { it.isNotBlank() }?.let { " · " + it.lineSequence().first().take(60) } ?: ""}" else "Thinking about the result…"
        is ChatItem.Assistant -> when { last.text.isNotBlank() -> "Writing the reply…"; last.reasoning.isNotBlank() -> "Thinking…"; else -> "Working…" }
        is ChatItem.User -> "Starting…"
        else -> "Working…"
    }
}

fun friendlyTool(name: String): String = when {
    name.contains("terminal", true) || name.contains("shell", true) || name == "bash" -> "Running a command"
    name.contains("search", true) -> "Searching"
    name.contains("browser", true) || name.contains("web", true) || name.contains("fetch", true) -> "Browsing"
    name.contains("read", true) -> "Reading files"
    name.contains("write", true) || name.contains("patch", true) || name.contains("edit", true) -> "Editing files"
    name.contains("delegate", true) || name.contains("agent", true) -> "Working with a helper"
    name.contains("memory", true) -> "Checking memory"
    name.contains("image", true) || name.contains("vision", true) -> "Looking at an image"
    else -> "Using ${name.replace('_', ' ')}"
}

fun fmtDur(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}


/**
 * Speed for a whole turn, however many steps it took: all output tokens over the time actually spent
 * generating (the sum of each streamed segment), so tool time doesn't drag it down and the final
 * reply's short stream doesn't inflate it.
 */
data class TurnStats(val segs: List<ChatItem.Assistant>) {
    val live get() = segs.any { it.streaming }
    val exact get() = segs.any { it.outTokens > 0 }
    val tokens: Long get() = segs.maxOfOrNull { it.outTokens }?.takeIf { it > 0 } ?: segs.sumOf { it.chars / 4L }
    val startMs get() = segs.filter { it.startMs > 0 }.minOfOrNull { it.startMs } ?: segs.filter { it.firstMs > 0 }.minOfOrNull { it.firstMs } ?: 0L
    val firstMs get() = segs.filter { it.firstMs > 0 }.minOfOrNull { it.firstMs } ?: 0L
    fun genSecs(now: Long) = segs.filter { it.firstMs > 0 }.sumOf { ((if (it.endMs > 0) it.endMs else now) - it.firstMs).coerceAtLeast(0) } / 1000.0
    fun tps(now: Long = System.currentTimeMillis()): Double { val g = genSecs(now); return if (g < 0.25) 0.0 else tokens / g }
    fun ttft() = if (startMs > 0 && firstMs > 0) (firstMs - startMs) / 1000.0 else 0.0
    fun total(now: Long) = ((segs.maxOfOrNull { if (it.endMs > 0) it.endMs else if (it.streaming) now else it.firstMs } ?: 0L) - startMs).coerceAtLeast(0) / 1000.0
    companion object { fun of(segs: List<ChatItem.Assistant>) = TurnStats(segs).takeIf { it.firstMs > 0 } }
}
