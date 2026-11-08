package com.playcrew.ludo.server

import com.playcrew.ludo.protocol.LudoProtocolJson
import com.playcrew.ludo.protocol.LudoServerMessage
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.serialization.encodeToString

interface LudoConnection {
    val connectionId: String
    suspend fun send(message: LudoServerMessage)
    suspend fun close()
}

class SocketLudoConnection(
    override val connectionId: String,
    private val session: DefaultWebSocketServerSession,
) : LudoConnection {
    override suspend fun send(message: LudoServerMessage) {
        session.send(Frame.Text(LudoProtocolJson.encodeToString(LudoServerMessage.serializer(), message)))
    }

    override suspend fun close() {
        session.close()
    }
}
