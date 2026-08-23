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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** One participant. A [connection] of null marks a server-run bot. */
class Participant(
    val id: String,
    var name: String,
    val connection: Connection?,
    var seat: Seat? = null,
    val isBot: Boolean = false,
    var connected: Boolean = true,
    var avatar: String = "",
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
) {
    private val mutex = Mutex()
    private val participants = LinkedHashMap<String, Participant>()
    private val roundHistory = mutableListOf<Play>()

    var hostId: String? = null
        private set
    private var game: GameState? = null

    val isStarted: Boolean get() = game != null

    // ---- Lobby ------------------------------------------------------------------

    /** Seat the joining human (host = first). Returns their seat, or null if full/started. */
    suspend fun join(playerId: String, name: String, connection: Connection, avatar: String = ""): Seat? = mutex.withLock {
        if (game != null) return@withLock null
        val free = Seat.entries.firstOrNull { seat -> participants.values.none { it.seat == seat } }
            ?: return@withLock null
        if (hostId == null) hostId = playerId
        participants[playerId] = Participant(playerId, name, connection, seat = free, isBot = false, avatar = avatar)
        connection.send(ServerMessage.RoomJoined(code, playerId, free, snapshot()))
        broadcast(ServerMessage.RoomUpdated(snapshot()), except = playerId)
        free
    }

    /** True if a new human could still be seated (pre-game, seat available). */
    suspend fun hasFreeHumanSeat(): Boolean = mutex.withLock {
        game == null && Seat.entries.any { seat -> participants.values.none { it.seat == seat } }
    }

    /** True once no connected human remains — the registry prunes such rooms. */
    suspend fun isAbandoned(): Boolean = mutex.withLock {
        participants.values.none { !it.isBot && it.connected }
    }

    // ---- Message handling -------------------------------------------------------

    suspend fun handle(playerId: String, msg: ClientMessage) = mutex.withLock {
        when (msg) {
            is ClientMessage.StartGame -> if (playerId == hostId && game == null) startGame()
            is ClientMessage.LeaveRoom -> removeLocked(playerId)
            is ClientMessage.MakeCall -> applyPlayerIntent(playerId) { seat -> Intent.MakeCall(seat, msg.count) }
            is ClientMessage.PlayCard -> applyPlayerIntent(playerId) { seat -> Intent.PlayCard(seat, msg.card) }
            is ClientMessage.AdvanceRound -> applyPlayerIntent(playerId) { Intent.AdvanceRound }
            is ClientMessage.Chat -> participants[playerId]?.seat?.let {
                // Sender shows their own bubble locally, so broadcast only to the others.
                broadcast(ServerMessage.Chat(it, msg.text), except = playerId)
            }
            is ClientMessage.CreateRoom, is ClientMessage.JoinByCode, is ClientMessage.QuickMatch -> Unit
        }
    }

    /** Handle a socket dropping: pre-game removes the player; in-game hands the seat to a bot. */
    suspend fun onDisconnect(playerId: String) = mutex.withLock { removeLocked(playerId) }

    private suspend fun removeLocked(playerId: String) {
        val p = participants[playerId] ?: return
        if (game == null) {
            participants.remove(playerId)
            if (playerId == hostId) hostId = participants.values.firstOrNull { !it.isBot }?.id
            broadcast(ServerMessage.RoomUpdated(snapshot()))
        } else {
            p.connected = false
            driveBots() // seat is now bot-driven; keep the game moving if it was their turn
        }
    }

    // ---- Game -------------------------------------------------------------------

    private suspend fun startGame() {
        if (game != null) return
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

    private suspend fun driveBots() {
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
    }

    // ---- Helpers ----------------------------------------------------------------

    private fun seatIsHuman(seat: Seat): Boolean =
        participants.values.any { it.seat == seat && !it.isBot && it.connected }

    private fun snapshot() = RoomSnapshot(
        code = code,
        players = participants.values.map { it.toInfo() },
        hostId = hostId ?: "",
        started = game != null,
    )

    private suspend fun broadcast(message: ServerMessage, except: String? = null) {
        for (p in participants.values) {
            if (p.id == except) continue
            p.connection?.send(message)
        }
    }

    private companion object {
        // Friendly names + avatars for the bots that fill empty seats.
        val BOT_NAMES = listOf("Rohan", "Priya", "Akash", "Neha")
        val BOT_AVATARS = listOf("🤖", "👾", "🐱", "🐶")
    }
}
