# Privacy

`parallel-downloader` is a single-purpose HTTP file fetcher. This page enumerates what does
**not** leave the process, where the boundary is, and what would change the picture.

## What the downloader does not collect, persist, or transmit

- **No identifying request headers.** The HTTP requests emitted by [`JdkHttpRangeFetcher`](src/main/kotlin/com/example/downloader/http/JdkHttpRangeFetcher.kt)
  do not send `User-Agent`, `Referer`, `Cookie`, `Authorization`, or `From`. The only
  request headers the downloader sets are `Range` (per-chunk byte range) and `If-Range`
  (when the probe returned an `ETag` / `Last-Modified`). The JDK's default `User-Agent:
  Java-http-client/<jdk-version>` is explicitly suppressed so the JDK build is not
  fingerprintable from the wire.
- **No environment reads.** Production code does not read `System.getenv("USER")`,
  `System.getenv("HOSTNAME")`, `System.getProperty("user.name")`, or call
  `InetAddress.getLocalHost()`. The [`PiiScanner`](src/test/kotlin/com/example/downloader/internal/PiiScanner.kt)
  enforces this at build time (wired into `./gradlew check`).
- **Resume sidecar carries chunk geometry only.** `<destination>.partial` contains
  exactly five keys: `version`, `total`, `chunkSize`, `validator`, `completed`. No
  hostname, no username, no source URL, no path beyond what's already in the destination
  path the caller chose. Serialization round-trip is asserted in
  [`ResumeSidecarTest`](src/test/kotlin/com/example/downloader/ResumeSidecarTest.kt).
- **No telemetry beacons.** The downloader does not phone home, ship metrics to a
  third-party endpoint, or open any sockets except to the URL the caller passes in. The
  [`Telemetry`](src/main/kotlin/com/example/downloader/Telemetry.kt) interface is the
  only seam for in-process metric collection; its method signatures accept counters,
  byte counts, and chunk indices — not URLs, paths, validators, or error message text.
- **Error messages don't synthesize local context.** A `DownloadResult.IoFailure` wraps
  the underlying `Throwable`; its message reflects whatever the JDK / kernel gave us
  for the destination the caller passed. The downloader does not append `user.home`,
  cwd, environment variables, or hostname to error text.

## What could change this

- **Custom `HttpClient`.** The `JdkHttpRangeFetcher` uses a default `HttpClient`. A caller
  who instantiates the underlying `HttpClient` themselves (e.g., to add a proxy, custom
  TLS, or auth) takes responsibility for whatever headers and connection metadata that
  client emits.
- **Custom `Telemetry` implementation.** The `Telemetry` interface receives non-PII
  values, but a user-supplied implementation can persist or transmit them in any way.
  The interface's signature is the contract; what an implementation does with
  `onChunkComplete(chunkIndex, chunkBytes)` is between the implementation and its sink.
- **Server-side observability.** The downloader cannot affect what the server logs.
  The destination server sees the connecting IP, TLS handshake metadata, and the
  `Range` headers — that's a property of HTTP, not the downloader.
- **Custom progress listeners.** A `ProgressListener` implementation could log paths,
  URLs, or chunks anywhere the implementation chooses. The interface signatures
  receive only the values the docstrings advertise (`downloaded`, `total`,
  `chunkIndex`, `result`).
- **Error message contents.** When an `IoFailure` wraps a JDK exception about a path
  the caller passed, the path is in the message. The downloader does not mask paths
  the caller chose; it does not append paths the caller did not choose.

## Telemetry boundary

[`Telemetry`](src/main/kotlin/com/example/downloader/Telemetry.kt) is the supported seam
for future telemetry attachment. The interface takes byte counts, chunk indices, and
retry attempt numbers — values that don't re-identify a user. URL hosts, file paths,
validator strings, and error message text are deliberately not part of the surface.
See [`docs/DESIGN.md#telemetry-boundary`](docs/DESIGN.md#telemetry-boundary) for the
rationale.

## Verification

[`AnonymityTest`](src/test/kotlin/com/example/downloader/AnonymityTest.kt) is the
executable form of this policy. Each claim above is asserted programmatically against
real production code paths via the in-process `TestHttpServer`. The build fails if
any of those assertions regress.
