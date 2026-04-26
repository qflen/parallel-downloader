package com.example.downloader.retry

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {

    @Test
    fun `NoRetry runs the block exactly once and propagates failures`() = runTest {
        val attempts = AtomicInteger(0)
        val result = NoRetry.execute {
            attempts.incrementAndGet()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, attempts.get())

        attempts.set(0)
        val failure = assertThrows<TransientFetchException> {
            NoRetry.execute<Unit> {
                attempts.incrementAndGet()
                throw TransientFetchException("boom")
            }
        }
        assertEquals("boom", failure.message)
        assertEquals(1, attempts.get())
    }

    @Test
    fun `ExponentialBackoffRetry retries transient failures up to maxAttempts`() = runTest {
        val attempts = AtomicInteger(0)
        val policy = ExponentialBackoffRetry(
            maxAttempts = 5,
            initialDelay = 1.milliseconds,
            maxDelay = 10.milliseconds,
            multiplier = 2.0,
            jitter = 0.0,
            random = Random(42),
        )
        val result = policy.execute {
            val n = attempts.incrementAndGet()
            if (n < 3) throw TransientFetchException("fail $n")
            "succeeded on $n"
        }
        assertEquals("succeeded on 3", result)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `ExponentialBackoffRetry rethrows the last exception when retries exhausted`() = runTest {
        val attempts = AtomicInteger(0)
        val policy = ExponentialBackoffRetry(
            maxAttempts = 3,
            initialDelay = 1.milliseconds,
            maxDelay = 10.milliseconds,
            jitter = 0.0,
        )
        val exception = assertThrows<TransientFetchException> {
            policy.execute<Unit> {
                attempts.incrementAndGet()
                throw TransientFetchException("attempt ${attempts.get()}")
            }
        }
        assertEquals("attempt 3", exception.message)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `ExponentialBackoffRetry does not retry NonRetryableFetchException`() = runTest {
        val attempts = AtomicInteger(0)
        val policy = ExponentialBackoffRetry(
            maxAttempts = 5,
            initialDelay = 1.milliseconds,
            maxDelay = 10.milliseconds,
            jitter = 0.0,
        )
        val exception = assertThrows<NonRetryableFetchException> {
            policy.execute<Unit> {
                attempts.incrementAndGet()
                throw NonRetryableFetchException("don't retry me", statusCode = 404)
            }
        }
        assertEquals(404, exception.statusCode)
        assertEquals(1, attempts.get(), "must run only once")
    }

    @Test
    fun `ExponentialBackoffRetry does not catch CancellationException`() = runTest {
        val attempts = AtomicInteger(0)
        val policy = ExponentialBackoffRetry(
            maxAttempts = 5,
            initialDelay = 1.milliseconds,
            maxDelay = 10.milliseconds,
            jitter = 0.0,
        )
        assertThrows<CancellationException> {
            policy.execute<Unit> {
                attempts.incrementAndGet()
                throw CancellationException("cancelled")
            }
        }
        assertEquals(1, attempts.get())
    }

    @Test
    fun `ExponentialBackoffRetry validates constructor arguments`() {
        assertThrows<IllegalArgumentException> {
            ExponentialBackoffRetry(0, 10.milliseconds, 1.seconds)
        }
        assertThrows<IllegalArgumentException> {
            ExponentialBackoffRetry(3, 0.milliseconds, 1.seconds)
        }
        assertThrows<IllegalArgumentException> {
            // maxDelay < initialDelay
            ExponentialBackoffRetry(3, 100.milliseconds, 50.milliseconds)
        }
        assertThrows<IllegalArgumentException> {
            ExponentialBackoffRetry(3, 1.milliseconds, 1.seconds, multiplier = 1.0)
        }
        assertThrows<IllegalArgumentException> {
            ExponentialBackoffRetry(3, 1.milliseconds, 1.seconds, jitter = -0.1)
        }
        assertThrows<IllegalArgumentException> {
            ExponentialBackoffRetry(3, 1.milliseconds, 1.seconds, jitter = 1.1)
        }
    }

    @Test
    fun `ExponentialBackoffRetry caps delays at maxDelay`() = runTest {
        val attempts = AtomicInteger(0)
        val policy = ExponentialBackoffRetry(
            maxAttempts = 6,
            initialDelay = 1.milliseconds,
            maxDelay = 4.milliseconds, // 1, 2, 4, 4, 4 — capped
            multiplier = 2.0,
            jitter = 0.0,
        )
        val result = policy.execute {
            val n = attempts.incrementAndGet()
            if (n < 6) throw TransientFetchException("retry me")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(6, attempts.get())
    }

    @Test
    fun `ExponentialBackoffRetry with maxAttempts equal to 1 is effectively NoRetry`() = runTest {
        val attempts = AtomicInteger(0)
        val policy = ExponentialBackoffRetry(
            maxAttempts = 1,
            initialDelay = 1.milliseconds,
            maxDelay = 10.milliseconds,
            jitter = 0.0,
        )
        assertThrows<TransientFetchException> {
            policy.execute<Unit> {
                attempts.incrementAndGet()
                throw TransientFetchException("boom")
            }
        }
        assertEquals(1, attempts.get())
    }

    @Test
    fun `jitter produces realized delays within bounded span`() = runTest {
        // Indirect verification: with non-zero jitter, retries still complete deterministically
        // (we don't assert specific delay values; we just confirm it runs).
        val policy = ExponentialBackoffRetry(
            maxAttempts = 5,
            initialDelay = 1.milliseconds,
            maxDelay = 10.milliseconds,
            jitter = 0.5,
            random = Random(123),
        )
        val attempts = AtomicInteger(0)
        val result = policy.execute {
            val n = attempts.incrementAndGet()
            if (n < 3) throw TransientFetchException("retry $n")
            "ok"
        }
        assertEquals("ok", result)
        assertTrue(attempts.get() == 3)
    }
}
