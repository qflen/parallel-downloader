# RFC 9110 compliance matrix

This document maps the parts of [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html)
(HTTP Semantics) that the downloader interacts with to one of three states:

- **Covered** - the implementation honors the RFC text and a test asserts it.
- **Out of scope** - the RFC defines a feature the downloader deliberately does not use.
- **Known gap** - a behavior the implementation could or should support but does not yet.

The matrix is the closing argument on the question "what does this actually implement?".

The downloader is a single-resource GET client. POST, PUT, DELETE, multipart bodies, content
negotiation beyond `Accept-Ranges`, caching semantics, and proxy behavior (RFC 9111, RFC 9112)
are all outside the project's scope and not enumerated below.

## §14 Range Requests

| Section | Topic | Status | Rationale | Test |
|---------|-------|--------|-----------|------|
| §14.1.1 | `Range: bytes=N-M` (single byte-range) | Covered | The downloader's primary request shape. Built in `JdkHttpRangeFetcher.buildRangedRequest`. | `JdkHttpRangeFetcherTest`, `FileDownloaderTest` |
| §14.1.1 | `Range: bytes=N-` (open-ended) | Out of scope | The orchestrator computes the chunk plan from `Content-Length`, so every request has a definite end byte. Open-ended would force a slower probe loop with no win. | - |
| §14.1.1 | `Range: bytes=-N` (suffix range) | Out of scope (request side) | Same reason. The chunk planner emits closed ranges. (Server *responses* labeled with a suffix range still have to round-trip safely - see `RangeEdgeCasesTest`.) | `RangeEdgeCasesTest` |
| §14.1.1 | Multi-range (`bytes=0-499,1000-1499`) | Out of scope | Multipart `multipart/byteranges` parsing would be a non-trivial addition for no win - the fan-out is already one request per chunk. | - |
| §14.2 | `Accept-Ranges: bytes` | Covered | Probed in `HttpProbe`; controls the dispatch fork between ranged-parallel and single-GET fallback. | `EdgeCaseTest`, `FileDownloaderTest` |
| §14.2 | `Accept-Ranges: none` | Covered | Treated identically to a missing `Accept-Ranges` header: fall through to single-GET. | `EdgeCaseTest` |
| §14.3 | `Content-Range: bytes N-M/T` | Covered | Parsed and validated to match the requested range. Mismatch raises a transient retryable error so a flaky proxy doesn't permanently corrupt a chunk. | `JdkHttpRangeFetcherTest` (`parseContentRange`) |
| §14.3 | `Content-Range: bytes N-M/*` (unknown total) | Covered | The parser accepts `*` for the resource size; we validate range bounds without requiring a known total. | `RangeEdgeCasesTest` |
| §14.4 | `If-Range` with ETag (strong validator) | Covered | Forwarded to every chunk request whenever the probe yielded an ETag; a server must reply 206 if still valid, 200 otherwise. The 200-on-If-Range case surfaces as the typed `DownloadResult.ValidatorMismatch(expected, observed)`, distinct from `HttpError(200, CHUNK)` (which is reserved for the 200-without-If-Range "server ignored Range" shape). Two file versions are never spliced silently. | `IfRangeTest` |
| §14.4 | `If-Range` with `Last-Modified` (weak validator) | Covered | Same path; falls back when no ETag is present. Mismatched weak validators surface the same typed result. | `IfRangeTest` |
| §14.4 | `If-Range` with weak ETag (`W/"..."`) | Covered | We forward the validator verbatim, including the weak prefix; the server is the authority on equality. | `IfRangeTest` |
| §14.5 | Combining ranges (server returns one 206 covering several ranges) | Out of scope | We never request multipart, so a server combining single-range responses isn't reachable. | - |
| §15.5.17 | 416 Range Not Satisfiable | Covered | Mapped to `NonRetryableFetchException(statusCode=416)` and surfaced as `DownloadResult.HttpError`. Retries would not help. | `RangeEdgeCasesTest`, `JdkHttpRangeFetcherTest` |
| §15.3.7 | 206 Partial Content | Covered | Required status for a ranged GET. A 200 reply to a ranged GET is treated as a deterministic terminal failure (`ValidatorMismatch` when `If-Range` was sent, `HttpError(200, CHUNK)` otherwise) rather than spliced into the destination. | `JdkHttpRangeFetcherTest`, `IfRangeTest`, `RangeEdgeCasesTest` |
| §8.4 | `Content-Encoding: identity` | Covered | The default; ranged GETs proceed normally. | `ContentEncodingRangeTest` |
| §8.4 | `Content-Encoding: gzip` (etc.) on a ranged resource | Covered (refusal) | Ranged byte offsets are defined over the resource as named by `Content-Encoding`; combining an encoded body with byte ranges is a documented footgun. The probe refuses ranges when the encoding is anything but `identity` and falls through to single-GET. | `ContentEncodingRangeTest` |
| §12.5.5 | `Vary: ...` interaction with `If-Range` | Covered | The validator carried by `If-Range` is the only correctness anchor we hold. If a probe and a chunk GET would resolve to different representations, the server's `If-Range` evaluation is what catches it (we get a 200 reply, which we treat as the typed `DownloadResult.ValidatorMismatch(expected, observed)` rather than spliced bytes). | `VaryRangeTest` |

## §13 Conditional Requests

| Section | Topic | Status | Rationale | Test |
|---------|-------|--------|-----------|------|
| §13.1.1 | `If-Match` | Out of scope | `If-Match` is a write-side precondition (PUT, POST, DELETE). The downloader is GET-only. | - |
| §13.1.2 | `If-None-Match` | Out of scope | We do not implement client-side conditional fetching (no on-disk cache to revalidate). | - |
| §13.1.3 | `If-Modified-Since` | Out of scope | Same reason as `If-None-Match`: no caching layer to revalidate. | - |
| §13.1.4 | `If-Unmodified-Since` | Out of scope | Write-side. | - |
| §13.1.5 | `If-Range` | Covered | The downloader's safety anchor against mid-download file change. The 200-on-If-Range case is surfaced as `DownloadResult.ValidatorMismatch(expected, observed)`. See §14.4 row above. | `IfRangeTest` |
| §13.2.2 | Strong vs weak validator selection | Covered | `HttpProbe` prefers `ETag` over `Last-Modified`; the orchestrator forwards whichever the probe yielded, untouched. | `EdgeCaseTest` |

## §10.2.3 Retry-After

| Section | Topic | Status | Rationale | Test |
|---------|-------|--------|-----------|------|
| §10.2.3 | `Retry-After: <delta-seconds>` | Covered | Parsed in `JdkHttpRangeFetcher.parseRetryAfter`; attached to `TransientFetchException.retryAfter`; used as a lower bound on the next retry delay. | `JdkHttpRangeFetcherTest`, `RetryAfterIntegrationTest`, `RetryAfterParserPropertyTest` |
| §10.2.3 | `Retry-After: <HTTP-date>` (RFC 1123 / IMF-fixdate) | Covered | Parsed via `DateTimeFormatter.RFC_1123_DATE_TIME`; converted to delta-from-now via the supplied `Clock`. A past date is dropped (treated as null). | `JdkHttpRangeFetcherTest`, `RetryAfterParserPropertyTest` |
| §10.2.3 | Negative delta-seconds | Covered (rejected) | Treated as null per the "stale before sent" reasoning - the server's hint is meaningless. The retry policy then falls back to its scheduled backoff. | `JdkHttpRangeFetcherTest`, `RetryAfterParserPropertyTest` |
| §10.2.3 | Malformed value (whitespace, garbage, mixed forms) | Covered | The parser short-circuits to null on any unparseable input; no exception escapes the parser. | `RetryAfterParserPropertyTest` |
| §10.2.3 | Cap at policy `maxDelay` | Covered | The realized wait is `max(jitter(scheduledBackoff), retryAfter).coerceAtMost(maxDelay)`, so a misbehaving server returning `Retry-After: 86400` cannot pin the client past its retry budget. | `RetryPolicyTest`, `RetryPolicyPropertyTest` |
| §15.5.30 | 429 + `Retry-After` | Covered | 429 is classified as transient (carved out from the 4xx non-retryable bucket); the retry decorator honors the header. | `JdkHttpRangeFetcherTest`, `RetryAfterIntegrationTest` |
| §15.6 | 5xx + `Retry-After` | Covered | 5xx → transient; same retry path. | `RetryAfterIntegrationTest` |

## Out-of-band guarantees the RFCs don't require

A few invariants the downloader holds that aren't stipulated by RFC 9110 but are useful to
state explicitly because they constrain how the implementation responds to a malformed
server:

| Invariant | Rationale | Test |
|-----------|-----------|------|
| A non-206 reply to a ranged GET (other than retryable 5xx / 429) is fatal, never spliced. | Silently writing a 200 body at a chunk offset would corrupt neighbor chunks. | `JdkHttpRangeFetcherTest`, `IfRangeTest` |
| A 200 reply to a ranged GET that carried `If-Range` surfaces as `DownloadResult.ValidatorMismatch(expected, observed)` — distinct from `HttpError(200, CHUNK)`, which is reserved for the 200-without-`If-Range` "server ignored Range" shape. | The two collapse onto wire status 200 in the chunk phase but differ in what the caller can do next: validator mismatch is deterministic on a re-fetch (the resource has moved on), while ignored-Range is a server-bug case a caller may want to report. The typed split lets a `--resume` caller tell them apart without having to read response headers we've already discarded. | `IfRangeTest`, `VaryRangeTest`, `RangeEdgeCasesTest` |
| A `Content-Range` header that disagrees with the requested range is treated as transient. | Some proxies serve garbled headers under load; a fresh attempt may succeed. | `JdkHttpRangeFetcherTest` |
| A premature EOF mid-body is transient. | Same proxy-flakiness story; the chunk plan is unchanged so the retry simply re-fetches. | `JdkHttpRangeFetcherTest`, `StressTest` |
| Anything outside the chunk's requested range that the fetcher tries to write is rejected at the sink. | Defense-in-depth: a misbehaving fetcher cannot corrupt neighbor chunks. | `MakeChunkSinkTest` |

## Status codes the project explicitly handles

| Code | Class | Where | What we do |
|------|-------|-------|-----------|
| 200 | success | single-GET fallback | accept |
| 200 | success | ranged GET reply, `If-Range` sent | `ValidatorMismatch(expected, observed)` |
| 200 | success | ranged GET reply, no `If-Range` | non-retryable `HttpError(200, CHUNK)` (server ignored Range) |
| 206 | success | ranged GET reply | accept after `Content-Range` validation |
| 416 | client error | ranged GET reply | non-retryable `HttpError(416, CHUNK)` |
| 429 | client error | any phase | transient + `Retry-After` |
| 4xx (other) | client error | any phase | non-retryable `HttpError(status, phase)` |
| 5xx | server error | any phase | transient + optional `Retry-After` |

## See also

- [DESIGN.md - Failure taxonomy](DESIGN.md#failure-taxonomy)
- [DESIGN.md - Retry-After is honored on 429 and 5xx](DESIGN.md#retry-after-is-honored-on-429-and-5xx)
- [PRIVACY.md](../PRIVACY.md) - the headers we deliberately don't emit (User-Agent, Referer,
  Cookie, Authorization, From) and why.
