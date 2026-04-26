package com.example.downloader.http

import java.net.URL

/**
 * Pattern: **Adapter** — wraps `java.net.http.HttpClient` (JDK built-in, zero extra runtime
 * deps so a reviewer can read every line) behind the [HttpRangeFetcher] port. Streams each
 * response body via `BodyHandlers.ofInputStream()` and writes transport-sized slices into the
 * [RangeSink] without buffering the whole range in memory.
 *
 * Filled in during Phase 3.
 */
class JdkHttpRangeFetcher : HttpRangeFetcher {
    override suspend fun probe(url: URL): ProbeResult = TODO("phase 3")
    override suspend fun fetchRange(url: URL, range: LongRange, sink: RangeSink): Unit = TODO("phase 3")
    override suspend fun fetchAll(url: URL, sink: RangeSink): Unit = TODO("phase 3")
}
