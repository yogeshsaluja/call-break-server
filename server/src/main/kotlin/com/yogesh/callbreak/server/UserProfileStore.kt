package com.yogesh.callbreak.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class UserProfileStore(private val path: Path? = null) {
    private val profiles = mutableMapOf<String, UserProfileResponse>()

    init {
        path?.takeIf(Files::exists)?.let { file ->
            Files.readAllLines(file).forEach { line ->
                runCatching { Json.decodeFromString(UserProfileResponse.serializer(), line) }
                    .getOrNull()
                    ?.let { profiles[it.userId] = it }
            }
        }
    }

    @Synchronized
    fun sync(user: AuthenticatedUser): UserProfileResponse {
        val userId = CoinWalletStore.walletIdForUser(user.uid)
        val existing = profiles[userId]
        val displayName = user.name?.trim()?.take(SecurityPolicy.MAX_NAME_LENGTH)?.takeIf(String::isNotBlank)
        val email = user.email?.trim()?.take(MAX_EMAIL_LENGTH)?.takeIf(String::isNotBlank)
        val photoUrl = user.photoUrl?.take(MAX_URL_LENGTH)?.takeIf { it.startsWith("https://") }
        val provider = user.provider?.take(MAX_PROVIDER_LENGTH)
        if (
            existing != null &&
            existing.displayName == displayName &&
            existing.email == email &&
            existing.photoUrl == photoUrl &&
            existing.provider == provider
        ) {
            return existing
        }
        val now = System.currentTimeMillis()
        val updated =
            UserProfileResponse(
                userId = userId,
                displayName = displayName,
                email = email,
                photoUrl = photoUrl,
                provider = provider,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
            )
        profiles[userId] = updated
        append(updated)
        return updated
    }

    @Synchronized
    fun get(user: AuthenticatedUser): UserProfileResponse? =
        profiles[CoinWalletStore.walletIdForUser(user.uid)]

    private fun append(profile: UserProfileResponse) {
        val file = path ?: return
        file.parent?.let(Files::createDirectories)
        Files.writeString(
            file,
            Json.encodeToString(UserProfileResponse.serializer(), profile) + System.lineSeparator(),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    companion object {
        private const val MAX_EMAIL_LENGTH = 254
        private const val MAX_URL_LENGTH = 2_048
        private const val MAX_PROVIDER_LENGTH = 64

        fun fromEnvironment(): UserProfileStore =
            UserProfileStore(Path.of(System.getenv("USER_PROFILE_FILE") ?: "data/user-profiles.jsonl"))
    }
}

@Serializable
data class UserProfileResponse(
    val userId: String,
    val displayName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val provider: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
