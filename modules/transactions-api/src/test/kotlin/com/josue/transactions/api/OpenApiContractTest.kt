package com.josue.transactions.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OpenApiContractTest {
    private val yamlMapper = ObjectMapper(YAMLFactory())
    private val jsonMapper = ObjectMapper()
    private val document: JsonNode = yamlMapper.readTree(Files.readString(contractPath()))
    private val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    @Test
    fun `contract is OpenAPI 3_1 and every local reference resolves`() {
        assertThat(document.at("/openapi").textValue()).isEqualTo("3.1.0")

        val references = mutableListOf<String>()
        walk(document) { node -> node.get("\$ref")?.textValue()?.let(references::add) }

        assertThat(references).isNotEmpty()
        assertThat(references).allSatisfy { reference ->
            assertThat(reference).startsWith("#/")
            assertThat(document.at(reference.removePrefix("#")))
                .describedAs(reference)
                .isNotSameAs(MissingNode.getInstance())
        }
    }

    @Test
    fun `approved request and response examples conform to resolved component schemas`() {
        val requestExample = document.at(
            "/paths/~1v1~1transactions/post/requestBody/content/application~1json/examples/bankTransfer/value",
        )
        val transactionExample = resolve(document.at("/components/examples/TransactionInCreatedState/value"))

        assertValid("/components/schemas/CreateTransactionRequest", requestExample)
        assertValid("/components/schemas/Transaction", transactionExample)
        assertValid("/components/schemas/CreateTransactionResponse", transactionExample)

        PROBLEM_EXAMPLES.forEach { pointer ->
            assertValid("/components/schemas/ProblemDetail", resolve(document.at(pointer)))
        }
    }

    @Test
    fun `representative implementation responses obey success and problem schemas`() {
        val success = jsonMapper.readTree(
            """{
              "transactionId":"01a067a6-5e3b-7a3b-8c4d-123456789abc",
              "externalReference":" ORDER-12345 ",
              "amountMinor":4250,
              "currency":"EUR",
              "transactionType":"PAYMENT",
              "paymentMethod":"BANK_TRANSFER",
              "status":"CREATED",
              "createdAt":"2026-09-03T14:22:31.483Z",
              "updatedAt":"2026-09-03T14:22:31.483Z"
            }""",
        )
        val problem = jsonMapper.readTree(
            """{
              "type":"/problems/invalid-request-body",
              "title":"Invalid request body",
              "status":400,
              "detail":"One or more request fields are invalid.",
              "errors":[{"detail":"must be a positive integer","pointer":"#/amountMinor"}]
            }""",
        )

        assertValid("/components/schemas/CreateTransactionResponse", success)
        assertValid("/components/schemas/ProblemDetail", problem)
    }

    private fun assertValid(schemaPointer: String, instance: JsonNode) {
        val schema = schemaRegistry.getSchema(resolve(document.at(schemaPointer)))
        assertThat(schema.validate(instance)).describedAs(schemaPointer).isEmpty()
    }

    private fun resolve(node: JsonNode, seen: Set<String> = emptySet()): JsonNode {
        if (node.isObject && node.has("\$ref")) {
            val reference = node.get("\$ref").textValue()
            require(reference.startsWith("#/") && reference !in seen) { "Invalid or cyclic local reference: $reference" }
            return resolve(document.at(reference.removePrefix("#")), seen + reference)
        }
        return when {
            node.isObject -> jsonMapper.createObjectNode().also { copy ->
                node.properties().forEach { (name, value) -> copy.set<JsonNode>(name, resolve(value, seen)) }
            }
            node.isArray -> jsonMapper.createArrayNode().also { copy ->
                node.forEach { value -> (copy as ArrayNode).add(resolve(value, seen)) }
            }
            else -> node.deepCopy()
        }
    }

    private fun walk(node: JsonNode, visitor: (ObjectNode) -> Unit) {
        when {
            node.isObject -> {
                visitor(node as ObjectNode)
                node.elements().forEachRemaining { walk(it, visitor) }
            }
            node.isArray -> node.elements().forEachRemaining { walk(it, visitor) }
        }
    }

    private fun contractPath(): Path {
        val candidates = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("api/openapi.yaml") }
        return candidates.firstOrNull(Files::exists)
            ?: error("Cannot locate api/openapi.yaml from ${Path.of("").toAbsolutePath()}")
    }

    private companion object {
        val PROBLEM_EXAMPLES = listOf(
            "/paths/~1v1~1transactions/post/responses/400/content/application~1problem+json/examples/malformedJson/value",
            "/paths/~1v1~1transactions/post/responses/400/content/application~1problem+json/examples/invalidRequestBody/value",
            "/paths/~1v1~1transactions/post/responses/400/content/application~1problem+json/examples/missingRequiredHeader/value",
            "/paths/~1v1~1transactions/post/responses/400/content/application~1problem+json/examples/invalidHeaderValue/value",
            "/paths/~1v1~1transactions/post/responses/415/content/application~1problem+json/example",
            "/paths/~1v1~1transactions/post/responses/422/content/application~1problem+json/example",
            "/paths/~1v1~1transactions~1{transactionId}/get/responses/400/content/application~1problem+json/examples/missingRequiredHeader/value",
            "/paths/~1v1~1transactions~1{transactionId}/get/responses/400/content/application~1problem+json/examples/invalidHeaderValue/value",
            "/paths/~1v1~1transactions~1{transactionId}/get/responses/400/content/application~1problem+json/examples/invalidPathParameter/value",
            "/paths/~1v1~1transactions~1{transactionId}/get/responses/404/content/application~1problem+json/example",
            "/components/responses/InternalError/content/application~1problem+json/example",
            "/components/responses/ServiceUnavailable/content/application~1problem+json/example",
        )
    }
}
