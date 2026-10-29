package com.josue.transactions.core

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TransactionDataTest {
    @Test
    fun `accepts the maximum signed amount and counts Unicode code points`() {
        val reference = "😀".repeat(128)

        assertThatCode {
            TransactionData(reference, Long.MAX_VALUE, Currency.EUR, TransactionType.PAYMENT, PaymentMethod.BANK_TRANSFER)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `rejects invalid amount and reference values`() {
        assertThatThrownBy { transactionData(amountMinor = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { transactionData(externalReference = " \t\n") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { transactionData(externalReference = "x".repeat(129)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun transactionData(
        externalReference: String = "ORDER-12345",
        amountMinor: Long = 4250,
    ) = TransactionData(externalReference, amountMinor, Currency.EUR, TransactionType.PAYMENT, PaymentMethod.BANK_TRANSFER)
}

