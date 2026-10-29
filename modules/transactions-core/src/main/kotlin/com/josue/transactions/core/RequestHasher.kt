package com.josue.transactions.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class RequestHasher {
    fun idempotencyKeyHash(key: IdempotencyKey): String = sha256(key.raw)

    fun requestHash(data: TransactionData): String = sha256(
        buildString {
            append("{\"amountMinor\":")
            append(data.amountMinor)
            append(",\"currency\":\"")
            append(data.currency.name)
            append("\",\"externalReference\":")
            appendJsonString(data.externalReference)
            append(",\"paymentMethod\":\"")
            append(data.paymentMethod.name)
            append("\",\"transactionType\":\"")
            append(data.transactionType.name)
            append("\"}")
        },
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
