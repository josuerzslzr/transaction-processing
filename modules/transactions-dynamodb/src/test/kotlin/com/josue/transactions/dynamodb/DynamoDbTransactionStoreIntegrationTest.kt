package com.josue.transactions.dynamodb

import com.josue.transactions.core.ClientId
import com.josue.transactions.core.CreateTransactionCommand
import com.josue.transactions.core.CreateTransactionResult
import com.josue.transactions.core.CreateReceipt
import com.josue.transactions.core.Currency
import com.josue.transactions.core.Transaction
import com.josue.transactions.core.TransactionCreationAttempt
import com.josue.transactions.core.TransactionCreatedEvent
import com.josue.transactions.core.TransactionData
import com.josue.transactions.core.TransactionId
import com.josue.transactions.core.PaymentMethod
import com.josue.transactions.core.TransactionService
import com.josue.transactions.core.TransactionStatus
import com.josue.transactions.core.TransactionType
import com.josue.transactions.core.StoredIdempotency
import com.josue.transactions.core.SystemUuidV7Generator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.ScanRequest
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class DynamoDbTransactionStoreIntegrationTest {
    private lateinit var client: DynamoDbClient
    private lateinit var tableName: String
    private lateinit var store: DynamoDbTransactionStore

    @BeforeEach
    fun setUp() {
        client = DynamoDbClient.builder()
            .endpointOverride(URI.create("http://${dynamo.host}:${dynamo.getMappedPort(8000)}"))
            .region(Region.EU_CENTRAL_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")),
            )
            .build()
        tableName = "transactions-${UUID.randomUUID()}"
        DynamoDbTableInitializer(client).initialize(tableName)
        store = DynamoDbTransactionStore(client, tableName)
    }

    @Test
    fun `atomic create writes exactly the approved three records and supports strong tenant reads`() {
        val attempt = attempt(TRANSACTION_ID, EVENT_ID, expiresAt = NOW.epochSecond + 86_400)

        assertThat(store.createTransaction(attempt, NOW.epochSecond).name).isEqualTo("COMMITTED")

        val items = allItems()
        assertThat(items).hasSize(3)
        assertThat(items.map { it.getValue("entityType").s() })
            .containsExactlyInAnyOrder("TRANSACTION", "TRANSACTION_EVENT", "IDEMPOTENCY")
        assertThat(store.findTransaction(CLIENT, requireNotNull(TransactionId.parse(TRANSACTION_ID))))
            .isEqualTo(attempt.idempotency.receipt.transaction)
        assertThat(store.findTransaction(OTHER_CLIENT, requireNotNull(TransactionId.parse(TRANSACTION_ID)))).isNull()
        assertThat(store.findIdempotency(CLIENT, KEY_HASH)).isEqualTo(attempt.idempotency)
    }

    @Test
    fun `expired idempotency item is replaced while the original transaction and event remain`() {
        val first = attempt(TRANSACTION_ID, EVENT_ID, expiresAt = NOW.epochSecond)
        val replacement = attempt(TRANSACTION_ID_2, EVENT_ID_2, expiresAt = NOW.epochSecond + 86_400)

        assertThat(store.createTransaction(first, NOW.epochSecond - 1).name).isEqualTo("COMMITTED")
        assertThat(store.createTransaction(replacement, NOW.epochSecond).name).isEqualTo("COMMITTED")

        assertThat(allItems()).hasSize(5)
        assertThat(store.findTransaction(CLIENT, requireNotNull(TransactionId.parse(TRANSACTION_ID)))).isNotNull()
        assertThat(store.findTransaction(CLIENT, requireNotNull(TransactionId.parse(TRANSACTION_ID_2)))).isNotNull()
        assertThat(store.findIdempotency(CLIENT, KEY_HASH)).isEqualTo(replacement.idempotency)
    }

    @Test
    fun `transaction cancellation leaves no idempotency or event partial writes`() {
        val attempt = attempt(TRANSACTION_ID, EVENT_ID, expiresAt = NOW.epochSecond + 86_400)
        client.putItem(
            PutItemRequest.builder()
                .tableName(tableName)
                .item(DynamoDbItemMapper().transactionItem(attempt.idempotency.receipt.transaction))
                .build(),
        )

        assertThat(store.createTransaction(attempt, NOW.epochSecond).name).isEqualTo("IDEMPOTENCY_CONFLICT")
        assertThat(allItems()).hasSize(1)
        assertThat(store.findIdempotency(CLIENT, KEY_HASH)).isNull()
    }

    @Test
    fun `two simultaneous identical creates converge on one stored response`() {
        val service = TransactionService(
            store = store,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            uuidGenerator = SystemUuidV7Generator(),
        )
        val command = CreateTransactionCommand(
            clientId = CLIENT,
            idempotencyKey = requireNotNull(com.josue.transactions.core.IdempotencyKey.parse(IDEMPOTENCY_KEY)),
            transactionData = DATA,
        )
        val executor = Executors.newFixedThreadPool(2)
        val results = try {
            executor.invokeAll(List(2) { Callable { service.createTransaction(command) } }).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertThat(results).allMatch { it is CreateTransactionResult.Created }
        val receipts = results.map { (it as CreateTransactionResult.Created).receipt }
        assertThat(receipts[0]).isEqualTo(receipts[1])
        assertThat(allItems()).hasSize(3)
    }

    private fun allItems(): List<Map<String, AttributeValue>> = client.scan(
        ScanRequest.builder().tableName(tableName).consistentRead(true).build(),
    ).items()

    private fun attempt(transactionId: String, eventId: String, expiresAt: Long): TransactionCreationAttempt {
        val transaction = Transaction(
            clientId = CLIENT,
            transactionId = requireNotNull(TransactionId.parse(transactionId)),
            data = DATA,
            status = TransactionStatus.CREATED,
            version = 1,
            createdAt = NOW,
            updatedAt = NOW,
        )
        return TransactionCreationAttempt(
            idempotency = StoredIdempotency(
                clientId = CLIENT,
                keyHash = KEY_HASH,
                requestHash = REQUEST_HASH,
                createdAt = NOW,
                expiresAtEpochSeconds = expiresAt,
                receipt = CreateReceipt(201, "/v1/transactions/$transactionId", transaction),
            ),
            event = TransactionCreatedEvent(UUID.fromString(eventId), transaction, NOW),
            clientRequestToken = SystemUuidV7Generator().generate(),
        )
    }

    private companion object {
        const val IMAGE = "amazon/dynamodb-local:3.3.1"
        const val CLIENT_ID = "01a067a6-5e3a-7a3b-8c4d-123456789abc"
        const val OTHER_CLIENT_ID = "01a067a6-5e30-7a3b-8c4d-123456789abc"
        const val TRANSACTION_ID = "01a067a6-5e3b-7a3b-8c4d-123456789abc"
        const val EVENT_ID = "01a067a6-5e3c-7a3b-8c4d-123456789abc"
        const val TRANSACTION_ID_2 = "01a067a6-5e4b-7a3b-8c4d-123456789abc"
        const val EVENT_ID_2 = "01a067a6-5e4c-7a3b-8c4d-123456789abc"
        const val IDEMPOTENCY_KEY = "01a067a6-5e3d-7a3b-8c4d-123456789abc"
        const val KEY_HASH = "c4b2f6868fa702b34a4b502cefd0d50384768e7a6645a46b5c2ce7dd57599695"
        const val REQUEST_HASH = "3d3fd09b678c3d752c9a745fe8ce161b42406476de28ccb948b359dd5cd29fff"
        // Keep physical DynamoDB Local TTL deletion out of this logical-expiry test.
        val NOW: Instant = Instant.parse("2099-09-03T14:22:31.483Z")
        val CLIENT = requireNotNull(ClientId.parse(CLIENT_ID))
        val OTHER_CLIENT = requireNotNull(ClientId.parse(OTHER_CLIENT_ID))
        val DATA = TransactionData("ORDER-12345", 4250, Currency.EUR, TransactionType.PAYMENT, PaymentMethod.BANK_TRANSFER)

        @Container
        @JvmStatic
        val dynamo: GenericContainer<*> = GenericContainer(DockerImageName.parse(IMAGE))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
    }
}
