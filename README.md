# parallel-downloader

A Kotlin file downloader that fetches multiple byte ranges in parallel from any HTTP server that
supports `Range` requests, falling back to a single GET when it doesn't.

## What it does

Given an HTTP(S) URL pointing to a static file and a destination path on local disk, the tool:

1. Sends a HEAD request to discover length, `Accept-Ranges`, and the post-redirect URL.
2. Plans contiguous byte-range chunks of configurable size.
3. Fetches the chunks in parallel inside structured-concurrency scope (`Dispatchers.IO.limitedParallelism(N)`).
4. Streams each response straight to disk via `FileChannel.write(ByteBuffer, position)` - nothing
   is buffered to memory beyond a 64 KiB transport buffer per in-flight chunk.
5. Verifies the assembled file's length matches the server's `Content-Length`.

If the server doesn't support ranges, falls back to a single GET that streams to disk the same
way. Failures are returned as a typed `DownloadResult` (sealed class) - only programmer errors
throw.

## Requirements

- JDK 17 (the Gradle wrapper targets the toolchain)
- Docker (only for the optional manual demo against a real Apache `httpd`)

## Build

```bash
./gradlew build
```

Runs the Kotlin compiler with `allWarningsAsErrors=true`, detekt static analysis on the default
ruleset (zero issues required), all 108 unit + integration tests against a real loopback HTTP
server, and the JaCoCo coverage gate (≥90% line, ≥85% branch on production code).

## Test

```bash
./gradlew test         # 108 tests, ~2s
./gradlew stressTest   # 8 scenarios, ~30s, runs with -Xmx256m
```

The non-stress suite runs against a real `com.sun.net.httpserver.HttpServer` test fake with
fault-injection knobs (status overrides, latency, throttling, mid-stream disconnect, deterministic
chaos). The stress suite covers a 1 GiB streaming download under capped heap, 1024 small chunks at
parallelism 32, throttled-server timing, retry-budget chaos, mid-stream disconnect recovery, a
1000-iteration leak hunt, and a 50-iteration cancellation cleanup.

## CLI

Build a runnable distribution:

```bash
./gradlew installDist
./build/install/parallel-downloader/bin/parallel-downloader URL DEST [OPTIONS]
```

Or run directly through Gradle:

```bash
./gradlew run --args="URL DEST"
./gradlew run --args="--chunk-size 8MiB --parallelism 16 --retries 3 URL DEST"
```

Flags:

| Flag                | Description                                                         | Default |
| ------------------- | ------------------------------------------------------------------- | ------- |
| `--chunk-size SIZE` | Bytes per ranged GET. Accepts plain bytes or `KiB`/`MiB`/`GiB` etc. | 8 MiB   |
| `--parallelism N`   | Maximum number of in-flight chunks.                                 | 8       |
| `--retries N`       | Per-chunk retry attempts on transient failures.                     | 3       |

Progress is rendered to stderr at ~10 Hz: `downloaded / total MiB, %, throughput MiB/s, elapsed`.

Exit codes:

| Code | Meaning                                                  |
| ---- | -------------------------------------------------------- |
| 0    | Success                                                  |
| 1    | HTTP-level failure (HttpError, LengthMismatch, RangeNotSupported) |
| 2    | Local I/O failure (IoFailure)                            |
| 64   | Usage error (bad arguments)                              |
| 130  | Cancelled (signal-style; not currently emitted by typed return path) |

## Manual demo (Docker)

The original task description suggests an Apache `httpd` container as the reference server.
This setup was used to verify the CLI end-to-end:

```bash
mkdir -p /tmp/demo-files
dd if=/dev/urandom of=/tmp/demo-files/medium.bin bs=1M count=50
: > /tmp/demo-files/empty.bin   # zero-byte file

docker run --rm -d --name dl-demo \
    -p 8888:80 \
    -v /tmp/demo-files:/usr/local/apache2/htdocs/ \
    httpd:latest

./gradlew installDist
./build/install/parallel-downloader/bin/parallel-downloader \
    --chunk-size 4MiB --parallelism 8 \
    http://127.0.0.1:8888/medium.bin \
    /tmp/dl-medium.bin

shasum -a 256 /tmp/demo-files/medium.bin /tmp/dl-medium.bin    # SHAs match
docker stop dl-demo
```

Sample output (from a verified run on this machine):

```
downloading...                                                  2.0 /   50.0 MiB    4.0%   16.84 MiB/s    0.12s
downloading...                                                 46.0 /   50.0 MiB   92.0%  153.07 MiB/s    0.30s
downloading...                                                 50.0 /   50.0 MiB  100.0%  163.45 MiB/s    0.31s
✓ saved 52428800 bytes to /tmp/dl-medium.bin in 330.768042ms

80313975536c2c3e9b551dfeaa0b85323aeb4eac553de208472e0c387438763b  /tmp/demo-files/medium.bin
80313975536c2c3e9b551dfeaa0b85323aeb4eac553de208472e0c387438763b  /tmp/dl-medium.bin
```

## Architecture

Seven design patterns earn their place; each is named and justified inline at the implementation
site rather than just listed here:

- **Adapter / Port** - `HttpRangeFetcher` is the orchestrator's only window onto HTTP;
  `JdkHttpRangeFetcher` is the single production adapter wrapping the JDK's
  `java.net.http.HttpClient` (zero extra runtime deps so a reviewer can read every line).
- **Decorator** - `RetryingHttpRangeFetcher` wraps any `HttpRangeFetcher` and applies a
  `RetryPolicy`. Composable; single-responsibility (retry mechanics live here, not inside the
  fetcher implementation or the orchestrator).
- **Strategy** - `RetryPolicy` interface with `ExponentialBackoffRetry` and `NoRetry`
  implementations; the orchestrator's runtime choice between ranged-parallel and single-GET
  fallback driven by `ProbeResult` is itself a Strategy selection.
- **Builder DSL** - `DownloadConfig` + the top-level `downloadConfig { chunkSize = 1.MiB }`
  function; idiomatic Kotlin trailing-lambda construction.
- **Observer** - `ProgressListener` decouples reporting from download logic; the CLI's stderr
  printer is one implementation, tests use a recording one.
- **Sealed Result Type** - `DownloadResult` (Kotlin's idiomatic Either) with `Success`,
  `HttpError`, `LengthMismatch`, `IoFailure`, `Cancelled`, `RangeNotSupported`. Expected
  failure modes live in the type system; `IllegalArgumentException` is reserved for programmer
  errors at the boundary.
- **Template Method** - `FileDownloader.download` orchestrates the lifecycle as a sequence of
  named private steps: `validate → probe → checkEnvironment → preallocate → executeChunks →
  verifyLength → finalize`. Reads top-to-bottom.

The downloader does **not** use Singleton, AbstractFactory, Visitor, Chain of Responsibility, or
Mediator - they wouldn't pay rent here.

## Known limitations

- **Per-chunk retry replays bytes from chunk start.** Idempotent on the destination
  `FileChannel.write(buffer, position)`, but consumes upstream bandwidth proportional to retry
  count. With `If-Range` enabled (the default when the server advertises an ETag or
  Last-Modified), a server-side file change mid-download fails loudly instead of silently
  splicing two versions.
- **Single-GET fallback is not retried.** The retry decorator is at the fetcher layer, but
  fallback is reserved for servers that don't advertise range support - those tend to be either
  reliable static-file servers or fundamentally broken, so retry rarely helps.
- **No resumable downloads across process restarts.** The pre-allocated destination is deleted
  on any non-success terminal state.
- **No HTTPS certificate / hostname verification customization.** JDK defaults apply.
- **Cancellation reports as `DownloadResult.Cancelled` only via the progress listener.** The
  suspend `download()` function rethrows `CancellationException` to honor structured concurrency.
  Listener-based UIs see the synthetic `onFinished(Cancelled)` event and can distinguish
  cancelled-vs-failed without observing the exception.

## What I'd improve with more time

- Add a `--resume` mode that detects a partial destination and only fetches missing chunks.
  The design supports it (chunks are independent and use `FileChannel.write` at fixed offsets);
  what's missing is a sidecar file that tracks completed chunk ranges across process restarts.
