package com.playcrew.ludo.protocol

import com.playcrew.ludo.engine.LudoColor
import com.playcrew.ludo.engine.LudoState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val LudoProtocolJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
data class LudoRoomPlayer(
    val id: String,
    val name: String,
    val color: LudoColor,
    val avatar: String = "",
    val connected: Boolean = true,
    val isBot: Boolean = false,
)

@Serializable
data class LudoRoomSnapshot(
    val code: String,
    val hostId: String,
    val players: List<LudoRoomPlayer>,
    val started: Boolean,
)

@Serializable
sealed interface LudoClientMessage {
    @Serializable data class CreateRoom(val playerId: String, val name: String, val avatar: String = "") : LudoClientMessage
    @Serializable data class JoinByCode(val code: String, val playerId: String, val name: String, val avatar: String = "") : LudoClientMessage
    @Serializable data class QuickMatch(val playerId: String, val name: String, val avatar: String = "") : LudoClientMessage
    @Serializable data class Reconnect(val roomCode: String, val playerId: String, val reconnectToken: String) : LudoClientMessage
    @Serializable data object StartGame : LudoClientMessage
    @Serializable data object RollDice : LudoClientMessage
    @Serializable data class MoveToken(val tokenIndex: Int) : LudoClientMessage
    @Serializable data class Chat(val text: String) : LudoClientMessage
    @Serializable data class Emoji(val value: String) : LudoClientMessage
    @Serializable data object LeaveRoom : LudoClientMessage
}

@Serializable
sealed interface LudoServerMessage {
    @Serializable
    data class RoomJoined(
        val code: String,
        val youId: String,
        val color: LudoColor,
        val snapshot: LudoRoomSnapshot,
        val reconnectToken: String = "",
    ) : LudoServerMessage

    @Serializable data class RoomUpdated(val snapshot: LudoRoomSnapshot) : LudoServerMessage
    @Serializable data class GameStarted(val state: LudoState, val youId: String) : LudoServerMessage
    @Serializable data class StateUpdated(val state: LudoState) : LudoServerMessage
    @Serializable data class Reconnected(val code: String, val youId: String, val color: LudoColor, val snapshot: LudoRoomSnapshot, val state: LudoState?) : LudoServerMessage
    @Serializable data class Chat(val playerId: String, val text: String) : LudoServerMessage
    @Serializable data class Emoji(val playerId: String, val value: String) : LudoServerMessage
    @Serializable data class Error(val message: String) : LudoServerMessage
    @Serializable data class ReconnectRejected(val reason: String) : LudoServerMessage
    @Serializable data object LeftRoom : LudoServerMessage
}
