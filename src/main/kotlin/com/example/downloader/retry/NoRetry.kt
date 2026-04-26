package com.example.downloader.retry

/** Strategy: no retries — used in tests and when the caller wants to handle failures themselves. */
object NoRetry : RetryPolicy {
    override suspend fun <T> execute(block: suspend () -> T): T = block()
}
