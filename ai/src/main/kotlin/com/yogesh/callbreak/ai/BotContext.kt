package com.yogesh.callbreak.ai

import com.yogesh.callbreak.engine.GameState
import com.yogesh.callbreak.engine.Play
import com.yogesh.callbreak.engine.Seat

/** Skill tiers. Higher tiers call more accurately and play with memory + planning. */
enum class Difficulty { EASY, MEDIUM, HARD }

/**
 * Everything a bot may legitimately use to decide: the current [state], whose turn it
 * is ([seat]), and the public [playHistory] — every card played so far *this round*,
 * in order, including the current trick. Opponents' hidden hands are never included.
 */
data class BotContext(
    val state: GameState,
    val seat: Seat,
    val playHistory: List<Play>,
)
