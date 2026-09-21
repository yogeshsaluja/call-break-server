package com.yogesh.callbreak.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaymentServiceTest {
    private val gateway = FakeGateway()
    private val service = PaymentService(gateway, SECRET, PaymentLedger())

    @Test
    fun allCoinPackagesUseTrustedServerPrices() = runTest {
        val expected = mapOf("coins_200" to 1_000, "coins_500" to 3_000, "coins_1000" to 5_000)

        expected.forEach { (productId, amount) ->
            assertEquals(amount, service.createOrder(CreateCoinOrderRequest(productId, WALLET)).amount)
        }
    }

    @Test
    fun verifiedPaymentIsIdempotentForItsWallet() = runTest {
        val order = service.createOrder(CreateCoinOrderRequest("coins_500", WALLET))
        val request = VerifyCoinPaymentRequest(
            orderId = order.orderId,
            paymentId = "pay_test_1",
            signature = PaymentSignature.sign(order.orderId, "pay_test_1", SECRET),
            walletId = WALLET,
        )

        val first = service.verify(request)
        val retry = service.verify(request)

        assertTrue(first.verified)
        assertEquals(500, first.coins)
        assertEquals(first, retry)
    }

    @Test
    fun paymentCannotBeReusedForAnotherOrder() = runTest {
        val first = service.createOrder(CreateCoinOrderRequest("coins_200", WALLET))
        val second = service.createOrder(CreateCoinOrderRequest("coins_1000", WALLET))
        service.verify(
            VerifyCoinPaymentRequest(
                first.orderId,
                "pay_reused",
                PaymentSignature.sign(first.orderId, "pay_reused", SECRET),
                WALLET,
            ),
        )

        val error = assertFailsWith<PaymentRequestException> {
            service.verify(
                VerifyCoinPaymentRequest(
                    second.orderId,
                    "pay_reused",
                    PaymentSignature.sign(second.orderId, "pay_reused", SECRET),
                    WALLET,
                ),
            )
        }
        assertEquals(HttpStatusCode.Conflict, error.status)
    }

    @Test
    fun invalidSignatureIsRejected() = runTest {
        val order = service.createOrder(CreateCoinOrderRequest("coins_200", WALLET))

        val error = assertFailsWith<PaymentRequestException> {
            service.verify(VerifyCoinPaymentRequest(order.orderId, "pay_bad", "bad", WALLET))
        }

        assertEquals(HttpStatusCode.BadRequest, error.status)
    }

    private class FakeGateway : RazorpayOrderGateway {
        override val keyId = "rzp_test_example"
        private var sequence = 0

        override suspend fun createOrder(amount: Int, currency: String, receipt: String) =
            RazorpayOrder("order_test_${++sequence}", amount, currency)
    }

    private companion object {
        const val SECRET = "test-secret"
        const val WALLET = "0123456789abcdef0123456789abcdef"
    }
}
