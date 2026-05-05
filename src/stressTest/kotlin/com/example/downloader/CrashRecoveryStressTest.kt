package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.assertIs

/**
 * Crash recovery: a forked JVM is killed mid-download via [Runtime.halt]; the parent
 * verifies the atomic-assembly and sidecar invariants, then resumes the download from the
 * leftover `.part` file and confirms the result matches the expected SHA-256.
 *
 * What this scenario actually proves: the destination path never reflects a half-written
 * state observable to a concurrent reader, regardless of when the writer process dies.
 * The parent process, holding the source-of-truth payload, asserts:
 *   - destination either does not exist OR is byte-identical to its pre-seeded sentinel
 *   - the `.part` file exists and is non-empty
 *   - the `.partial` sidecar parses and lists at least one completed chunk
 *
 * Implementation note: the child JVM uses the same classpath as this test JVM (looked up
 * via `System.getProperty("java.class.path")`), so no external coordination is needed.
 * The TestHttpServer in the parent is reachable from the child over loopback once we
 * pass its URL on argv.
 *
 * The path layout is hard-coded inline (`.part`, `.partial`) rather than reaching into
 * the production `ResumeSidecar` / `partFor` symbols: those are `internal` to the main
 * source set, and stress tests live in a sibling source set without friend-module
 * visibility. Hard-coding the same names that the production code chose is a deliberate
 * cross-check that the on-disk protocol stays stable.
 */
@Tag("stress")
class CrashRecoveryStressTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `child JVM halt mid-download leaves invariants intact and parent resume succeeds`() {
        val sourceFile = tempDir.resolve("source-64mib.bin")
        val expectedSha = Bytes.writeDeterministicFile(sourceFile, totalLength = SIXTY_FOUR_MIB, seed = 91)
        val dest = tempDir.resolve("dl-crash.bin")
        TestHttpServer().use { server ->
            server.serveFromFile("/crash.bin", sourceFile)

            // Phase 1: fork a child that will kill itself after 25% of the bytes are
            // downloaded. The child uses resume=true so we get a sidecar to check.
            val childExit = runChild(
                serverUrl = server.url("/crash.bin").toString(),
                destination = dest,
                totalBytes = SIXTY_FOUR_MIB,
            )
            assertNotEquals(0, childExit, "child JVM should not have exited cleanly")

            // Atomic-assembly invariant: the destination must NOT exist after the kill.
            // (No pre-seed sentinel to compare against in this scenario - the simpler
            // assertion is "destination absent until success".)
            assertFalse(
                Files.exists(dest),
                "destination must not appear before the download completes successfully",
            )
            // The .part file is the in-flight scratch path. After a mid-download crash,
            // it should exist and be non-empty.
            val partPath = dest.resolveSibling("${dest.fileName}.part")
            assertTrue(Files.exists(partPath), "part file must exist after crash")
            assertTrue(Files.size(partPath) > 0L, "part file must contain bytes")
            // Sidecar must parse and list at least one completed chunk.
            val sidecarPath = dest.resolveSibling("${dest.fileName}.partial")
            assertTrue(Files.exists(sidecarPath), "sidecar must exist after crash")
            val completedCount = parseCompletedCount(sidecarPath.readText())
            assertTrue(
                completedCount > 0,
                "at least one chunk should have completed before the kill (got $completedCount)",
            )

            // Phase 2: parent resumes the download from its own JVM. The atomic-assembly
            // protocol must produce a destination matching the expected SHA-256, with the
            // .part and sidecar both gone after success.
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val cfg = downloadConfig {
                chunkSize = CHILD_CHUNK_SIZE
                parallelism = CHILD_PARALLELISM
                resume = true
            }
            runBlocking(Dispatchers.IO) {
                val result = downloader.download(server.url("/crash.bin"), dest, cfg)
                assertIs<DownloadResult.Success>(result)
            }
            assertEquals(expectedSha, Bytes.sha256(dest))
            assertFalse(Files.exists(partPath), "part file must be gone after a successful resume")
            assertFalse(Files.exists(sidecarPath), "sidecar must be deleted on success")
        }
    }

    private fun parseCompletedCount(text: String): Int {
        // Sidecar format: a "completed=" line carrying a comma-separated list of chunk
        // indices (or empty). Mirroring the on-disk protocol locally so this test does not
        // depend on internal-visibility production symbols.
        val line = text.lineSequence().firstOrNull { it.startsWith("completed=") }
        val rhs = line?.substringAfter("completed=")?.trim().orEmpty()
        return if (rhs.isEmpty()) 0 else rhs.split(",").count { it.toIntOrNull() != null }
    }

    private fun runChild(serverUrl: String, destination: Path, totalBytes: Long): Int {
        val javaHome = System.getProperty("java.home")
        val javaBin = Path.of(javaHome, "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(
            javaBin,
            "-cp", classpath,
            "com.example.downloader.CrashRecoveryChildKt",
            serverUrl,
            destination.toString(),
            totalBytes.toString(),
        )
            .redirectErrorStream(true)
            .start()
        // 30 s upper bound: the child is expected to halt within a few seconds once a
        // chunk crosses 25%. Anything slower means a regression worth surfacing.
        val finished = process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("child JVM did not finish within $CHILD_TIMEOUT_SECONDS seconds")
        }
        return process.exitValue()
    }

    private companion object {
        const val SIXTY_FOUR_MIB: Long = 64L * 1024 * 1024
        const val CHILD_CHUNK_SIZE: Long = 4L * 1024 * 1024
        const val CHILD_PARALLELISM: Int = 4
        const val CHILD_TIMEOUT_SECONDS: Long = 30
    }
}
