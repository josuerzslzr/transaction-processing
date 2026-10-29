package com.josue.transactions.api

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

open class ApiProblemException(
    val problem: ApiProblem,
) : RuntimeException(problem.detail) {
    companion object {
        fun malformedJson() = ApiProblemException(
            ApiProblem("/problems/malformed-json", "Malformed JSON", 400, "The request body is not valid JSON."),
        )

        fun missingHeader(name: String) = ApiProblemException(
            ApiProblem(
                "/problems/missing-required-header",
                "Missing required header",
                400,
                "The $name header is required.",
            ),
        )

        fun invalidHeader(name: String) = ApiProblemException(
            ApiProblem(
                "/problems/invalid-header-value",
                "Invalid header value",
                400,
                "The $name header must be a valid UUIDv7.",
            ),
        )

        fun invalidTransactionId() = ApiProblemException(
            ApiProblem(
                "/problems/invalid-path-parameter",
                "Invalid path parameter",
                400,
                "The transactionId path parameter must be a valid UUIDv7.",
            ),
        )

        fun notFound() = ApiProblemException(
            ApiProblem("/problems/transaction-not-found", "Transaction not found", 404, "The requested transaction was not found."),
        )

        fun idempotencyReused() = ApiProblemException(
            ApiProblem(
                "/problems/idempotency-key-reused",
                "Idempotency key reused",
                422,
                "The idempotency key was previously used with different transaction data.",
            ),
        )

        fun unavailable() = ApiProblemException(
            ApiProblem(
                "/problems/service-unavailable",
                "Service unavailable",
                503,
                "The service is temporarily unable to process the request.",
            ),
        )

        fun internal() = ApiProblemException(
            ApiProblem("/problems/internal-error", "Internal error", 500, "An unexpected error occurred."),
        )
    }
}

class RequestBodyProblemException(errors: List<RequestBodyErrorDto>) : ApiProblemException(
    ApiProblem(
        type = "/problems/invalid-request-body",
        title = "Invalid request body",
        status = 400,
        detail = "One or more request fields are invalid.",
        errors = errors,
    ),
)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiProblemException::class)
    fun apiProblem(exception: ApiProblemException): ResponseEntity<ApiProblem> = response(exception.problem)

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun unsupportedMediaType(): ResponseEntity<ApiProblem> = response(
        ApiProblem(
            "/problems/unsupported-media-type",
            "Unsupported media type",
            415,
            "Content-Type must be application/json.",
        ),
    )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableMessage(): ResponseEntity<ApiProblem> = response(ApiProblemException.malformedJson().problem)

    @ExceptionHandler(Exception::class)
    fun unexpected(): ResponseEntity<ApiProblem> = response(ApiProblemException.internal().problem)

    private fun response(problem: ApiProblem): ResponseEntity<ApiProblem> = ResponseEntity
        .status(HttpStatus.valueOf(problem.status))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem)
}
