package com.josue.transactions.dynamodb

import com.josue.transactions.core.ClientId
import com.josue.transactions.core.CreateReceipt
import com.josue.transactions.core.Currency
import com.josue.transactions.core.Transaction
import com.josue.transactions.core.TransactionCreatedEvent
import com.josue.transactions.core.TransactionData
import com.josue.transactions.core.TransactionId
import com.josue.transactions.core.PaymentMethod
import com.josue.transactions.core.TransactionStatus
import com.josue.transactions.core.TransactionStoreDataException
import com.josue.transactions.core.StoredIdempotency
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

class DynamoDbItemMapper {
    fun transactionItem(transaction: Transaction): Map<String, AttributeValue> = linkedMapOf(
        PK to text(transactionKey(transaction.clientId, transaction.transactionId)),
        SK to text(TRANSACTION_SK),
        ENTITY_TYPE to text(TRANSACTION_ENTITY),
        SCHEMA_VERSION to number(CURRENT_SCHEMA_VERSION),
        CLIENT_ID to text(transaction.clientId.toString()),
        TRANSACTION_ID to text(transaction.transactionId.toString()),
        EXTERNAL_REFERENCE to text(transaction.data.externalReference),
        AMOUNT_MINOR to number(transaction.data.amountMinor),
        CURRENCY to text(transaction.data.currency.name),
        TRANSACTION_TYPE to text(transaction.data.transactionType.name),
        PAYMENT_METHOD to text(transaction.data.paymentMethod.name),
        STATUS to text(transaction.status.name),
        VERSION to number(transaction.version),
        CREATED_AT to text(formatInstant(transaction.createdAt)),
        UPDATED_AT to text(formatInstant(transaction.updatedAt)),
    )

    fun creationEventItem(event: TransactionCreatedEvent): Map<String, AttributeValue> {
        val transaction = event.transaction
        return linkedMapOf(
            PK to text(transactionKey(transaction.clientId, transaction.transactionId)),
            SK to text("EVENT#${EVENT_DATE_FORMATTER.format(event.occurredAt)}#${event.eventId}"),
            ENTITY_TYPE to text(EVENT_ENTITY),
            SCHEMA_VERSION to number(CURRENT_SCHEMA_VERSION),
            CLIENT_ID to text(transaction.clientId.toString()),
            TRANSACTION_ID to text(transaction.transactionId.toString()),
            EVENT_ID to text(event.eventId.toString()),
            TYPE to text(TRANSACTION_CREATED),
            SOURCE to text(TRANSACTION_API),
            OCCURRED_AT to text(formatInstant(event.occurredAt)),
            FROM_STATUS to nullValue(),
            TO_STATUS to text(TransactionStatus.CREATED.name),
            FROM_VERSION to nullValue(),
            TO_VERSION to number(1),
            DATA to map(
                linkedMapOf(
                    EXTERNAL_REFERENCE to text(transaction.data.externalReference),
                    AMOUNT_MINOR to number(transaction.data.amountMinor),
                    CURRENCY to text(transaction.data.currency.name),
                    TRANSACTION_TYPE to text(transaction.data.transactionType.name),
                    PAYMENT_METHOD to text(transaction.data.paymentMethod.name),
                ),
            ),
        )
    }

    fun idempotencyItem(stored: StoredIdempotency): Map<String, AttributeValue> {
        val transaction = stored.receipt.transaction
        return linkedMapOf(
            PK to text(idempotencyKey(stored.clientId, stored.keyHash)),
            SK to text(IDEMPOTENCY_SK),
            ENTITY_TYPE to text(IDEMPOTENCY_ENTITY),
            SCHEMA_VERSION to number(CURRENT_SCHEMA_VERSION),
            CLIENT_ID to text(stored.clientId.toString()),
            OPERATION_TYPE to text(CREATE_TRANSACTION),
            REQUEST_HASH to text(stored.requestHash),
            TRANSACTION_ID to text(transaction.transactionId.toString()),
            CREATED_AT to text(formatInstant(stored.createdAt)),
            EXPIRES_AT to number(stored.expiresAtEpochSeconds),
            RESPONSE_STATUS to number(stored.receipt.responseStatus),
            RESPONSE_LOCATION to text(stored.receipt.responseLocation),
            RESPONSE_BODY to map(responseBody(transaction)),
        )
    }

    fun readTransaction(item: Map<String, AttributeValue>): Transaction = decode("transaction") {
        requireRecord(item, TRANSACTION_ENTITY)
        val clientId = requireNotNull(ClientId.parse(item.requiredText(CLIENT_ID)))
        val transactionId = requireNotNull(TransactionId.parse(item.requiredText(TRANSACTION_ID)))
        require(item.requiredText(PK) == transactionKey(clientId, transactionId))
        require(item.requiredText(SK) == TRANSACTION_SK)
        Transaction(
            clientId = clientId,
            transactionId = transactionId,
            data = item.readTransactionData(),
            status = enumValueOf(item.requiredText(STATUS)),
            version = item.requiredLong(VERSION).also { require(it > 0) },
            createdAt = item.requiredInstant(CREATED_AT),
            updatedAt = item.requiredInstant(UPDATED_AT),
        )
    }

    fun readCreationEvent(item: Map<String, AttributeValue>): TransactionCreatedEvent = decode("transaction event") {
        requireRecord(item, EVENT_ENTITY)
        val clientId = requireNotNull(ClientId.parse(item.requiredText(CLIENT_ID)))
        val transactionId = requireNotNull(TransactionId.parse(item.requiredText(TRANSACTION_ID)))
        val eventId = UUID.fromString(item.requiredText(EVENT_ID)).also(::requireUuidV7)
        val occurredAt = item.requiredInstant(OCCURRED_AT)
        require(item.requiredText(PK) == transactionKey(clientId, transactionId))
        require(item.requiredText(SK) == "EVENT#${EVENT_DATE_FORMATTER.format(occurredAt)}#$eventId")
        require(item.requiredText(TYPE) == TRANSACTION_CREATED)
        require(item.requiredText(SOURCE) == TRANSACTION_API)
        require(item.requiredNull(FROM_STATUS))
        require(item.requiredText(TO_STATUS) == TransactionStatus.CREATED.name)
        require(item.requiredNull(FROM_VERSION))
        require(item.requiredLong(TO_VERSION) == 1L)
        val data = item.requiredMap(DATA).readTransactionData()
        TransactionCreatedEvent(
            eventId = eventId,
            transaction = Transaction(clientId, transactionId, data, TransactionStatus.CREATED, 1, occurredAt, occurredAt),
            occurredAt = occurredAt,
        )
    }

    fun readIdempotency(item: Map<String, AttributeValue>): StoredIdempotency = decode("idempotency") {
        requireRecord(item, IDEMPOTENCY_ENTITY)
        val clientId = requireNotNull(ClientId.parse(item.requiredText(CLIENT_ID)))
        val transactionId = requireNotNull(TransactionId.parse(item.requiredText(TRANSACTION_ID)))
        val keyHash = item.requiredText(PK).substringAfterLast('#')
        require(keyHash.matches(SHA_256))
        require(item.requiredText(PK) == idempotencyKey(clientId, keyHash))
        require(item.requiredText(SK) == IDEMPOTENCY_SK)
        require(item.requiredText(OPERATION_TYPE) == CREATE_TRANSACTION)
        val requestHash = item.requiredText(REQUEST_HASH).also { require(it.matches(SHA_256)) }
        val responseStatus = item.requiredLong(RESPONSE_STATUS).also { require(it == 201L) }.toInt()
        val responseLocation = item.requiredText(RESPONSE_LOCATION)
        require(responseLocation == "/v1/transactions/$transactionId")
        val body = item.requiredMap(RESPONSE_BODY)
        require(body.requiredText(TRANSACTION_ID) == transactionId.toString())
        val transaction = Transaction(
            clientId = clientId,
            transactionId = transactionId,
            data = body.readTransactionData(),
            status = enumValueOf(body.requiredText(STATUS)),
            version = 1,
            createdAt = body.requiredInstant(CREATED_AT),
            updatedAt = body.requiredInstant(UPDATED_AT),
        )
        StoredIdempotency(
            clientId = clientId,
            keyHash = keyHash,
            requestHash = requestHash,
            createdAt = item.requiredInstant(CREATED_AT),
            expiresAtEpochSeconds = item.requiredLong(EXPIRES_AT),
            receipt = CreateReceipt(responseStatus, responseLocation, transaction),
        )
    }

    fun transactionKey(clientId: ClientId, transactionId: TransactionId): String =
        "CLIENT#$clientId#TRANSACTION#$transactionId"

    fun idempotencyKey(clientId: ClientId, keyHash: String): String =
        "CLIENT#$clientId#IDEMPOTENCY#CREATE_TRANSACTION#$keyHash"

    private fun responseBody(transaction: Transaction): Map<String, AttributeValue> = linkedMapOf(
        TRANSACTION_ID to text(transaction.transactionId.toString()),
        EXTERNAL_REFERENCE to text(transaction.data.externalReference),
        AMOUNT_MINOR to number(transaction.data.amountMinor),
        CURRENCY to text(transaction.data.currency.name),
        TRANSACTION_TYPE to text(transaction.data.transactionType.name),
        PAYMENT_METHOD to text(transaction.data.paymentMethod.name),
        STATUS to text(transaction.status.name),
        CREATED_AT to text(formatInstant(transaction.createdAt)),
        UPDATED_AT to text(formatInstant(transaction.updatedAt)),
    )

    private fun requireRecord(item: Map<String, AttributeValue>, entityType: String) {
        require(item.requiredText(ENTITY_TYPE) == entityType)
        require(item.requiredLong(SCHEMA_VERSION) == CURRENT_SCHEMA_VERSION.toLong())
    }

    private fun Map<String, AttributeValue>.readTransactionData(): TransactionData = TransactionData(
        externalReference = requiredText(EXTERNAL_REFERENCE),
        amountMinor = requiredLong(AMOUNT_MINOR),
        currency = enumValueOf(requiredText(CURRENCY)),
        transactionType = enumValueOf(requiredText(TRANSACTION_TYPE)),
        paymentMethod = enumValueOf(requiredText(PAYMENT_METHOD)),
    )

    private fun Map<String, AttributeValue>.requiredText(name: String): String =
        requireNotNull(get(name)?.s()).also { require(it.isNotEmpty()) }

    private fun Map<String, AttributeValue>.requiredLong(name: String): Long =
        requireNotNull(get(name)?.n()).toLong()

    private fun Map<String, AttributeValue>.requiredMap(name: String): Map<String, AttributeValue> =
        requireNotNull(get(name)?.m())

    private fun Map<String, AttributeValue>.requiredNull(name: String): Boolean =
        requireNotNull(get(name)?.nul())

    private fun Map<String, AttributeValue>.requiredInstant(name: String): Instant {
        val raw = requiredText(name)
        return Instant.parse(raw).also { require(formatInstant(it) == raw) }
    }

    private fun formatInstant(value: Instant): String = INSTANT_FORMATTER.format(value)

    private fun text(value: String): AttributeValue = AttributeValue.builder().s(value).build()
    private fun number(value: Number): AttributeValue = AttributeValue.builder().n(value.toString()).build()
    private fun nullValue(): AttributeValue = AttributeValue.builder().nul(true).build()
    private fun map(value: Map<String, AttributeValue>): AttributeValue = AttributeValue.builder().m(value).build()

    private inline fun <T> decode(recordName: String, block: () -> T): T = try {
        block()
    } catch (exception: TransactionStoreDataException) {
        throw exception
    } catch (exception: RuntimeException) {
        throw TransactionStoreDataException("Invalid persisted $recordName record", exception)
    }

    private fun requireUuidV7(value: UUID) {
        require(value.version() == 7 && value.variant() == 2)
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val PK = "PK"
        const val SK = "SK"
        const val ENTITY_TYPE = "entityType"
        const val SCHEMA_VERSION = "schemaVersion"
        const val CLIENT_ID = "clientId"
        const val TRANSACTION_ID = "transactionId"
        const val EVENT_ID = "eventId"
        const val EXTERNAL_REFERENCE = "externalReference"
        const val AMOUNT_MINOR = "amountMinor"
        const val CURRENCY = "currency"
        const val TRANSACTION_TYPE = "transactionType"
        const val PAYMENT_METHOD = "paymentMethod"
        const val STATUS = "status"
        const val VERSION = "version"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
        const val TYPE = "type"
        const val SOURCE = "source"
        const val OCCURRED_AT = "occurredAt"
        const val FROM_STATUS = "fromStatus"
        const val TO_STATUS = "toStatus"
        const val FROM_VERSION = "fromVersion"
        const val TO_VERSION = "toVersion"
        const val DATA = "data"
        const val OPERATION_TYPE = "operationType"
        const val REQUEST_HASH = "requestHash"
        const val EXPIRES_AT = "expiresAtEpochSeconds"
        const val RESPONSE_STATUS = "responseStatus"
        const val RESPONSE_LOCATION = "responseLocation"
        const val RESPONSE_BODY = "responseBody"
        const val TRANSACTION_SK = "TRANSACTION"
        const val IDEMPOTENCY_SK = "IDEMPOTENCY"
        const val TRANSACTION_ENTITY = "TRANSACTION"
        const val EVENT_ENTITY = "TRANSACTION_EVENT"
        const val IDEMPOTENCY_ENTITY = "IDEMPOTENCY"
        const val TRANSACTION_CREATED = "TRANSACTION_CREATED"
        const val TRANSACTION_API = "TRANSACTION_API"
        const val CREATE_TRANSACTION = "CREATE_TRANSACTION"
        val SHA_256 = Regex("^[0-9a-f]{64}$")
        val INSTANT_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        val EVENT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(java.time.ZoneOffset.UTC)
    }
}
