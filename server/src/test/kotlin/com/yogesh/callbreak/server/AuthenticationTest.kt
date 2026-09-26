package com.yogesh.callbreak.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.close
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthenticationTest {
    private val security = IdentitySecurity(
        verifier = IdentityTokenVerifier { token ->
            token.takeIf { it == "valid-token" }?.let { AuthenticatedUser("uid-1", "Player", null) }
        },
        required = true,
    )

    @Test
    fun protectedRoutesRejectMissingToken() = testApplication {
        application { module(identitySecurity = security, coinWallets = CoinWalletStore()) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/wallet").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/payments/google-play/verify").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/profile").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/profile/sync").status)
    }

    @Test
    fun websocketAllowsGuestWithoutToken() = testApplication {
        application { module(identitySecurity = security, coinWallets = CoinWalletStore()) }
        val websocketClient = createClient { install(WebSockets) }

        val session = websocketClient.webSocketSession(path = "/ws")
        assertTrue(session.isActive)
        session.close()
    }

    @Test
    fun verifiedIdentityCanSyncAndRestoreProfile() = testApplication {
        application {
            module(
                identitySecurity = security,
                coinWallets = CoinWalletStore(),
                userProfiles = UserProfileStore(),
            )
        }

        val synced = client.post("/api/v1/profile/sync") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }
        assertEquals(HttpStatusCode.OK, synced.status)
        assertTrue(synced.bodyAsText().contains("Player"))

        val restored = client.get("/api/v1/profile") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }
        assertEquals(HttpStatusCode.OK, restored.status)
        assertTrue(restored.bodyAsText().contains("Player"))
    }

    @Test
    fun protectedRoutesAcceptVerifiedToken() = testApplication {
        application { module(identitySecurity = security, coinWallets = CoinWalletStore()) }

        val response = client.get("/api/v1/wallet") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun removedPaymentRoutesReturnNotFound() = testApplication {
        application { module(identitySecurity = security, coinWallets = CoinWalletStore()) }

        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/payments/razorpay/orders").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/payments/razorpay/verify").status)
    }
}
