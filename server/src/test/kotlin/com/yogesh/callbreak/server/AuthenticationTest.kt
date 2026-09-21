package com.yogesh.callbreak.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

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
    }

    @Test
    fun protectedRoutesAcceptVerifiedToken() = testApplication {
        application { module(identitySecurity = security, coinWallets = CoinWalletStore()) }

        val response = client.get("/api/v1/wallet") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
