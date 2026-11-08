package com.playcrew.ludo.ai

import com.playcrew.ludo.engine.FINISH
import com.playcrew.ludo.engine.LudoEngine
import com.playcrew.ludo.engine.LudoState
import com.playcrew.ludo.engine.SAFE_GLOBAL_SQUARES
import com.playcrew.ludo.engine.globalSquare

object LudoBot {
    fun chooseToken(state: LudoState): Int? {
        val roll = state.dice ?: return null
        val color = state.currentPlayer.color
        val tokens = state.tokens.getValue(color)
        return LudoEngine.legalMoves(state).maxByOrNull { index ->
            val token = tokens[index]
            val destination = if (token.isInYard) 0 else token.progress + roll
            val square = token.copy(progress = destination).globalSquare(color)
            when {
                destination == FINISH -> 10_000
                square != null && captures(state, square) -> 5_000 + destination
                square in SAFE_GLOBAL_SQUARES -> 2_000 + destination
                token.isInYard -> 1_000
                else -> destination
            }
        }
    }

    private fun captures(state: LudoState, square: Int): Boolean =
        square !in SAFE_GLOBAL_SQUARES && state.players
            .filter { it.color != state.currentPlayer.color }
            .any { player -> state.tokens.getValue(player.color).any { it.globalSquare(player.color) == square } }
}
