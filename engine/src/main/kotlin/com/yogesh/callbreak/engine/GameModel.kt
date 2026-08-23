package com.yogesh.callbreak.engine

import kotlinx.serialization.Serializable

/**
 * House rules. Defaults follow standard Call Break; every variant-sensitive rule
 * is a field here so changing it is a one-line edit, not a code rewrite.
 *
 * [mustOvertrump]: when you play a spade and spades are already on the trick, must
 * you beat the highest one if you can? Standard Call Break says yes. Flip to `false`
 * if the reference app you're matching allows any spade. Covered by tests either way.
 */
@Serializable
data class CallBreakConfig(
    val rounds: Int = 5,
    val minCall: Int = 1,
    val maxCall: Int = 13,
    val overtrickValue: Double = 0.1,
    val mustOvertrump: Boolean = true,
)

@Serializable
data class PlayerState(
    val seat: Seat,
    val hand: List<Card>,
    val call: Int? = null,
    val tricksWon: Int = 0,
    val roundScore: Double = 0.0,
    val totalScore: Double = 0.0,
)

/** One card played by one seat within the current trick. */
@Serializable
data class Play(val seat: Seat, val card: Card)

@Serializable
enum class Phase {
    /** All seats call (bid) their target tricks, in play order. */
    BIDDING,

    /** 13 tricks are played out. */
    PLAYING,

    /** A round finished and was scored; awaits [Intent.AdvanceRound]. */
    ROUND_OVER,

    /** All rounds complete. */
    GAME_OVER,
}

/**
 * The complete, immutable game state. Pure data — no RNG, no I/O — so it is
 * trivially serializable (for the network layer) and reproducible from [seed].
 */
@Serializable
data class GameState(
    val config: CallBreakConfig,
    val seed: Long,
    val round: Int,
    val dealer: Seat,
    val phase: Phase,
    val currentTurn: Seat,
    val trickLeader: Seat,
    val currentTrick: List<Play>,
    val players: Map<Seat, PlayerState>,
) {
    fun player(seat: Seat): PlayerState = players.getValue(seat)

    /** The suit that was led on the current trick, or null if no card is out yet. */
    val ledSuit: Suit?
        get() = currentTrick.firstOrNull()?.card?.suit
}

/** The only ways to mutate the game — all validated by [CallBreakEngine.applyIntent]. */
@Serializable
sealed interface Intent {
    @Serializable
    data class MakeCall(val seat: Seat, val count: Int) : Intent

    @Serializable
    data class PlayCard(val seat: Seat, val card: Card) : Intent

    @Serializable
    data object AdvanceRound : Intent
}

/** Result of applying an [Intent]: either the next state, or a rejection reason. */
sealed interface ApplyResult {
    data class Success(val state: GameState) : ApplyResult
    data class Rejected(val reason: String) : ApplyResult
}
