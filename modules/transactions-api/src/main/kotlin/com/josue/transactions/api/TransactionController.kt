package com.josue.transactions.api

import com.josue.transactions.core.ClientId
import com.josue.transactions.core.CreateTransactionCommand
import com.josue.transactions.core.CreateTransactionResult
import com.josue.transactions.core.Currency
import com.josue.transactions.core.GetTransactionResult
import com.josue.transactions.core.IdempotencyKey
import com.josue.transactions.core.TransactionData
import com.josue.transactions.core.TransactionId
import com.josue.transactions.core.PaymentMethod
import com.josue.transactions.core.TransactionService
import com.josue.transactions.core.TransactionType
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/v1/transactions")
class TransactionController(
    private val service: TransactionService,
    private val decoder: CreateTransactionRequestDecoder,
) {
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun createTransaction(
        @RequestHeader(name = CLIENT_HEADER, required = false) rawClientId: String?,
        @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) rawIdempotencyKey: String?,
        @RequestBody(required = false) rawBody: String?,
    ): ResponseEntity<TransactionDto> {
        val clientId = clientId(rawClientId)
        val idempotencyKey = when {
            rawIdempotencyKey == null -> throw ApiProblemException.missingHeader(IDEMPOTENCY_HEADER)
            else -> IdempotencyKey.parse(rawIdempotencyKey)
                ?: throw ApiProblemException.invalidHeader(IDEMPOTENCY_HEADER)
        }
        val request = decoder.decode(rawBody)
        val result = service.createTransaction(
            CreateTransactionCommand(
                clientId,
                idempotencyKey,
                TransactionData(
                    request.externalReference,
                    request.amountMinor,
                    Currency.valueOf(request.currency),
                    TransactionType.valueOf(request.transactionType),
                    PaymentMethod.valueOf(request.paymentMethod),
                ),
            ),
        )
        return when (result) {
            is CreateTransactionResult.Created -> ResponseEntity
                .status(result.receipt.responseStatus)
                .location(URI.create(result.receipt.responseLocation))
                .cacheControl(CacheControl.noStore())
                .body(TransactionDto.from(result.receipt.transaction))
            CreateTransactionResult.IdempotencyKeyReused -> throw ApiProblemException.idempotencyReused()
            CreateTransactionResult.Unavailable -> throw ApiProblemException.unavailable()
            CreateTransactionResult.InvalidPersistedData -> throw ApiProblemException.internal()
        }
    }

    @GetMapping("/{transactionId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getTransaction(
        @RequestHeader(name = CLIENT_HEADER, required = false) rawClientId: String?,
        @PathVariable transactionId: String,
    ): TransactionDto {
        val clientId = clientId(rawClientId)
        val parsedTransactionId = TransactionId.parse(transactionId) ?: throw ApiProblemException.invalidTransactionId()
        return when (val result = service.getTransaction(clientId, parsedTransactionId)) {
            is GetTransactionResult.Found -> TransactionDto.from(result.transaction)
            GetTransactionResult.NotFound -> throw ApiProblemException.notFound()
            GetTransactionResult.Unavailable -> throw ApiProblemException.unavailable()
            GetTransactionResult.InvalidPersistedData -> throw ApiProblemException.internal()
        }
    }

    private fun clientId(raw: String?): ClientId = when {
        raw == null -> throw ApiProblemException.missingHeader(CLIENT_HEADER)
        else -> ClientId.parse(raw) ?: throw ApiProblemException.invalidHeader(CLIENT_HEADER)
    }

    private companion object {
        const val CLIENT_HEADER = "X-Client-Id"
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
    }
}
