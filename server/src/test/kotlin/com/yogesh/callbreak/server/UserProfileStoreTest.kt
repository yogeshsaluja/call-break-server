package com.yogesh.callbreak.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserProfileStoreTest {
    @Test
    fun profilePersistsWithoutStoringRawFirebaseUid() {
        val file = Files.createTempDirectory("callbreak-profile").resolve("profiles.jsonl")
        val user =
            AuthenticatedUser(
                uid = "firebase-secret-uid",
                name = " Player One ",
                email = "player@example.com",
                photoUrl = "https://example.com/player.jpg",
                provider = "google.com",
            )

        val saved = UserProfileStore(file).sync(user)
        val restored = UserProfileStore(file).get(user)

        assertEquals(saved, restored)
        assertEquals("Player One", saved.displayName)
        assertTrue(saved.userId.isNotBlank())
        assertTrue("firebase-secret-uid" !in Files.readString(file))

        UserProfileStore(file).sync(user)
        assertEquals(1, Files.readAllLines(file).size)
    }

    @Test
    fun rejectsNonHttpsProfilePhotos() {
        val store = UserProfileStore()
        val profile = store.sync(AuthenticatedUser("uid", "Player", null, "http://example.com/a.jpg", "facebook.com"))

        assertNull(profile.photoUrl)
    }
}
