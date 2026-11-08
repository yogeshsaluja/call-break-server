package com.playcrew.ludo.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LudoEngineTest {
    private val players =
        listOf(
            LudoPlayer("red", "Red", LudoColor.RED),
            LudoPlayer("green", "Green", LudoColor.GREEN),
        )

    @Test
    fun sixReleasesToken() {
        val rolled = accepted(LudoEngine.apply(LudoState.new(players), LudoAction.RollDice(6))).state
        val moved = accepted(LudoEngine.apply(rolled, LudoAction.MoveToken(0))).state
        assertEquals(0, moved.tokens.getValue(LudoColor.RED)[0].progress)
        assertEquals(LudoColor.RED, moved.currentPlayer.color)
    }

    @Test
    fun captureReturnsOpponentToYard() {
        val state =
            LudoState.new(players).copy(
                tokens =
                    mapOf(
                        LudoColor.RED to listOf(LudoToken(4), LudoToken(), LudoToken(), LudoToken()),
                        LudoColor.GREEN to listOf(LudoToken(44), LudoToken(), LudoToken(), LudoToken()),
                    ),
                dice = 1,
            )
        val result = accepted(LudoEngine.apply(state, LudoAction.MoveToken(0)))
        assertEquals(YARD, result.state.tokens.getValue(LudoColor.GREEN)[0].progress)
        assertEquals(1, result.captured.size)
    }

    private fun accepted(result: LudoActionResult): LudoActionResult.Accepted = assertIs(result)
}
