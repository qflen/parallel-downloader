package com.example.downloader

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path

class AtomicReplaceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `successful atomic move replaces dst contents and removes src`() {
        val src = tempDir.resolve("src.bin")
        val dst = tempDir.resolve("dst.bin")
        val original = "fresh bytes".toByteArray()
        Files.write(dst, "stale bytes".toByteArray())
        Files.write(src, original)

        atomicReplace(src, dst)

        assertArrayEquals(original, Files.readAllBytes(dst))
        assertFalse(Files.exists(src), "src should be gone after move")
    }

    @Test
    fun `fallback branch runs when the atomic move signals AtomicMoveNotSupportedException`() {
        val src = tempDir.resolve("src.bin")
        val dst = tempDir.resolve("dst.bin")
        val original = "fallback bytes".toByteArray()
        Files.write(src, original)

        var fallbackCalled = false
        atomicReplace(
            src, dst,
            atomic = { _, _ -> throw AtomicMoveNotSupportedException(src.toString(), dst.toString(), "synthetic") },
            fallback = { s, d ->
                fallbackCalled = true
                Files.move(s, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            },
        )

        assertEquals(true, fallbackCalled, "fallback strategy must run on AtomicMoveNotSupported")
        assertArrayEquals(original, Files.readAllBytes(dst))
        assertFalse(Files.exists(src), "fallback also removes src after move")
    }

    @Test
    fun `non-atomic IOException from the atomic strategy is not swallowed`() {
        val src = tempDir.resolve("src.bin")
        val dst = tempDir.resolve("dst.bin")
        Files.write(src, ByteArray(0))

        val sentinel = java.io.IOException("permission denied")
        var fallbackCalled = false
        val thrown = runCatching {
            atomicReplace(
                src, dst,
                atomic = { _, _ -> throw sentinel },
                fallback = { _, _ -> fallbackCalled = true },
            )
        }.exceptionOrNull()
        assertEquals(sentinel, thrown, "non-atomic IOException must propagate, not trigger fallback")
        assertEquals(false, fallbackCalled, "fallback strategy must not run on a generic IOException")
    }
}
