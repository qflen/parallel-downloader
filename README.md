# parallel-downloader

[![CI](https://github.com/qflen/parallel-downloader/actions/workflows/ci.yml/badge.svg)](https://github.com/qflen/parallel-downloader/actions/workflows/ci.yml)
[![Coverage](https://img.shields.io/badge/coverage-90%25%20line%20%2F%2085%25%20branch-brightgreen)](docs/DESIGN.md#coverage-gate)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7f52ff?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/JDK-17%20%7C%2021-007396?logo=openjdk&logoColor=white)](https://adoptium.net)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSES.md)

> A Kotlin CLI and library that downloads a single HTTP(S) file as N parallel byte-range GETs,
> streams each chunk straight to disk via positional `FileChannel` writes, and falls back to a
> single GET when the server doesn't advertise `Accept-Ranges`. Runtime classpath is
> `kotlinx-coroutines-core` only.

![demo](docs/demo.gif)

> 50 MiB file fetched from a local Apache `httpd` at `http://localhost:8080/my-local-file.txt`,
> 8 ranged GETs in parallel, with `--sha256` verifying the result inline. The full reproducer
> (docker run, dd-the-source, installDist) is in
> [docs/DESIGN.md#demo-reproducer](docs/DESIGN.md#demo-reproducer); the GIF is composed by
> [docs/make_demo_gif.sh](docs/make_demo_gif.sh).

---

## Highlights

- **Pure-JDK runtime, zero extra dependencies.** The HTTP layer wraps `java.net.http.HttpClient`;
  the only library on the production classpath is `kotlinx-coroutines-core`.
- **171 unit + integration tests, 11 jqwik properties, 8 stress scenarios.** JaCoCo gate at
  90% line / 85% branch. Pitest reports a 67% mutation kill rate on production code.
- **Privacy by design.** No `User-Agent` / `Referer` / `Cookie` / `Authorization` / `From`
  headers, no env reads, no telemetry beacons. Every claim has a test in `AnonymityTest`.
- **Two layered PII checks on every PR.** A regex `PiiScanner` wired into `gradle check` and
  an LLM review workflow (`prompts/pii_review.md`) for shape-detectable leaks the regex misses.
- **Multi-platform CI matrix.** `ubuntu` / `macos` / `windows` x JDK 17 / 21. Byte-reproducible
  jar / dist archives. JMH benchmarks + license report shipped with the project.

## Table of contents

- [Quick start](#quick-start)
- [CLI](#cli)
- [Library use](#library-use)
- [Architecture](#architecture)
- [Resume](#resume)
- [Privacy](#privacy)
- [What's tested](#whats-tested)
- [Build and verify](#build-and-verify)
- [Limitations](#limitations)
- [Further reading](#further-reading)

## Quick start

**Prerequisite:** JDK 17 or 21 on `PATH` (`java -version` should report 17.x or 21.x).
The Gradle wrapper handles Gradle itself.

Clone and run the test suite:

```bash
git clone https://github.com/qflen/parallel-downloader
cd parallel-downloader

./gradlew test           # 171 unit + 11 jqwik property tests, ~30s warm
./gradlew check          # test + detekt + JaCoCo 90/85 gate + PII scan, ~1 min
./gradlew stressTest     # 8 stress scenarios under -Xmx256m, ~30s
```

Build the CLI binary and run a real download:

```bash
./gradlew installDist
./build/install/parallel-downloader/bin/parallel-downloader \
    https://example.com/big.bin /tmp/big.bin \
    --chunk-size 8MiB --parallelism 8 \
    --sha256 c18c0796ed8575c484490bb2df2f4f4a1097eb1e33038c91c36cb8cb0916f54c
```

Stderr ends with `✓ saved 50.0 MiB to <path> in 394.99ms` and
`✓ sha256 matches: <hex>` on a clean run. Byte counts are formatted via the
[`humanBytes`](src/main/kotlin/com/example/downloader/Sizes.kt) helper, picking the
largest binary unit that keeps the integer part below 1024 (`B` / `KiB` / `MiB` / `GiB` / `TiB`).

## CLI

```
parallel-downloader URL DEST [OPTIONS]
```

| Flag | Default | Purpose |
| ---- | ------- | ------- |
| `--chunk-size SIZE` | `8MiB` | Bytes per ranged GET. Accepts `B`/`KB`/`KiB`/`MB`/`MiB`/`GB`/`GiB`. |
| `--parallelism N` | `8` | Maximum chunks in flight at once. Bound is enforced by a coroutine `Semaphore`. |
| `--retries N` | `3` | Per-chunk retries on transient failures (5xx, premature EOF). |
| `--sha256 HEX` | off | 64-hex SHA-256 of the file. On match: `✓ sha256 matches: ...`. On mismatch: `✗ checksum mismatch: ...` and exit 1. |
| `--rate-limit RATE` | off | Total throughput cap across all chunks. Accepts size suffixes plus optional `/s` or `/sec` (e.g. `5MB/s`, `1MiB/s`). Leaky-bucket: idle periods don't accumulate burst credit. |

**Exit codes:** `0` success, `1` HTTP-level failure or `--sha256` mismatch, `2` local I/O failure,
`64` usage error.

## Library use

```kotlin
val downloader = FileDownloader(
    RetryingHttpRangeFetcher(JdkHttpRangeFetcher(), ExponentialBackoffRetry(maxAttempts = 3))
)

val result: DownloadResult = downloader.download(
    URL("https://example.com/big.bin"),
    Path.of("/tmp/big.bin"),
    downloadConfig {
        chunkSize = 8.MiB
        parallelism = 8
        rateLimitBytesPerSec = 5L * 1024L * 1024L
        telemetry = MyOpsCounter()
    }
)

when (result) {
    is DownloadResult.Success         -> println("ok: ${result.bytes} bytes in ${result.elapsed}")
    is DownloadResult.HttpError       -> println("http ${result.status} during ${result.phase}")
    is DownloadResult.LengthMismatch  -> println("length mismatch")
    is DownloadResult.IoFailure       -> println("disk: ${result.cause}")
    DownloadResult.Cancelled          -> println("cancelled")
    DownloadResult.RangeNotSupported  -> println("server can't do ranges")
}
```

The `Flow<ProgressEvent>` API is also available via `downloader.downloadAsFlow(...)` for
callers that prefer pull semantics over the push-based `ProgressListener`.

## Architecture

[![Architecture](docs/architecture.png)](docs/architecture.svg)

> Click for the [SVG](docs/architecture.svg). Source: [docs/architecture.d2](docs/architecture.d2)
> (render: `d2 --layout=elk docs/architecture.d2 docs/architecture.svg`).

| Component | Pattern | Role |
| --------- | ------- | ---- |
| `FileDownloader` | Template Method | Orchestrates `validate -> probe -> preallocate -> executeChunks -> verifyLength -> finalize`. |
| `HttpRangeFetcher` | Adapter / Port | Single HTTP seam; `JdkHttpRangeFetcher` wraps `java.net.http.HttpClient`. |
| `RetryingHttpRangeFetcher` | Decorator | Wraps any `HttpRangeFetcher`; applies a `RetryPolicy`. |
| `RetryPolicy` | Strategy | `ExponentialBackoffRetry` (CLI default) or `NoRetry`. |
| `DownloadConfig` | Builder DSL | `downloadConfig { chunkSize = 8.MiB; resume = true }`. |
| `ProgressListener` / `downloadAsFlow` / `Telemetry` | Observer | Push, pull (`Flow<ProgressEvent>`), or privacy-typed metrics (counters and indices only). |
| `DownloadResult` | Sealed result | `Success` / `HttpError` / `LengthMismatch` / `IoFailure` / `Cancelled` / `RangeNotSupported`. |

Each pattern is named at its implementation site; `grep -r "Pattern:" src/main/kotlin` lists all
seven. Tradeoffs ("why a Decorator and not retry-in-the-orchestrator?", "why a sealed result and
not exceptions?") are documented in
[DESIGN.md](docs/DESIGN.md#design-forks-and-the-call-it-made).

### Concurrency

```kotlin
coroutineScope {
    val gate = Semaphore(config.parallelism)
    plan.map { chunk ->
        async(Dispatchers.IO) { gate.withPermit { fetchAndWriteChunk(chunk) } }
    }.awaitAll()
}
```

The `Semaphore` permit is held for the full `fetchRange` suspension, so the bound applies to
in-flight HTTP requests rather than just dispatcher-slot occupancy. (`limitedParallelism`
releases its slot when the body read suspends, letting in-flight requests grow unbounded; see
[DESIGN.md#concurrency-model](docs/DESIGN.md#concurrency-model).) `FileChannel.write(ByteBuffer,
position)` is documented thread-safe for positional writes, so disjoint chunks need no locking.
Per-chunk transport buffer is 64 KiB; total memory is `O(parallelism * 64 KiB)`, not
`O(file size)`, validated by the stress harness streaming a 1 GiB download under a 256 MiB
heap cap.

## Resume

```kotlin
FileDownloader(fetcher).download(url, dest, downloadConfig { resume = true })
```

A sidecar at `<dest>.partial` records the chunk geometry and the server's `ETag` (or
`Last-Modified`). On a later call with the same destination, the orchestrator re-probes the
server, validates the recorded entity tag against the current one, and re-fetches only the
missing chunks. On validator mismatch the sidecar and partial file are discarded; splicing
two file versions would be silent corruption. Format and protocol details:
[DESIGN.md#resume-protocol](docs/DESIGN.md#resume-protocol).

## Privacy

The full policy lives in [PRIVACY.md](PRIVACY.md);
[`AnonymityTest`](src/test/kotlin/com/example/downloader/AnonymityTest.kt) is the executable
form and runs as part of `gradle check`. Two layered checks enforce it on every change:

- **`./gradlew piiScan`** — static regex pass over `src/main`, `src/test`, `src/stressTest`,
  `src/bench` for emails, non-loopback IPs, identifying env reads, hardcoded user paths, and
  `InetAddress.getLocalHost`. Wired into `gradle check`. Test fixtures live in
  `src/test/resources/piiscanner-fixtures/`.
- **LLM PII review** — `.github/workflows/llm-pii-review.yml` sends each PR's changed
  `.kt` / `.kts` / `.java` / `.yml` / `.md` patch through the prompt at
  [`prompts/pii_review.md`](prompts/pii_review.md), aggregates findings, and posts a single PR
  comment if anything is flagged. Catches shape-detectable leaks a regex can't (URL hosts in
  log strings, subtle path concatenations, validator strings that quote user input). The
  prompt has been rehearsed on four synthetic diffs in
  [`prompts/rehearsal-examples.md`](prompts/rehearsal-examples.md).

The workflow exits 0 cleanly when `ANTHROPIC_API_KEY` is unset, so CI stays green until the
secret is wired into the repo.

## What's tested

| Suite | Count | Highlights |
| ----- | ----- | ---------- |
| Unit + integration | 171 tests | Real `com.sun.net.httpserver.HttpServer` test fake with fault-injection knobs (latency, throttling, mid-stream disconnect, status overrides, malformed `Content-Range`). |
| jqwik properties | 11 properties | Chunk-plan algebra (1000 random `(totalBytes, chunkSize)` pairs each); resume-sidecar round-trip stability and version-mismatch handling. |
| Stress scenarios | 8 | 1 GiB streaming download under `-Xmx256m`, 1024 chunks at parallelism 32, throttled-server timing, retry-budget chaos, mid-stream disconnect recovery, 1000-iteration leak hunt, 50-iteration cancellation cleanup. |
| Mutation testing | 67% kill rate | Pitest, on-demand via `./gradlew pitest`. Same exclusions as JaCoCo. |
| JMH benchmarks | 4 | Parallelism scaling, chunk size sweep, ranged-vs-fallback, WAN-latency parallelism scaling. |

The stress suite uses an embedded Jetty (necessary at the `chunkSize=8 MiB / parallelism=16`
geometry, where the JDK stdlib server deadlocks under load).

## Build and verify

| Command | What it does |
| ------- | ------------ |
| `./gradlew build` | compile + detekt + tests + JaCoCo gate + LICENSES.md refresh + reproducible jar |
| `./gradlew check` | the verification gate: test + detekt + JaCoCo + piiScan |
| `./gradlew piiScan` | static PII regex scanner across `src/main`, `src/test`, `src/stressTest`, `src/bench` |
| `./gradlew test` | 171 tests + 11 jqwik properties (~3s warm) |
| `./gradlew stressTest` | 8 stress scenarios under `-Xmx256m` (~30s) |
| `./gradlew pitest` | Pitest mutation testing on production code (~3 min) |
| `./gradlew jmh` | run all JMH benchmarks |
| `./gradlew jmh -Pjmh.includes=ParallelismScalingBenchmark` | filter to one benchmark class |
| `./gradlew jmh -Pjmh.profilers=gc` | attach a JMH profiler |
| `./gradlew installDist` | builds `./build/install/parallel-downloader/bin/parallel-downloader` |
| `./gradlew syncLicensesMd` | regenerate `LICENSES.md` from the runtime classpath |

## Limitations

- Per-chunk retry replays bytes from the chunk's start. `If-Range` is on by default whenever
  the server advertises a validator, so a mid-download file change fails loudly via
  `HttpError(200, CHUNK)` rather than splicing two versions.
- Single-GET fallback isn't retried. The retry decorator sits at the fetcher layer, but the
  fallback path is reserved for servers that don't advertise `Accept-Ranges`; those tend to
  be either reliable static-file servers or fundamentally broken.
- HTTPS uses JDK defaults. No custom certificate or hostname-verification hooks.
- The suspend `download()` rethrows `CancellationException` per structured concurrency.
  Listener-based UIs see a synthetic `Finished(Cancelled)` event so they can distinguish
  cancelled-vs-failed without observing the exception.

## Further reading

- [docs/DESIGN.md](docs/DESIGN.md): design patterns, concurrency model, design forks, failure
  taxonomy, resume protocol, telemetry boundary, throughput numbers, test matrix, coverage gate.
- [PRIVACY.md](PRIVACY.md): the privacy policy and what could change it.
- [LICENSES.md](LICENSES.md): runtime-classpath license inventory.
- [prompts/pii_review.md](prompts/pii_review.md) and
  [prompts/rehearsal-examples.md](prompts/rehearsal-examples.md): the LLM PII review prompt and
  its rehearsal evidence.
- [docs/demo.gif](docs/demo.gif) source: [docs/make_demo_gif.sh](docs/make_demo_gif.sh).
