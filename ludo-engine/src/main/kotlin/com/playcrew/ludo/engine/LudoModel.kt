package com.playcrew.ludo.engine

import kotlinx.serialization.Serializable

@Serializable
enum class LudoColor(val startOffset: Int) {
    RED(0), GREEN(13), YELLOW(26), BLUE(39),
}

@Serializable
data class LudoPlayer(
    val id: String,
    val name: String,
    val color: LudoColor,
    val isBot: Boolean = false,
)

@Serializable
data class LudoToken(val progress: Int = YARD) {
    val isInYard: Boolean get() = progress == YARD
    val isFinished: Boolean get() = progress == FINISH
    val isOnSharedTrack: Boolean get() = progress in 0 until TRACK_SIZE
}

@Serializable
data class LudoState(
    val players: List<LudoPlayer>,
    val tokens: Map<LudoColor, List<LudoToken>>,
    val currentPlayerIndex: Int = 0,
    val dice: Int? = null,
    val consecutiveSixes: Int = 0,
    val winnerId: String? = null,
    val turnNumber: Int = 1,
) {
    val currentPlayer: LudoPlayer get() = players[currentPlayerIndex]

    companion object {
        fun new(players: List<LudoPlayer>): LudoState {
            require(players.size in 2..4)
            require(players.map { it.id }.distinct().size == players.size)
            require(players.map { it.color }.distinct().size == players.size)
            return LudoState(
                players = players,
                tokens = players.associate { it.color to List(TOKENS_PER_PLAYER) { LudoToken() } },
            )
        }
    }
}

@Serializable
sealed interface LudoAction {
    @Serializable
    data class RollDice(val value: Int) : LudoAction

    @Serializable
    data class MoveToken(val tokenIndex: Int) : LudoAction
}

sealed interface LudoActionResult {
    data class Accepted(
        val state: LudoState,
        val movedToken: Int? = null,
        val captured: List<CapturedToken> = emptyList(),
    ) : LudoActionResult

    data class Rejected(val reason: String) : LudoActionResult
}

data class CapturedToken(val color: LudoColor, val tokenIndex: Int)

const val TOKENS_PER_PLAYER = 4
const val TRACK_SIZE = 52
const val HOME_STRETCH_START = 52
const val FINISH = 57
const val YARD = -1
val SAFE_GLOBAL_SQUARES = setOf(0, 8, 13, 21, 26, 34, 39, 47)

fun LudoToken.globalSquare(color: LudoColor): Int? =
    if (isOnSharedTrack) (color.startOffset + progress) % TRACK_SIZE else null
