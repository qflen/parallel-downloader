package com.example.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.URL
import java.nio.file.Path

/**
 * Forked-JVM entry point used by [CrashRecoveryStressTest]. The parent test launches this
 * via [ProcessBuilder], passing the server URL and destination path on argv. The child
 * starts a resume-mode download whose [ProgressListener] calls [Runtime.halt] (not
 * [Runtime.exit] - exit runs shutdown hooks, which can defeat the test) once the running
 * downloaded-byte count crosses 25% of the total.
 *
 * No telemetry, no progress printer: the child runs in stress-test mode and we only care
 * that the child reaches its kill threshold and dies abruptly with a non-zero status.
 */
@Suppress("ForbiddenComment") // intentional: this binary is invoked by the stress test only.
fun main(args: Array<String>) {
    require(args.size == ARG_COUNT) {
        "expected exactly 3 arguments: <server-url> <destination> <total-bytes>, got ${args.toList()}"
    }
    val url = URL(args[ARG_URL])
    val destination = Path.of(args[ARG_DEST])
    val totalBytes = args[ARG_TOTAL].toLong()

    val killThreshold = totalBytes / KILL_DENOMINATOR
    val killer = object : ProgressListener {
        override fun onStarted(total: Long) = Unit
        override fun onProgress(downloaded: Long, total: Long) {
            if (downloaded >= killThreshold) {
                // halt skips shutdown hooks - exit would flush JaCoCo / coroutines and
                // potentially complete writes the parent must not observe.
                Runtime.getRuntime().halt(KILL_EXIT_CODE)
            }
        }
        override fun onChunkComplete(chunkIndex: Int) = Unit
        override fun onFinished(result: DownloadResult) = Unit
    }

    val downloader = FileDownloader(com.example.downloader.http.JdkHttpRangeFetcher())
    val cfg = downloadConfig {
        chunkSize = CHILD_CHUNK_SIZE
        parallelism = CHILD_PARALLELISM
        resume = true
        progressListener = killer
    }
    runBlocking(Dispatchers.IO) {
        downloader.download(url, destination, cfg)
    }
    // We do not expect to reach here: the listener kills the JVM mid-download. If we do,
    // exit non-zero so the parent assertion notices the divergence.
    Runtime.getRuntime().halt(EXIT_NEVER_KILLED)
}

private const val ARG_COUNT = 3
private const val ARG_URL = 0
private const val ARG_DEST = 1
private const val ARG_TOTAL = 2
private const val KILL_DENOMINATOR = 4L
private const val KILL_EXIT_CODE = 137
private const val EXIT_NEVER_KILLED = 99
private const val CHILD_CHUNK_SIZE: Long = 4L * 1024 * 1024
private const val CHILD_PARALLELISM: Int = 4
