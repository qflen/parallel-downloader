package com.example.downloader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Focused tests for the resume sidecar's serialization round-trip and the malformed-input
 * paths a real on-disk file might present (corrupt version, missing fields, etc.).
 */
class ResumeSidecarTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `roundtrip preserves all fields`() {
        val dest = tempDir.resolve("file.bin")
        val state = ResumeState(
            totalBytes = 12_345L,
            chunkSize = 1024L,
            entityValidator = "\"v1-abc\"",
            completedChunks = setOf(2, 0, 5, 1),
        )
        ResumeSidecar.save(dest, state)
        val loaded = ResumeSidecar.load(dest)
        assertNotNull(loaded)
        assertEquals(state.totalBytes, loaded!!.totalBytes)
        assertEquals(state.chunkSize, loaded.chunkSize)
        assertEquals(state.entityValidator, loaded.entityValidator)
        assertEquals(state.completedChunks, loaded.completedChunks)
    }

    @Test
    fun `null entityValidator survives roundtrip`() {
        val dest = tempDir.resolve("file.bin")
        val state = ResumeState(100L, 50L, entityValidator = null, completedChunks = emptySet())
        ResumeSidecar.save(dest, state)
        val loaded = ResumeSidecar.load(dest)
        assertNotNull(loaded)
        assertNull(loaded!!.entityValidator)
        assertEquals(emptySet<Int>(), loaded.completedChunks)
    }

    @Test
    fun `load returns null when sidecar file does not exist`() {
        assertNull(ResumeSidecar.load(tempDir.resolve("missing.bin")))
    }

    @Test
    fun `load rejects sidecar with wrong version`() {
        val dest = tempDir.resolve("file.bin")
        Files.writeString(
            ResumeSidecar.pathFor(dest),
            "version=99\ntotal=100\nchunkSize=10\nvalidator=\ncompleted=\n",
        )
        assertNull(ResumeSidecar.load(dest))
    }

    @Test
    fun `load rejects sidecar with non-numeric total`() {
        val dest = tempDir.resolve("file.bin")
        Files.writeString(
            ResumeSidecar.pathFor(dest),
            "version=1\ntotal=not-a-number\nchunkSize=10\nvalidator=\ncompleted=\n",
        )
        assertNull(ResumeSidecar.load(dest))
    }

    @Test
    fun `load rejects sidecar missing chunkSize`() {
        val dest = tempDir.resolve("file.bin")
        Files.writeString(
            ResumeSidecar.pathFor(dest),
            "version=1\ntotal=100\nvalidator=\ncompleted=\n",
        )
        assertNull(ResumeSidecar.load(dest))
    }

    @Test
    fun `load tolerates a sidecar with a malformed completed-chunk entry by dropping it`() {
        val dest = tempDir.resolve("file.bin")
        Files.writeString(
            ResumeSidecar.pathFor(dest),
            "version=1\ntotal=100\nchunkSize=10\nvalidator=\ncompleted=0,not-a-number,2\n",
        )
        val loaded = ResumeSidecar.load(dest)
        assertNotNull(loaded)
        assertEquals(setOf(0, 2), loaded!!.completedChunks)
    }

    @Test
    fun `delete removes the sidecar file`() {
        val dest = tempDir.resolve("file.bin")
        ResumeSidecar.save(dest, ResumeState(10L, 5L, null, emptySet()))
        assertNotNull(ResumeSidecar.load(dest))
        ResumeSidecar.delete(dest)
        assertFalse(Files.exists(ResumeSidecar.pathFor(dest)))
    }

    @Test
    fun `save with no existing sidecar leaves no temp file behind`() {
        val dest = tempDir.resolve("file.bin")
        val state = ResumeState(1024L, 256L, "\"v\"", setOf(0, 1))
        ResumeSidecar.save(dest, state)
        val tmp = ResumeSidecar.pathFor(dest).resolveSibling("${ResumeSidecar.pathFor(dest).fileName}.tmp")
        assertFalse(Files.exists(tmp), "atomic move should consume the staging file")
    }

    @Test
    fun `failed save leaves the existing sidecar byte-identical to its pre-call state`() {
        // Stage a known-good sidecar, then trigger a save that cannot complete: pre-create
        // a directory at the sibling temp path so Files.writeString fails before any rename
        // happens. The live sidecar must remain exactly as it was.
        val dest = tempDir.resolve("file.bin")
        val original = ResumeState(2048L, 512L, "\"original\"", setOf(0, 1, 2))
        ResumeSidecar.save(dest, original)
        val live = ResumeSidecar.pathFor(dest)
        val originalBytes = Files.readAllBytes(live)

        // Pre-create a directory at the staging path. Files.writeString cannot write to a
        // directory; the resulting IOException propagates out of save() before atomicReplace
        // is reached.
        val staging = live.resolveSibling("${live.fileName}.tmp")
        Files.createDirectory(staging)

        val rotten = ResumeState(2048L, 512L, "\"rotten\"", setOf(0, 1, 2, 3))
        runCatching { ResumeSidecar.save(dest, rotten) }
        // The live sidecar must be byte-identical: the failed save did not corrupt it.
        org.junit.jupiter.api.Assertions.assertArrayEquals(
            originalBytes, Files.readAllBytes(live),
            "torn-write resistance: failed save must not damage the existing sidecar",
        )
    }

    @Test
    fun `tracker recordChunkComplete persists incrementally and delete drops the sidecar`() {
        val dest = tempDir.resolve("file.bin")
        val tracker = ResumeTracker(
            destination = dest,
            totalBytes = 1024L,
            chunkSize = 256L,
            entityValidator = "\"v\"",
            initialCompleted = emptySet(),
        )
        assertEquals(emptySet<Int>(), tracker.completedChunks())
        tracker.recordChunkComplete(0)
        tracker.recordChunkComplete(2)
        assertEquals(setOf(0, 2), ResumeSidecar.load(dest)?.completedChunks)
        tracker.delete()
        assertNull(ResumeSidecar.load(dest))
    }
}
