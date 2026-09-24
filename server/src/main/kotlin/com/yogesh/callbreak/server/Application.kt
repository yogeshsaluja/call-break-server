package com.yogesh.callbreak.server

import com.yogesh.callbreak.protocol.ClientMessage
import com.yogesh.callbreak.protocol.ProtocolJson
import com.yogesh.callbreak.protocol.ServerMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
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
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID

const val DEFAULT_PORT = 8080

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val host = System.getenv("HOST")?.takeIf(String::isNotBlank) ?: "127.0.0.1"
    embeddedServer(CIO, port = port, host = host) { module() }.start(wait = true)
}

fun Application.module(
    identitySecurity: IdentitySecurity = IdentitySecurity.fromEnvironment(),
    coinWallets: CoinWalletStore = CoinWalletStore.fromEnvironment(),
    playBilling: GooglePlayBillingService = GooglePlayBillingService.fromEnvironment(coinWallets),
) {
    val applicationLog = environment.log
    // pingPeriod/timeout let the server detect a client that force-quit (its TCP socket
    // never closes cleanly). Without this the read loop blocks forever and the player's
    // disconnect is never handled — freezing the game for everyone else. On timeout the
    // incoming channel closes, the finally runs onDisconnect, and the seat falls to a bot.
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 15_000
        maxFrameSize = SecurityPolicy.MAX_FRAME_BYTES
    }
    install(CallLogging)
    install(XForwardedHeaders)
    install(Authentication) {
        bearer("firebase") {
            authenticate { credentials -> identitySecurity.verifier?.verify(credentials.token) }
        }
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(StatusPages) {
        exception<GooglePlayBillingException> { call, cause ->
            call.respond(cause.status, ApiErrorResponse(cause.message ?: "Google Play purchase failed"))
        }
        exception<Throwable> { call, cause ->
            applicationLog.error("Unhandled request failure", cause)
            call.respondText("Internal server error", status = HttpStatusCode.InternalServerError)
        }
    }

    val registry = RoomRegistry(coinWallets)
    val connections = ConnectionLimiter()

    routing {
        get("/") { call.respondText("Call Break server is up") }

        authenticate("firebase", optional = !identitySecurity.required) {
            get("/api/v1/wallet") {
                val user = call.principal<AuthenticatedUser>()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("Authentication required"))
                    return@get
                }
                val walletId = CoinWalletStore.walletIdForUser(user.uid)
                call.respond(WalletBalanceResponse(coinWallets.balance(walletId), walletId))
            }

            post("/api/v1/payments/google-play/verify") {
                val user = call.principal<AuthenticatedUser>()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("Authentication required"))
                    return@post
                }
                call.respond(playBilling.verifyAndCredit(user, call.receive()))
            }

            webSocket("/ws") {
                val remoteAddress = call.request.origin.remoteHost
                if (!connections.tryAcquire(remoteAddress)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Connection limit exceeded"))
                    return@webSocket
                }
                var playerId = UUID.randomUUID().toString().take(8)
                val authenticatedUser = call.principal<AuthenticatedUser>()
                val walletId = authenticatedUser?.let { CoinWalletStore.walletIdForUser(it.uid) }
                val connection = SocketConnection(playerId, this)
                var room: Room? = null
                val messageRate = MessageRateLimiter()
                try {
                    while (true) {
                        val frame =
                            if (room == null) {
                                withTimeoutOrNull(SecurityPolicy.JOIN_TIMEOUT) {
                                    incoming.receiveCatching().getOrNull()
                                }
                            } else {
                                incoming.receiveCatching().getOrNull()
                            } ?: break
                        if (frame !is Frame.Text) continue
                        if (!messageRate.tryAcquire()) {
                            connection.send(ServerMessage.ErrorMsg("Too many requests"))
                            continue
                        }
                        val text = frame.readText()
                        if (text.length > SecurityPolicy.MAX_MESSAGE_CHARS) {
                            close(CloseReason(CloseReason.Codes.TOO_BIG, "Message too large"))
                            break
                        }
                        val msg =
                            runCatching { ProtocolJson.decodeFromString<ClientMessage>(text) }
                                .getOrElse {
                                    connection.send(ServerMessage.ErrorMsg("Invalid message"))
                                    continue
                                }
                        if (!SecurityPolicy.isValid(msg)) {
                            connection.send(ServerMessage.ErrorMsg("Invalid message"))
                            continue
                        }
                        val current = room
                        if (current == null) {
                            room =
                                when (msg) {
                                    is ClientMessage.CreateRoom ->
                                        registry.createRoom(
                                            playerId,
                                            authenticatedUser?.name ?: msg.name,
                                            connection,
                                            msg.avatar,
                                            walletId,
                                        )
                                    is ClientMessage.JoinByCode ->
                                        registry.joinByCode(
                                            msg.code,
                                            playerId,
                                            authenticatedUser?.name ?: msg.name,
                                            connection,
                                            msg.avatar,
                                            walletId,
                                        )
                                    is ClientMessage.QuickMatch ->
                                        registry.quickMatch(
                                            playerId,
                                            authenticatedUser?.name ?: msg.name,
                                            connection,
                                            msg.avatar,
                                            walletId,
                                        )
                                    is ClientMessage.Reconnect -> {
                                        registry.reconnect(msg.roomCode, msg.playerId, msg.reconnectToken, connection)
                                            ?.also { playerId = msg.playerId }
                                    }
                                    else -> {
                                        connection.send(ServerMessage.ErrorMsg("Join or create a room first"))
                                        null
                                    }
                                }
                        } else if (msg is ClientMessage.LeaveRoom) {
                            registry.leave(current, playerId, connection)
                            connection.send(ServerMessage.LeftRoom)
                            room = null
                            break
                        } else {
                            current.handle(playerId, msg)
                        }
                    }
                } finally {
                    room?.let { registry.onDisconnect(it, playerId, connection) }
                    connections.release(remoteAddress)
                }
            }
        }
    }
}
