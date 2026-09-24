package com.yogesh.callbreak.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GooglePlayBillingServiceTest {
    private val user = AuthenticatedUser("uid-1", "Player", null)
    private val walletId = CoinWalletStore.walletIdForUser(user.uid)

    @Test
    fun verifiedPurchaseCreditsOnceAndConsumes() = runTest {
        val verifier = FakeVerifier(GooglePlayPurchase(true, setOf("coins_500"), walletId))
        val service = GooglePlayBillingService(CoinWalletStore(), verifier)
        val request = VerifyGooglePlayPurchaseRequest("coins_500", "purchase-token-that-is-long-enough")

        assertEquals(900, service.verifyAndCredit(user, request).balance)
        assertEquals(900, service.verifyAndCredit(user, request).balance)
        assertEquals(2, verifier.consumeCalls)
    }

    @Test
    fun purchaseForAnotherAccountIsRejected() = runTest {
        val service = GooglePlayBillingService(
            CoinWalletStore(),
            FakeVerifier(GooglePlayPurchase(true, setOf("coins_200"), "another-wallet")),
        )

        val error = assertFailsWith<GooglePlayBillingException> {
            service.verifyAndCredit(user, VerifyGooglePlayPurchaseRequest("coins_200", "purchase-token-that-is-long-enough"))
        }
        assertEquals(HttpStatusCode.Conflict, error.status)
    }

    private class FakeVerifier(private val purchase: GooglePlayPurchase) : GooglePlayPurchaseVerifier {
        var consumeCalls = 0

        override suspend fun get(purchaseToken: String): GooglePlayPurchase = purchase

        override suspend fun consume(productId: String, purchaseToken: String) {
            consumeCalls++
        }
    }
}
