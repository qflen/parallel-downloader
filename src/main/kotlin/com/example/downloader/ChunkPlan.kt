@file:Suppress("MatchingDeclarationName") // file holds Chunk + planChunks; spec mandates this filename.

package com.example.downloader

/**
 * A contiguous byte range to be fetched independently.
 *
 * @property index zero-based ordinal in the plan; useful for ordered logging and progress
 *   callbacks. Chunks are otherwise independent - they can complete in any order.
 * @property start absolute file offset of the first byte (inclusive).
 * @property endInclusive absolute file offset of the last byte (inclusive). HTTP `Range:
 *   bytes=start-endInclusive` is byte-inclusive at both ends, so we mirror that here to avoid
 *   off-by-one errors when constructing the header.
 */
internal data class Chunk(val index: Int, val start: Long, val endInclusive: Long) {
    init {
        require(start >= 0) { "start must be >= 0, got $start" }
        require(endInclusive >= start) { "endInclusive ($endInclusive) must be >= start ($start)" }
    }

    /** Number of bytes covered by this chunk (always >= 1). */
    val length: Long get() = endInclusive - start + 1
}

/**
 * Pure chunk math, separated from any I/O so it can be exercised exhaustively without a server.
 *
 * Returns an ordered list of [Chunk]s covering `[0, totalBytes)`. The last chunk's [Chunk.length]
 * may be smaller than `chunkSize` when `totalBytes` is not a multiple of `chunkSize`.
 *
 * Returns an empty list for `totalBytes == 0` - callers handle zero-byte downloads without
 * invoking the chunk pipeline at all (just create an empty file).
 *
 * @throws IllegalArgumentException when `totalBytes < 0` or `chunkSize <= 0`, or when the
 *   resulting plan would exceed [Int.MAX_VALUE] chunks (which a Kotlin [List] cannot represent).
 */
internal fun planChunks(totalBytes: Long, chunkSize: Long): List<Chunk> {
    require(totalBytes >= 0) { "totalBytes must be >= 0, got $totalBytes" }
    require(chunkSize > 0) { "chunkSize must be > 0, got $chunkSize" }
    if (totalBytes == 0L) return emptyList()

    val fullChunks = totalBytes / chunkSize
    val hasRemainder = totalBytes % chunkSize != 0L
    val totalChunks = fullChunks + if (hasRemainder) 1L else 0L
    require(totalChunks <= Int.MAX_VALUE.toLong()) {
        "Chunk plan too large: $totalChunks chunks (chunkSize=$chunkSize, totalBytes=$totalBytes)"
    }

    return List(totalChunks.toInt()) { i ->
        val start = i.toLong() * chunkSize
        val endExclusive = minOf(start + chunkSize, totalBytes)
        Chunk(index = i, start = start, endInclusive = endExclusive - 1)
    }
}
