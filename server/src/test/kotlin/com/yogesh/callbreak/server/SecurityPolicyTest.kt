package com.yogesh.callbreak.server

import com.yogesh.callbreak.protocol.ClientMessage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityPolicyTest {
    @Test
    fun rejectsOversizedOrInvalidUserInput() {
        assertFalse(SecurityPolicy.isValid(ClientMessage.CreateRoom("", "")))
        assertFalse(SecurityPolicy.isValid(ClientMessage.CreateRoom("A".repeat(17), "")))
        assertFalse(SecurityPolicy.isValid(ClientMessage.JoinByCode("O0I1", "Player")))
        assertFalse(SecurityPolicy.isValid(ClientMessage.Chat("x".repeat(121))))
        assertFalse(SecurityPolicy.isValid(ClientMessage.Chat("line\nbreak")))
        assertFalse(SecurityPolicy.isValid(ClientMessage.Throw("<script>", com.yogesh.callbreak.engine.Seat.NORTH)))
        assertTrue(SecurityPolicy.isValid(ClientMessage.JoinByCode("4ERR", "Player")))
    }

    @Test
    fun connectionLimiterReleasesCapacity() {
        val limiter = ConnectionLimiter(maximum = 1)
        assertTrue(limiter.tryAcquire("127.0.0.1"))
        assertFalse(limiter.tryAcquire("127.0.0.1"))
        limiter.release("127.0.0.1")
        assertTrue(limiter.tryAcquire("127.0.0.1"))
    }
}
