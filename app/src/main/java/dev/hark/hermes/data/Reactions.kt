package dev.hark.hermes.data

import kotlinx.coroutines.flow.MutableStateFlow

/** The six Tapback defaults Hermes desktop offers, in Apple's order. */
val QUICK_REACTIONS = listOf("❤️", "👍", "👎", "😂", "‼️", "❓")

/** Lets a tap anywhere (like a helper step in the work block) open the Subagents sheet. */
object SubagentSheetState { val open = MutableStateFlow(false) }
