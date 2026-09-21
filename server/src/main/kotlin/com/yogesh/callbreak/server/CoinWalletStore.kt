package com.yogesh.callbreak.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class CoinWalletStore(private val path: Path? = null) {
    private val balances = mutableMapOf<String, Int>()
    private val transactions = mutableSetOf<String>()

    init {
        path?.takeIf(Files::exists)?.let { file ->
            Files.readAllLines(file).forEach { line ->
                runCatching { Json.decodeFromString(WalletEntry.serializer(), line) }
                    .getOrNull()
                    ?.let(::apply)
            }
        }
    }

    @Synchronized
    fun balance(walletId: String): Int = balances.getOrPut(walletId) { INITIAL_BALANCE }

    @Synchronized
    fun creditOnce(walletId: String, transactionId: String, amount: Int): Int {
        require(amount > 0)
        if (!transactions.add(transactionId)) return balance(walletId)
        val entry = WalletEntry(walletId, transactionId, amount)
        applyBalance(entry)
        append(entry)
        return balance(walletId)
    }

    @Synchronized
    fun debitOnce(walletId: String, transactionId: String, amount: Int): Boolean {
        require(amount > 0)
        if (transactionId in transactions) return true
        if (balance(walletId) < amount) return false
        transactions.add(transactionId)
        val entry = WalletEntry(walletId, transactionId, -amount)
        applyBalance(entry)
        append(entry)
        return true
    }

    private fun apply(entry: WalletEntry) {
        if (transactions.add(entry.transactionId)) applyBalance(entry)
    }

    private fun applyBalance(entry: WalletEntry) {
        balances[entry.walletId] = (balances[entry.walletId] ?: INITIAL_BALANCE) + entry.delta
    }

    private fun append(entry: WalletEntry) {
        val file = path ?: return
        file.parent?.let(Files::createDirectories)
        Files.writeString(
            file,
            Json.encodeToString(WalletEntry.serializer(), entry) + System.lineSeparator(),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    companion object {
        const val INITIAL_BALANCE = 400

        fun fromEnvironment(): CoinWalletStore =
            CoinWalletStore(Path.of(System.getenv("COIN_LEDGER_FILE") ?: "data/coin-wallet.jsonl"))

        fun walletIdForUser(uid: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(uid.encodeToByteArray())
                .take(16)
                .joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class WalletBalanceResponse(val balance: Int)

@Serializable
private data class WalletEntry(
    val walletId: String,
    val transactionId: String,
    val delta: Int,
)
