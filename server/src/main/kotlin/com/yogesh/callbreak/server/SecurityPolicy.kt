package com.yogesh.callbreak.server

import com.yogesh.callbreak.protocol.ClientMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal object SecurityPolicy {
    const val MAX_FRAME_BYTES = 64L * 1024L
    const val MAX_MESSAGE_CHARS = 16 * 1024
    const val MAX_CONNECTIONS_PER_IP = 20
    const val MAX_MESSAGES_PER_WINDOW = 60
    const val MAX_NAME_LENGTH = 16
    const val MAX_AVATAR_LENGTH = 512
    const val MAX_CHAT_LENGTH = 120
    const val MAX_THROW_ITEM_LENGTH = 24
    val RATE_WINDOW = 10.seconds
    val JOIN_TIMEOUT = 10.seconds

    fun isValid(message: ClientMessage): Boolean =
        when (message) {
            is ClientMessage.CreateRoom -> validIdentity(message.name, message.avatar)
            is ClientMessage.JoinByCode ->
                validRoomCode(message.code) && validIdentity(message.name, message.avatar)
            is ClientMessage.QuickMatch -> validIdentity(message.name, message.avatar)
            is ClientMessage.Reconnect ->
                validRoomCode(message.roomCode) &&
                    message.playerId.matches(PLAYER_ID) &&
                    message.reconnectToken.length in 32..256
            is ClientMessage.MakeCall -> message.count in 1..13
            is ClientMessage.Chat ->
                message.text.isNotBlank() &&
                    message.text.length <= MAX_CHAT_LENGTH &&
                    message.text.none(Char::isISOControl)
            is ClientMessage.Throw -> message.item in ALLOWED_THROW_ITEMS
            ClientMessage.AdvanceRound,
            ClientMessage.LeaveRoom,
            is ClientMessage.PlayCard,
            is ClientMessage.SetAutoPlay,
            ClientMessage.StartGame,
            -> true
        }

    private fun validIdentity(name: String, avatar: String): Boolean =
        name.isNotBlank() &&
            name.length <= MAX_NAME_LENGTH &&
            name.none(Char::isISOControl) &&
            validAvatar(avatar)

    private fun validAvatar(avatar: String): Boolean =
        avatar.isBlank() ||
            (avatar.length <= 8 && avatar.none(Char::isISOControl)) ||
            (avatar.length <= MAX_AVATAR_LENGTH && avatar.startsWith("https://"))

    private fun validRoomCode(code: String): Boolean = code.matches(ROOM_CODE)

    private val ROOM_CODE = Regex("[A-HJ-NP-Z2-9]{4}", RegexOption.IGNORE_CASE)
    private val PLAYER_ID = Regex("[a-f0-9]{8}")
    private val ALLOWED_THROW_ITEMS = setOf("🍅", "🥚", "👟", "🍌", "💣", "🍰")
}

internal class ConnectionLimiter(
    private val maximum: Int = SecurityPolicy.MAX_CONNECTIONS_PER_IP,
) {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun tryAcquire(key: String): Boolean {
        val count = counts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        if (count <= maximum) return true
        release(key)
        return false
    }

    fun release(key: String) {
        counts.computeIfPresent(key) { _, count ->
            if (count.decrementAndGet() <= 0) null else count
        }
    }
}

internal class MessageRateLimiter(
    private val maximum: Int = SecurityPolicy.MAX_MESSAGES_PER_WINDOW,
    private val window: Duration = SecurityPolicy.RATE_WINDOW,
) {
    private var windowStarted = TimeSource.Monotonic.markNow()
    private var count = 0

    @Synchronized
    fun tryAcquire(): Boolean {
        if (windowStarted.elapsedNow() >= window) {
            windowStarted = TimeSource.Monotonic.markNow()
            count = 0
        }
        count++
        return count <= maximum
    }
}
