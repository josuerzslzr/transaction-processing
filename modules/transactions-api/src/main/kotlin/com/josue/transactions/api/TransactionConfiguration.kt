package com.josue.transactions.api

import com.josue.transactions.core.TransactionService
import com.josue.transactions.core.SystemUuidV7Generator
import com.josue.transactions.core.UuidGenerator
import com.josue.transactions.dynamodb.DynamoDbTransactionStore
import com.josue.transactions.dynamodb.DynamoDbTableInitializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class TransactionConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun uuidGenerator(): UuidGenerator = SystemUuidV7Generator()

    @Bean
    fun requestDecoder(): CreateTransactionRequestDecoder = CreateTransactionRequestDecoder()

    @Bean(destroyMethod = "close")
    fun dynamoDbClient(
        @Value("\${transactions.dynamodb.region:eu-central-1}") region: String,
        @Value("\${transactions.dynamodb.endpoint:}") endpoint: String,
    ): DynamoDbClient {
        val retryStrategy = StandardRetryStrategy.builder()
            .maxAttempts(3)
            .build()
        val builder = DynamoDbClient.builder()
            .region(Region.of(region))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .retryStrategy(retryStrategy)
                    .build(),
            )
        if (endpoint.isNotBlank()) {
            builder.endpointOverride(URI.create(endpoint))
            builder.credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")),
            )
        }
        return builder.build()
    }

    @Bean
    fun transactionStore(
        client: DynamoDbClient,
        @Value("\${transactions.dynamodb.table-name:transactions}") tableName: String,
    ): DynamoDbTransactionStore = DynamoDbTransactionStore(client, tableName)

    @Bean
    fun transactionService(store: DynamoDbTransactionStore, clock: Clock, uuidGenerator: UuidGenerator): TransactionService =
        TransactionService(store, clock, uuidGenerator)

    @Bean
    @Profile("local")
    fun localTableInitializer(
        client: DynamoDbClient,
        @Value("\${transactions.dynamodb.table-name:transactions}") tableName: String,
    ): ApplicationRunner = ApplicationRunner { DynamoDbTableInitializer(client).initialize(tableName) }
}
