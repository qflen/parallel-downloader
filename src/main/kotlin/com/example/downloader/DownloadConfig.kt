package com.example.downloader

@DslMarker
annotation class DownloadConfigDsl

/**
 * Per-download configuration.
 *
 * @property chunkSize bytes per ranged GET request. Smaller chunks expose more parallelism on
 *   small files but inflate per-chunk HTTP overhead. Default is 8 MiB - small enough to fan out
 *   on multi-MB files, large enough that headers/handshake amortize to <1% of bytes-on-the-wire.
 * @property parallelism maximum number of chunk fetches in flight at once. Default is 8 - bounded
 *   so we don't melt the connection pool on a 10 GB file with 1 KiB chunks.
 * @property progressListener Observer for download events; defaults to [ProgressListener.NoOp].
 * @property overwriteExisting if `true` (default) and [destination] already exists, it is
 *   overwritten. If `false`, the download fails before any HTTP traffic with [DownloadResult.IoFailure].
 * @property resume if `true`, a sidecar file `<destination>.partial` is consulted and updated:
 *   on success, completed chunks are persisted; on failure, the partial destination + sidecar
 *   are preserved so the next call with `resume=true` can pick up where the previous one left
 *   off. The sidecar's recorded entity validator (ETag / Last-Modified) must still match the
 *   server's current probe — otherwise the sidecar is discarded and the download starts fresh.
 *   Default `false`: any failure deletes the partial file (current behavior).
 */
class DownloadConfig private constructor(
    val chunkSize: Long,
    val parallelism: Int,
    val progressListener: ProgressListener,
    val overwriteExisting: Boolean,
    val resume: Boolean,
) {
    init {
        require(chunkSize > 0) { "chunkSize must be > 0, got $chunkSize" }
        require(parallelism > 0) { "parallelism must be > 0, got $parallelism" }
    }

    /**
     * Pattern: **Builder DSL** - idiomatic Kotlin trailing-lambda construction. We isolate call
     * sites from future config additions: a new field gets a default in the Builder and existing
     * call sites keep working without changes.
     */
    @DownloadConfigDsl
    class Builder {
        var chunkSize: Long = DEFAULT_CHUNK_SIZE
        var parallelism: Int = DEFAULT_PARALLELISM
        var progressListener: ProgressListener = ProgressListener.NoOp
        var overwriteExisting: Boolean = true
        var resume: Boolean = false

        fun build(): DownloadConfig =
            DownloadConfig(chunkSize, parallelism, progressListener, overwriteExisting, resume)
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE: Long = 8L * 1024L * 1024L
        const val DEFAULT_PARALLELISM: Int = 8

        /** No-arg construction: `DownloadConfig()` returns the default config. */
        operator fun invoke(): DownloadConfig = Builder().build()
    }
}

/**
 * Idiomatic Kotlin DSL entry point:
 * ```
 * val cfg = downloadConfig {
 *     chunkSize = 1.MiB
 *     parallelism = 16
 * }
 * ```
 */
fun downloadConfig(block: DownloadConfig.Builder.() -> Unit): DownloadConfig =
    DownloadConfig.Builder().apply(block).build()
