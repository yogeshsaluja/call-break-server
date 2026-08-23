package com.yogesh.callbreak.engine

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** The four suits. Spades are always trump in Call Break. */
@Serializable
enum class Suit { SPADES, HEARTS, DIAMONDS, CLUBS }

/** Card ranks, Ace high. [value] is used for all comparisons. */
@Serializable
enum class Rank(val value: Int) {
    TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7), EIGHT(8), NINE(9),
    TEN(10), JACK(11), QUEEN(12), KING(13), ACE(14),
}

@Serializable
data class Card(val suit: Suit, val rank: Rank)

/** The four seats in clockwise play order. Labels are for display only. */
@Serializable
enum class Seat {
    SOUTH, WEST, NORTH, EAST;

    /** The next seat clockwise. */
    fun next(): Seat = entries[(ordinal + 1) % entries.size]
}

/** A standard 52-card deck. */
val FULL_DECK: List<Card> = Suit.entries.flatMap { suit ->
    Rank.entries.map { rank -> Card(suit, rank) }
}

/** Sort order for a hand: grouped by suit, ascending rank — for tidy display. */
val HAND_ORDER: Comparator<Card> = compareBy({ it.suit.ordinal }, { it.rank.value })

/**
 * Deal 13 cards to each seat for a given [round], deterministically.
 *
 * The deal is derived purely from [seed] and [round], so the full game is
 * reproducible from a single seed — the foundation for server authority,
 * replays, and headless bot simulation.
 */
fun dealRound(seed: Long, round: Int): Map<Seat, List<Card>> {
    val rng = Random(seed * 1_000_003L + round)
    val shuffled = FULL_DECK.shuffled(rng)
    return Seat.entries.associateWith { seat ->
        val start = seat.ordinal * 13
        shuffled.subList(start, start + 13).sortedWith(HAND_ORDER)
    }
}
