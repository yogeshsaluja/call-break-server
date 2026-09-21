package com.yogesh.callbreak.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
data class CreateCoinOrderRequest(val productId: String, val walletId: String)

@Serializable
data class CoinOrderResponse(
    val keyId: String,
    val orderId: String,
    val productId: String,
    val amount: Int,
    val currency: String,
)

@Serializable
data class VerifyCoinPaymentRequest(
    val orderId: String,
    val paymentId: String,
    val signature: String,
    val walletId: String,
)

@Serializable
data class VerifiedCoinPaymentResponse(
    val verified: Boolean,
    val productId: String,
    val paymentId: String,
    val coins: Int,
    val balance: Int? = null,
)

@Serializable
data class PaymentErrorResponse(val message: String)

class PaymentRequestException(val status: HttpStatusCode, message: String) : Exception(message)

data class RazorpayOrder(val id: String, val amount: Int, val currency: String)

interface RazorpayOrderGateway {
    val keyId: String
    suspend fun createOrder(amount: Int, currency: String, receipt: String): RazorpayOrder
}

class PaymentService(
    private val gateway: RazorpayOrderGateway,
    private val keySecret: String,
    private val ledger: PaymentLedger = PaymentLedger(),
) {
    suspend fun createOrder(request: CreateCoinOrderRequest): CoinOrderResponse {
        validateWallet(request.walletId)
        val pack = PACKS[request.productId]
            ?: throw PaymentRequestException(HttpStatusCode.BadRequest, "Unknown coin package")
        val order = gateway.createOrder(pack.amount, CURRENCY, "coins-${UUID.randomUUID()}")
        if (order.amount != pack.amount || order.currency != CURRENCY) {
            throw PaymentRequestException(HttpStatusCode.BadGateway, "Payment provider returned an invalid order")
        }
        ledger.recordOrder(OrderRecord(order.id, request.walletId, pack.productId, pack.amount, pack.coins))
        return CoinOrderResponse(gateway.keyId, order.id, pack.productId, pack.amount, CURRENCY)
    }

    fun verify(request: VerifyCoinPaymentRequest): VerifiedCoinPaymentResponse {
        validateWallet(request.walletId)
        val order = ledger.findOrder(request.orderId)
            ?: throw PaymentRequestException(HttpStatusCode.BadRequest, "Unknown payment order")
        if (order.walletId != request.walletId) {
            throw PaymentRequestException(HttpStatusCode.Conflict, "Payment belongs to another wallet")
        }
        if (!PaymentSignature.matches(request.orderId, request.paymentId, request.signature, keySecret)) {
            throw PaymentRequestException(HttpStatusCode.BadRequest, "Payment verification failed")
        }
        ledger.recordPayment(PaymentRecord(request.paymentId, request.orderId, request.walletId))
        return VerifiedCoinPaymentResponse(true, order.productId, request.paymentId, order.coins)
    }

    private fun validateWallet(walletId: String) {
        if (!walletId.matches(WALLET_ID_PATTERN)) {
            throw PaymentRequestException(HttpStatusCode.BadRequest, "Invalid wallet")
        }
    }

    companion object {
        fun fromEnvironment(): PaymentService? {
            val keyId = System.getenv("RAZORPAY_KEY_ID")?.takeIf(String::isNotBlank) ?: return null
            val keySecret = System.getenv("RAZORPAY_KEY_SECRET")?.takeIf(String::isNotBlank) ?: return null
            require(keyId.startsWith("rzp_test_")) { "Only a Razorpay Test Mode key is allowed" }
            val ledgerPath = Path.of(System.getenv("PAYMENT_LEDGER_FILE") ?: "data/razorpay-payments.jsonl")
            return PaymentService(HttpRazorpayOrderGateway(keyId, keySecret), keySecret, PaymentLedger(ledgerPath))
        }

        private val PACKS = listOf(
            CoinPack("coins_200", amount = 1_000, coins = 200),
            CoinPack("coins_500", amount = 3_000, coins = 500),
            CoinPack("coins_1000", amount = 5_000, coins = 1_000),
        ).associateBy(CoinPack::productId)
        private val WALLET_ID_PATTERN = Regex("[a-f0-9]{32}")
        private const val CURRENCY = "INR"
    }
}

private data class CoinPack(val productId: String, val amount: Int, val coins: Int)

class HttpRazorpayOrderGateway(
    override val keyId: String,
    private val keySecret: String,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : RazorpayOrderGateway {
    override suspend fun createOrder(amount: Int, currency: String, receipt: String): RazorpayOrder =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("amount", amount)
                put("currency", currency)
                put("receipt", receipt)
            }.toString()
            val auth = Base64.getEncoder().encodeToString("$keyId:$keySecret".toByteArray())
            val request = HttpRequest.newBuilder(URI.create(ORDERS_URL))
                .header("Authorization", "Basic $auth")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw PaymentRequestException(HttpStatusCode.BadGateway, "Unable to create payment order")
            }
            val json = Json.parseToJsonElement(response.body()).jsonObject
            RazorpayOrder(
                id = json.getValue("id").jsonPrimitive.content,
                amount = json.getValue("amount").jsonPrimitive.int,
                currency = json.getValue("currency").jsonPrimitive.content,
            )
        }

    private companion object {
        const val ORDERS_URL = "https://api.razorpay.com/v1/orders"
    }
}

internal object PaymentSignature {
    fun sign(orderId: String, paymentId: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$orderId|$paymentId".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun matches(orderId: String, paymentId: String, signature: String, secret: String): Boolean =
        MessageDigest.isEqual(
            sign(orderId, paymentId, secret).toByteArray(StandardCharsets.UTF_8),
            signature.lowercase().toByteArray(StandardCharsets.UTF_8),
        )
}

data class OrderRecord(
    val orderId: String,
    val walletId: String,
    val productId: String,
    val amount: Int,
    val coins: Int,
)

data class PaymentRecord(val paymentId: String, val orderId: String, val walletId: String)

class PaymentLedger(private val path: Path? = null) {
    private val orders = mutableMapOf<String, OrderRecord>()
    private val payments = mutableMapOf<String, PaymentRecord>()

    init {
        path?.takeIf(Files::exists)?.let { file ->
            Files.readAllLines(file).forEach(::loadLine)
        }
    }

    @Synchronized
    fun recordOrder(record: OrderRecord) {
        if (orders.putIfAbsent(record.orderId, record) == null) append(LedgerEntry.from(record))
    }

    @Synchronized
    fun findOrder(orderId: String): OrderRecord? = orders[orderId]

    @Synchronized
    fun recordPayment(record: PaymentRecord) {
        val existing = payments[record.paymentId]
        if (existing != null && existing != record) {
            throw PaymentRequestException(HttpStatusCode.Conflict, "Payment has already been used")
        }
        if (existing == null) {
            payments[record.paymentId] = record
            append(LedgerEntry.from(record))
        }
    }

    private fun append(entry: LedgerEntry) {
        val file = path ?: return
        file.parent?.let(Files::createDirectories)
        Files.writeString(
            file,
            Json.encodeToString(LedgerEntry.serializer(), entry) + System.lineSeparator(),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun loadLine(line: String) {
        val entry = runCatching { Json.decodeFromString(LedgerEntry.serializer(), line) }.getOrNull() ?: return
        when (entry.type) {
            "order" -> orders[entry.orderId] = OrderRecord(
                entry.orderId,
                entry.walletId,
                requireNotNull(entry.productId),
                requireNotNull(entry.amount),
                requireNotNull(entry.coins),
            )
            "payment" -> payments[requireNotNull(entry.paymentId)] =
                PaymentRecord(entry.paymentId, entry.orderId, entry.walletId)
        }
    }
}

@Serializable
private data class LedgerEntry(
    val type: String,
    val orderId: String,
    val walletId: String,
    val productId: String? = null,
    val amount: Int? = null,
    val coins: Int? = null,
    val paymentId: String? = null,
) {
    companion object {
        fun from(record: OrderRecord) = LedgerEntry(
            type = "order",
            orderId = record.orderId,
            walletId = record.walletId,
            productId = record.productId,
            amount = record.amount,
            coins = record.coins,
        )

        fun from(record: PaymentRecord) = LedgerEntry(
            type = "payment",
            orderId = record.orderId,
            walletId = record.walletId,
            paymentId = record.paymentId,
        )
    }
}
