package com.example.downloader.telemetry

import com.example.downloader.Telemetry
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration

/**
 * Reference implementation of [Telemetry] backed by `java.util.logging` (JUL). The
 * privacy-typed contract on [Telemetry] - chunk indices, byte counts, retry counts, the
 * download's elapsed wall time - is the entire surface this class touches; nothing else can
 * leak into the log message.
 *
 * JUL is the only supported logging backend because it ships with the JDK. Keeping the
 * runtime classpath at `kotlinx-coroutines-core` only is a project-level invariant
 * (PRIVACY.md, build.gradle.kts), and adding a `slf4j-api` -> `logback` -> `jansi` chain
 * would be a multi-megabyte cost for what is structurally a few `logger.info(...)` calls.
 *
 * Default level is `Level.INFO`. Override via the standard JUL config:
 * ```
 * com.example.downloader.telemetry.level = FINE
 * ```
 *
 * Wired into the CLI by `--telemetry log` in `Main.kt`. Library callers construct it
 * directly:
 * ```kotlin
 * downloadConfig { telemetry = LoggingTelemetry() }
 * ```
 */
class LoggingTelemetry(
    private val logger: Logger = Logger.getLogger("com.example.downloader.telemetry"),
    private val level: Level = Level.INFO,
) : Telemetry {

    override fun onChunkComplete(chunkIndex: Int, chunkBytes: Long) {
        if (logger.isLoggable(level)) {
            logger.log(level, "chunk_complete index=$chunkIndex bytes=$chunkBytes")
        }
    }

    override fun onDownloadComplete(totalBytes: Long, elapsed: Duration, chunks: Int, retries: Int) {
        if (logger.isLoggable(level)) {
            logger.log(
                level,
                "download_complete bytes=$totalBytes elapsed_ms=${elapsed.inWholeMilliseconds} " +
                    "chunks=$chunks retries=$retries",
            )
        }
    }

    override fun onTransientFailure(retryAttempt: Int) {
        if (logger.isLoggable(level)) {
            logger.log(level, "transient_failure attempt=$retryAttempt")
        }
    }
}
