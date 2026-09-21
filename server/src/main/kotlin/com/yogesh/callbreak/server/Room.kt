package com.yogesh.callbreak.server

import com.yogesh.callbreak.ai.BotContext
import com.yogesh.callbreak.ai.CallBreakAI
import com.yogesh.callbreak.ai.Difficulty
import com.yogesh.callbreak.engine.ApplyResult
import com.yogesh.callbreak.engine.CallBreakConfig
import com.yogesh.callbreak.engine.CallBreakEngine
import com.yogesh.callbreak.engine.GameState
import com.yogesh.callbreak.engine.Intent
import com.yogesh.callbreak.engine.Phase
import com.yogesh.callbreak.engine.Play
import com.yogesh.callbreak.engine.Seat
import com.yogesh.callbreak.protocol.ClientMessage
import com.yogesh.callbreak.protocol.PlayerInfo
import com.yogesh.callbreak.protocol.RoomSnapshot
import com.yogesh.callbreak.protocol.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.random.Random

/** One participant. A null [connection] marks a bot or a temporarily disconnected human. */
class Participant(
    val id: String,
    var name: String,
    var connection: Connection?,
    var seat: Seat? = null,
    var isBot: Boolean = false,
    var connected: Boolean = true,
    var avatar: String = "",
    var autoPlay: Boolean = false,
    var reconnectTokenHash: ByteArray? = null,
    var reconnectExpiryJob: Job? = null,
    val walletId: String? = null,
) {
    fun toInfo() = PlayerInfo(id = id, name = name, seat = seat, isBot = isBot, connected = connected, avatar = avatar)
}

/**
 * A single game room: the **authoritative** holder of the game. It runs the identical
 * [CallBreakEngine] the clients run offline, so it is the sole source of truth. Empty
 * seats are filled with [CallBreakAI] bots at start; a disconnected human's seat also
 * falls back to bot control so the game never stalls.
 *
 * The bot-driving loop mirrors the offline `LocalSession.driveBots()`: everything runs
 * under one [mutex] so turns never interleave.
 */
class Room(
    val code: String,
    private val botDifficulty: Difficulty = Difficulty.HARD,
    private val config: CallBreakConfig = CallBreakConfig(),
    private val pace: Long = 700L,
    private val trickHoldMs: Long = 650L,
    private val sweepMs: Long = 450L,
    private val roundAdvanceDelayMs: Long = 5_000L,
    private val reconnectGraceMs: Long = 60_000L,
    private val onReservationExpired: suspend (Room) -> Unit = {},
    private val coinWallets: CoinWalletStore = CoinWalletStore(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val participants = LinkedHashMap<String, Participant>()
    private val roundHistory = mutableListOf<Play>()
    private var roundAdvanceJob: Job? = null
    private var disconnectedBotJob: Job? = null

    var hostId: String? = null
        private set
    private var game: GameState? = null

    val isStarted: Boolean get() = game != null

    // ---- Lobby ------------------------------------------------------------------

    /** Seat the joining human (host = first). Returns their seat, or null if full/started. */
    suspend fun join(
        playerId: String,
        name: String,
        connection: Connection,
        avatar: String = "",
        walletId: String? = null,
    ): Seat? = mutex.withLock {
        if (game != null) return@withLock null
        val free = Seat.entries.firstOrNull { seat -> participants.values.none { it.seat == seat } }
            ?: return@withLock null
        if (hostId == null) hostId = playerId
        val reconnectToken = newReconnectToken()
        participants[playerId] = Participant(
            playerId,
            name,
            connection,
            seat = free,
            isBot = false,
            avatar = avatar,
            reconnectTokenHash = hashToken(reconnectToken),
            walletId = walletId,
        )
        connection.send(ServerMessage.RoomJoined(code, playerId, free, snapshot(), reconnectToken))
        walletId?.let { connection.send(ServerMessage.WalletBalance(coinWallets.balance(it))) }
        broadcast(ServerMessage.RoomUpdated(snapshot()), except = playerId)
        free
    }

    /** True if a new human could still be seated (pre-game, seat available). */
    suspend fun hasFreeHumanSeat(): Boolean = mutex.withLock {
        game == null && Seat.entries.any { seat -> participants.values.none { it.seat == seat } }
    }

    /** True once no human seat or reconnect reservation remains. */
    suspend fun isAbandoned(): Boolean = mutex.withLock {
        participants.values.none { !it.isBot }
    }

    /** Stop delayed room work after the registry removes an abandoned table. */
    fun close() {
        roundAdvanceJob?.cancel()
        roundAdvanceJob = null
        disconnectedBotJob?.cancel()
        disconnectedBotJob = null
        participants.values.forEach { it.reconnectExpiryJob?.cancel() }
    }

    // ---- Message handling -------------------------------------------------------

    suspend fun handle(playerId: String, msg: ClientMessage) = mutex.withLock {
        when (msg) {
            is ClientMessage.StartGame -> if (playerId == hostId && game == null) startGame()
            is ClientMessage.LeaveRoom -> Unit
            is ClientMessage.MakeCall -> applyPlayerIntent(playerId) { seat -> Intent.MakeCall(seat, msg.count) }
            is ClientMessage.PlayCard -> applyPlayerIntent(playerId) { seat -> Intent.PlayCard(seat, msg.card) }
            is ClientMessage.AdvanceRound -> advanceRound(playerId)
            is ClientMessage.SetAutoPlay -> setAutoPlay(playerId, msg.enabled)
            is ClientMessage.Chat -> participants[playerId]?.seat?.let {
                broadcast(ServerMessage.Chat(it, msg.text))
            }
            is ClientMessage.Throw -> participants[playerId]?.seat?.let {
                broadcast(ServerMessage.Throw(it, msg.targetSeat, msg.item))
            }
            is ClientMessage.CreateRoom, is ClientMessage.JoinByCode,
            is ClientMessage.QuickMatch, is ClientMessage.Reconnect -> Unit
        }
    }

    /** Reattach a socket to a reserved seat and return a complete authoritative snapshot. */
    suspend fun reconnect(playerId: String, token: String, connection: Connection): Boolean = mutex.withLock {
        val participant = participants[playerId]
        val expectedHash = participant?.reconnectTokenHash
        if (participant == null || participant.isBot || expectedHash == null ||
            !MessageDigest.isEqual(expectedHash, hashToken(token))
        ) {
            connection.send(ServerMessage.ReconnectRejected("Seat reservation is invalid or expired"))
            return@withLock false
        }

        val previous = participant.connection
        participant.reconnectExpiryJob?.cancel()
        participant.reconnectExpiryJob = null
        participant.connection = connection
        participant.connected = true
        previous?.takeIf { it !== connection }?.let { old ->
            scope.launch { runCatching { old.close() } }
        }

        val seat = requireNotNull(participant.seat)
        connection.send(
            ServerMessage.Reconnected(
                code = code,
                youId = participant.id,
                yourSeat = seat,
                snapshot = snapshot(),
                state = game,
                autoPlay = participant.autoPlay,
            ),
        )
        broadcast(ServerMessage.RoomUpdated(snapshot()), except = playerId)
        driveBots()
        true
    }

    /** Ignore stale disconnects from a socket that has already been replaced. */
    suspend fun onDisconnect(playerId: String, connection: Connection) = mutex.withLock {
        val participant = participants[playerId] ?: return@withLock
        if (participant.connection !== connection || !participant.connected) return@withLock
        participant.connection = null
        participant.connected = false
        participant.reconnectExpiryJob?.cancel()
        participant.reconnectExpiryJob = scope.launch {
            delay(reconnectGraceMs)
            expireReservation(playerId, participant)
        }
        broadcast(ServerMessage.RoomUpdated(snapshot()))
        driveBots()
    }

    /** Explicit leave invalidates the reconnect credential immediately. */
    suspend fun leave(playerId: String, connection: Connection): Boolean = mutex.withLock {
        val participant = participants[playerId] ?: return@withLock false
        if (participant.connection !== connection) return@withLock false
        removeLocked(playerId)
        true
    }

    private suspend fun expireReservation(playerId: String, expected: Participant) {
        var abandoned = false
        mutex.withLock {
            val participant = participants[playerId]
            if (participant !== expected || participant.connected) return@withLock
            participant.reconnectExpiryJob = null
            if (game == null) {
                participants.remove(playerId)
                if (playerId == hostId) hostId = participants.values.firstOrNull { !it.isBot }?.id
            } else {
                participant.isBot = true
                participant.connected = true
                participant.autoPlay = true
                participant.reconnectTokenHash = null
            }
            broadcast(ServerMessage.RoomUpdated(snapshot()))
            driveBots()
            abandoned = participants.values.none { !it.isBot }
        }
        if (abandoned) onReservationExpired(this)
    }

    private suspend fun removeLocked(playerId: String) {
        val p = participants[playerId] ?: return
        p.reconnectExpiryJob?.cancel()
        p.reconnectExpiryJob = null
        p.reconnectTokenHash = null
        if (game == null) {
            participants.remove(playerId)
            if (playerId == hostId) hostId = participants.values.firstOrNull { !it.isBot }?.id
            broadcast(ServerMessage.RoomUpdated(snapshot()))
        } else {
            p.connection = null
            p.connected = true
            p.isBot = true
            p.autoPlay = true
            broadcast(ServerMessage.RoomUpdated(snapshot()))
            driveBots() // seat is now bot-driven; keep the game moving if it was their turn
        }
    }

    // ---- Game -------------------------------------------------------------------

    private suspend fun startGame() {
        if (game != null) return
        val humans = participants.values.filter { !it.isBot && it.walletId != null }
        val canStart = humans.all { participant ->
            coinWallets.debitOnce(
                requireNotNull(participant.walletId),
                "game:$code:entry:${participant.id}",
                ENTRY_FEE,
            )
        }
        if (!canStart) {
            humans.forEach { participant ->
                participant.connection?.send(ServerMessage.ErrorMsg("A player does not have enough coins"))
            }
            return
        }
        val taken = participants.values.mapNotNull { it.seat }.toSet()
        var botIndex = 0
        for (seat in Seat.entries) {
            if (seat !in taken) {
                val id = "bot-$code-${botIndex + 1}"
                participants[id] = Participant(
                    id = id,
                    name = BOT_NAMES[botIndex % BOT_NAMES.size],
                    connection = null,
                    seat = seat,
                    isBot = true,
                    avatar = BOT_AVATARS[botIndex % BOT_AVATARS.size],
                )
                botIndex++
            }
        }
        val fresh = CallBreakEngine.newGame(seed = Random.nextLong(), config = config, firstDealer = Seat.SOUTH)
        game = fresh
        roundHistory.clear()
        val roster = participants.values.map { it.toInfo() }
        for (p in participants.values) {
            val seat = p.seat ?: continue
            p.connection?.send(ServerMessage.GameStarted(fresh, seat, roster))
            p.walletId?.let { p.connection?.send(ServerMessage.WalletBalance(coinWallets.balance(it))) }
        }
        driveBots()
    }

    private suspend fun applyPlayerIntent(playerId: String, build: (Seat) -> Intent) {
        val p = participants[playerId] ?: return
        val seat = p.seat ?: return
        val g = game ?: return
        val intent = build(seat)
        when (intent) {
            is Intent.MakeCall, is Intent.PlayCard ->
                if (g.currentTurn != seat) {
                    p.connection?.send(ServerMessage.ErrorMsg("Not your turn"))
                    return
                }
            Intent.AdvanceRound -> if (g.phase != Phase.ROUND_OVER) return
        }
        applyStep(intent)
        driveBots()
    }

    /** Any connected player can continue the whole table before the fallback timer fires. */
    private suspend fun advanceRound(playerId: String) {
        val participant = participants[playerId] ?: return
        if (!participant.connected || game?.phase != Phase.ROUND_OVER) return
        roundAdvanceJob?.cancel()
        roundAdvanceJob = null
        applyStep(Intent.AdvanceRound)
        driveBots()
    }

    private suspend fun setAutoPlay(playerId: String, enabled: Boolean) {
        val participant = participants[playerId] ?: return
        if (participant.isBot || !participant.connected || game == null) return
        participant.autoPlay = enabled
        if (enabled) driveBots()
    }

    private suspend fun driveBots() {
        if (!hasManualHuman()) {
            scheduleDisconnectedBotStep()
            return
        }
        while (true) {
            val g = game ?: return
            when (g.phase) {
                Phase.GAME_OVER, Phase.ROUND_OVER -> return
                Phase.BIDDING -> {
                    val seat = g.currentTurn
                    if (seatIsHuman(seat)) return
                    delay(pace)
                    applyStep(Intent.MakeCall(seat, CallBreakAI.call(g.player(seat).hand, botDifficulty, config)))
                }
                Phase.PLAYING -> {
                    val seat = g.currentTurn
                    if (seatIsHuman(seat)) return
                    delay(pace)
                    applyStep(Intent.PlayCard(seat, CallBreakAI.play(BotContext(g, seat, roundHistory.toList()), botDifficulty)))
                }
            }
        }
    }

    /** Let an empty table progress without monopolising the room lock needed by reconnect. */
    private fun scheduleDisconnectedBotStep() {
        if (disconnectedBotJob?.isActive == true) return
        val current = game ?: return
        if (current.phase == Phase.GAME_OVER || current.phase == Phase.ROUND_OVER) return
        disconnectedBotJob = scope.launch {
            delay(pace)
            mutex.withLock {
                disconnectedBotJob = null
                if (hasManualHuman()) {
                    driveBots()
                    return@withLock
                }
                val state = game ?: return@withLock
                when (state.phase) {
                    Phase.BIDDING -> applyStep(
                        Intent.MakeCall(
                            state.currentTurn,
                            CallBreakAI.call(state.player(state.currentTurn).hand, botDifficulty, config),
                        ),
                    )
                    Phase.PLAYING -> applyStep(
                        Intent.PlayCard(
                            state.currentTurn,
                            CallBreakAI.play(BotContext(state, state.currentTurn, roundHistory.toList()), botDifficulty),
                        ),
                    )
                    Phase.ROUND_OVER, Phase.GAME_OVER -> return@withLock
                }
                driveBots()
            }
        }
    }

    /** Apply one intent through the engine and broadcast the authoritative result. */
    private suspend fun applyStep(intent: Intent) {
        val pre = game ?: return
        val next = (CallBreakEngine.applyIntent(pre, intent) as? ApplyResult.Success)?.state ?: return

        when (intent) {
            is Intent.PlayCard -> roundHistory.add(Play(intent.seat, intent.card))
            Intent.AdvanceRound -> roundHistory.clear()
            is Intent.MakeCall -> Unit
        }

        if (intent is Intent.PlayCard) {
            val fullTrick = pre.currentTrick + Play(intent.seat, intent.card)
            if (fullTrick.size == 4) {
                val winner = CallBreakEngine.trickWinner(fullTrick)
                val handsAfter = pre.players.mapValues { (s, ps) ->
                    if (s == intent.seat) ps.copy(hand = ps.hand - intent.card) else ps
                }
                val displayed = pre.copy(players = handsAfter, currentTrick = fullTrick)
                broadcast(ServerMessage.TrickResolved(displayed, winner, sweeping = false))
                delay(trickHoldMs)
                broadcast(ServerMessage.TrickResolved(displayed, winner, sweeping = true))
                delay(sweepMs)
            }
        }

        game = next
        broadcast(ServerMessage.StateUpdate(next))
        if (pre.phase != Phase.GAME_OVER && next.phase == Phase.GAME_OVER) awardWinner(next)
        if (next.phase == Phase.ROUND_OVER) scheduleRoundAdvance()
    }

    private suspend fun awardWinner(state: GameState) {
        val winningSeat = state.players.maxByOrNull { it.value.totalScore }?.key ?: return
        val winner = participants.values.firstOrNull { it.seat == winningSeat } ?: return
        val walletId = winner.walletId ?: return
        val balance = coinWallets.creditOnce(walletId, "game:$code:winner", WIN_POT)
        winner.connection?.send(ServerMessage.WalletBalance(balance))
    }

    private fun scheduleRoundAdvance() {
        roundAdvanceJob?.cancel()
        roundAdvanceJob = scope.launch {
            delay(roundAdvanceDelayMs)
            mutex.withLock {
                if (game?.phase != Phase.ROUND_OVER) return@withLock
                roundAdvanceJob = null
                applyStep(Intent.AdvanceRound)
                driveBots()
            }
        }
    }

    // ---- Helpers ----------------------------------------------------------------

    private fun seatIsHuman(seat: Seat): Boolean =
        participants.values.any { it.seat == seat && !it.isBot && it.connected && !it.autoPlay }

    private fun hasManualHuman(): Boolean =
        participants.values.any { !it.isBot && it.connected && !it.autoPlay }

    private fun snapshot() = RoomSnapshot(
        code = code,
        players = participants.values.map { it.toInfo() },
        hostId = hostId ?: "",
        started = game != null,
    )

    private suspend fun broadcast(message: ServerMessage, except: String? = null) {
        for (p in participants.values) {
            if (p.id == except || !p.connected) continue
            runCatching { p.connection?.send(message) }
                .onFailure { p.connected = false }
        }
    }

    private companion object {
        // Friendly names + avatars for the bots that fill empty seats.
        val BOT_NAMES = listOf("Rohan", "Priya", "Akash", "Neha")
        val BOT_AVATARS = listOf("🤖", "👾", "🐱", "🐶")

        val SECURE_RANDOM = SecureRandom()

        fun newReconnectToken(): String = ByteArray(32)
            .also(SECURE_RANDOM::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        fun hashToken(token: String): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(token.encodeToByteArray())

        const val ENTRY_FEE = 30
        const val WIN_POT = 120
    }
}
