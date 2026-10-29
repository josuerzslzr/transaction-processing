package com.josue.transactions.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.UUID

class TransactionServiceTest {
    private val now = Instant.parse("2026-09-03T14:22:31.483Z")
    private val clientId = requireNotNull(ClientId.parse("01a067a6-5e3a-7a3b-8c4d-123456789abc"))
    private val idempotencyKey = requireNotNull(IdempotencyKey.parse("01a067a6-5e3d-7a3b-8c4d-123456789abc"))
    private val data = TransactionData("ORDER-12345", 4250, Currency.EUR, TransactionType.PAYMENT, PaymentMethod.BANK_TRANSFER)

    @Test
    fun `first create builds and commits one deterministic attempt`() {
        val store = FakeStore()
        val service = service(store)

        val result = service.createTransaction(CreateTransactionCommand(clientId, idempotencyKey, data))

        assertThat(result).isInstanceOf(CreateTransactionResult.Created::class.java)
        val attempt = requireNotNull(store.lastAttempt)
        assertThat(attempt.idempotency.receipt.transaction.transactionId.toString()).isEqualTo(TRANSACTION_ID)
        assertThat(attempt.event.eventId.toString()).isEqualTo(EVENT_ID)
        assertThat(attempt.clientRequestToken.toString()).isEqualTo(TOKEN_ID)
        assertThat(attempt.idempotency.createdAt).isEqualTo(now)
        assertThat(attempt.event.occurredAt).isEqualTo(now)
        assertThat(attempt.idempotency.expiresAtEpochSeconds).isEqualTo(1788531752)
        assertThat(attempt.idempotency.receipt.responseLocation).isEqualTo("/v1/transactions/$TRANSACTION_ID")
    }

    @Test
    fun `identical replay returns the exact stored receipt without writing`() {
        val stored = storedIdempotency(requestHash = RequestHasher().requestHash(data))
        val store = FakeStore(existing = stored)

        val result = service(store).createTransaction(CreateTransactionCommand(clientId, idempotencyKey, data))

        assertThat(result).isEqualTo(CreateTransactionResult.Created(stored.receipt))
        assertThat(store.createCalls).isZero()
    }

    @Test
    fun `same active key with different data is rejected without writing`() {
        val store = FakeStore(existing = storedIdempotency(requestHash = "different"))

        val result = service(store).createTransaction(CreateTransactionCommand(clientId, idempotencyKey, data))

        assertThat(result).isEqualTo(CreateTransactionResult.IdempotencyKeyReused)
        assertThat(store.createCalls).isZero()
    }

    @Test
    fun `expired key permits a new creation`() {
        val expired = storedIdempotency(
            requestHash = "old",
            expiresAtEpochSeconds = now.epochSecond,
        )
        val store = FakeStore(existing = expired)

        val result = service(store).createTransaction(CreateTransactionCommand(clientId, idempotencyKey, data))

        assertThat(result).isInstanceOf(CreateTransactionResult.Created::class.java)
        assertThat(store.createCalls).isOne()
        assertThat(store.lastAttempt?.idempotency?.receipt?.transaction?.transactionId.toString()).isEqualTo(TRANSACTION_ID)
    }

    @Test
    fun `conditional loser resolves the committed winner`() {
        val winner = storedIdempotency(requestHash = RequestHasher().requestHash(data))
        val store = FakeStore(createResult = CreateCommitResult.IDEMPOTENCY_CONFLICT, winner = winner)

        val result = service(store).createTransaction(CreateTransactionCommand(clientId, idempotencyKey, data))

        assertThat(result).isEqualTo(CreateTransactionResult.Created(winner.receipt))
    }

    @Test
    fun `uncertain write recovers a matching stored outcome`() {
        val store = FakeStore(failAfterCommit = true)

        val result = service(store).createTransaction(CreateTransactionCommand(clientId, idempotencyKey, data))

        assertThat(result).isInstanceOf(CreateTransactionResult.Created::class.java)
    }

    @Test
    fun `unrecoverable store failure returns unavailable`() {
        val store = FakeStore(failReads = true)

        val result = service(store).createTransaction(CreateTransactionCommand(clientId, idempotencyKey, data))

        assertThat(result).isEqualTo(CreateTransactionResult.Unavailable)
    }

    @Test
    fun `get transaction preserves tenant scoped store outcome`() {
        val transaction = storedIdempotency(RequestHasher().requestHash(data)).receipt.transaction
        val store = FakeStore(transaction = transaction)
        val service = service(store)

        assertThat(service.getTransaction(clientId, transaction.transactionId)).isEqualTo(GetTransactionResult.Found(transaction))
        store.transaction = null
        assertThat(service.getTransaction(clientId, transaction.transactionId)).isEqualTo(GetTransactionResult.NotFound)
    }

    @Test
    fun `invalid persisted records have explicit use case outcomes`() {
        val store = FakeStore(failDataReads = true)
        val service = service(store)

        assertThat(service.createTransaction(CreateTransactionCommand(clientId, idempotencyKey, data)))
            .isEqualTo(CreateTransactionResult.InvalidPersistedData)
        assertThat(service.getTransaction(clientId, requireNotNull(TransactionId.parse(TRANSACTION_ID))))
            .isEqualTo(GetTransactionResult.InvalidPersistedData)
    }

    private fun service(store: FakeStore): TransactionService = TransactionService(
        store = store,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        uuidGenerator = QueueUuidGenerator(TRANSACTION_ID, EVENT_ID, TOKEN_ID),
    )

    private fun storedIdempotency(
        requestHash: String,
        expiresAtEpochSeconds: Long = now.plusSeconds(86_401).epochSecond,
    ): StoredIdempotency {
        val transaction = Transaction(
            clientId,
            requireNotNull(TransactionId.parse("01a067a6-5e3e-7a3b-8c4d-123456789abc")),
            data,
            TransactionStatus.CREATED,
            1,
            now,
            now,
        )
        return StoredIdempotency(
            clientId,
            RequestHasher().idempotencyKeyHash(idempotencyKey),
            requestHash,
            now,
            expiresAtEpochSeconds,
            CreateReceipt(201, "/v1/transactions/${transaction.transactionId}", transaction),
        )
    }

    private class QueueUuidGenerator(vararg values: String) : UuidGenerator {
        private val values = ArrayDeque(values.map(UUID::fromString))
        override fun generate(): UUID = values.removeFirst()
    }

    private class FakeStore(
        private var existing: StoredIdempotency? = null,
        private val createResult: CreateCommitResult = CreateCommitResult.COMMITTED,
        private val winner: StoredIdempotency? = null,
        var transaction: Transaction? = null,
        private val failAfterCommit: Boolean = false,
        private val failReads: Boolean = false,
        private val failDataReads: Boolean = false,
    ) : TransactionStore {
        var createCalls = 0
        var lastAttempt: TransactionCreationAttempt? = null

        override fun findIdempotency(clientId: ClientId, keyHash: String): StoredIdempotency? {
            if (failReads) throw TransactionStoreUnavailableException("unavailable")
            if (failDataReads) throw TransactionStoreDataException("invalid data")
            return winner ?: existing
        }

        override fun createTransaction(attempt: TransactionCreationAttempt, nowEpochSeconds: Long): CreateCommitResult {
            createCalls++
            lastAttempt = attempt
            if (createResult == CreateCommitResult.COMMITTED) existing = attempt.idempotency
            if (failAfterCommit) throw TransactionStoreUnavailableException("outcome unknown")
            return createResult
        }

        override fun findTransaction(clientId: ClientId, transactionId: TransactionId): Transaction? {
            if (failDataReads) throw TransactionStoreDataException("invalid data")
            return transaction
        }
    }

    private companion object {
        const val TRANSACTION_ID = "01a067a6-5e3b-7a3b-8c4d-123456789abc"
        const val EVENT_ID = "01a067a6-5e3c-7a3b-8c4d-123456789abc"
        const val TOKEN_ID = "01a067a6-5e3f-7a3b-8c4d-123456789abc"
    }
}
