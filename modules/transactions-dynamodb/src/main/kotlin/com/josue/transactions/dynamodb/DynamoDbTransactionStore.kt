package com.josue.transactions.dynamodb

import com.josue.transactions.core.ClientId
import com.josue.transactions.core.CreateCommitResult
import com.josue.transactions.core.Transaction
import com.josue.transactions.core.TransactionCreationAttempt
import com.josue.transactions.core.TransactionId
import com.josue.transactions.core.TransactionStore
import com.josue.transactions.core.TransactionStoreDataException
import com.josue.transactions.core.TransactionStoreUnavailableException
import com.josue.transactions.core.StoredIdempotency
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.Put
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException

class DynamoDbTransactionStore(
    private val client: DynamoDbClient,
    private val tableName: String,
    private val mapper: DynamoDbItemMapper = DynamoDbItemMapper(),
) : TransactionStore {
    init {
        require(tableName.isNotBlank()) { "DynamoDB table name must not be blank" }
    }

    override fun findIdempotency(clientId: ClientId, keyHash: String): StoredIdempotency? = read(
        key = mapOf(
            PK to text(mapper.idempotencyKey(clientId, keyHash)),
            SK to text(IDEMPOTENCY_SK),
        ),
        recordName = "idempotency",
        decode = mapper::readIdempotency,
    )

    override fun findTransaction(clientId: ClientId, transactionId: TransactionId): Transaction? = read(
        key = mapOf(
            PK to text(mapper.transactionKey(clientId, transactionId)),
            SK to text(TRANSACTION_SK),
        ),
        recordName = "transaction",
        decode = mapper::readTransaction,
    )

    override fun createTransaction(attempt: TransactionCreationAttempt, nowEpochSeconds: Long): CreateCommitResult {
        val idempotencyPut = Put.builder()
            .tableName(tableName)
            .item(mapper.idempotencyItem(attempt.idempotency))
            .conditionExpression("attribute_not_exists(PK) OR expiresAtEpochSeconds <= :now")
            .expressionAttributeValues(mapOf(":now" to number(nowEpochSeconds)))
            .build()
        val transactionPut = Put.builder()
            .tableName(tableName)
            .item(mapper.transactionItem(attempt.idempotency.receipt.transaction))
            .conditionExpression("attribute_not_exists(PK)")
            .build()
        val eventPut = Put.builder()
            .tableName(tableName)
            .item(mapper.creationEventItem(attempt.event))
            .conditionExpression("attribute_not_exists(PK) AND attribute_not_exists(SK)")
            .build()
        val request = TransactWriteItemsRequest.builder()
            .clientRequestToken(attempt.clientRequestToken.toString())
            .transactItems(
                TransactWriteItem.builder().put(idempotencyPut).build(),
                TransactWriteItem.builder().put(transactionPut).build(),
                TransactWriteItem.builder().put(eventPut).build(),
            )
            .build()

        return try {
            client.transactWriteItems(request)
            CreateCommitResult.COMMITTED
        } catch (exception: TransactionCanceledException) {
            if (exception.cancellationReasons().any { it.code() == CONDITIONAL_CHECK_FAILED }) {
                CreateCommitResult.IDEMPOTENCY_CONFLICT
            } else {
                throw TransactionStoreUnavailableException("DynamoDB transaction outcome unavailable", exception)
            }
        } catch (exception: SdkException) {
            throw TransactionStoreUnavailableException("DynamoDB transaction outcome unavailable", exception)
        }
    }

    private fun <T> read(
        key: Map<String, AttributeValue>,
        recordName: String,
        decode: (Map<String, AttributeValue>) -> T,
    ): T? = try {
        val response = client.getItem(
            GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .consistentRead(true)
                .build(),
        )
        if (!response.hasItem() || response.item().isEmpty()) null else decode(response.item())
    } catch (exception: TransactionStoreDataException) {
        throw exception
    } catch (exception: SdkException) {
        throw TransactionStoreUnavailableException("DynamoDB $recordName read unavailable", exception)
    }

    private fun text(value: String): AttributeValue = AttributeValue.builder().s(value).build()
    private fun number(value: Long): AttributeValue = AttributeValue.builder().n(value.toString()).build()

    private companion object {
        const val PK = "PK"
        const val SK = "SK"
        const val TRANSACTION_SK = "TRANSACTION"
        const val IDEMPOTENCY_SK = "IDEMPOTENCY"
        const val CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed"
    }
}
