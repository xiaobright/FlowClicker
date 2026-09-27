package com.flowclicker.app.ai

import java.io.IOException

class AiHttpException(val status: Int, message: String) : IOException(message)
object RetryPolicy {
    fun retryable(error: Throwable): Boolean = when (error) {
        is AiHttpException -> error.status in setOf(408, 429, 500, 502, 503, 504)
        is IOException -> true
        else -> false
    }
}
