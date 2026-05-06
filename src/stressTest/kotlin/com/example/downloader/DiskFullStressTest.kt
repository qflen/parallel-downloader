package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.assertIs

/**
 * What happens when the destination filesystem fills up mid-write? The orchestrator
 * preallocates the part file via a sparse one-byte write at `totalBytes - 1`; on a tmpfs
 * smaller than the file, that preallocation either succeeds (kernel reservation) or fails
 * with ENOSPC, depending on the kernel and mount options. Either way, the chunk-fetch
 * loop's positional `FileChannel.write` will eventually hit ENOSPC, and the orchestrator
 * must:
 *   - return `DownloadResult.IoFailure(cause)` rather than throwing or silently truncating
 *   - delete the partial file, leaving the destination directory in a clean state
 *
 * The test stages the file under a tmpfs with `size=8M` (set up by the CI workflow before
 * stressTest runs) and asks the downloader to fetch a 16 MiB body into it. ENOSPC
 * is unavoidable; we assert it's surfaced cleanly.
 *
 * **Linux only.** macOS and Windows don't ship a comparable lightweight quota mechanism,
 * and forging one via per-test container/VM setup would dwarf the test's value. Both OSes
 * skip the test cleanly via `@EnabledOnOs(LINUX)`.
 *
 * **Requires the tmpfs at the path named by the `disk.full.tmpfs` system property** (or
 * `DISK_FULL_TMPFS` env var). The CI workflow sets this up; locally:
 * ```
 * sudo mkdir -p /tmp/disk-full-test
 * sudo mount -t tmpfs -o size=8M tmpfs /tmp/disk-full-test
 * sudo chmod 777 /tmp/disk-full-test
 * ./gradlew stressTest -Ddisk.full.tmpfs=/tmp/disk-full-test
 * ```
 * Without the property the test skips with a clear message.
 */
@Tag("stress")
@EnabledOnOs(OS.LINUX)
class DiskFullStressTest {

    @Test
    fun `mid-write ENOSPC surfaces as IoFailure and leaves no partial file`() {
        val tmpfsPath = locateTmpfs()
        val server = TestHttpServer()
        try {
            val payload = Bytes.deterministic(PAYLOAD_BYTES, seed = 91)
            server.serve("/big.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tmpfsPath / "big.bin"
            val result = runBlocking {
                downloader.download(
                    server.url("/big.bin"),
                    dest,
                    downloadConfig {
                        chunkSize = CHUNK_BYTES
                        parallelism = 4
                    },
                )
            }
            // The exact failure mode (preallocate ENOSPC vs mid-chunk write ENOSPC) depends
            // on the kernel; both manifest as DownloadResult.IoFailure with a typed cause.
            assertIs<DownloadResult.IoFailure>(result)
            assertFalse(
                Files.exists(dest),
                "destination must not exist on a failed download (atomic-assembly invariant)",
            )
            // The .part file should also be cleaned up since resume=false (the default).
            // Replicate the part-path naming inline; partFor() is internal to the orchestrator
            // module and the stress source set lives behind the source-set boundary.
            val partFile = dest.resolveSibling("${dest.fileName}.part")
            val partSize = if (Files.exists(partFile)) Files.size(partFile) else -1L
            assertFalse(
                Files.exists(partFile),
                "part file must be deleted on failure when resume=false; was $partSize bytes",
            )
        } finally {
            server.close()
            // If anything stuck around in the tmpfs, clean up so the next test run starts fresh.
            runCatching { Files.list(tmpfsPath).use { it.forEach { p -> Files.deleteIfExists(p) } } }
        }
    }

    private fun locateTmpfs(): Path {
        val pathStr = System.getProperty(TMPFS_PROPERTY)
            ?: System.getenv(TMPFS_ENV_VAR)
        assumeTrue(
            pathStr != null,
            "Skipping disk-full test: set -D$TMPFS_PROPERTY=/path or env $TMPFS_ENV_VAR " +
                "to a tmpfs mount with `size=8M`. See test docstring for setup.",
        )
        val path = Path.of(pathStr!!)
        assumeTrue(
            Files.isDirectory(path),
            "Skipping disk-full test: $path is not a directory",
        )
        // Sanity check: confirm the path is actually small. Write attempts beyond ~8 MiB
        // should fail; if this directory has GiBs free, the test is meaningless.
        assumeTrue(
            isPlausiblySmallVolume(path),
            "Skipping disk-full test: $path appears to have substantial free space; " +
                "is it actually a tmpfs with size=8M?",
        )
        return path
    }

    private fun isPlausiblySmallVolume(path: Path): Boolean {
        // FileStore.getUsableSpace() returns bytes available to the JVM. A genuine tmpfs
        // with size=8M will report 8 MiB or less; a plain /tmp will report the host's
        // free disk. We assert the former.
        return try {
            val store = Files.getFileStore(path)
            val usable = store.usableSpace
            usable in 1L..MAX_USABLE_BYTES
        } catch (_: java.io.IOException) {
            false
        }
    }

    private companion object {
        const val TMPFS_PROPERTY = "disk.full.tmpfs"
        const val TMPFS_ENV_VAR = "DISK_FULL_TMPFS"
        const val PAYLOAD_BYTES = 16 * 1024 * 1024  // 16 MiB - 2x the tmpfs cap
        const val CHUNK_BYTES = 1L * 1024 * 1024    // 1 MiB chunks → 16 chunks total
        const val MAX_USABLE_BYTES = 32L * 1024 * 1024  // 32 MiB sanity ceiling
    }
}
