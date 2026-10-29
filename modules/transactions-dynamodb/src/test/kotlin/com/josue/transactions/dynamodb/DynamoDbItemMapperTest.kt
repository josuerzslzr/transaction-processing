package com.josue.transactions.dynamodb

import com.josue.transactions.core.ClientId
import com.josue.transactions.core.CreateReceipt
import com.josue.transactions.core.Currency
import com.josue.transactions.core.Transaction
import com.josue.transactions.core.TransactionCreationAttempt
import com.josue.transactions.core.TransactionCreatedEvent
import com.josue.transactions.core.TransactionData
import com.josue.transactions.core.TransactionId
import com.josue.transactions.core.PaymentMethod
import com.josue.transactions.core.TransactionStatus
import com.josue.transactions.core.TransactionStoreDataException
import com.josue.transactions.core.TransactionType
import com.josue.transactions.core.StoredIdempotency
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Instant
import java.util.UUID

class DynamoDbItemMapperTest {
    private val mapper = DynamoDbItemMapper()
    private val attempt = fixtureAttempt()

    @Test
    fun `maps the exact transaction item shape and reads it back`() {
        val item = mapper.transactionItem(attempt.idempotency.receipt.transaction)

        assertThat(item.keys).containsExactlyInAnyOrder(
            "PK", "SK", "entityType", "schemaVersion", "clientId", "transactionId",
            "externalReference", "amountMinor", "currency", "transactionType", "paymentMethod", "status",
            "version", "createdAt", "updatedAt",
        )
        assertThat(item.text("PK")).isEqualTo("CLIENT#$CLIENT_ID#TRANSACTION#$TRANSACTION_ID")
        assertThat(item.text("SK")).isEqualTo("TRANSACTION")
        assertThat(item.text("externalReference")).isEqualTo(" ORDER-12345 ")
        assertThat(item.text("createdAt")).isEqualTo("2026-09-03T14:22:31.483Z")
        assertThat(mapper.readTransaction(item)).isEqualTo(attempt.idempotency.receipt.transaction)
    }

    @Test
    fun `maps the exact creation event shape`() {
        val item = mapper.creationEventItem(attempt.event)

        assertThat(item.keys).containsExactlyInAnyOrder(
            "PK", "SK", "entityType", "schemaVersion", "clientId", "transactionId", "eventId",
            "type", "source", "occurredAt", "fromStatus", "toStatus", "fromVersion",
            "toVersion", "data",
        )
        assertThat(item.text("SK")).isEqualTo("EVENT#20260903#$EVENT_ID")
        assertThat(item["fromStatus"]?.nul()).isTrue()
        assertThat(item["fromVersion"]?.nul()).isTrue()
        assertThat(item["data"]?.m()?.get("externalReference")?.s()).isEqualTo(" ORDER-12345 ")
        assertThat(item["data"]?.m()?.get("transactionType")?.s()).isEqualTo("PAYMENT")
        assertThat(mapper.readCreationEvent(item)).isEqualTo(attempt.event)
    }

    @Test
    fun `maps the exact idempotency shape and stored public response`() {
        val item = mapper.idempotencyItem(attempt.idempotency)

        assertThat(item.keys).containsExactlyInAnyOrder(
            "PK", "SK", "entityType", "schemaVersion", "clientId", "operationType",
            "requestHash", "transactionId", "createdAt", "expiresAtEpochSeconds",
            "responseStatus", "responseLocation", "responseBody",
        )
        assertThat(item.text("PK")).isEqualTo("CLIENT#$CLIENT_ID#IDEMPOTENCY#CREATE_TRANSACTION#$KEY_HASH")
        assertThat(item).doesNotContainKeys("idempotencyKey", "version")
        assertThat(item["responseBody"]?.m()).doesNotContainKeys("clientId", "version")
        assertThat(mapper.readIdempotency(item)).isEqualTo(attempt.idempotency)
    }

    @Test
    fun `rejects unsupported and malformed persisted records safely`() {
        val unsupported = mapper.transactionItem(attempt.idempotency.receipt.transaction).toMutableMap().apply {
            put("schemaVersion", number("2"))
        }
        val malformed = mapper.transactionItem(attempt.idempotency.receipt.transaction).toMutableMap().apply {
            remove("amountMinor")
        }

        assertThatThrownBy { mapper.readTransaction(unsupported) }
            .isInstanceOf(TransactionStoreDataException::class.java)
        assertThatThrownBy { mapper.readTransaction(malformed) }
            .isInstanceOf(TransactionStoreDataException::class.java)

        val unsupportedEvent = mapper.creationEventItem(attempt.event).toMutableMap().apply {
            put("schemaVersion", number("2"))
        }
        val malformedIdempotency = mapper.idempotencyItem(attempt.idempotency).toMutableMap().apply {
            remove("responseBody")
        }
        assertThatThrownBy { mapper.readCreationEvent(unsupportedEvent) }
            .isInstanceOf(TransactionStoreDataException::class.java)
        assertThatThrownBy { mapper.readIdempotency(malformedIdempotency) }
            .isInstanceOf(TransactionStoreDataException::class.java)
    }

    private fun fixtureAttempt(): TransactionCreationAttempt {
        val clientId = requireNotNull(ClientId.parse(CLIENT_ID))
        val transaction = Transaction(
            clientId = clientId,
            transactionId = requireNotNull(TransactionId.parse(TRANSACTION_ID)),
            data = TransactionData(
                " ORDER-12345 ",
                4250,
                Currency.EUR,
                TransactionType.PAYMENT,
                PaymentMethod.BANK_TRANSFER,
            ),
            status = TransactionStatus.CREATED,
            version = 1,
            createdAt = NOW,
            updatedAt = NOW,
        )
        return TransactionCreationAttempt(
            idempotency = StoredIdempotency(
                clientId = clientId,
                keyHash = KEY_HASH,
                requestHash = REQUEST_HASH,
                createdAt = NOW,
                expiresAtEpochSeconds = 1_788_531_752,
                receipt = CreateReceipt(201, "/v1/transactions/$TRANSACTION_ID", transaction),
            ),
            event = TransactionCreatedEvent(UUID.fromString(EVENT_ID), transaction, NOW),
            clientRequestToken = UUID.fromString(TOKEN_ID),
        )
    }

    private fun Map<String, AttributeValue>.text(name: String): String? = get(name)?.s()

    private fun number(value: String): AttributeValue = AttributeValue.builder().n(value).build()

    private companion object {
        const val CLIENT_ID = "01a067a6-5e3a-7a3b-8c4d-123456789abc"
        const val TRANSACTION_ID = "01a067a6-5e3b-7a3b-8c4d-123456789abc"
        const val EVENT_ID = "01a067a6-5e3c-7a3b-8c4d-123456789abc"
        const val TOKEN_ID = "01a067a6-5e3f-7a3b-8c4d-123456789abc"
        const val KEY_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val REQUEST_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val NOW: Instant = Instant.parse("2026-09-03T14:22:31.483Z")
    }
}
