package com.yogesh.callbreak.ai

import com.yogesh.callbreak.engine.CallBreakConfig
import com.yogesh.callbreak.engine.CallBreakEngine
import com.yogesh.callbreak.engine.Card
import com.yogesh.callbreak.engine.FULL_DECK
import com.yogesh.callbreak.engine.GameState
import com.yogesh.callbreak.engine.Play
import com.yogesh.callbreak.engine.Rank
import com.yogesh.callbreak.engine.Seat
import com.yogesh.callbreak.engine.Suit
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Rule-based Call Break AI with three tiers ([Difficulty]).
 *
 * - EASY: greedy — grabs any trick it can, no notion of its own call.
 * - MEDIUM: plays to its call (win cheaply while short, duck once met), sensible leads,
 *   conserves trumps — but has no memory of past cards.
 * - HARD: MEDIUM plus card memory — tracks played cards to find guaranteed winners
 *   ("bosses"), infers opponents' voids from past tricks, and leads accordingly.
 *
 * All three only ever return a legal move (validated against [CallBreakEngine.legalMoves]).
 */
object CallBreakAI {

    // ---- Calling ----------------------------------------------------------------

    /** The call a bot of [difficulty] would make with [hand]. */
    fun call(hand: List<Card>, difficulty: Difficulty, config: CallBreakConfig = CallBreakConfig()): Int {
        val expected = expectedTricks(hand)
        val raw = when (difficulty) {
            Difficulty.EASY -> floor(expected).toInt() // timid
            Difficulty.MEDIUM, Difficulty.HARD -> expected.roundToInt()
        }
        return raw.coerceIn(config.minCall, config.maxCall)
    }

    /** A neutral call estimate for UI hints and the human's auto-play. */
    fun suggestedCall(hand: List<Card>, config: CallBreakConfig = CallBreakConfig()): Int =
        call(hand, Difficulty.MEDIUM, config)

    /** Expected trick count via honour strength, trump length, and short-suit ruffs. */
    private fun expectedTricks(hand: List<Card>): Double {
        val bySuit = hand.groupBy { it.suit }
        val spades = bySuit[Suit.SPADES].orEmpty()
        var tricks = 0.0

        if (spades.hasRank(Rank.ACE)) tricks += 1.0
        if (spades.hasRank(Rank.KING)) tricks += if (spades.size >= 2) 0.85 else 0.4
        if (spades.hasRank(Rank.QUEEN)) tricks += if (spades.size >= 3) 0.55 else 0.2
        if (spades.hasRank(Rank.JACK) && spades.size >= 4) tricks += 0.3
        if (spades.size > 4) tricks += (spades.size - 4) * 0.7 // long trumps

        for (suit in SIDE_SUITS) {
            val cards = bySuit[suit].orEmpty()
            if (cards.hasRank(Rank.ACE)) tricks += 0.9
            if (cards.hasRank(Rank.KING)) tricks += if (cards.size >= 2) 0.55 else 0.25
            if (cards.hasRank(Rank.QUEEN) && cards.size >= 3) tricks += 0.25
            // Short side suits become ruffs once we can trump.
            if (spades.isNotEmpty()) {
                when (cards.size) {
                    0 -> tricks += minOf(spades.size, 2) * 0.5
                    1 -> tricks += minOf(spades.size, 2) * 0.3
                }
            }
        }
        return tricks
    }

    // ---- Playing ----------------------------------------------------------------

    /** The card a bot of [difficulty] plays. Always legal. */
    fun play(context: BotContext, difficulty: Difficulty): Card {
        val legal = CallBreakEngine.legalMoves(context.state, context.seat)
        require(legal.isNotEmpty()) { "no legal cards for ${context.seat}" }
        if (legal.size == 1) return legal.single()
        return when (difficulty) {
            Difficulty.EASY -> playEasy(context, legal)
            Difficulty.MEDIUM -> playMedium(context, legal)
            Difficulty.HARD -> playHard(context, legal)
        }
    }

    private fun playEasy(context: BotContext, legal: List<Card>): Card {
        if (context.state.currentTrick.isEmpty()) return legal.lowestPreferNonSpade()
        val winners = legal.filter { wins(context.state, context.seat, it) }
        return winners.minByValueOrNull() ?: legal.lowestPreferNonSpade()
    }

    private fun playMedium(context: BotContext, legal: List<Card>): Card {
        val need = tricksNeeded(context)
        if (context.state.currentTrick.isEmpty()) {
            return if (need <= 0) legal.lowestPreferNonSpade() else leadToWin(legal)
        }
        val winners = legal.filter { wins(context.state, context.seat, it) }
        return if (need > 0 && winners.isNotEmpty()) winners.minByValue() else legal.lowestPreferNonSpade()
    }

    private fun playHard(context: BotContext, legal: List<Card>): Card {
        val need = tricksNeeded(context)
        val unseen = unseenCards(context)

        if (context.state.currentTrick.isEmpty()) {
            if (need <= 0) return legal.lowestPreferNonSpade() // met the call: shed the lead cheaply

            // Cash a guaranteed top trump if we hold one.
            legal.filter { it.suit == Suit.SPADES && isTopTrump(it, unseen) }
                .maxByValueOrNull()?.let { return it }

            // Cash a side-suit boss only if no opponent is known to be void (else it gets trumped).
            val voidSuits = opponentVoids(context)
            legal.filter { card ->
                card.suit != Suit.SPADES &&
                    isSuitBoss(card, unseen) &&
                    card.suit !in voidSuits
            }.maxByValueOrNull()?.let { return it }

            return leadLowFromLongestSide(legal)
        }

        val winners = legal.filter { wins(context.state, context.seat, it) }
        if (need > 0 && winners.isNotEmpty()) {
            // Win as cheaply as possible, spending side cards before trumps.
            val nonTrump = winners.filter { it.suit != Suit.SPADES }
            return (nonTrump.ifEmpty { winners }).minByValue()
        }
        // Don't need it (or can't win): discard the lowest, keeping trumps and honours.
        return legal.lowestPreferNonSpade()
    }

    // ---- Shared helpers ---------------------------------------------------------

    private fun tricksNeeded(context: BotContext): Int {
        val player = context.state.player(context.seat)
        return (player.call ?: 0) - player.tricksWon
    }

    private fun wins(state: GameState, seat: Seat, card: Card): Boolean =
        CallBreakEngine.trickWinner(state.currentTrick + Play(seat, card)) == seat

    /** Cards opponents might still hold: the deck minus our hand minus everything played. */
    private fun unseenCards(context: BotContext): List<Card> {
        val seen = context.state.player(context.seat).hand.toMutableSet()
        context.playHistory.forEach { seen += it.card }
        return FULL_DECK.filter { it !in seen }
    }

    private fun isTopTrump(card: Card, unseen: List<Card>): Boolean =
        card.suit == Suit.SPADES && unseen.none { it.suit == Suit.SPADES && it.rank.value > card.rank.value }

    private fun isSuitBoss(card: Card, unseen: List<Card>): Boolean =
        unseen.none { it.suit == card.suit && it.rank.value > card.rank.value }

    /**
     * Suits each opponent is known to be void in, inferred from completed tricks: a
     * player who didn't follow the led suit must be void in it.
     */
    private fun opponentVoids(context: BotContext): Set<Suit> {
        val voids = mutableSetOf<Suit>()
        var index = 0
        val history = context.playHistory
        while (index < history.size) {
            val trick = history.subList(index, minOf(index + 4, history.size))
            val led = trick.first().card.suit
            for (play in trick) {
                if (play.seat != context.seat && play.card.suit != led) voids += led
            }
            index += 4
        }
        return voids
    }

    private fun leadToWin(legal: List<Card>): Card {
        legal.filter { it.suit != Suit.SPADES && it.rank == Rank.ACE }.minByValueOrNull()?.let { return it }
        return leadLowFromLongestSide(legal)
    }

    private fun leadLowFromLongestSide(legal: List<Card>): Card {
        val sides = legal.filter { it.suit != Suit.SPADES }
        if (sides.isEmpty()) return legal.minByValue() // only trumps left
        val longest = sides.groupBy { it.suit }.maxByOrNull { it.value.size }!!.value
        return longest.minByValue()
    }

    private fun List<Card>.hasRank(rank: Rank) = any { it.rank == rank }

    private fun List<Card>.lowestPreferNonSpade(): Card =
        minWith(compareBy({ if (it.suit == Suit.SPADES) 1 else 0 }, { it.rank.value }))

    private fun List<Card>.minByValue(): Card = minBy { it.rank.value }

    private fun List<Card>.minByValueOrNull(): Card? = minByOrNull { it.rank.value }

    private fun List<Card>.maxByValueOrNull(): Card? = maxByOrNull { it.rank.value }

    private val SIDE_SUITS = listOf(Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS)
}
