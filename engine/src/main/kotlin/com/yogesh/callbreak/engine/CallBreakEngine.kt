package com.yogesh.callbreak.engine

/**
 * The Call Break rules engine: the single source of truth for game legality and
 * scoring. Pure and deterministic — the client uses it to drive offline play and
 * highlight legal moves; the server runs the exact same code to stay authoritative.
 *
 * Everything flows through [applyIntent], which only ever returns a legal next
 * state or a rejection — illegal moves can never mutate the game.
 */
object CallBreakEngine {

    /** Start a fresh game at round 1, in [Phase.BIDDING]. */
    fun newGame(
        seed: Long,
        config: CallBreakConfig = CallBreakConfig(),
        firstDealer: Seat = Seat.SOUTH,
    ): GameState {
        val hands = dealRound(seed, round = 1)
        val players = Seat.entries.associateWith { seat ->
            PlayerState(seat = seat, hand = hands.getValue(seat))
        }
        val leader = firstDealer.next()
        return GameState(
            config = config,
            seed = seed,
            round = 1,
            dealer = firstDealer,
            phase = Phase.BIDDING,
            currentTurn = leader,
            trickLeader = leader,
            currentTrick = emptyList(),
            players = players,
        )
    }

    /**
     * The cards [seat] is allowed to play right now. Empty unless it is [seat]'s
     * turn in [Phase.PLAYING]. This is the same logic the UI uses to light up
     * playable cards and the server uses to reject illegal plays.
     *
     * Rules: follow the led suit if you can; otherwise you must trump (play a spade)
     * if you hold one; otherwise play anything. When [CallBreakConfig.mustOvertrump]
     * is set, a spade you play must beat the highest spade already on the trick if
     * you have a higher one.
     */
    fun legalMoves(state: GameState, seat: Seat): List<Card> {
        if (state.phase != Phase.PLAYING || state.currentTurn != seat) return emptyList()
        val hand = state.player(seat).hand
        val led = state.ledSuit ?: return hand // leading: any card (no spades-broken rule)

        val highestSpade = state.currentTrick
            .filter { it.card.suit == Suit.SPADES }
            .maxOfOrNull { it.card.rank.value }

        val followers = hand.filter { it.suit == led }
        if (followers.isNotEmpty()) {
            // Must follow suit. Overtrump only bites when the led suit is itself spades.
            return applyOvertrump(followers, led == Suit.SPADES, highestSpade, state.config)
        }

        val spades = hand.filter { it.suit == Suit.SPADES }
        if (spades.isNotEmpty()) {
            // Void of the led suit but holding trumps: must trump.
            return applyOvertrump(spades, forceSpade = true, highestSpade, state.config)
        }

        return hand // void of both led suit and spades: play anything
    }

    private fun applyOvertrump(
        candidates: List<Card>,
        forceSpade: Boolean,
        highestSpade: Int?,
        config: CallBreakConfig,
    ): List<Card> {
        if (!forceSpade || !config.mustOvertrump || highestSpade == null) return candidates
        val higher = candidates.filter { it.rank.value > highestSpade }
        return if (higher.isNotEmpty()) higher else candidates
    }

    /** The seat that wins a completed 4-card trick. */
    fun trickWinner(trick: List<Play>): Seat {
        require(trick.isNotEmpty()) { "cannot resolve an empty trick" }
        val led = trick.first().card.suit
        val spades = trick.filter { it.card.suit == Suit.SPADES }
        val contenders = spades.ifEmpty { trick.filter { it.card.suit == led } }
        return contenders.maxBy { it.card.rank.value }.seat
    }

    /** Score for one seat in one round given their [call] and tricks [won]. */
    fun roundScore(call: Int, won: Int, config: CallBreakConfig = CallBreakConfig()): Double =
        if (won >= call) call + (won - call) * config.overtrickValue else -call.toDouble()

    fun applyIntent(state: GameState, intent: Intent): ApplyResult = when (intent) {
        is Intent.MakeCall -> handleCall(state, intent)
        is Intent.PlayCard -> handlePlay(state, intent)
        Intent.AdvanceRound -> handleAdvance(state)
    }

    private fun handleCall(state: GameState, intent: Intent.MakeCall): ApplyResult {
        if (state.phase != Phase.BIDDING) return reject("not in bidding phase")
        if (state.currentTurn != intent.seat) return reject("not ${intent.seat}'s turn to call")
        if (state.player(intent.seat).call != null) return reject("${intent.seat} already called")
        if (intent.count !in state.config.minCall..state.config.maxCall) {
            return reject("call ${intent.count} out of range")
        }

        val players = state.players.mutate(intent.seat) { it.copy(call = intent.count) }
        val allCalled = players.values.all { it.call != null }
        return ApplyResult.Success(
            state.copy(
                players = players,
                phase = if (allCalled) Phase.PLAYING else Phase.BIDDING,
                currentTurn = if (allCalled) state.trickLeader else intent.seat.next(),
            ),
        )
    }

    private fun handlePlay(state: GameState, intent: Intent.PlayCard): ApplyResult {
        if (state.phase != Phase.PLAYING) return reject("not in playing phase")
        if (state.currentTurn != intent.seat) return reject("not ${intent.seat}'s turn to play")
        if (intent.card !in state.player(intent.seat).hand) return reject("card not in hand")
        if (intent.card !in legalMoves(state, intent.seat)) return reject("illegal move: must follow suit or trump")

        var players = state.players.mutate(intent.seat) { it.copy(hand = it.hand - intent.card) }
        val trick = state.currentTrick + Play(intent.seat, intent.card)

        if (trick.size < 4) {
            return ApplyResult.Success(
                state.copy(players = players, currentTrick = trick, currentTurn = intent.seat.next()),
            )
        }

        // Trick complete: award it, then either continue, score the round, or end the game.
        val winner = trickWinner(trick)
        players = players.mutate(winner) { it.copy(tricksWon = it.tricksWon + 1) }
        val roundComplete = players.values.all { it.hand.isEmpty() }

        if (!roundComplete) {
            return ApplyResult.Success(
                state.copy(
                    players = players,
                    currentTrick = emptyList(),
                    trickLeader = winner,
                    currentTurn = winner,
                ),
            )
        }

        players = scoreRound(players, state.config)
        val gameOver = state.round >= state.config.rounds
        return ApplyResult.Success(
            state.copy(
                players = players,
                currentTrick = emptyList(),
                trickLeader = winner,
                currentTurn = winner,
                phase = if (gameOver) Phase.GAME_OVER else Phase.ROUND_OVER,
            ),
        )
    }

    private fun handleAdvance(state: GameState): ApplyResult {
        if (state.phase != Phase.ROUND_OVER) return reject("round is not over")
        val nextRound = state.round + 1
        val newDealer = state.dealer.next()
        val hands = dealRound(state.seed, nextRound)
        val players = state.players.mapValues { (seat, p) ->
            p.copy(hand = hands.getValue(seat), call = null, tricksWon = 0, roundScore = 0.0)
        }
        val leader = newDealer.next()
        return ApplyResult.Success(
            state.copy(
                round = nextRound,
                dealer = newDealer,
                phase = Phase.BIDDING,
                currentTurn = leader,
                trickLeader = leader,
                currentTrick = emptyList(),
                players = players,
            ),
        )
    }

    private fun scoreRound(players: Map<Seat, PlayerState>, config: CallBreakConfig): Map<Seat, PlayerState> =
        players.mapValues { (_, p) ->
            val score = roundScore(p.call ?: 0, p.tricksWon, config)
            p.copy(roundScore = score, totalScore = p.totalScore + score)
        }

    private inline fun Map<Seat, PlayerState>.mutate(
        seat: Seat,
        transform: (PlayerState) -> PlayerState,
    ): Map<Seat, PlayerState> = this + (seat to transform(getValue(seat)))

    private fun reject(reason: String): ApplyResult = ApplyResult.Rejected(reason)
}
