package com.playcrew.ludo.server

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LudoRoomRegistry(private val scope: CoroutineScope) {
    private val random = SecureRandom()
    private val rooms = ConcurrentHashMap<String, LudoRoom>()

    suspend fun create(
        playerId: String,
        name: String,
        avatar: String,
        connection: LudoConnection,
    ): LudoRoom? {
        if (rooms.size >= MAX_ROOMS) return null
        val room = createRoom(acceptsQuickMatch = false)
        return room.takeIf { it.join(playerId, name, avatar, connection) }
    }

    suspend fun join(
        code: String,
        playerId: String,
        name: String,
        avatar: String,
        connection: LudoConnection,
    ): LudoRoom? {
        val room = rooms[code.uppercase()] ?: return null
        return room.takeIf { it.join(playerId, name, avatar, connection) }
    }

    suspend fun quickMatch(
        playerId: String,
        name: String,
        avatar: String,
        connection: LudoConnection,
    ): LudoRoom? {
        var existing: LudoRoom? = null
        for (candidate in rooms.values) {
            if (candidate.acceptsQuickMatch && candidate.hasSpace()) {
                existing = candidate
                break
            }
        }
        val room = existing ?: createRoom(acceptsQuickMatch = true)
        if (!room.join(playerId, name, avatar, connection)) return null
        if (room.humanCount() >= QUICK_START_HUMANS) {
            room.startIfWaiting()
        } else if (existing == null) {
            scope.launch {
                delay(QUICK_MATCH_WAIT_MS)
                room.startIfWaiting()
            }
        }
        return room
    }

    suspend fun reconnect(
        code: String,
        playerId: String,
        token: String,
        connection: LudoConnection,
    ): LudoRoom? {
        val room = rooms[code.uppercase()]
        if (room == null) {
            connection.send(com.playcrew.ludo.protocol.LudoServerMessage.ReconnectRejected("Room not found"))
            return null
        }
        return room.takeIf { it.reconnect(playerId, token, connection) }
    }

    private fun createRoom(acceptsQuickMatch: Boolean): LudoRoom {
        while (true) {
            val code = buildString(ROOM_CODE_LENGTH) { repeat(ROOM_CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            val room =
                LudoRoom(code, scope, acceptsQuickMatch = acceptsQuickMatch) { abandoned ->
                    rooms.remove(abandoned.code, abandoned)
                }
            if (rooms.putIfAbsent(code, room) == null) return room
        }
    }

    private companion object {
        const val MAX_ROOMS = 500
        const val ROOM_CODE_LENGTH = 4
        const val QUICK_START_HUMANS = 2
        const val QUICK_MATCH_WAIT_MS = 5_000L
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
