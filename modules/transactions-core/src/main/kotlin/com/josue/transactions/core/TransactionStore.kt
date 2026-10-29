package com.josue.transactions.core

interface TransactionStore {
    fun findIdempotency(clientId: ClientId, keyHash: String): StoredIdempotency?

    fun createTransaction(attempt: TransactionCreationAttempt, nowEpochSeconds: Long): CreateCommitResult

    fun findTransaction(clientId: ClientId, transactionId: TransactionId): Transaction?
}

enum class CreateCommitResult {
    COMMITTED,
    IDEMPOTENCY_CONFLICT,
}

class TransactionStoreUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class TransactionStoreDataException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

