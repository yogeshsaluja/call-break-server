package com.playcrew.ludo.server

import com.playcrew.ludo.protocol.LudoClientMessage
import com.playcrew.ludo.protocol.LudoProtocolJson
import com.playcrew.ludo.protocol.LudoServerMessage
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString

private const val DEFAULT_PORT = 8083

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val host = System.getenv("HOST")?.takeIf(String::isNotBlank) ?: "127.0.0.1"
    embeddedServer(CIO, port = port, host = host) { ludoModule() }.start(wait = true)
}

fun Application.ludoModule() {
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 15_000
        maxFrameSize = MAX_FRAME_BYTES
    }
    install(CallLogging)
    install(XForwardedHeaders)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    monitor.subscribe(ApplicationStopped) { scope.cancel() }
    val registry = LudoRoomRegistry(scope)
    val connections = ConnectionLimiter()

    routing {
        get("/") { call.respondText("Ludo server is up") }
        webSocket("/ws") {
            val remoteAddress = call.request.origin.remoteHost
            if (!connections.tryAcquire(remoteAddress)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Connection limit exceeded"))
                return@webSocket
            }
            var playerId = UUID.randomUUID().toString().take(8)
            val connection = SocketLudoConnection(UUID.randomUUID().toString(), this)
            var room: LudoRoom? = null
            val messageRate = SlidingMessageRate()
            try {
                while (true) {
                    val frame =
                        if (room == null) {
                            withTimeoutOrNull(JOIN_TIMEOUT_MS) { incoming.receiveCatching().getOrNull() }
                        } else {
                            incoming.receiveCatching().getOrNull()
                        } ?: break
                    if (frame !is Frame.Text) continue
                    if (!messageRate.tryAcquire()) {
                        connection.send(LudoServerMessage.Error("Too many requests"))
                        continue
                    }
                    val text = frame.readText()
                    if (text.length > MAX_MESSAGE_CHARS) {
                        close(CloseReason(CloseReason.Codes.TOO_BIG, "Message too large"))
                        break
                    }
                    val message = runCatching { LudoProtocolJson.decodeFromString<LudoClientMessage>(text) }
                        .getOrElse {
                            connection.send(LudoServerMessage.Error("Invalid message"))
                            continue
                        }
                    if (!valid(message)) {
                        connection.send(LudoServerMessage.Error("Invalid message"))
                        continue
                    }

                    val current = room
                    if (current == null) {
                        room =
                            when (message) {
                                is LudoClientMessage.CreateRoom ->
                                    registry.create(playerId, message.name, message.avatar, connection)
                                is LudoClientMessage.JoinByCode ->
                                    registry.join(message.code, playerId, message.name, message.avatar, connection)
                                is LudoClientMessage.QuickMatch ->
                                    registry.quickMatch(playerId, message.name, message.avatar, connection)
                                is LudoClientMessage.Reconnect -> {
                                    registry.reconnect(message.roomCode, message.playerId, message.reconnectToken, connection)
                                        ?.also { playerId = message.playerId }
                                }
                                else -> null
                            }
                        if (room == null && message !is LudoClientMessage.Reconnect) {
                            connection.send(LudoServerMessage.Error("Room is unavailable"))
                        }
                    } else if (message is LudoClientMessage.LeaveRoom) {
                        current.leave(playerId, connection)
                        room = null
                        break
                    } else {
                        current.handle(playerId, message)
                    }
                }
            } finally {
                room?.disconnect(playerId, connection)
                connections.release(remoteAddress)
            }
        }
    }
}

private fun valid(message: LudoClientMessage): Boolean =
    when (message) {
        is LudoClientMessage.CreateRoom -> validIdentity(message.name, message.avatar)
        is LudoClientMessage.JoinByCode -> message.code.matches(ROOM_CODE) && validIdentity(message.name, message.avatar)
        is LudoClientMessage.QuickMatch -> validIdentity(message.name, message.avatar)
        is LudoClientMessage.Reconnect ->
            message.roomCode.matches(ROOM_CODE) && message.playerId.matches(PLAYER_ID) && message.reconnectToken.length in 32..256
        is LudoClientMessage.MoveToken -> message.tokenIndex in 0..3
        is LudoClientMessage.Chat -> message.text.length <= 120
        is LudoClientMessage.Emoji -> message.value.length <= 8
        else -> true
    }

private fun validIdentity(name: String, avatar: String): Boolean =
    name.isNotBlank() && name.length <= 16 && name.none(Char::isISOControl) &&
        (avatar.isBlank() || (avatar.length <= 512 && avatar.startsWith("https://")))

private class ConnectionLimiter {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun tryAcquire(key: String): Boolean {
        val count = counts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        if (count <= MAX_CONNECTIONS_PER_IP) return true
        release(key)
        return false
    }

    fun release(key: String) {
        counts.computeIfPresent(key) { _, count -> if (count.decrementAndGet() <= 0) null else count }
    }
}

private class SlidingMessageRate {
    private var startedAt = System.currentTimeMillis()
    private var count = 0

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        if (now - startedAt >= RATE_WINDOW_MS) {
            startedAt = now
            count = 0
        }
        count++
        return count <= MAX_MESSAGES_PER_WINDOW
    }
}

private const val MAX_FRAME_BYTES = 64L * 1024L
private const val MAX_MESSAGE_CHARS = 16 * 1024
private const val MAX_CONNECTIONS_PER_IP = 8
private const val MAX_MESSAGES_PER_WINDOW = 60
private const val RATE_WINDOW_MS = 10_000L
private const val JOIN_TIMEOUT_MS = 10_000L
private val ROOM_CODE = Regex("[A-HJ-NP-Z2-9]{4}", RegexOption.IGNORE_CASE)
private val PLAYER_ID = Regex("[a-f0-9]{8}")
