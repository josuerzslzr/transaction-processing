package com.josue.transactions.core

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

data class CreateTransactionCommand(
    val clientId: ClientId,
    val idempotencyKey: IdempotencyKey,
    val transactionData: TransactionData,
)

sealed interface CreateTransactionResult {
    data class Created(val receipt: CreateReceipt) : CreateTransactionResult
    data object IdempotencyKeyReused : CreateTransactionResult
    data object Unavailable : CreateTransactionResult
    data object InvalidPersistedData : CreateTransactionResult
}

sealed interface GetTransactionResult {
    data class Found(val transaction: Transaction) : GetTransactionResult
    data object NotFound : GetTransactionResult
    data object Unavailable : GetTransactionResult
    data object InvalidPersistedData : GetTransactionResult
}

class TransactionService(
    private val store: TransactionStore,
    private val clock: Clock,
    private val uuidGenerator: UuidGenerator,
    private val hasher: RequestHasher = RequestHasher(),
) {
    fun createTransaction(command: CreateTransactionCommand): CreateTransactionResult {
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)
        val keyHash = hasher.idempotencyKeyHash(command.idempotencyKey)
        val requestHash = hasher.requestHash(command.transactionData)

        try {
            store.findIdempotency(command.clientId, keyHash)?.let { existing ->
                if (!isExpired(existing, now)) {
                    return resolve(existing, requestHash)
                }
            }

            val attempt = newAttempt(command, keyHash, requestHash, now)
            return when (store.createTransaction(attempt, now.epochSecond)) {
                CreateCommitResult.COMMITTED -> CreateTransactionResult.Created(attempt.idempotency.receipt)
                CreateCommitResult.IDEMPOTENCY_CONFLICT -> resolveWinner(command.clientId, keyHash, requestHash, now)
            }
        } catch (_: TransactionStoreUnavailableException) {
            return recoverOutcome(command.clientId, keyHash, requestHash, now)
        } catch (_: TransactionStoreDataException) {
            return CreateTransactionResult.InvalidPersistedData
        }
    }

    fun getTransaction(clientId: ClientId, transactionId: TransactionId): GetTransactionResult = try {
        store.findTransaction(clientId, transactionId)?.let(GetTransactionResult::Found) ?: GetTransactionResult.NotFound
    } catch (_: TransactionStoreUnavailableException) {
        GetTransactionResult.Unavailable
    } catch (_: TransactionStoreDataException) {
        GetTransactionResult.InvalidPersistedData
    }

    private fun newAttempt(
        command: CreateTransactionCommand,
        keyHash: String,
        requestHash: String,
        now: Instant,
    ): TransactionCreationAttempt {
        val transaction = Transaction(
            clientId = command.clientId,
            transactionId = TransactionId.of(uuidGenerator.generate()),
            data = command.transactionData,
            status = TransactionStatus.CREATED,
            version = 1,
            createdAt = now,
            updatedAt = now,
        )
        val receipt = CreateReceipt(
            responseStatus = 201,
            responseLocation = "/v1/transactions/${transaction.transactionId}",
            transaction = transaction,
        )
        val idempotency = StoredIdempotency(
            clientId = command.clientId,
            keyHash = keyHash,
            requestHash = requestHash,
            createdAt = now,
            expiresAtEpochSeconds = ceilEpochSecond(now.plus(24, ChronoUnit.HOURS)),
            receipt = receipt,
        )
        val event = TransactionCreatedEvent(
            eventId = uuidGenerator.generate().also(::requireUuidV7),
            transaction = transaction,
            occurredAt = now,
        )
        return TransactionCreationAttempt(idempotency, event, uuidGenerator.generate().also(::requireUuidV7))
    }

    private fun resolveWinner(
        clientId: ClientId,
        keyHash: String,
        requestHash: String,
        now: Instant,
    ): CreateTransactionResult {
        val winner = store.findIdempotency(clientId, keyHash) ?: return CreateTransactionResult.Unavailable
        if (isExpired(winner, now)) return CreateTransactionResult.Unavailable
        return resolve(winner, requestHash)
    }

    private fun recoverOutcome(
        clientId: ClientId,
        keyHash: String,
        requestHash: String,
        now: Instant,
    ): CreateTransactionResult = try {
        val stored = store.findIdempotency(clientId, keyHash) ?: return CreateTransactionResult.Unavailable
        if (isExpired(stored, now)) CreateTransactionResult.Unavailable else resolve(stored, requestHash)
    } catch (_: TransactionStoreUnavailableException) {
        CreateTransactionResult.Unavailable
    } catch (_: TransactionStoreDataException) {
        CreateTransactionResult.InvalidPersistedData
    }

    private fun resolve(stored: StoredIdempotency, requestHash: String): CreateTransactionResult =
        if (stored.requestHash == requestHash) {
            CreateTransactionResult.Created(stored.receipt)
        } else {
            CreateTransactionResult.IdempotencyKeyReused
        }

    private fun isExpired(stored: StoredIdempotency, now: Instant): Boolean =
        stored.expiresAtEpochSeconds <= now.epochSecond

    private fun ceilEpochSecond(instant: Instant): Long =
        instant.epochSecond + if (instant.nano == 0) 0 else 1

    private fun requireUuidV7(value: java.util.UUID) {
        require(value.version() == 7 && value.variant() == 2) { "Generated identifier must be an RFC 9562 UUIDv7" }
    }
}
