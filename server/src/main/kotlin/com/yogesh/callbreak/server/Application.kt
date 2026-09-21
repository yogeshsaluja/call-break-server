package com.yogesh.callbreak.server

import com.yogesh.callbreak.protocol.ClientMessage
import com.yogesh.callbreak.protocol.ProtocolJson
import com.yogesh.callbreak.protocol.ServerMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.UUID

const val DEFAULT_PORT = 8080

fun main() {
    // 0.0.0.0 so the Android emulator can reach it via 10.0.2.2.
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module(paymentService: PaymentService? = PaymentService.fromEnvironment()) {
    // pingPeriod/timeout let the server detect a client that force-quit (its TCP socket
    // never closes cleanly). Without this the read loop blocks forever and the player's
    // disconnect is never handled — freezing the game for everyone else. On timeout the
    // incoming channel closes, the finally runs onDisconnect, and the seat falls to a bot.
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 15_000
    }
    install(CallLogging)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(StatusPages) {
        exception<PaymentRequestException> { call, cause ->
            call.respond(cause.status, PaymentErrorResponse(cause.message ?: "Payment request failed"))
        }
        exception<Throwable> { call, cause ->
            call.respondText("Server error: ${cause.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    val registry = RoomRegistry()

    routing {
        get("/") { call.respondText("Call Break server is up") }

        post("/api/v1/payments/razorpay/orders") {
            val service = paymentService ?: throw PaymentRequestException(
                HttpStatusCode.ServiceUnavailable,
                "Razorpay Test Mode is not configured",
            )
            call.respond(HttpStatusCode.Created, service.createOrder(call.receive()))
        }

        post("/api/v1/payments/razorpay/verify") {
            val service = paymentService ?: throw PaymentRequestException(
                HttpStatusCode.ServiceUnavailable,
                "Razorpay Test Mode is not configured",
            )
            call.respond(service.verify(call.receive()))
        }

        webSocket("/ws") {
            var playerId = UUID.randomUUID().toString().take(8)
            val connection = SocketConnection(playerId, this)
            var room: Room? = null
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val msg = ProtocolJson.decodeFromString<ClientMessage>(frame.readText())
                    val current = room
                    if (current == null) {
                        room = when (msg) {
                            is ClientMessage.CreateRoom -> registry.createRoom(playerId, msg.name, connection, msg.avatar)
                            is ClientMessage.JoinByCode -> registry.joinByCode(msg.code, playerId, msg.name, connection, msg.avatar)
                            is ClientMessage.QuickMatch -> registry.quickMatch(playerId, msg.name, connection, msg.avatar)
                            is ClientMessage.Reconnect -> {
                                registry.reconnect(msg.roomCode, msg.playerId, msg.reconnectToken, connection)
                                    ?.also { playerId = msg.playerId }
                            }
                            else -> {
                                connection.send(ServerMessage.ErrorMsg("Join or create a room first"))
                                null
                            }
                        }
                    } else {
                        if (msg is ClientMessage.LeaveRoom) {
                            registry.leave(current, playerId, connection)
                            connection.send(ServerMessage.LeftRoom)
                            room = null
                            break
                        } else {
                            current.handle(playerId, msg)
                        }
                    }
                }
            } finally {
                room?.let { registry.onDisconnect(it, playerId, connection) }
            }
        }
    }
}
