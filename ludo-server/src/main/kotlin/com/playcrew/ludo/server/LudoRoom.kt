package com.playcrew.ludo.server

import com.playcrew.ludo.ai.LudoBot
import com.playcrew.ludo.engine.LudoAction
import com.playcrew.ludo.engine.LudoActionResult
import com.playcrew.ludo.engine.LudoColor
import com.playcrew.ludo.engine.LudoEngine
import com.playcrew.ludo.engine.LudoPlayer
import com.playcrew.ludo.engine.LudoState
import com.playcrew.ludo.protocol.LudoClientMessage
import com.playcrew.ludo.protocol.LudoRoomPlayer
import com.playcrew.ludo.protocol.LudoRoomSnapshot
import com.playcrew.ludo.protocol.LudoServerMessage
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class Participant(
    val id: String,
    val name: String,
    val color: LudoColor,
    val avatar: String,
    var connection: LudoConnection?,
    var reconnectTokenHash: ByteArray?,
    var permanentBot: Boolean = false,
    var expiry: Job? = null,
)

class LudoRoom(
    val code: String,
    private val scope: CoroutineScope,
    val acceptsQuickMatch: Boolean = false,
    private val onAbandoned: (LudoRoom) -> Unit,
) {
    private val mutex = Mutex()
    private val random = SecureRandom()
    private val participants = linkedMapOf<String, Participant>()
    private var hostId = ""
    private var game: LudoState? = null
    private var botTurnJob: Job? = null

    suspend fun join(
        playerId: String,
        name: String,
        avatar: String,
        connection: LudoConnection,
    ): Boolean = mutex.withLock {
        if (game != null || participants.size >= MAX_PLAYERS || playerId in participants) return@withLock false
        val color = LudoColor.entries.first { candidate -> participants.values.none { it.color == candidate } }
        val token = newReconnectToken()
        participants[playerId] =
            Participant(playerId, name.take(MAX_NAME), color, avatar.take(MAX_AVATAR), connection, token.sha256())
        if (hostId.isBlank()) hostId = playerId
        connection.send(LudoServerMessage.RoomJoined(code, playerId, color, snapshot(), token))
        broadcast(LudoServerMessage.RoomUpdated(snapshot()), except = playerId)
        true
    }

    suspend fun reconnect(
        playerId: String,
        token: String,
        connection: LudoConnection,
    ): Boolean = mutex.withLock {
        val participant = participants[playerId]
        val expected = participant?.reconnectTokenHash
        if (participant == null || participant.permanentBot || expected == null || !constantTimeEquals(expected, token.sha256())) {
            connection.send(LudoServerMessage.ReconnectRejected("Room not found or reservation expired"))
            return@withLock false
        }
        participant.expiry?.cancel()
        participant.expiry = null
        participant.connection?.takeIf { it.connectionId != connection.connectionId }?.close()
        participant.connection = connection
        connection.send(
            LudoServerMessage.Reconnected(code, playerId, participant.color, snapshot(), game),
        )
        broadcast(LudoServerMessage.RoomUpdated(snapshot()), except = playerId)
        true
    }

    suspend fun handle(playerId: String, message: LudoClientMessage) = mutex.withLock {
        val participant = participants[playerId] ?: return@withLock
        when (message) {
            LudoClientMessage.StartGame -> if (playerId == hostId) startLocked()
            LudoClientMessage.RollDice -> handleRollLocked(participant)
            is LudoClientMessage.MoveToken -> handleMoveLocked(participant, message.tokenIndex)
            is LudoClientMessage.Chat -> {
                if (message.text.isNotBlank() && message.text.length <= MAX_CHAT && message.text.none(Char::isISOControl)) {
                    broadcast(LudoServerMessage.Chat(playerId, message.text))
                }
            }
            is LudoClientMessage.Emoji -> {
                if (message.value in ALLOWED_EMOJIS) broadcast(LudoServerMessage.Emoji(playerId, message.value))
            }
            else -> Unit
        }
    }

    suspend fun startIfWaiting() = mutex.withLock { startLocked() }

    suspend fun disconnect(playerId: String, connection: LudoConnection) = mutex.withLock {
        val participant = participants[playerId] ?: return@withLock
        if (participant.connection?.connectionId != connection.connectionId) return@withLock
        participant.connection = null
        participant.expiry?.cancel()
        participant.expiry = scope.launch {
            delay(RECONNECT_WINDOW_MS)
            expire(playerId)
        }
        broadcast(LudoServerMessage.RoomUpdated(snapshot()))
        scheduleBotTurnLocked()
    }

    suspend fun leave(playerId: String, connection: LudoConnection) = mutex.withLock {
        val participant = participants[playerId] ?: return@withLock
        if (participant.connection?.connectionId != connection.connectionId) return@withLock
        participant.expiry?.cancel()
        participant.connection = null
        participant.reconnectTokenHash = null
        if (game == null) {
            participants.remove(playerId)
            if (hostId == playerId) hostId = participants.values.firstOrNull { !it.permanentBot }?.id.orEmpty()
        } else {
            participant.permanentBot = true
        }
        connection.send(LudoServerMessage.LeftRoom)
        broadcast(LudoServerMessage.RoomUpdated(snapshot()))
        pruneOrContinueLocked()
    }

    suspend fun hasSpace(): Boolean = mutex.withLock { game == null && participants.size < MAX_PLAYERS }
    suspend fun humanCount(): Int = mutex.withLock { participants.values.count { !it.permanentBot } }

    private suspend fun startLocked() {
        if (game != null || participants.isEmpty()) return
        while (participants.size < MAX_PLAYERS) {
            val color = LudoColor.entries.first { candidate -> participants.values.none { it.color == candidate } }
            val number = participants.count { it.value.permanentBot } + 1
            val id = "bot-${color.name.lowercase()}"
            participants[id] = Participant(id, "Bot $number", color, "", null, null, permanentBot = true)
        }
        game =
            LudoState.new(
                participants.values.map {
                    LudoPlayer(it.id, it.name, it.color, isBot = it.permanentBot)
                },
            )
        participants.values.filter { !it.permanentBot }.forEach { participant ->
            participant.connection?.send(LudoServerMessage.GameStarted(requireNotNull(game), participant.id))
        }
        scheduleBotTurnLocked()
    }

    private suspend fun handleRollLocked(participant: Participant) {
        val current = game ?: return
        if (current.currentPlayer.id != participant.id || participant.connection == null) return
        val result = LudoEngine.apply(current, LudoAction.RollDice(random.nextInt(6) + 1))
        if (result is LudoActionResult.Accepted) {
            game = result.state
            broadcast(LudoServerMessage.StateUpdated(result.state))
            scheduleBotTurnLocked()
        }
    }

    private suspend fun handleMoveLocked(participant: Participant, tokenIndex: Int) {
        val current = game ?: return
        if (current.currentPlayer.id != participant.id || participant.connection == null) return
        val result = LudoEngine.apply(current, LudoAction.MoveToken(tokenIndex))
        if (result is LudoActionResult.Accepted) {
            game = result.state
            broadcast(LudoServerMessage.StateUpdated(result.state))
            scheduleBotTurnLocked()
        }
    }

    private fun scheduleBotTurnLocked() {
        botTurnJob?.cancel()
        val current = game ?: return
        if (current.winnerId != null) return
        val participant = participants[current.currentPlayer.id] ?: return
        if (!participant.permanentBot && participant.connection != null) return
        botTurnJob = scope.launch {
            delay(BOT_DELAY_MS)
            playOneBotAction()
        }
    }

    private suspend fun playOneBotAction() = mutex.withLock {
        val current = game ?: return@withLock
        val participant = participants[current.currentPlayer.id] ?: return@withLock
        if (!participant.permanentBot && participant.connection != null) return@withLock
        val action =
            if (current.dice == null) {
                LudoAction.RollDice(random.nextInt(6) + 1)
            } else {
                LudoBot.chooseToken(current)?.let(LudoAction::MoveToken) ?: return@withLock
            }
        val result = LudoEngine.apply(current, action)
        if (result is LudoActionResult.Accepted) {
            game = result.state
            broadcast(LudoServerMessage.StateUpdated(result.state))
            scheduleBotTurnLocked()
        }
    }

    private suspend fun expire(playerId: String) = mutex.withLock {
        val participant = participants[playerId] ?: return@withLock
        if (participant.connection != null) return@withLock
        participant.reconnectTokenHash = null
        if (game == null) {
            participants.remove(playerId)
            if (hostId == playerId) hostId = participants.values.firstOrNull { !it.permanentBot }?.id.orEmpty()
        } else {
            participant.permanentBot = true
        }
        broadcast(LudoServerMessage.RoomUpdated(snapshot()))
        pruneOrContinueLocked()
    }

    private fun pruneOrContinueLocked() {
        if (participants.values.none { !it.permanentBot && (it.connection != null || it.reconnectTokenHash != null) }) {
            botTurnJob?.cancel()
            onAbandoned(this)
        } else {
            scheduleBotTurnLocked()
        }
    }

    private fun snapshot(): LudoRoomSnapshot =
        LudoRoomSnapshot(
            code = code,
            hostId = hostId,
            players = participants.values.map {
                LudoRoomPlayer(it.id, it.name, it.color, it.avatar, it.connection != null, it.permanentBot)
            },
            started = game != null,
        )

    private suspend fun broadcast(message: LudoServerMessage, except: String? = null) {
        participants.values
            .filter { it.id != except }
            .mapNotNull(Participant::connection)
            .forEach { connection -> runCatching { connection.send(message) } }
    }

    private fun newReconnectToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun String.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(toByteArray())

    private fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean = MessageDigest.isEqual(first, second)

    private companion object {
        const val MAX_PLAYERS = 4
        const val MAX_NAME = 16
        const val MAX_AVATAR = 512
        const val MAX_CHAT = 120
        const val RECONNECT_WINDOW_MS = 60_000L
        const val BOT_DELAY_MS = 650L
        val ALLOWED_EMOJIS = setOf("👍", "😂", "😮", "😢", "🔥", "🎲")
    }
}
