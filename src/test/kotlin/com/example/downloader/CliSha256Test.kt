package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.TestHttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class CliSha256Test {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var origErr: PrintStream
    private lateinit var capturedErr: ByteArrayOutputStream

    @BeforeEach
    fun captureStderr() {
        origErr = System.err
        capturedErr = ByteArrayOutputStream()
        // Force UTF-8 explicitly: on Windows the platform default is CP-1252, which lacks
        // ✓ / ✗ and turns them into '?' before our assertions ever see them.
        System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8))
    }

    @AfterEach
    fun restoreStderr() {
        System.setErr(origErr)
    }

    @Test
    fun `matching sha256 exits 0 and prints checkmark line`() {
        val payload = Bytes.deterministic(2048, seed = 41)
        val expectedSha = Bytes.sha256(payload)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val dest = tempDir.resolve("out.bin")
            val rc = runCli(arrayOf(
                "--chunk-size", "1024",
                "--sha256", expectedSha,
                server.url("/file.bin").toString(),
                dest.toString(),
            ))
            assertEquals(0, rc, "expected exit 0, got $rc; stderr=${capturedErr.toString(Charsets.UTF_8)}")
            assertTrue(Files.exists(dest), "destination should exist on success")
            val err = capturedErr.toString(Charsets.UTF_8)
            assertTrue("✓ sha256 matches" in err, "expected ✓ confirmation line, got: $err")
            assertTrue(expectedSha in err, "expected hash in confirmation, got: $err")
        }
    }

    @Test
    fun `mismatching sha256 exits 1 with stderr message`() {
        val payload = Bytes.deterministic(2048, seed = 42)
        val wrongSha = "0".repeat(64)
        val rightSha = Bytes.sha256(payload)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val dest = tempDir.resolve("out.bin")
            val rc = runCli(arrayOf(
                "--chunk-size", "1024",
                "--sha256", wrongSha,
                server.url("/file.bin").toString(),
                dest.toString(),
            ))
            assertEquals(1, rc, "expected exit 1, got $rc")
            val err = capturedErr.toString(Charsets.UTF_8)
            assertTrue("checksum mismatch" in err, "expected mismatch message, got: $err")
            assertTrue("expected $wrongSha" in err, "expected message to include expected hash, got: $err")
            assertTrue("got $rightSha" in err, "expected message to include actual hash, got: $err")
        }
    }

    @Test
    fun `bad hex format exits 64 - too short`() {
        val rc = runCli(arrayOf("--sha256", "deadbeef", "http://x/y", tempDir.resolve("z").toString()))
        assertEquals(64, rc)
        assertTrue("64 hex characters" in capturedErr.toString(Charsets.UTF_8), "expected hex-format error, got: $capturedErr")
    }

    @Test
    fun `bad hex format exits 64 - non-hex chars`() {
        val rc = runCli(arrayOf("--sha256", "z".repeat(64), "http://x/y", tempDir.resolve("z").toString()))
        assertEquals(64, rc)
        assertTrue("64 hex characters" in capturedErr.toString(Charsets.UTF_8), "expected hex-format error")
    }

    @Test
    fun `case-insensitive sha256 match accepts uppercase expected`() {
        val payload = Bytes.deterministic(1024, seed = 43)
        val expectedSha = Bytes.sha256(payload).uppercase()
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val dest = tempDir.resolve("out.bin")
            val rc = runCli(arrayOf(
                "--chunk-size", "1024",
                "--sha256", expectedSha,
                server.url("/file.bin").toString(),
                dest.toString(),
            ))
            assertEquals(0, rc, "uppercase hex should still match: ${capturedErr.toString(Charsets.UTF_8)}")
        }
    }

    @Test
    fun `download failure preempts checksum verification`() {
        // Server returns 404 for unknown path; the CLI exits 1 (HTTP error) without trying
        // to checksum a nonexistent destination file.
        TestHttpServer().use { server ->
            val dest = tempDir.resolve("out.bin")
            val rc = runCli(arrayOf(
                "--sha256", "0".repeat(64),
                server.url("/missing").toString(),
                dest.toString(),
            ))
            assertEquals(1, rc)
            // The mismatch message must NOT appear - the failure happened upstream.
            assertTrue("checksum mismatch" !in capturedErr.toString(Charsets.UTF_8),
                "checksum check should be skipped on download failure")
        }
    }
}
