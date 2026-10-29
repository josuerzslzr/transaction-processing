package com.josue.transactions.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("local")
class TransactionApplicationIntegrationTest {
    @Value("\${local.server.port}")
    private var port: Int = 0

    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

    @Test
    fun `real HTTP and DynamoDB adapter support create replay conflict and tenant retrieval`() {
        val created = post(VALID_BODY)
        assertThat(created.statusCode()).isEqualTo(201)
        assertThat(created.headers().firstValue("Location")).hasValueSatisfying {
            assertThat(it).startsWith("/v1/transactions/")
        }
        val createdBody = json.readTree(created.body())
        val transactionId = createdBody.get("transactionId").stringValue()
        assertThat(createdBody.get("externalReference").stringValue()).isEqualTo(" ORDER-12345 ")
        assertThat(createdBody.has("clientId")).isFalse()
        assertThat(createdBody.has("version")).isFalse()

        val replay = post(VALID_BODY)
        assertThat(replay.statusCode()).isEqualTo(201)
        assertThat(replay.body()).isEqualTo(created.body())
        assertThat(replay.headers().firstValue("Location")).isEqualTo(created.headers().firstValue("Location"))

        val conflict = post(VALID_BODY.replace("4250", "4251"))
        assertThat(conflict.statusCode()).isEqualTo(422)
        assertThat(json.readTree(conflict.body()).get("type").stringValue())
            .isEqualTo("/problems/idempotency-key-reused")

        val retrieved = get(transactionId, CLIENT_ID)
        assertThat(retrieved.statusCode()).isEqualTo(200)
        assertThat(retrieved.body()).isEqualTo(created.body())

        val wrongTenant = get(transactionId, OTHER_CLIENT_ID)
        assertThat(wrongTenant.statusCode()).isEqualTo(404)
        assertThat(json.readTree(wrongTenant.body()).get("type").stringValue())
            .isEqualTo("/problems/transaction-not-found")
    }

    private fun post(body: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/transactions"))
            .header("Content-Type", "application/json")
            .header("X-Client-Id", CLIENT_ID)
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun get(transactionId: String, clientId: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/transactions/$transactionId"))
            .header("X-Client-Id", clientId)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private companion object {
        const val IMAGE = "amazon/dynamodb-local:3.3.1"
        const val TABLE_NAME = "transactions-api-integration"
        const val CLIENT_ID = "01a067a6-5e3a-7a3b-8c4d-123456789abc"
        const val OTHER_CLIENT_ID = "01a067a6-5e30-7a3b-8c4d-123456789abc"
        const val IDEMPOTENCY_KEY = "01a067a6-5e3d-7a3b-8c4d-123456789abc"
        val VALID_BODY = """{
            "externalReference":" ORDER-12345 ",
            "amountMinor":4250,
            "currency":"EUR",
            "transactionType":"PAYMENT",
            "paymentMethod":"BANK_TRANSFER"
        }""".trimIndent()

        @Container
        @JvmStatic
        val dynamo: GenericContainer<*> = GenericContainer(DockerImageName.parse(IMAGE))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")

        @DynamicPropertySource
        @JvmStatic
        fun dynamoProperties(registry: DynamicPropertyRegistry) {
            if (!dynamo.isRunning) {
                dynamo.start()
            }
            registry.add("transactions.dynamodb.endpoint") { "http://${dynamo.host}:${dynamo.getMappedPort(8000)}" }
            registry.add("transactions.dynamodb.region") { "eu-central-1" }
            registry.add("transactions.dynamodb.table-name") { TABLE_NAME }
        }
    }
}
