package com.yogesh.callbreak.server

import com.google.auth.oauth2.GoogleCredentials
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Serializable
data class VerifyGooglePlayPurchaseRequest(val productId: String, val purchaseToken: String)

@Serializable
data class VerifiedGooglePlayPurchaseResponse(
    val productId: String,
    val purchaseToken: String,
    val coins: Int,
    val balance: Int,
)

class GooglePlayBillingException(val status: HttpStatusCode, message: String) : Exception(message)

data class GooglePlayPurchase(
    val purchased: Boolean,
    val productIds: Set<String>,
    val obfuscatedAccountId: String?,
)

interface GooglePlayPurchaseVerifier {
    suspend fun get(purchaseToken: String): GooglePlayPurchase

    suspend fun consume(productId: String, purchaseToken: String)
}

class GooglePlayBillingService(
    private val wallets: CoinWalletStore,
    private val verifier: GooglePlayPurchaseVerifier,
) {
    suspend fun verifyAndCredit(user: AuthenticatedUser, request: VerifyGooglePlayPurchaseRequest): VerifiedGooglePlayPurchaseResponse {
        val coins = PACKS[request.productId]
            ?: throw GooglePlayBillingException(HttpStatusCode.BadRequest, "Unknown Google Play product")
        if (request.purchaseToken.length !in 20..4096) {
            throw GooglePlayBillingException(HttpStatusCode.BadRequest, "Invalid purchase token")
        }

        val walletId = CoinWalletStore.walletIdForUser(user.uid)
        val transactionId = "google-play:${request.purchaseToken.sha256()}"
        if (wallets.hasTransaction(transactionId)) {
            verifier.consume(request.productId, request.purchaseToken)
            return response(request, coins, wallets.balance(walletId))
        }

        val purchase = verifier.get(request.purchaseToken)
        if (!purchase.purchased || request.productId !in purchase.productIds) {
            throw GooglePlayBillingException(HttpStatusCode.BadRequest, "Google Play purchase is not complete")
        }
        if (purchase.obfuscatedAccountId != walletId) {
            throw GooglePlayBillingException(HttpStatusCode.Conflict, "Purchase belongs to another account")
        }

        val balance = wallets.creditOnce(walletId, transactionId, coins)
        try {
            verifier.consume(request.productId, request.purchaseToken)
        } catch (error: Exception) {
            throw GooglePlayBillingException(HttpStatusCode.BadGateway, "Purchase credited but consumption is pending")
        }
        return response(request, coins, balance)
    }

    private fun response(
        request: VerifyGooglePlayPurchaseRequest,
        coins: Int,
        balance: Int,
    ) = VerifiedGooglePlayPurchaseResponse(request.productId, request.purchaseToken, coins, balance)

    companion object {
        private val PACKS = mapOf("coins_200" to 200, "coins_500" to 500, "coins_1000" to 1_000)

        fun fromEnvironment(wallets: CoinWalletStore): GooglePlayBillingService =
            GooglePlayBillingService(
                wallets,
                GooglePlayDeveloperApiVerifier(
                    packageName = System.getenv("GOOGLE_PLAY_PACKAGE_NAME") ?: "com.callbreak.india",
                ),
            )
    }
}

class GooglePlayDeveloperApiVerifier(
    private val packageName: String,
    private val credentialsProvider: () -> GoogleCredentials = {
        GoogleCredentials.getApplicationDefault().createScoped(ANDROID_PUBLISHER_SCOPE)
    },
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) : GooglePlayPurchaseVerifier {
    private val credentials by lazy(credentialsProvider)

    override suspend fun get(purchaseToken: String): GooglePlayPurchase =
        withContext(Dispatchers.IO) {
            val response = client.send(
                authorizedRequest(
                    "$API_ROOT/applications/${packageName.urlEncode()}/purchases/productsv2/tokens/${purchaseToken.urlEncode()}",
                ).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() !in 200..299) {
                throw GooglePlayBillingException(HttpStatusCode.BadGateway, "Google Play verification failed")
            }
            val value = Json.decodeFromString(GoogleProductPurchaseV2.serializer(), response.body())
            GooglePlayPurchase(
                purchased = value.purchaseStateContext?.purchaseState == "PURCHASED",
                productIds = value.productLineItem.mapTo(mutableSetOf()) { it.productId },
                obfuscatedAccountId = value.obfuscatedExternalAccountId,
            )
        }

    override suspend fun consume(productId: String, purchaseToken: String) {
        withContext(Dispatchers.IO) {
            val response = client.send(
                authorizedRequest(
                    "$API_ROOT/applications/${packageName.urlEncode()}/purchases/products/${productId.urlEncode()}/tokens/${purchaseToken.urlEncode()}:consume",
                ).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            if (response.statusCode() !in 200..299) {
                throw GooglePlayBillingException(HttpStatusCode.BadGateway, "Google Play consumption failed")
            }
        }
    }

    @Synchronized
    private fun authorizedRequest(url: String): HttpRequest.Builder {
        credentials.refreshIfExpired()
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer ${credentials.accessToken.tokenValue}")
            .header("Content-Type", "application/json")
    }

    private companion object {
        const val API_ROOT = "https://androidpublisher.googleapis.com/androidpublisher/v3"
        const val ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
    }
}

@Serializable
private data class GoogleProductPurchaseV2(
    val productLineItem: List<GoogleProductLineItem> = emptyList(),
    val purchaseStateContext: GooglePurchaseStateContext? = null,
    val obfuscatedExternalAccountId: String? = null,
)

@Serializable
private data class GoogleProductLineItem(val productId: String)

@Serializable
private data class GooglePurchaseStateContext(val purchaseState: String)

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
