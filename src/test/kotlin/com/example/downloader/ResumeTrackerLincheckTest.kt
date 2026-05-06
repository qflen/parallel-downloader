package com.example.downloader

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.Test

/**
 * Lincheck verification for [ResumeTracker]'s in-memory completed-chunk set. The orchestrator
 * fires [ResumeTracker.recordChunkComplete] from each chunk's coroutine; if those updates
 * raced (lost an index, observed a stale set), a future resume could refetch a chunk that
 * already finished or, worse, skip a chunk that didn't.
 *
 * The invariant the verifier holds: under any concurrent interleaving of `recordChunkComplete`
 * and `completedChunks`, every observation of the set must equal the union of completions
 * recorded before some serialization point - i.e., a valid prefix of some sequential
 * reordering of the calls. The default Lincheck linearizability verifier checks this against
 * an auto-generated sequential specification (the same class run on a single thread).
 *
 * Both StressOptions and ModelCheckingOptions are exercised. [ResumePersister.NO_OP] is
 * substituted for the file-backed persister so the test exercises only the AtomicReference +
 * writeLock pair the class uses for in-memory concurrency control; sidecar serialization
 * correctness is covered by `ResumeSidecarTest` and `ResumeSidecarPropertyTest`.
 */
@Param(name = "chunk", gen = IntGen::class, conf = "0:7")
class ResumeTrackerLincheckTest {

    private val tracker = ResumeTracker(
        totalBytes = TOTAL_BYTES,
        chunkSize = CHUNK_SIZE,
        entityValidator = null,
        initialCompleted = emptySet(),
        persister = ResumePersister.NO_OP,
    )

    @Operation
    fun recordChunkComplete(@Param(name = "chunk") chunk: Int) {
        tracker.recordChunkComplete(chunk)
    }

    @Operation
    fun completedChunks(): Set<Int> = tracker.completedChunks()

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
        const val TOTAL_BYTES = 64L
        const val CHUNK_SIZE = 8L
        const val STRESS_ITERATIONS = 50
        const val STRESS_INVOCATIONS = 500
        const val MODEL_CHECKING_ITERATIONS = 30
        const val MODEL_CHECKING_INVOCATIONS = 500
        const val THREADS = 3
        const val ACTORS_PER_THREAD = 2
    }
}
