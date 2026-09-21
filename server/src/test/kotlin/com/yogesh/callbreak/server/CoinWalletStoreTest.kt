package com.yogesh.callbreak.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoinWalletStoreTest {
    @Test
    fun creditsAndDebitsAreIdempotent() {
        val store = CoinWalletStore()

        assertEquals(400, store.balance("wallet"))
        assertEquals(900, store.creditOnce("wallet", "payment:1", 500))
        assertEquals(900, store.creditOnce("wallet", "payment:1", 500))
        assertTrue(store.debitOnce("wallet", "game:1", 30))
        assertTrue(store.debitOnce("wallet", "game:1", 30))
        assertEquals(870, store.balance("wallet"))
    }

    @Test
    fun insufficientDebitDoesNotCreateTransaction() {
        val store = CoinWalletStore()

        assertFalse(store.debitOnce("wallet", "game:1", 500))
        assertEquals(400, store.balance("wallet"))
    }
}
