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
