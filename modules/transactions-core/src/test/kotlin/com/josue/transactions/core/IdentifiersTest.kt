package com.josue.transactions.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IdentifiersTest {
    @Test
    fun `accepts canonical UUIDv7 values and canonicalizes identifiers`() {
        val raw = "01A067A6-5E3A-7A3B-8C4D-123456789ABC"

        assertThat(ClientId.parse(raw).toString()).isEqualTo(raw.lowercase())
        assertThat(TransactionId.parse(raw).toString()).isEqualTo(raw.lowercase())
        assertThat(IdempotencyKey.parse(raw)?.raw).isEqualTo(raw)
    }

    @Test
    fun `rejects non-v7 and non-canonical UUID text`() {
        assertThat(ClientId.parse("550e8400-e29b-41d4-a716-446655440000")).isNull()
        assertThat(ClientId.parse("1-1-7000-8000-1")).isNull()
        assertThat(ClientId.parse("not-a-uuid")).isNull()
    }

    @Test
    fun `system generator creates RFC 9562 UUIDv7 values`() {
        val generated = SystemUuidV7Generator().generate()

        assertThat(generated.version()).isEqualTo(7)
        assertThat(generated.variant()).isEqualTo(2)
    }
}
