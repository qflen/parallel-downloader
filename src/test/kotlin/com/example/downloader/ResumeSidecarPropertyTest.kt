package com.example.downloader

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import net.jqwik.api.constraints.StringLength
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Property-based correctness checks for [ResumeSidecar] (parser and serializer).
 *
 * Three properties:
 *   1. **Round-trip stability:** `parse(serialize(state)) == state` for any valid state.
 *   2. **Malformed input never throws:** an arbitrary byte string yields `null` or a state
 *      (the parser is lenient on whitespace), never a thrown exception.
 *   3. **Version mismatch returns null:** a sidecar whose `version=` line is anything other
 *      than the supported `1` is treated as no usable state.
 *
 * Generators are bounded so the materialized sidecar text stays well under 50 KB even at
 * the upper end (1000 chunks × ~6 chars per index + framing).
 *
 * The `completedChunks` set is generated through an explicit [Arbitrary] provider rather
 * than `List<@IntRange Int>`, because jqwik's @IntRange annotation isn't reliably honored
 * on Kotlin generic type arguments - the generator falls back to unconstrained values that
 * collide with `Int` only at the JVM-erasure level, leading to ClassCastException-shaped
 * heisenbugs at runtime.
 */
class ResumeSidecarPropertyTest {

    // jqwik @ForAll has no built-in @TempDir; the JUnit one is per-method injection. A single
    // class-level temp dir is fine here - each property invocation creates its own file via
    // Files.createTempFile, and the directory is deleted at JVM exit.
    private val testTempDir: Path = Files.createTempDirectory("rs-prop-").also {
        it.toFile().deleteOnExit()
    }

    @Provide
    fun completedChunkSets(): Arbitrary<Set<Int>> =
        Arbitraries.integers().between(0, MAX_CHUNK_INDEX)
            .set()
            .ofMinSize(0)
            .ofMaxSize(MAX_COMPLETED_SIZE)

    @Property
    fun `serialize then parse round-trips any valid state`(
        @ForAll @LongRange(min = 1L, max = MAX_TOTAL) totalBytes: Long,
        @ForAll @LongRange(min = MIN_CHUNK, max = MAX_CHUNK) chunkSize: Long,
        @ForAll @StringLength(min = 0, max = MAX_VALIDATOR) validatorRaw: String,
        @ForAll @From("completedChunkSets") completed: Set<Int>,
    ) {
        // Sanitize the validator: the parser splits on '=' for key/value and trims whitespace,
        // so a validator with newline / '=' / leading-or-trailing whitespace would not
        // round-trip. Real-world ETag and Last-Modified values don't have those - strip them
        // here so the property tests the round-trip claim, not parser corner cases.
        val validator = validatorRaw
            .replace(Regex("[\n\r=]"), "")
            .trim()
            .takeIf { it.isNotEmpty() }

        val original = ResumeState(
            totalBytes = totalBytes,
            chunkSize = chunkSize,
            entityValidator = validator,
            completedChunks = completed,
        )

        val dest = Files.createTempFile(testTempDir, "rs-prop-", ".bin")
        ResumeSidecar.save(dest, original)
        val roundTripped = ResumeSidecar.load(dest)
        assertEquals(original, roundTripped, "round-trip lost or altered state")
    }

    @Property
    fun `arbitrary bytes never escape as exceptions from the parser`(
        @ForAll @StringLength(min = 0, max = MAX_GARBAGE) garbage: String,
    ) {
        val dest = Files.createTempFile(testTempDir, "rs-prop-garbage-", ".bin")
        ResumeSidecar.pathFor(dest).writeText(garbage)
        // The contract: a malformed sidecar yields null OR a state, never an exception. We
        // don't pin the value beyond not-throwing - a malformed input that happens to look
        // like a valid sidecar is allowed to parse, which is the parser being lenient.
        runCatching { ResumeSidecar.load(dest) }.getOrThrow()
    }

    @Property
    fun `version mismatch always returns null`(
        @ForAll @LongRange(min = 1L, max = MAX_TOTAL) totalBytes: Long,
        @ForAll @LongRange(min = MIN_CHUNK, max = MAX_CHUNK) chunkSize: Long,
        @ForAll @IntRange(min = 2, max = 100) badVersion: Int,
    ) {
        val dest = Files.createTempFile(testTempDir, "rs-prop-version-", ".bin")
        val text = buildString {
            appendLine("version=$badVersion")
            appendLine("total=$totalBytes")
            appendLine("chunkSize=$chunkSize")
            appendLine("validator=")
            appendLine("completed=")
        }
        ResumeSidecar.pathFor(dest).writeText(text)
        assertNull(ResumeSidecar.load(dest), "version $badVersion should be rejected")
    }

    private companion object {
        // Same ceiling as ChunkPlanPropertyTest - realistic file sizes, well under Long.MAX_VALUE.
        const val MAX_TOTAL: Long = 1_000_000_000L
        const val MIN_CHUNK: Long = 1L
        const val MAX_CHUNK: Long = 100_000_000L
        // Bound the completed-set size so the serialized text stays small. 1000 chunks at
        // ~6 chars per index plus comma framing keeps the file well under 10 KB.
        const val MAX_COMPLETED_SIZE: Int = 1000
        const val MAX_CHUNK_INDEX: Int = 100_000
        const val MAX_VALIDATOR: Int = 64
        const val MAX_GARBAGE: Int = 4096
    }
}
