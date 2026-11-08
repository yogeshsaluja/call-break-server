package com.playcrew.ludo.server

import com.playcrew.ludo.protocol.LudoClientMessage
import com.playcrew.ludo.protocol.LudoServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class RecordingConnection(override val connectionId: String) : LudoConnection {
    val messages = mutableListOf<LudoServerMessage>()

    override suspend fun send(message: LudoServerMessage) {
        messages += message
    }

    override suspend fun close() = Unit
}

class LudoRoomTest {
    @Test
    fun roomStartsWithBotsAndUsesServerDice() = runTest {
        val room = LudoRoom("TEST", CoroutineScope(coroutineContext + SupervisorJob())) {}
        val host = RecordingConnection("host-connection")
        assertTrue(room.join("1234abcd", "Host", "", host))
        room.handle("1234abcd", LudoClientMessage.StartGame)
        assertIs<LudoServerMessage.GameStarted>(host.messages.last())
        room.handle("1234abcd", LudoClientMessage.RollDice)
        assertIs<LudoServerMessage.StateUpdated>(host.messages.last())
    }
}
