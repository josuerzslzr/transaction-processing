package com.josue.transactions.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TransactionProcessingApplication

fun main(args: Array<String>) {
    runApplication<TransactionProcessingApplication>(*args)
}
