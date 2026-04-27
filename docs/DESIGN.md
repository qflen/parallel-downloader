# parallel-downloader: design

This document is the architecture brief. It assumes you've already read the
[README](../README.md) and want to understand *why* the code is shaped the way it is. The companion
[architecture diagram](architecture.svg) (source: [architecture.d2](architecture.d2)) renders the
same component graph visually.

- [Component map](#component-map)
- [Design patterns](#design-patterns)
- [Concurrency model](#concurrency-model)
- [Throughput](#throughput)
- [Design forks](#design-forks-and-the-call-it-made)
- [Failure taxonomy](#failure-taxonomy)
- [Resume protocol](#resume-protocol)
- [Observer surfaces](#observer-surfaces-progresslistener-and-flowprogressevent)
- [Test matrix](#test-matrix)
- [Coverage gate](#coverage-gate)
- [Demo reproducer](#demo-reproducer)

## Component map

```
com.example.downloader (orchestration)
    FileDownloader            -- Template Method lifecycle
    DownloadConfig            -- Builder DSL
    DownloadResult            -- Sealed result type
    ProgressListener          -- Observer (push)
    downloadAsFlow            -- Observer (Flow<ProgressEvent>)
    ResumeTracker             -- sidecar persistence
    ChunkPlan / planChunks    -- pure planner

com.example.downloader.http (transport)
    HttpRangeFetcher          -- Adapter / Port (orchestrator's only HTTP window)
    JdkHttpRangeFetcher       --   sole production adapter (zero deps beyond JDK)
    RetryingHttpRangeFetcher  -- Decorator over any HttpRangeFetcher
    HttpProbe / ProbeResult   -- HEAD discovery

com.example.downloader.retry (policy)
    RetryPolicy               -- Strategy
    ExponentialBackoffRetry   --   default for the CLI
    NoRetry                   --   used by tests and the single-GET fallback path
```

Production wiring (`Main.kt`):

```
RetryingHttpRangeFetcher(
    JdkHttpRangeFetcher(),
    ExponentialBackoffRetry(maxAttempts = 3, ...))
```

## Design patterns

Seven patterns earn their place. Each is named at the implementation site, not just here.

| Pattern             | Type                          | Where it lives                       | What it buys |
| ------------------- | ----------------------------- | ------------------------------------ | ------------ |
| **Template Method** | `FileDownloader`              | `FileDownloader.kt`                  | The lifecycle reads top-to-bottom: `validate -> probe -> checkEnvironment -> preallocate -> executeChunks -> verifyLength -> finalize`. Each step is a private `suspend` function. |
| **Adapter / Port**  | `HttpRangeFetcher`            | `http/HttpRangeFetcher.kt`           | Single seam between the orchestrator and HTTP. Tests substitute `FakeRangeFetcher` to drive every catch arm without spinning up a real server. |
| **Decorator**       | `RetryingHttpRangeFetcher`    | `http/RetryingHttpRangeFetcher.kt`   | Retry mechanics live in one composable wrapper, not threaded through the orchestrator or the JDK adapter. Compose-or-skip per call site. |
| **Strategy**        | `RetryPolicy`                 | `retry/RetryPolicy.kt`               | `ExponentialBackoffRetry` and `NoRetry` are interchangeable. The orchestrator's *runtime* fork between ranged-parallel and single-GET fallback (driven by `ProbeResult`) is itself a Strategy selection. |
| **Builder DSL**     | `DownloadConfig`              | `DownloadConfig.kt`                  | Idiomatic Kotlin trailing-lambda construction: `downloadConfig { chunkSize = 8.MiB; resume = true }`. |
| **Observer**        | `ProgressListener` + `Flow`   | `ProgressListener.kt`, `ProgressEvent.kt` | Reporting decoupled from download logic. Two surfaces: a push callback for the CLI, a `Flow<ProgressEvent>` for callers that prefer pull semantics. |
| **Sealed Result**   | `DownloadResult`              | `DownloadResult.kt`                  | Expected failure modes (`HttpError`, `LengthMismatch`, `IoFailure`, `Cancelled`, `RangeNotSupported`) are visible in the type system. `IllegalArgumentException` is reserved for programmer errors at the boundary. |

Patterns deliberately **not** used: Singleton, Abstract Factory, Visitor, Chain of Responsibility,
Mediator. None of them would pay rent here.

## Concurrency model

```kotlin
coroutineScope {
    val limited = Dispatchers.IO.limitedParallelism(config.parallelism)
    plan.map { chunk ->
        async(limited) { fetchAndWriteChunk(chunk) }
    }.awaitAll()
}
```

Three guarantees this gives us:

| Property                       | How |
| ------------------------------ | --- |
| Bounded in-flight chunks       | `limitedParallelism(N)` caps live HTTP connections at `N`, regardless of `plan.size`. |
| Structured cancellation        | `coroutineScope` waits for every child; one chunk's failure cancels siblings; the parent's cancellation cancels every child. |
| Deterministic teardown on fail | A failing chunk propagates up through `awaitAll`; the orchestrator's `runWithCleanup` deletes the partial file (unless `resume = true`). |

Disk writes use `FileChannel.write(ByteBuffer, position)`, which is thread-safe for *positional*
writes - we never touch the channel's position cursor, so concurrent chunks writing to disjoint
regions don't need a lock. Each in-flight chunk holds one 64 KiB transport buffer; total memory is
`O(parallelism)`, not `O(file size)`.

Cancellation runs through `runInterruptible` so a blocking `InputStream.read` is interrupted
cleanly. `classifyReadFailure` in `JdkHttpRangeFetcher` maps `ClosedChannelException` and
`InterruptedIOException` back to `CancellationException` so the structured-concurrency contract is
honored even when the JDK swallows the interrupt into a different exception type.

## Throughput

JMH benchmarks (`./gradlew jmh`; raw results land in `build/reports/jmh/results.json`).
Numbers below were observed on **Apple M5 / 10 cores / 32 GiB**, JDK 17.0.18 (Temurin via
Homebrew), JMH 1.36, with the Jetty file server bound to `127.0.0.1`. Mode is
`SingleShotTime`: each measurement iteration is one full 100 MiB download. 5 warmup + 10
measurement iterations × 1 fork. Errors are ±99.9% confidence.

Localhost is the deliberate test bed — it removes wide-area network latency so the numbers
reflect *implementation* overhead (HTTP, dispatch, write) rather than network capacity.
Absolute MiB/s will look very different over a real WAN; the *relative* trends across
parallelism, chunk size, and ranged-vs-fallback are what's interesting.

### Parallelism scaling (100 MiB file, 4 MiB chunks)

| Parallelism | Time (ms)        | Throughput (MiB/s) |
|-------------|------------------|--------------------|
| 1           | 111.5 ± 19.9     | 897                |
| 4           | 116.6 ± 26.7     | 858                |
| 8           | 128.4 ± 19.0     | 779                |
| 16          | 169.8 ± 22.5     | 589                |
| 32          | 176.0 ± 37.8     | 568                |

On loopback the curve is *inverted*: parallelism=1 is fastest. That is not a downloader bug —
it is loopback collapsing the latency that parallelism is designed to hide. With effectively
zero network delay, every additional in-flight chunk adds connection-pool contention,
coroutine dispatch, and disk-write interleaving without buying any latency-hiding in return.
The 1→8 differences sit close to the error bars; 16 and 32 are clearly slower than 1–8.

The result still shows the property the design *claims*: parallelism is bounded
(`limitedParallelism(N)`), so even at 32 the implementation degrades gracefully (~1.6×
slower than parallelism=1) rather than melting under contention. Over a real WAN, where each
GET pays tens to hundreds of milliseconds of round-trip latency, parallelism inverts back —
but loopback isn't where that gain shows up.

### Chunk size (100 MiB file, parallelism 8)

| Chunk size | Time (ms)       | Throughput (MiB/s) | Chunk count |
|------------|-----------------|--------------------|-------------|
| 1 MiB      | 166.2 ± 64.8    | 602                | 100         |
| 4 MiB      | 116.6 ± 27.0    | 858                | 25          |
| 8 MiB      | 114.0 ± 75.8    | 877                | 13          |
| 16 MiB     |  98.1 ± 34.2    | 1020               | 7           |

Bigger chunks win because the per-chunk fixed cost (HTTP request, header round-trip,
coroutine dispatch, sink-bounds checks) is paid once per chunk and amortized across the
chunk's bytes. 1 MiB pays it 100 times; 16 MiB pays it 7 times. The curve flattens past
4 MiB — the default 8 MiB sits in the knee where larger chunks no longer buy much but
smaller files run out of useful parallelism.

### Ranged vs single-GET fallback (100 MiB file, parallelism 8, 4 MiB chunks)

| Server                  | Time (ms)     | Throughput (MiB/s) |
|-------------------------|---------------|--------------------|
| `Accept-Ranges: bytes`  | 130.1 ± 29.8  | 769                |
| no `Accept-Ranges`      | 298.8 ± 37.5  | 335                |

The ranged path is ~2.3× faster on a 100 MiB localhost file. The fallback path is
single-stream by definition (one GET, one socket, sequential write) — its throughput is
the floor below which the downloader cannot fall on a server that doesn't advertise range
support. Confidence intervals don't overlap, so the gap is real.

### Profiling and re-running specific benchmarks

The build script bridges two Gradle properties to the JMH plugin so triage doesn't require
editing `build.gradle.kts`:

```bash
# Run only one benchmark class (substring match on JMH's include regex)
./gradlew jmh -Pjmh.includes=ParallelismScalingBenchmark

# Attach the GC profiler (alloc rate, GC count, GC pause time per @Param)
./gradlew jmh -Pjmh.profilers=gc

# Combine - filter and profile in one go
./gradlew jmh -Pjmh.includes=ChunkSizeBenchmark -Pjmh.profilers=gc,stack
```

Both properties accept comma-separated values. The full set of available profilers for
the bundled JMH 1.36 is shown by `java -jar build/libs/parallel-downloader-*-jmh.jar -lprof`
once `./gradlew jmhJar` has built the standalone harness; `gc` and `stack` are the two
that don't need extra JDK flags.

## Design forks (and the call it made)

| Fork | Choice | Why |
| ---- | ------ | --- |
| Where to keep retry state | Decorator on the fetcher port, not inside the JDK adapter or the orchestrator | Lets `NoRetry` plug in for tests and for the single-GET fallback path, where retry would be wasteful. |
| What the orchestrator sees on failure | A typed sealed `DownloadResult`, not exceptions | Forces the caller to consider failure modes. The CLI maps the sealed type to exit codes; library callers can `when`-exhaust without try/catch. |
| Per-chunk write strategy | `FileChannel.write(ByteBuffer, position)` directly to destination | No assemble-then-merge step, no `O(file)` memory, no temp files. |
| Single-GET fallback | Built in, transparent | Servers without `Accept-Ranges` are common enough; raising `RangeNotSupported` would be a usability tax. The reserved sealed variant exists for a future strict-mode flag. |
| HTTP version | JDK default (HTTP/2 with HTTP/1.1 fallback) | Earlier the adapter was pinned to HTTP/1.1; that pin is gone. Jetty's HTTP/2 connector is exercised in stress tests. |
| If-Range guard | Always sent when probe yields an ETag or `Last-Modified` | A mid-download file change otherwise silently splices two file versions. With `If-Range`, the server falls back to a 200 + full body, which the orchestrator treats as a fatal mismatch. |
| Resume sidecar location | `<destination>.partial` next to the destination file | Discoverable, deletable, no global state directory. |
| Partial file on transient failure | Delete (default), keep (resume mode) | The destination should never reflect a half-finished state unless the caller opts into resume. |
| Cancellation reporting | `CancellationException` re-thrown to caller; synthetic `Finished(Cancelled)` event for listeners | Honors structured concurrency for suspend callers; gives push-based UIs a non-exception path. |

## Failure taxonomy

The sealed `DownloadResult` is the public contract. Internal exception types map to it:

| Result                       | Triggered by                                                              |
| ---------------------------- | ------------------------------------------------------------------------- |
| `Success(path, bytes, took)` | All chunks written, length verified.                                      |
| `HttpError(status, phase)`   | Probe HEAD or chunk GET returned a non-success status that retry exhausted. `phase` distinguishes probe-time from chunk-time. |
| `RangeNotSupported`          | Reserved for a future strict-mode flag. Default config falls back to single-GET transparently. |
| `LengthMismatch(expected, actual)` | Bytes written disagree with `Content-Length`. Most often surfaced from the single-GET fallback path; the ranged path's pre-allocation makes this hard to reach. |
| `Cancelled`                  | Parent coroutine cancelled. Exception is re-thrown to the suspend caller; a `Finished(Cancelled)` event fires for listeners. |
| `IoFailure(cause)`           | Local disk error, raw `IOException` escaping a non-wrapping fetcher, or any other I/O surface that isn't a typed HTTP failure. |

`IllegalArgumentException` is reserved for *programmer errors* at the boundary (negative chunk
size, blank URL, destination is a directory). Those are caller bugs, not transport conditions.

## Resume protocol

```
1. Probe the server. Compute a validator: prefer ETag, fall back to Last-Modified.
2. If <dest>.partial exists, parse it. Validate:
     - same total bytes
     - same chunk size
     - same validator
   If any field disagrees, discard the partial file and start fresh.
3. Plan only the missing chunks. Open the destination in append mode if it's already partially
    written.
4. After each chunk completes, atomically rewrite the sidecar with the updated completed-set.
5. On Success: delete the sidecar.
   On transient failure: keep both sidecar and partial file.
   On validator mismatch: delete both - splicing two file versions is silent corruption.
```

Sidecar format (text, version-prefixed):

```
version=1
total=104857600
chunkSize=8388608
validator="W/\"abc-123\""
completed=0,1,2,5,7
```

Mismatch handling is paranoid by design: if the server's validator doesn't match the recorded one,
we'd rather throw away minutes of partial work than silently produce a Frankenstein file.

## Observer surfaces (`ProgressListener` and `Flow<ProgressEvent>`)

Two equivalent reporting APIs over the same internal event stream:

| API                       | Style       | Used by                            |
| ------------------------- | ----------- | ---------------------------------- |
| `ProgressListener`        | Push (callback) | `Main.kt`'s stderr renderer (`CliProgressPrinter`) |
| `downloadAsFlow(...)`     | Pull (Flow) | Library callers preferring `flowOn` / `collect` semantics |

Internally `downloadAsFlow` builds a private `ProgressListener` that pushes events into an
unbounded `Channel`, runs the download in a launched coroutine, and drains the channel into the
flow. Cancelling the collector cancels the download via structured concurrency.

Events emitted: `Started(total)`, `Progress(downloaded, total)`, `ChunkComplete(index)`,
`Finished(result)`. The terminal `Finished` event always fires - even on cancellation, where a
synthetic `Finished(Cancelled)` lets listener-based UIs distinguish cancelled-vs-failed without
catching `CancellationException`.

## Test matrix

122 unit + integration tests, 8 stress scenarios.

| Suite | Class | Coverage focus |
| ----- | ----- | -------------- |
| Planner | `ChunkPlanTest` | Pure boundary math: zero-byte files, exact-multiple sizes, off-by-one chunk boundaries. |
| Planner properties | `ChunkPlanPropertyTest` | jqwik feeds 1000 random `(totalBytes, chunkSize)` pairs per property and asserts contiguity, no gaps, total coverage, and ceil chunk-count. Generators are bounded so chunk count stays under ~1M to keep each property under tens of MB of allocation. |
| Sizes | `SizesTest` | `KiB`/`MiB`/`GiB` parsing and rejection of bad units. |
| Builder | `DownloadConfigTest` | DSL invariants and validation. |
| Orchestrator (golden path) | `FileDownloaderTest` | End-to-end against a real `com.sun.net.httpserver.HttpServer`. |
| Edge cases | `EdgeCaseTest` | 25 scenarios: empty file, length mismatch, range not supported, single-GET fallback, overwrite policy, conflicting probe headers, etc. |
| Defensive paths | `DefensivePathsTest` | Catch arms only reachable when the fetcher port leaks raw `IOException`s (covered via `FakeRangeFetcher`). |
| Cancellation | `CancellationTest` | Mid-flight cancellation cleans up the partial file and surfaces `Cancelled`. |
| Concurrency | `ConcurrencyTest` | `limitedParallelism(N)` honored under load; positional writes don't tear. |
| Per-chunk sink | `MakeChunkSinkTest` | Out-of-bounds writes rejected; sink rejects writes past the chunk's length. |
| If-Range | `IfRangeTest` | Validator forwarded; mid-download file change surfaces as a typed mismatch. |
| Resume sidecar | `ResumeSidecarTest` | Format round-trip, version mismatch handling, malformed input. |
| Resume orchestration | `ResumeTest` | Restart skips completed chunks; validator mismatch discards partial. |
| Flow API | `ProgressEventFlowTest` | All event types delivered; collector cancellation tears down the download. |
| HTTP adapter | `JdkHttpRangeFetcherTest` | Typed exception mapping; range-header construction. |
| Retry policy | `RetryPolicyTest` | Backoff schedule, max-attempt cap, non-retryable status pass-through. |
| Stress | `StressTest` | 1 GiB streaming download, 1024 chunks at parallelism 32, throttled-server timing, retry-budget chaos, mid-stream disconnect recovery, 1000-iteration leak hunt, 50-iteration cancellation cleanup. Capped at `-Xmx256m` to validate streaming behavior. |

Stress tests use the Jetty test fake (`JettyFileServer`). The default JDK `com.sun.net.httpserver`
deadlocks under `chunkSize=8MiB / parallelism=16`; that's a JDK-server bug, not a downloader bug,
documented in `StressTest.kt`.

## Coverage gate

The build fails if line coverage drops below 90% or branch coverage drops below 85% on production
code. The gate is wired through `tasks.check`:

```kotlin
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
    dependsOn(tasks.named("detekt"))
}
```

`Main.kt` and `Cli*.kt` are excluded from the gate - argv parsing, exit-code mapping, and the
stderr renderer are exercised end-to-end manually rather than via unit tests; their coverage isn't
informative.

## Demo reproducer

End-to-end run against an Apache `httpd` container - used to verify the CLI on a real HTTP server,
not a test fake:

```bash
mkdir -p /tmp/demo-files
dd if=/dev/urandom of=/tmp/demo-files/medium.bin bs=1M count=50

docker run --rm -d --name dl-demo \
    -p 8888:80 \
    -v /tmp/demo-files:/usr/local/apache2/htdocs/ \
    httpd:latest

./gradlew installDist
./build/install/parallel-downloader/bin/parallel-downloader \
    --chunk-size 4MiB --parallelism 8 \
    http://127.0.0.1:8888/medium.bin /tmp/dl-medium.bin

shasum -a 256 /tmp/demo-files/medium.bin /tmp/dl-medium.bin   # SHAs match
docker stop dl-demo
```

Sample output (real run; 50 MiB file, 8 ranged GETs at parallelism 8):

```
downloading...                                 4.0 /   50.0 MiB    8.0%   30.09 MiB/s    0.13s
downloading...                                14.0 /   50.0 MiB   28.0%   43.37 MiB/s    0.32s
downloading...                                50.0 /   50.0 MiB  100.0%  138.71 MiB/s    0.36s
✓ saved 52428800 bytes to /tmp/dl-medium.bin in 394.986083ms

c18c0796ed8575c484490bb2df2f4f4a1097eb1e33038c91c36cb8cb0916f54c  /tmp/demo-files/medium.bin
c18c0796ed8575c484490bb2df2f4f4a1097eb1e33038c91c36cb8cb0916f54c  /tmp/dl-medium.bin
```
