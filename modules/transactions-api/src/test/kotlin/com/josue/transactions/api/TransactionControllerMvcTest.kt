package com.josue.transactions.api

import com.josue.transactions.core.ClientId
import com.josue.transactions.core.CreateCommitResult
import com.josue.transactions.core.Currency
import com.josue.transactions.core.Transaction
import com.josue.transactions.core.TransactionCreationAttempt
import com.josue.transactions.core.TransactionData
import com.josue.transactions.core.TransactionId
import com.josue.transactions.core.PaymentMethod
import com.josue.transactions.core.TransactionService
import com.josue.transactions.core.TransactionStatus
import com.josue.transactions.core.TransactionStore
import com.josue.transactions.core.TransactionType
import com.josue.transactions.core.StoredIdempotency
import com.josue.transactions.core.UuidGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.UUID

class TransactionControllerMvcTest {
    private lateinit var store: FakeStore
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        store = FakeStore()
        val service = TransactionService(
            store,
            Clock.fixed(NOW, ZoneOffset.UTC),
            QueueUuidGenerator(TRANSACTION_ID, EVENT_ID, TOKEN_ID),
        )
        mvc = MockMvcBuilders
            .standaloneSetup(TransactionController(service, CreateTransactionRequestDecoder()))
            .setControllerAdvice(ApiExceptionHandler())
            .build()
    }

    @Test
    fun `create returns contract response and identical replay returns identical stored outcome`() {
        val first = create(VALID_BODY).andExpect {
            status { isCreated() }
            content { contentType(MediaType.APPLICATION_JSON) }
            header { string("Location", "/v1/transactions/$TRANSACTION_ID") }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.transactionId") { value(TRANSACTION_ID) }
            jsonPath("$.transactionType") { value("PAYMENT") }
            jsonPath("$.externalReference") { value(" ORDER-12345 ") }
            jsonPath("$.createdAt") { value("2026-09-03T14:22:31.483Z") }
            jsonPath("$.clientId") { doesNotExist() }
            jsonPath("$.version") { doesNotExist() }
        }.andReturn().response

        val replay = create(VALID_BODY).andExpect { status { isCreated() } }.andReturn().response

        assertThat(replay.contentAsString).isEqualTo(first.contentAsString)
        assertThat(replay.getHeader("Location")).isEqualTo(first.getHeader("Location"))
        assertThat(store.createCalls).isOne()
    }

    @Test
    fun `conflicting reuse produces deterministic 422 without rejected data`() {
        create(VALID_BODY).andExpect { status { isCreated() } }

        create(VALID_BODY.replace("4250", "4251")).andExpect {
            status { isUnprocessableContent() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("/problems/idempotency-key-reused") }
            jsonPath("$.status") { value(422) }
            jsonPath("$.errors") { doesNotExist() }
        }
        assertThat(store.createCalls).isOne()
    }

    @Test
    fun `missing and invalid headers have distinct deterministic problems`() {
        mvc.post("/v1/transactions") {
            contentType = MediaType.APPLICATION_JSON
            content = VALID_BODY
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("/problems/missing-required-header") }
            jsonPath("$.detail") { value("The X-Client-Id header is required.") }
        }

        mvc.post("/v1/transactions") {
            header("X-Client-Id", CLIENT_ID)
            header("Idempotency-Key", "not-a-uuid")
            contentType = MediaType.APPLICATION_JSON
            content = VALID_BODY
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("/problems/invalid-header-value") }
            jsonPath("$.detail") { value("The Idempotency-Key header must be a valid UUIDv7.") }
        }
    }

    @Test
    fun `malformed JSON is distinct from typed and schema validation`() {
        create("{").andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("/problems/malformed-json") }
            jsonPath("$.errors") { doesNotExist() }
        }

        create(VALID_BODY.replace("4250", "\"4250\"")).andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("/problems/invalid-request-body") }
            jsonPath("$.errors[0].pointer") { value("#/amountMinor") }
            jsonPath("$.errors[0].detail") { value("must be a signed 64-bit integer") }
        }
    }

    @Test
    fun `unknown and duplicate properties are rejected without echoing their values`() {
        create(VALID_BODY.dropLast(1) + ",\"unexpected\":\"secret\"}").andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("/problems/invalid-request-body") }
            jsonPath("$.errors[0].pointer") { value("#") }
            jsonPath("$.errors[0].detail") { value("unknown properties are not allowed") }
            content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))) }
        }

        create(VALID_BODY.dropLast(1) + ",\"amountMinor\":99}").andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("/problems/invalid-request-body") }
            jsonPath("$.errors[0].detail") { value("duplicate properties are not allowed") }
        }
    }

    @Test
    fun `all documented body constraints reject with stable pointers`() {
        val invalid = """{
            "externalReference":"   ",
            "amountMinor":0,
            "currency":"USD",
            "transactionType":"TRANSFER",
            "paymentMethod":"CARD"
        }""".trimIndent()

        create(invalid).andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("/problems/invalid-request-body") }
            jsonPath("$.errors.length()") { value(5) }
            jsonPath("$.errors[0].pointer") { value("#/amountMinor") }
            jsonPath("$.errors[1].pointer") { value("#/currency") }
            jsonPath("$.errors[2].pointer") { value("#/externalReference") }
            jsonPath("$.errors[3].pointer") { value("#/paymentMethod") }
            jsonPath("$.errors[4].pointer") { value("#/transactionType") }
        }
    }

    @Test
    fun `transaction type is required and only payment is supported`() {
        create(VALID_BODY.replace("\"transactionType\":\"PAYMENT\",", "")).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].pointer") { value("#/transactionType") }
            jsonPath("$.errors[0].detail") { value("is required") }
        }

        create(VALID_BODY.replace("PAYMENT", "WALLET_TRANSFER")).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].pointer") { value("#/transactionType") }
            jsonPath("$.errors[0].detail") { value("must be PAYMENT") }
        }

        val missingPaymentMethod = """{
            "externalReference":" ORDER-12345 ",
            "amountMinor":4250,
            "currency":"EUR",
            "transactionType":"PAYMENT"
        }""".trimIndent()
        create(missingPaymentMethod).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].pointer") { value("#/paymentMethod") }
            jsonPath("$.errors[0].detail") { value("is required") }
        }
    }

    @Test
    fun `non JSON content is rejected with 415`() {
        mvc.post("/v1/transactions") {
            header("X-Client-Id", CLIENT_ID)
            header("Idempotency-Key", IDEMPOTENCY_KEY)
            contentType = MediaType.TEXT_PLAIN
            content = VALID_BODY
        }.andExpect {
            status { isUnsupportedMediaType() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("/problems/unsupported-media-type") }
        }
    }

    @Test
    fun `retrieval is tenant scoped and invalid paths are rejected before persistence`() {
        create(VALID_BODY).andExpect { status { isCreated() } }

        mvc.get("/v1/transactions/$TRANSACTION_ID") {
            header("X-Client-Id", CLIENT_ID)
        }.andExpect {
            status { isOk() }
            jsonPath("$.transactionId") { value(TRANSACTION_ID) }
        }
        mvc.get("/v1/transactions/$TRANSACTION_ID") {
            header("X-Client-Id", OTHER_CLIENT_ID)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("/problems/transaction-not-found") }
        }
        mvc.get("/v1/transactions/not-a-uuid") {
            header("X-Client-Id", CLIENT_ID)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("/problems/invalid-path-parameter") }
        }
    }

    private fun create(body: String) = mvc.post("/v1/transactions") {
        header("X-Client-Id", CLIENT_ID)
        header("Idempotency-Key", IDEMPOTENCY_KEY)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private class QueueUuidGenerator(vararg values: String) : UuidGenerator {
        private val values = ArrayDeque(values.map(UUID::fromString))
        override fun generate(): UUID = values.removeFirst()
    }

    private class FakeStore : TransactionStore {
        private var idempotency: StoredIdempotency? = null
        private val transactions = mutableMapOf<Pair<ClientId, TransactionId>, Transaction>()
        var createCalls = 0

        override fun findIdempotency(clientId: ClientId, keyHash: String): StoredIdempotency? =
            idempotency?.takeIf { it.clientId == clientId && it.keyHash == keyHash }

        override fun createTransaction(attempt: TransactionCreationAttempt, nowEpochSeconds: Long): CreateCommitResult {
            createCalls++
            idempotency = attempt.idempotency
            val transaction = attempt.idempotency.receipt.transaction
            transactions[transaction.clientId to transaction.transactionId] = transaction
            return CreateCommitResult.COMMITTED
        }

        override fun findTransaction(clientId: ClientId, transactionId: TransactionId): Transaction? = transactions[clientId to transactionId]
    }

    private companion object {
        const val CLIENT_ID = "01a067a6-5e3a-7a3b-8c4d-123456789abc"
        const val OTHER_CLIENT_ID = "01a067a6-5e30-7a3b-8c4d-123456789abc"
        const val IDEMPOTENCY_KEY = "01a067a6-5e3d-7a3b-8c4d-123456789abc"
        const val TRANSACTION_ID = "01a067a6-5e3b-7a3b-8c4d-123456789abc"
        const val EVENT_ID = "01a067a6-5e3c-7a3b-8c4d-123456789abc"
        const val TOKEN_ID = "01a067a6-5e3f-7a3b-8c4d-123456789abc"
        val NOW: Instant = Instant.parse("2026-09-03T14:22:31.483Z")
        val DATA = TransactionData("ORDER-12345", 4250, Currency.EUR, TransactionType.PAYMENT, PaymentMethod.BANK_TRANSFER)
        val STATUS = TransactionStatus.CREATED
        val VALID_BODY = """{
            "externalReference":" ORDER-12345 ",
            "amountMinor":4250,
            "currency":"EUR",
            "transactionType":"PAYMENT",
            "paymentMethod":"BANK_TRANSFER"
        }""".trimIndent()
    }
}
