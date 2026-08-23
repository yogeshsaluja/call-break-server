package com.yogesh.callbreak.server

import com.yogesh.callbreak.protocol.ProtocolJson
import com.yogesh.callbreak.protocol.ServerMessage
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send

/**
 * A client the server can push [ServerMessage]s to. An interface so rooms can be unit
 * tested against a recording fake without a real socket.
 */
interface Connection {
    val playerId: String
    suspend fun send(message: ServerMessage)
}

/**
 * Live socket implementation. Ktor's outgoing channel serializes concurrent sends, so it
 * is safe for one player's action to broadcast to another player's connection.
 */
class SocketConnection(
    override val playerId: String,
    private val session: WebSocketSession,
) : Connection {
    override suspend fun send(message: ServerMessage) {
        session.send(Frame.Text(ProtocolJson.encodeToString(ServerMessage.serializer(), message)))
    }
}
