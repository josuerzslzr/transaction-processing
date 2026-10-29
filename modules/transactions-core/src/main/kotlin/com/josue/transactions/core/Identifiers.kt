package com.josue.transactions.core

import com.github.f4b6a3.uuid.UuidCreator
import java.util.UUID

private val UUID_TEXT = Regex(
    "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-7[0-9A-Fa-f]{3}-[89ABab][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}$",
)

private fun parseUuidV7(raw: String): UUID? =
    if (UUID_TEXT.matches(raw)) UUID.fromString(raw).takeIf { it.version() == 7 && it.variant() == 2 } else null

@JvmInline
value class ClientId private constructor(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun parse(raw: String): ClientId? = parseUuidV7(raw)?.let(::ClientId)
        fun of(value: UUID): ClientId = requireUuidV7(value).let(::ClientId)
    }
}

@JvmInline
value class TransactionId private constructor(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun parse(raw: String): TransactionId? = parseUuidV7(raw)?.let(::TransactionId)
        fun of(value: UUID): TransactionId = requireUuidV7(value).let(::TransactionId)
    }
}

@JvmInline
value class IdempotencyKey private constructor(val raw: String) {
    companion object {
        fun parse(raw: String): IdempotencyKey? = parseUuidV7(raw)?.let { IdempotencyKey(raw) }
    }
}

private fun requireUuidV7(value: UUID): UUID {
    require(value.version() == 7 && value.variant() == 2) { "Identifier must be an RFC 9562 UUIDv7" }
    return value
}

fun interface UuidGenerator {
    fun generate(): UUID
}

class SystemUuidV7Generator : UuidGenerator {
    override fun generate(): UUID = UuidCreator.getTimeOrderedEpoch()
}
