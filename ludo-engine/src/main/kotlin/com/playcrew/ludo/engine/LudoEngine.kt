package com.playcrew.ludo.engine

object LudoEngine {
    fun apply(state: LudoState, action: LudoAction): LudoActionResult {
        if (state.winnerId != null) return LudoActionResult.Rejected("Game is already complete")
        return when (action) {
            is LudoAction.RollDice -> roll(state, action.value)
            is LudoAction.MoveToken -> move(state, action.tokenIndex)
        }
    }

    fun legalMoves(state: LudoState): List<Int> {
        val roll = state.dice ?: return emptyList()
        val player = state.currentPlayer
        return state.tokens.getValue(player.color).indices.filter { canMove(state, player.color, it, roll) }
    }

    private fun roll(state: LudoState, value: Int): LudoActionResult {
        if (value !in 1..6) return LudoActionResult.Rejected("Invalid dice")
        if (state.dice != null) return LudoActionResult.Rejected("Move before rolling")
        val sixes = if (value == 6) state.consecutiveSixes + 1 else 0
        if (sixes == 3) return LudoActionResult.Accepted(nextTurn(state.copy(consecutiveSixes = 0)))
        val rolled = state.copy(dice = value, consecutiveSixes = sixes)
        return if (legalMoves(rolled).isEmpty()) {
            LudoActionResult.Accepted(nextTurn(rolled.copy(consecutiveSixes = 0)))
        } else {
            LudoActionResult.Accepted(rolled)
        }
    }

    private fun move(state: LudoState, tokenIndex: Int): LudoActionResult {
        val roll = state.dice ?: return LudoActionResult.Rejected("Roll first")
        val player = state.currentPlayer
        val own = state.tokens.getValue(player.color)
        if (tokenIndex !in own.indices || !canMove(state, player.color, tokenIndex, roll)) {
            return LudoActionResult.Rejected("Illegal move")
        }
        val old = own[tokenIndex]
        val progress = if (old.isInYard) 0 else old.progress + roll
        val movedOwn = own.toMutableList().also { it[tokenIndex] = LudoToken(progress) }
        var tokens = state.tokens + (player.color to movedOwn)
        val captured = mutableListOf<CapturedToken>()
        val destination = LudoToken(progress).globalSquare(player.color)
        if (destination != null && destination !in SAFE_GLOBAL_SQUARES) {
            state.players.filter { it.color != player.color }.forEach { opponent ->
                val opponents = tokens.getValue(opponent.color).toMutableList()
                opponents.forEachIndexed { index, token ->
                    if (token.globalSquare(opponent.color) == destination) {
                        opponents[index] = LudoToken()
                        captured += CapturedToken(opponent.color, index)
                    }
                }
                tokens = tokens + (opponent.color to opponents)
            }
        }
        val winner = movedOwn.takeIf { list -> list.all(LudoToken::isFinished) }?.let { player.id }
        val moved = state.copy(tokens = tokens, dice = null, winnerId = winner)
        val repeat = winner == null && (roll == 6 || captured.isNotEmpty())
        return LudoActionResult.Accepted(
            if (repeat) moved else nextTurn(moved.copy(consecutiveSixes = 0)),
            tokenIndex,
            captured,
        )
    }

    private fun canMove(state: LudoState, color: LudoColor, tokenIndex: Int, roll: Int): Boolean {
        val token = state.tokens.getValue(color)[tokenIndex]
        if (token.isFinished) return false
        if (token.isInYard) return roll == 6 && !isOpponentBlockade(state, color, color.startOffset)
        if (token.progress + roll > FINISH) return false
        if (token.isOnSharedTrack) {
            val sharedSteps = minOf(roll, TRACK_SIZE - token.progress)
            for (step in 1..sharedSteps) {
                val square = (color.startOffset + token.progress + step) % TRACK_SIZE
                if (isOpponentBlockade(state, color, square)) return false
            }
        }
        return true
    }

    private fun isOpponentBlockade(state: LudoState, movingColor: LudoColor, square: Int): Boolean =
        state.players.filter { it.color != movingColor }.any { opponent ->
            state.tokens.getValue(opponent.color).count { it.globalSquare(opponent.color) == square } >= 2
        }

    private fun nextTurn(state: LudoState): LudoState =
        state.copy(
            currentPlayerIndex = (state.currentPlayerIndex + 1) % state.players.size,
            dice = null,
            turnNumber = state.turnNumber + 1,
        )
}
