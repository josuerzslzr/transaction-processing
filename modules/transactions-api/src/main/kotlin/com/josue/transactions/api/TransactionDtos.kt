package com.josue.transactions.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.josue.transactions.core.Transaction
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

data class CreateTransactionRequestDto(
    val externalReference: String,
    val amountMinor: Long,
    val currency: String,
    val transactionType: String,
    val paymentMethod: String,
)

data class TransactionDto(
    val transactionId: String,
    val externalReference: String,
    val amountMinor: Long,
    val currency: String,
    val transactionType: String,
    val paymentMethod: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        private val INSTANT_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

        fun from(transaction: Transaction): TransactionDto = TransactionDto(
            transactionId = transaction.transactionId.toString(),
            externalReference = transaction.data.externalReference,
            amountMinor = transaction.data.amountMinor,
            currency = transaction.data.currency.name,
            transactionType = transaction.data.transactionType.name,
            paymentMethod = transaction.data.paymentMethod.name,
            status = transaction.status.name,
            createdAt = INSTANT_FORMATTER.format(transaction.createdAt),
            updatedAt = INSTANT_FORMATTER.format(transaction.updatedAt),
        )
    }
}

data class RequestBodyErrorDto(
    val detail: String,
    val pointer: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiProblem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val errors: List<RequestBodyErrorDto>? = null,
)
