package com.example.downloader

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.Test

/**
 * Lincheck verification for [RateLimiter]. Drives the limiter through its locked reservation
 * step ([RateLimiter.reserveWaitNanos]) and the cursor read ([RateLimiter.earliestNextNanos]),
 * which together carry every byte of state the leaky-bucket spec defines. The public
 * `acquire(bytes)` is a thin wrapper that calls `reserveWaitNanos` then `delay()` outside the
 * lock; testing the locked step directly keeps the verification independent of real-time
 * sleep behavior, which neither the stress nor the model-checking strategy can usefully
 * timebox.
 *
 * The leaky-bucket invariant the verifier holds: under any concurrent interleaving,
 *
 * 1. The cursor is monotonically non-decreasing.
 * 2. After a sequence of `reserveWaitNanos(b_i)` calls, the cursor has advanced by
 *    `sum(b_i) * NANOS_PER_SECOND / bytesPerSecond` (modulo a startup transient where
 *    `nanoClock()` outpaces the cursor).
 * 3. Every `cursorNanos()` observation must be the cursor value at the end of some prefix
 *    of a valid sequential reordering of the calls. (This is linearizability against the
 *    leaky-bucket spec.)
 *
 * The test pins `nanoClock` to `{ 0L }` so reservations are deterministic: each
 * `reserveWaitNanos(b)` advances the cursor by exactly `b * NANOS_PER_SECOND / bytesPerSecond`
 * nanoseconds. With `bytesPerSecond = 1e9`, that is one nanosecond per byte; over the bounded
 * actors in any scenario, the cursor stays well under one millisecond.
 *
 * Both StressOptions (random concurrent invocations) and ModelCheckingOptions (systematic
 * exploration of suspension-point interleavings) are exercised. ModelCheckingOptions is the
 * one that catches `limitedParallelism`-shaped bugs - where a primitive that looks correct
 * fails specifically when one coroutine suspends mid-region - earlier than benchmarks would.
 */
@Param(name = "bytes", gen = IntGen::class, conf = "1:100")
class RateLimiterLincheckTest {

    private val limiter = RateLimiter(
        bytesPerSecond = NANOS_PER_SECOND,
        nanoClock = { 0L },
    )

    @Operation
    suspend fun reserveWaitNanos(@Param(name = "bytes") bytes: Int): Long =
        limiter.reserveWaitNanos(bytes)

    @Operation
    fun cursorNanos(): Long = limiter.earliestNextNanos

    @Test
    fun stressTest() {
        StressOptions()
            .iterations(STRESS_ITERATIONS)
            .invocationsPerIteration(STRESS_INVOCATIONS)
            .threads(THREADS)
            .actorsPerThread(ACTORS_PER_THREAD)
            .check(this::class)
    }

    @Test
    fun modelCheckingTest() {
        ModelCheckingOptions()
            .iterations(MODEL_CHECKING_ITERATIONS)
            .invocationsPerIteration(MODEL_CHECKING_INVOCATIONS)
            .threads(THREADS)
            .actorsPerThread(ACTORS_PER_THREAD)
            .check(this::class)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val STRESS_ITERATIONS = 50
        const val STRESS_INVOCATIONS = 500
        const val MODEL_CHECKING_ITERATIONS = 30
        const val MODEL_CHECKING_INVOCATIONS = 500
        const val THREADS = 3
        const val ACTORS_PER_THREAD = 2
    }
}
