package com.example.downloader.http

import java.net.URL

/**
 * Sends a HEAD request and interprets the response into a [ProbeResult]. Extracted from
 * [JdkHttpRangeFetcher] so the response-interpretation logic (range support, content length,
 * post-redirect URL) gets its own focused tests.
 *
 * Filled in during Phase 3.
 */
internal class HttpProbe {
    @Suppress("UnusedParameter") // implemented in phase 3
    suspend fun probe(url: URL): ProbeResult = TODO("phase 3")
}
