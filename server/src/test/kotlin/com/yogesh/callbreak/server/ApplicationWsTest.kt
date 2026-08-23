package com.yogesh.callbreak.server

import com.yogesh.callbreak.engine.Seat
import com.yogesh.callbreak.protocol.ClientMessage
import com.yogesh.callbreak.protocol.ProtocolJson
import com.yogesh.callbreak.protocol.ServerMessage
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real `/ws` endpoint over actual WebSocket frames, exercising the pieces the
 * [RoomTest] fakes skip: create/join routing in [Application.module] and the on-the-wire
 * [ProtocolJson] framing that the Android client also uses.
 */
class ApplicationWsTest {

    private suspend fun io.ktor.websocket.WebSocketSession.send(message: ClientMessage) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    private suspend fun io.ktor.websocket.WebSocketSession.receive(): ServerMessage {
        val frame = incoming.receive() as Frame.Text
        return ProtocolJson.decodeFromString(ServerMessage.serializer(), frame.readText())
    }

    /** Read frames until one of type [T] arrives (skipping roster churn like RoomUpdated). */
    private suspend inline fun <reified T : ServerMessage> io.ktor.websocket.WebSocketSession.await(): T {
        repeat(20) {
            val message = receive()
            if (message is T) return message
        }
        error("did not receive ${T::class.simpleName} within 20 frames")
    }

    @Test
    fun createThenJoinByCode_assignsSeats_andStartDealsBothClients() = testApplication {
        application { module() }
        val client = createClient { install(WebSockets) }

        val host = client.webSocketSession(path = "/ws")
        host.send(ClientMessage.CreateRoom("Host"))
        val joined = host.await<ServerMessage.RoomJoined>()
        assertEquals(Seat.SOUTH, joined.yourSeat)
        val code = joined.code

        val guest = client.webSocketSession(path = "/ws")
        guest.send(ClientMessage.JoinByCode(code, "Guest"))
        val guestJoined = guest.await<ServerMessage.RoomJoined>()
        assertEquals(Seat.WEST, guestJoined.yourSeat, "second human takes the next clockwise seat")
        assertEquals(code, guestJoined.code)

        // Host starts; both clients receive a dealt board addressed to their own seat.
        host.send(ClientMessage.StartGame)
        val hostStart = host.await<ServerMessage.GameStarted>()
        val guestStart = guest.await<ServerMessage.GameStarted>()
        assertEquals(Seat.SOUTH, hostStart.yourSeat)
        assertEquals(Seat.WEST, guestStart.yourSeat)
        // Same authoritative deal reached both clients (server is the one source of truth).
        assertEquals(hostStart.state.seed, guestStart.state.seed)
        assertTrue(hostStart.state.players.values.all { it.hand.size == 13 }, "a full deal of 13 each")

        host.close()
        guest.close()
    }

    @Test
    fun quickMatch_pairsTwoClientsIntoTheSameRoom() = testApplication {
        application { module() }
        val client = createClient { install(WebSockets) }

        val a = client.webSocketSession(path = "/ws")
        a.send(ClientMessage.QuickMatch("A"))
        val aJoined = a.await<ServerMessage.RoomJoined>()

        val b = client.webSocketSession(path = "/ws")
        b.send(ClientMessage.QuickMatch("B"))
        val bJoined = b.await<ServerMessage.RoomJoined>()

        assertEquals(aJoined.code, bJoined.code, "quick match funnels both into the first open room")
        assertEquals(Seat.SOUTH, aJoined.yourSeat)
        assertEquals(Seat.WEST, bJoined.yourSeat)

        a.close()
        b.close()
    }

    @Test
    fun joiningAnUnknownCode_returnsError() = testApplication {
        application { module() }
        val client = createClient { install(WebSockets) }

        val lonely = client.webSocketSession(path = "/ws")
        lonely.send(ClientMessage.JoinByCode("ZZZZ", "Nobody"))
        val error = lonely.await<ServerMessage.ErrorMsg>()
        assertTrue(error.reason.isNotBlank())

        lonely.close()
    }
}
