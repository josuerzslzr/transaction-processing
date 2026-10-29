package com.josue.transactions.core

import java.time.Instant
import java.util.UUID

enum class Currency {
    EUR,
}

enum class PaymentMethod {
    BANK_TRANSFER,
}

enum class TransactionType {
    PAYMENT,
}

enum class TransactionStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
}

data class TransactionData(
    val externalReference: String,
    val amountMinor: Long,
    val currency: Currency,
    val transactionType: TransactionType,
    val paymentMethod: PaymentMethod,
) {
    init {
        val characterCount = externalReference.codePointCount(0, externalReference.length)
        require(characterCount in 1..128) { "externalReference must contain between 1 and 128 characters" }
        require(externalReference.codePoints().anyMatch { !Character.isWhitespace(it) }) {
            "externalReference must not be whitespace-only"
        }
        require(amountMinor > 0) { "amountMinor must be positive" }
    }
}

data class Transaction(
    val clientId: ClientId,
    val transactionId: TransactionId,
    val data: TransactionData,
    val status: TransactionStatus,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TransactionCreatedEvent(
    val eventId: UUID,
    val transaction: Transaction,
    val occurredAt: Instant,
)

data class CreateReceipt(
    val responseStatus: Int,
    val responseLocation: String,
    val transaction: Transaction,
)

data class StoredIdempotency(
    val clientId: ClientId,
    val keyHash: String,
    val requestHash: String,
    val createdAt: Instant,
    val expiresAtEpochSeconds: Long,
    val receipt: CreateReceipt,
)

data class TransactionCreationAttempt(
    val idempotency: StoredIdempotency,
    val event: TransactionCreatedEvent,
    val clientRequestToken: UUID,
)
