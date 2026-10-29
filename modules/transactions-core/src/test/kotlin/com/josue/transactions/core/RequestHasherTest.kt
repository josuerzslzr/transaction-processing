package com.josue.transactions.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequestHasherTest {
    private val hasher = RequestHasher()

    @Test
    fun `matches the approved request fingerprint`() {
        val data = TransactionData("ORDER-12345", 4250, Currency.EUR, TransactionType.PAYMENT, PaymentMethod.BANK_TRANSFER)

        assertThat(hasher.requestHash(data))
            .isEqualTo("12760cbd7383392c942b05e836a9770f4f8708226b00a0f57591645670a3ac62")
    }

    @Test
    fun `matches the approved raw idempotency key hash`() {
        val key = requireNotNull(IdempotencyKey.parse("01a067a6-5e3d-7a3b-8c4d-123456789abc"))

        assertThat(hasher.idempotencyKeyHash(key))
            .isEqualTo("c4b2f6868fa702b34a4b502cefd0d50384768e7a6645a46b5c2ce7dd57599695")
    }

    @Test
    fun `escapes JSON deterministically and preserves Unicode`() {
        val first = TransactionData("\"slash\\line\n😀", 1, Currency.EUR, TransactionType.PAYMENT, PaymentMethod.BANK_TRANSFER)
        val second = TransactionData("\"slash\\line\n😀", 1, Currency.EUR, TransactionType.PAYMENT, PaymentMethod.BANK_TRANSFER)

        assertThat(hasher.requestHash(first)).isEqualTo(hasher.requestHash(second))
        assertThat(hasher.requestHash(first)).hasSize(64)
    }

    @Test
    fun `hashes idempotency key text without case normalization`() {
        val lower = requireNotNull(IdempotencyKey.parse("01a067a6-5e3d-7a3b-8c4d-123456789abc"))
        val upper = requireNotNull(IdempotencyKey.parse(lower.raw.uppercase()))

        assertThat(hasher.idempotencyKeyHash(lower)).isNotEqualTo(hasher.idempotencyKeyHash(upper))
    }
}
