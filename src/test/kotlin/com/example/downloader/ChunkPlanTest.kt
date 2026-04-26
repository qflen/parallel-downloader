package com.example.downloader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Pure tests for chunk math — no server, no I/O. Chunk math is the bug-prone bit, so it gets
 * exhaustive coverage including the size-matrix boundary conditions called out in the spec.
 */
class ChunkPlanTest {

    @Test
    fun `zero-byte file produces empty plan`() {
        assertEquals(emptyList<Chunk>(), planChunks(totalBytes = 0L, chunkSize = 1024L))
    }

    @Test
    fun `one-byte file produces single chunk of length 1`() {
        val plan = planChunks(totalBytes = 1L, chunkSize = 1024L)
        assertEquals(1, plan.size)
        assertEquals(Chunk(index = 0, start = 0L, endInclusive = 0L), plan[0])
        assertEquals(1L, plan[0].length)
    }

    @Test
    fun `file exactly equal to chunkSize produces one full chunk`() {
        val plan = planChunks(totalBytes = 1024L, chunkSize = 1024L)
        assertEquals(listOf(Chunk(0, 0L, 1023L)), plan)
        assertEquals(1024L, plan[0].length)
    }

    @Test
    fun `file equal to chunkSize minus 1 produces one short chunk`() {
        val plan = planChunks(totalBytes = 1023L, chunkSize = 1024L)
        assertEquals(listOf(Chunk(0, 0L, 1022L)), plan)
        assertEquals(1023L, plan[0].length)
    }

    @Test
    fun `file equal to chunkSize plus 1 produces two chunks (full plus 1 byte)`() {
        val plan = planChunks(totalBytes = 1025L, chunkSize = 1024L)
        assertEquals(2, plan.size)
        assertEquals(Chunk(0, 0L, 1023L), plan[0])
        assertEquals(Chunk(1, 1024L, 1024L), plan[1])
        assertEquals(1L, plan[1].length)
    }

    @Test
    fun `file equal to N times chunkSize produces N full chunks`() {
        val plan = planChunks(totalBytes = 4L * 1024L, chunkSize = 1024L)
        assertEquals(4, plan.size)
        plan.forEachIndexed { i, c ->
            assertEquals(i, c.index)
            assertEquals(i * 1024L, c.start)
            assertEquals(i * 1024L + 1023L, c.endInclusive)
            assertEquals(1024L, c.length)
        }
    }

    @Test
    fun `file equal to N times chunkSize plus 1 has one trailing 1-byte chunk`() {
        val plan = planChunks(totalBytes = 3L * 1024L + 1L, chunkSize = 1024L)
        assertEquals(4, plan.size)
        assertEquals(1L, plan.last().length)
        assertEquals(3L * 1024L, plan.last().start)
        assertEquals(3L * 1024L, plan.last().endInclusive)
    }

    @Test
    fun `multi-chunk normal case (50 MiB at 8 MiB chunks)`() {
        val totalBytes = 50L * 1024L * 1024L
        val chunkSize = 8L * 1024L * 1024L
        val plan = planChunks(totalBytes, chunkSize)
        assertEquals(7, plan.size) // ceil(50/8) = 7
        for (i in 0 until 6) {
            assertEquals(chunkSize, plan[i].length, "chunk $i should be full")
        }
        assertEquals(2L * 1024L * 1024L, plan.last().length, "last chunk should be 2 MiB")
    }

    @Test
    fun `odd chunkSize with non-multiple total (10 bytes, chunkSize 3) produces 4 chunks`() {
        val plan = planChunks(totalBytes = 10L, chunkSize = 3L)
        assertEquals(
            listOf(
                Chunk(0, 0L, 2L),
                Chunk(1, 3L, 5L),
                Chunk(2, 6L, 8L),
                Chunk(3, 9L, 9L),
            ),
            plan,
        )
    }

    @Test
    fun `chunkSize of 1 on small file produces totalBytes chunks of length 1`() {
        val plan = planChunks(totalBytes = 5L, chunkSize = 1L)
        assertEquals(5, plan.size)
        plan.forEachIndexed { i, c ->
            assertEquals(i.toLong(), c.start)
            assertEquals(i.toLong(), c.endInclusive)
            assertEquals(1L, c.length)
        }
    }

    @Test
    fun `negative totalBytes throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { planChunks(totalBytes = -1L, chunkSize = 1024L) }
    }

    @Test
    fun `zero chunkSize throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { planChunks(totalBytes = 100L, chunkSize = 0L) }
    }

    @Test
    fun `negative chunkSize throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { planChunks(totalBytes = 100L, chunkSize = -8L) }
    }

    @Test
    fun `Chunk constructor rejects negative start`() {
        assertThrows<IllegalArgumentException> { Chunk(0, -1L, 10L) }
    }

    @Test
    fun `Chunk constructor rejects endInclusive less than start`() {
        assertThrows<IllegalArgumentException> { Chunk(0, 10L, 5L) }
    }

    @Test
    fun `Chunk of length 1 (start equals endInclusive) is allowed`() {
        val c = Chunk(0, 5L, 5L)
        assertEquals(1L, c.length)
    }

    @ParameterizedTest(name = "[{index}] totalBytes={0} chunkSize={1}")
    @MethodSource("planInvariantCases")
    fun `chunks are contiguous, gap-free, fully cover the file, and have ascending indices`(
        totalBytes: Long,
        chunkSize: Long,
    ) {
        val plan = planChunks(totalBytes, chunkSize)
        if (totalBytes == 0L) {
            assertTrue(plan.isEmpty())
            return
        }
        // Indices are 0..N-1
        plan.forEachIndexed { i, c -> assertEquals(i, c.index) }
        // First chunk starts at 0
        assertEquals(0L, plan.first().start)
        // Last chunk ends at totalBytes - 1
        assertEquals(totalBytes - 1, plan.last().endInclusive)
        // Sum of lengths equals totalBytes
        assertEquals(totalBytes, plan.sumOf { it.length })
        // No gaps and no overlaps: each chunk starts immediately after the previous one ends
        for (i in 1 until plan.size) {
            assertEquals(plan[i - 1].endInclusive + 1, plan[i].start, "gap or overlap at chunk $i")
        }
        // No chunk exceeds chunkSize
        plan.forEach { assertTrue(it.length <= chunkSize, "chunk ${it.index} exceeded chunkSize") }
        // All chunks except possibly the last are exactly chunkSize
        for (i in 0 until plan.size - 1) {
            assertEquals(chunkSize, plan[i].length, "non-final chunk $i should be full")
        }
    }

    companion object {
        @JvmStatic
        fun planInvariantCases(): List<Arguments> = listOf(
            Arguments.of(1L, 1L),
            Arguments.of(1L, 1024L),
            Arguments.of(1023L, 1024L),
            Arguments.of(1024L, 1024L),
            Arguments.of(1025L, 1024L),
            Arguments.of(2048L, 1024L),
            Arguments.of(2049L, 1024L),
            Arguments.of(10L, 3L),
            Arguments.of(99L, 7L),
            Arguments.of(100L, 7L),
            Arguments.of(50L * 1024L * 1024L, 8L * 1024L * 1024L),
            Arguments.of(1L * 1024L * 1024L * 1024L, 8L * 1024L * 1024L), // 1 GiB at 8 MiB
            Arguments.of(0L, 1024L),
            Arguments.of(7L, 1L),
        )
    }
}
