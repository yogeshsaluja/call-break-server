package com.yogesh.callbreak.server

import com.yogesh.callbreak.protocol.ClientMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

internal object SecurityPolicy {
    const val MAX_FRAME_BYTES = 64L * 1024L
    const val MAX_MESSAGE_CHARS = 16 * 1024
    const val MAX_CONNECTIONS_PER_IP = 8
    const val MAX_CONNECTIONS_PER_USER = 2
    const val MAX_MESSAGES_PER_WINDOW = 60
    const val MAX_ROOM_ENTRIES_PER_WINDOW = 12
    const val MAX_ROOM_CREATIONS_PER_WINDOW = 3
    const val MAX_PURCHASE_VERIFICATIONS_PER_WINDOW = 5
    const val MAX_ACTIVE_ROOMS = 500
    const val MAX_NAME_LENGTH = 16
    const val MAX_AVATAR_LENGTH = 512
    const val MAX_CHAT_LENGTH = 120
    const val MAX_THROW_ITEM_LENGTH = 24
    val RATE_WINDOW = 10.seconds
    val ABUSE_WINDOW = 1.minutes
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

internal class KeyedRateLimiter(
    private val maximum: Int,
    private val windowMillis: Long,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Window(var startedAt: Long, var count: Int)

    private val windows = LinkedHashMap<String, Window>()

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clockMillis()
        if (windows.size >= MAX_TRACKED_KEYS) {
            windows.entries.removeAll { now - it.value.startedAt >= windowMillis }
            if (windows.size >= MAX_TRACKED_KEYS && key !in windows) return false
        }
        val current = windows[key]
        if (current == null || now - current.startedAt >= windowMillis) {
            windows[key] = Window(now, 1)
            return true
        }
        current.count++
        return current.count <= maximum
    }

    private companion object {
        const val MAX_TRACKED_KEYS = 10_000
    }
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
