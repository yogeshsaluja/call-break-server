package com.yogesh.callbreak.server

import com.yogesh.callbreak.protocol.ServerMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * In-memory registry of active [Room]s, keyed by share code. Handles the three lobby
 * entry points (create / join-by-code / quick-match) and prunes rooms once every human
 * has left.
 */
class RoomRegistry {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val createMutex = Mutex()

    /** Create a fresh private room; the caller becomes host. */
    suspend fun createRoom(playerId: String, name: String, connection: Connection, avatar: String = ""): Room {
        val room = newRoom()
        room.join(playerId, name, connection, avatar)
        return room
    }

    /** Join an existing room by code. Returns null (and notifies the client) on failure. */
    suspend fun joinByCode(code: String, playerId: String, name: String, connection: Connection, avatar: String = ""): Room? {
        val room = rooms[code.uppercase()]
        if (room == null) {
            connection.send(ServerMessage.ErrorMsg("Room \"$code\" not found"))
            return null
        }
        val seat = room.join(playerId, name, connection, avatar)
        if (seat == null) {
            connection.send(ServerMessage.ErrorMsg("Room is full or already started"))
            return null
        }
        return room
    }

    /** Auto-pair into the first room with a free human seat, else create a new one. */
    suspend fun quickMatch(playerId: String, name: String, connection: Connection, avatar: String = ""): Room {
        for (candidate in rooms.values) {
            if (candidate.hasFreeHumanSeat() && candidate.join(playerId, name, connection, avatar) != null) {
                return candidate
            }
        }
        val room = newRoom()
        room.join(playerId, name, connection, avatar)
        return room
    }

    /** Drop a player from a room and remove the room if no humans remain. */
    suspend fun onDisconnect(room: Room, playerId: String) {
        room.onDisconnect(playerId)
        if (room.isAbandoned()) rooms.remove(room.code)
    }

    private suspend fun newRoom(): Room = createMutex.withLock {
        var code: String
        do {
            code = randomCode()
        } while (rooms.containsKey(code))
        Room(code).also { rooms[code] = it }
    }

    private fun randomCode(): String = (1..CODE_LENGTH)
        .map { CODE_ALPHABET[Random.nextInt(CODE_ALPHABET.length)] }
        .joinToString("")

    private companion object {
        const val CODE_LENGTH = 4

        // No 0/O/1/I to keep shared codes unambiguous.
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
