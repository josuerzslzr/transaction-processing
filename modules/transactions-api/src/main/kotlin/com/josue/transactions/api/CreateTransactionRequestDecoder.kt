package com.josue.transactions.api

import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class CreateTransactionRequestDecoder {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun decode(rawBody: String?): CreateTransactionRequestDto {
        if (rawBody.isNullOrEmpty()) {
            throw invalidBody(RequestBodyErrorDto("request body is required", "#"))
        }
        val root = try {
            mapper.readTree(rawBody)
        } catch (exception: JacksonException) {
            if (exception.message?.contains("Duplicate Object property", ignoreCase = true) == true) {
                throw invalidBody(RequestBodyErrorDto("duplicate properties are not allowed", "#"))
            }
            throw ApiProblemException.malformedJson()
        }
        if (root == null || !root.isObject) {
            throw invalidBody(RequestBodyErrorDto("must be a JSON object", "#"))
        }

        val errors = mutableListOf<RequestBodyErrorDto>()
        val names = root.propertyNames().asSequence().toSet()
        if ((names - EXPECTED_FIELDS).isNotEmpty()) {
            errors += RequestBodyErrorDto("unknown properties are not allowed", "#")
        }
        val externalReference = root.text(EXTERNAL_REFERENCE, errors)
        val amountMinor = root.long(AMOUNT_MINOR, errors)
        val currency = root.text(CURRENCY, errors)
        val transactionType = root.text(TRANSACTION_TYPE, errors)
        val paymentMethod = root.text(PAYMENT_METHOD, errors)

        if (externalReference != null) {
            val count = externalReference.codePointCount(0, externalReference.length)
            if (count !in 1..128) {
                errors += error(EXTERNAL_REFERENCE, "must contain between 1 and 128 characters")
            } else if (externalReference.codePoints().noneMatch { !Character.isWhitespace(it) }) {
                errors += error(EXTERNAL_REFERENCE, "must not be whitespace-only")
            }
        }
        if (amountMinor != null && amountMinor <= 0) {
            errors += error(AMOUNT_MINOR, "must be a positive integer")
        }
        if (currency != null && currency != "EUR") {
            errors += error(CURRENCY, "must be EUR")
        }
        if (transactionType != null && transactionType != "PAYMENT") {
            errors += error(TRANSACTION_TYPE, "must be PAYMENT")
        }
        if (paymentMethod != null && paymentMethod != "BANK_TRANSFER") {
            errors += error(PAYMENT_METHOD, "must be BANK_TRANSFER")
        }
        if (errors.isNotEmpty()) {
            throw RequestBodyProblemException(errors.sortedWith(compareBy({ it.pointer }, { it.detail })))
        }
        return CreateTransactionRequestDto(
            externalReference = requireNotNull(externalReference),
            amountMinor = requireNotNull(amountMinor),
            currency = requireNotNull(currency),
            transactionType = requireNotNull(transactionType),
            paymentMethod = requireNotNull(paymentMethod),
        )
    }

    private fun JsonNode.text(name: String, errors: MutableList<RequestBodyErrorDto>): String? {
        val value = get(name)
        if (value == null) {
            errors += error(name, "is required")
            return null
        }
        if (!value.isString) {
            errors += error(name, "must be a string")
            return null
        }
        return value.stringValue()
    }

    private fun JsonNode.long(name: String, errors: MutableList<RequestBodyErrorDto>): Long? {
        val value = get(name)
        if (value == null) {
            errors += error(name, "is required")
            return null
        }
        if (!value.isIntegralNumber || !value.canConvertToLong()) {
            errors += error(name, "must be a signed 64-bit integer")
            return null
        }
        return value.longValue()
    }

    private fun invalidBody(error: RequestBodyErrorDto): RequestBodyProblemException =
        RequestBodyProblemException(listOf(error))

    private fun error(field: String, detail: String): RequestBodyErrorDto =
        RequestBodyErrorDto(detail, "#/$field")

    private companion object {
        const val EXTERNAL_REFERENCE = "externalReference"
        const val AMOUNT_MINOR = "amountMinor"
        const val CURRENCY = "currency"
        const val TRANSACTION_TYPE = "transactionType"
        const val PAYMENT_METHOD = "paymentMethod"
        val EXPECTED_FIELDS = setOf(EXTERNAL_REFERENCE, AMOUNT_MINOR, CURRENCY, TRANSACTION_TYPE, PAYMENT_METHOD)
    }
}
