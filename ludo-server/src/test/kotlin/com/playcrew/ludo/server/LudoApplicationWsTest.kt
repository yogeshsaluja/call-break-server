package com.playcrew.ludo.server

import com.playcrew.ludo.protocol.LudoClientMessage
import com.playcrew.ludo.protocol.LudoProtocolJson
import com.playcrew.ludo.protocol.LudoServerMessage
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LudoApplicationWsTest {
    private suspend fun WebSocketSession.send(message: LudoClientMessage) =
        send(Frame.Text(LudoProtocolJson.encodeToString(LudoClientMessage.serializer(), message)))

    private suspend fun WebSocketSession.receive(): LudoServerMessage {
        val frame = incoming.receive() as Frame.Text
        return LudoProtocolJson.decodeFromString(LudoServerMessage.serializer(), frame.readText())
    }

    private suspend inline fun <reified T : LudoServerMessage> WebSocketSession.await(): T {
        repeat(20) {
            val message = receive()
            if (message is T) return message
        }
        error("Did not receive ${T::class.simpleName} within 20 frames")
    }

    @Test
    fun createStartReconnectAndLeaveUseTheRealProtocol() = testApplication {
        application { ludoModule() }
        val client = createClient { install(WebSockets) }

        val original = client.webSocketSession(path = "/ws")
        original.send(LudoClientMessage.CreateRoom("ignored", "Host"))
        val joined = original.await<LudoServerMessage.RoomJoined>()
        assertTrue(joined.code.length == 4)
        assertTrue(joined.reconnectToken.length >= 32)

        original.send(LudoClientMessage.StartGame)
        val started = original.await<LudoServerMessage.GameStarted>()
        assertEquals(joined.youId, started.youId)
        assertEquals(4, started.state.players.size)

        val replacement = client.webSocketSession(path = "/ws")
        replacement.send(LudoClientMessage.Reconnect(joined.code, joined.youId, joined.reconnectToken))
        val reconnected = replacement.await<LudoServerMessage.Reconnected>()
        assertEquals(joined.youId, reconnected.youId)
        assertEquals(joined.color, reconnected.color)
        assertNotNull(reconnected.state)

        replacement.send(LudoClientMessage.LeaveRoom)
        replacement.await<LudoServerMessage.LeftRoom>()
        replacement.close()
    }

    @Test
    fun quickMatchDoesNotEnterAPrivateRoom() = testApplication {
        application { ludoModule() }
        val client = createClient { install(WebSockets) }

        val privateHost = client.webSocketSession(path = "/ws")
        privateHost.send(LudoClientMessage.CreateRoom("ignored", "Private host"))
        val privateRoom = privateHost.await<LudoServerMessage.RoomJoined>()

        val quickPlayer = client.webSocketSession(path = "/ws")
        quickPlayer.send(LudoClientMessage.QuickMatch("ignored", "Quick player"))
        val quickRoom = quickPlayer.await<LudoServerMessage.RoomJoined>()

        assertTrue(privateRoom.code != quickRoom.code)
        privateHost.close()
        quickPlayer.close()
    }
}
