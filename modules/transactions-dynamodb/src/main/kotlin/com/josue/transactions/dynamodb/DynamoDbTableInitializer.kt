package com.josue.transactions.dynamodb

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest

class DynamoDbTableInitializer(
    private val client: DynamoDbClient,
) {
    fun initialize(tableName: String) {
        require(tableName.isNotBlank()) { "DynamoDB table name must not be blank" }
        try {
            client.createTable(
                CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                        attribute(PK),
                        attribute(SK),
                    )
                    .keySchema(
                        key(PK, KeyType.HASH),
                        key(SK, KeyType.RANGE),
                    )
                    .build(),
            )
            client.waiter().waitUntilTableExists { it.tableName(tableName) }
        } catch (_: ResourceInUseException) {
            // Another local process already created the table.
        }
        enableTimeToLive(tableName)
    }

    private fun enableTimeToLive(tableName: String) {
        try {
            client.updateTimeToLive(
                UpdateTimeToLiveRequest.builder()
                    .tableName(tableName)
                    .timeToLiveSpecification(
                        TimeToLiveSpecification.builder()
                            .attributeName(EXPIRES_AT)
                            .enabled(true)
                            .build(),
                    )
                    .build(),
            )
        } catch (_: RuntimeException) {
            // DynamoDB Local versions differ in TTL emulation; logical expiry is enforced by core.
        }
    }

    private fun attribute(name: String): AttributeDefinition = AttributeDefinition.builder()
        .attributeName(name)
        .attributeType(ScalarAttributeType.S)
        .build()

    private fun key(name: String, type: KeyType): KeySchemaElement = KeySchemaElement.builder()
        .attributeName(name)
        .keyType(type)
        .build()

    private companion object {
        const val PK = "PK"
        const val SK = "SK"
        const val EXPIRES_AT = "expiresAtEpochSeconds"
    }
}
