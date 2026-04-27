package com.example.downloader

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

class CliRateLimitTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var origErr: PrintStream
    private lateinit var capturedErr: ByteArrayOutputStream

    @BeforeEach
    fun captureStderr() {
        origErr = System.err
        capturedErr = ByteArrayOutputStream()
        // Force UTF-8 explicitly: on Windows the platform default is CP-1252, which mangles
        // any non-ASCII byte the CLI prints (✓ / ✗) into '?' before our assertions see it.
        System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8))
    }

    @AfterEach
    fun restoreStderr() {
        System.setErr(origErr)
    }

    @Test
    fun `bad rate-limit format exits 64 with usage error`() {
        // No suffix, no number - just a name.
        val rc = runCli(arrayOf(
            "--rate-limit", "hello",
            "http://x/y", tempDir.resolve("z").toString(),
        ))
        assertEquals(64, rc, "expected usage exit, got $rc; stderr=${capturedErr.toString(Charsets.UTF_8)}")
        assertTrue("--rate-limit" in capturedErr.toString(Charsets.UTF_8), "expected rate-limit error message")
    }

    @Test
    fun `rate-limit zero exits 64`() {
        val rc = runCli(arrayOf(
            "--rate-limit", "0",
            "http://x/y", tempDir.resolve("z").toString(),
        ))
        assertEquals(64, rc, "expected usage exit, got $rc; stderr=${capturedErr.toString(Charsets.UTF_8)}")
    }

    @Test
    fun `rate-limit accepts MB per second suffix`() {
        // Won't actually download because URL is bogus; we just want to confirm parsing
        // succeeded - which surfaces as a non-USAGE exit code.
        val rc = runCli(arrayOf(
            "--rate-limit", "1MiB/s",
            "http://127.0.0.1:1/never-binds", tempDir.resolve("z").toString(),
        ))
        // Anything but 64 means parsing got past the rate-limit step.
        assertTrue(rc != 64, "rate-limit format should parse cleanly; stderr=${capturedErr.toString(Charsets.UTF_8)}")
    }

    @Test
    fun `rate-limit accepts plain integer bytes per second`() {
        val rc = runCli(arrayOf(
            "--rate-limit", "1024",
            "http://127.0.0.1:1/never-binds", tempDir.resolve("z").toString(),
        ))
        assertTrue(rc != 64, "plain bytes/s should parse; stderr=${capturedErr.toString(Charsets.UTF_8)}")
    }
}
