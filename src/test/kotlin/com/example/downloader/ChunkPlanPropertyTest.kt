package com.example.downloader

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.LongRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based correctness checks for [planChunks]. jqwik feeds the planner 1000 random
 * `(totalBytes, chunkSize)` pairs per property by default; the named scenarios in
 * [ChunkPlanTest] stay as regression tests with readable failure messages.
 *
 * Generators are bounded so the resulting plan stays small enough not to OOM the test JVM.
 * The lower bound on `chunkSize` is 1 KiB rather than 1 - the `chunkSize=1` regression case
 * is covered explicitly in [ChunkPlanTest], and lifting the floor here keeps the worst-case
 * chunk count to ~1M (a 1 GiB file at 1 KiB chunks) instead of 1 B chunks. Chunk math is the
 * bug-prone bit, so these property tests exercise the algebraic invariants across many random
 * inputs while the named tests still pin down the human-meaningful boundaries.
 */
class ChunkPlanPropertyTest {

    @Property
    fun `first chunk starts at 0 when the plan is non-empty`(
        @ForAll @LongRange(min = 1, max = MAX_TOTAL) totalBytes: Long,
        @ForAll @LongRange(min = MIN_CHUNK, max = MAX_CHUNK) chunkSize: Long,
    ) {
        val plan = planChunks(totalBytes, chunkSize)
        assertEquals(0L, plan.first().start)
    }

    @Property
    fun `last chunk ends at totalBytes minus 1 when the plan is non-empty`(
        @ForAll @LongRange(min = 1, max = MAX_TOTAL) totalBytes: Long,
        @ForAll @LongRange(min = MIN_CHUNK, max = MAX_CHUNK) chunkSize: Long,
    ) {
        val plan = planChunks(totalBytes, chunkSize)
        assertEquals(totalBytes - 1, plan.last().endInclusive)
    }

    @Property
    fun `every adjacent chunk pair is contiguous - no gaps and no overlaps`(
        @ForAll @LongRange(min = 0, max = MAX_TOTAL) totalBytes: Long,
        @ForAll @LongRange(min = MIN_CHUNK, max = MAX_CHUNK) chunkSize: Long,
    ) {
        val plan = planChunks(totalBytes, chunkSize)
        for (i in 1 until plan.size) {
            assertEquals(
                plan[i - 1].endInclusive + 1,
                plan[i].start,
                "gap or overlap at chunk index $i",
            )
        }
    }

    @Property
    fun `chunk lengths sum to totalBytes - the plan covers exactly the file`(
        @ForAll @LongRange(min = 0, max = MAX_TOTAL) totalBytes: Long,
        @ForAll @LongRange(min = MIN_CHUNK, max = MAX_CHUNK) chunkSize: Long,
    ) {
        val plan = planChunks(totalBytes, chunkSize)
        assertEquals(totalBytes, plan.sumOf { it.length })
    }

    @Property
    fun `chunk count equals ceil(totalBytes div chunkSize)`(
        @ForAll @LongRange(min = 0, max = MAX_TOTAL) totalBytes: Long,
        @ForAll @LongRange(min = MIN_CHUNK, max = MAX_CHUNK) chunkSize: Long,
    ) {
        val plan = planChunks(totalBytes, chunkSize)
        // Integer-math ceiling: avoids any floating-point rounding surprises.
        val expectedCount = if (totalBytes == 0L) 0L else (totalBytes + chunkSize - 1) / chunkSize
        assertEquals(expectedCount, plan.size.toLong())
    }

    @Property
    fun `every non-final chunk is exactly chunkSize and the final one is at most chunkSize`(
        @ForAll @LongRange(min = 1, max = MAX_TOTAL) totalBytes: Long,
        @ForAll @LongRange(min = MIN_CHUNK, max = MAX_CHUNK) chunkSize: Long,
    ) {
        val plan = planChunks(totalBytes, chunkSize)
        for (i in 0 until plan.size - 1) {
            assertEquals(chunkSize, plan[i].length, "non-final chunk $i should be full")
        }
        assertTrue(
            plan.last().length in 1L..chunkSize,
            "last chunk length ${plan.last().length} not in 1..$chunkSize",
        )
    }

    @Property
    fun `zero totalBytes always yields an empty plan`(
        @ForAll @LongRange(min = MIN_CHUNK, max = MAX_CHUNK) chunkSize: Long,
    ) {
        assertTrue(planChunks(0L, chunkSize).isEmpty())
    }

    @Property
    fun `totalBytes less than chunkSize yields exactly one chunk covering 0 to totalBytes minus 1`(
        // Disjoint ranges guarantee the precondition without discarding samples - jqwik's
        // generators draw the two values independently.
        @ForAll @LongRange(min = 1, max = MIN_CHUNK - 1) totalBytes: Long,
        @ForAll @LongRange(min = MIN_CHUNK, max = MAX_CHUNK) chunkSize: Long,
    ) {
        val plan = planChunks(totalBytes, chunkSize)
        assertEquals(1, plan.size)
        assertEquals(0L, plan[0].start)
        assertEquals(totalBytes - 1, plan[0].endInclusive)
    }

    private companion object {
        const val MAX_TOTAL: Long = 1_000_000_000L
        const val MIN_CHUNK: Long = 1024L
        const val MAX_CHUNK: Long = 100_000_000L
    }
}
