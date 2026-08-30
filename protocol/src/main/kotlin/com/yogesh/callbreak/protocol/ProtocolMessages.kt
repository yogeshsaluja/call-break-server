package com.yogesh.callbreak.protocol

import com.yogesh.callbreak.engine.Card
import com.yogesh.callbreak.engine.GameState
import com.yogesh.callbreak.engine.Seat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a client wants to enter a room, chosen from the lobby. */
@Serializable
enum class JoinMode { CREATE, JOIN_BY_CODE, QUICK_MATCH }

/** One participant in a room. [seat] is the absolute engine seat once assigned. */
@Serializable
data class PlayerInfo(
    val id: String,
    val name: String,
    val seat: Seat? = null,
    val isBot: Boolean = false,
    val connected: Boolean = true,
    /** The player's chosen avatar emoji, shown on their seat by every client. */
    val avatar: String = "",
)

/** The pre-game room roster shown in the waiting lobby. */
@Serializable
data class RoomSnapshot(
    val code: String,
    val players: List<PlayerInfo>,
    val hostId: String,
    val started: Boolean = false,
)

/**
 * Messages a client sends to the server. Seat-independent by design: the player's seat
 * is fixed server-side at join time, so an intent never needs to name a seat — the
 * server attributes it to the connection that sent it.
 */
@Serializable
sealed interface ClientMessage {
    /** Create a fresh private room; the sender becomes host. */
    @Serializable
    data class CreateRoom(val name: String, val avatar: String = "") : ClientMessage

    /** Join an existing private room by its share code. */
    @Serializable
    data class JoinByCode(val code: String, val name: String, val avatar: String = "") : ClientMessage

    /** Auto-pair into the first open room, or create one if none is waiting. */
    @Serializable
    data class QuickMatch(val name: String, val avatar: String = "") : ClientMessage

    /** Host-only: begin the game, filling empty seats with bots. */
    @Serializable
    data object StartGame : ClientMessage

    /** Leave the room / abandon the game. */
    @Serializable
    data object LeaveRoom : ClientMessage

    @Serializable
    data class MakeCall(val count: Int) : ClientMessage

    @Serializable
    data class PlayCard(val card: Card) : ClientMessage

    @Serializable
    data object AdvanceRound : ClientMessage

    /** Let the authoritative server drive this player's bids and cards. */
    @Serializable
    data class SetAutoPlay(val enabled: Boolean) : ClientMessage

    @Serializable
    data class Chat(val text: String) : ClientMessage

    @Serializable
    data class Throw(val item: String, val targetSeat: Seat) : ClientMessage
}

/**
 * Messages the server broadcasts to clients. Game state carries **absolute** engine
 * seats; the client rotates them so the local player renders at [Seat.SOUTH].
 */
@Serializable
sealed interface ServerMessage {
    /** Sent once to the joining client: its identity, assigned seat, and the roster. */
    @Serializable
    data class RoomJoined(
        val code: String,
        val youId: String,
        val yourSeat: Seat,
        val snapshot: RoomSnapshot,
    ) : ServerMessage

    /** Roster changed (someone joined/left/renamed) while still in the waiting lobby. */
    @Serializable
    data class RoomUpdated(val snapshot: RoomSnapshot) : ServerMessage

    /**
     * The game began. [yourSeat] is this client's absolute seat for the whole game;
     * [players] is the final roster (humans + bots, each with seat/name/avatar) so every
     * client can render each seat's name and avatar for the rest of the game.
     */
    @Serializable
    data class GameStarted(
        val state: GameState,
        val yourSeat: Seat,
        val players: List<PlayerInfo> = emptyList(),
    ) : ServerMessage

    /** Authoritative state after any applied intent. */
    @Serializable
    data class StateUpdate(val state: GameState) : ServerMessage

    /**
     * A trick just completed. [displayed] is the state with all four cards still on the
     * table (the winner's trick not yet cleared) so clients can show it and sweep it to
     * [winner]; [sweeping] flips true for the fly-to-winner beat — mirroring the offline
     * `LocalSession` timing. The authoritative cleared state follows as a [StateUpdate].
     */
    @Serializable
    data class TrickResolved(
        val displayed: GameState,
        val winner: Seat,
        val sweeping: Boolean,
    ) : ServerMessage

    @Serializable
    data class Chat(val fromSeat: Seat, val text: String) : ServerMessage

    @Serializable
    data class Throw(val fromSeat: Seat, val targetSeat: Seat, val item: String) : ServerMessage

    /** A request was rejected or something went wrong. */
    @Serializable
    data class ErrorMsg(val reason: String) : ServerMessage
}

/**
 * Shared JSON codec for both ends. [classDiscriminator] must match on client and server
 * for the sealed hierarchies to round-trip.
 */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "t"
    encodeDefaults = true
}
