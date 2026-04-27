# parallel-downloader

Kotlin downloader that fetches a file as parallel HTTP byte ranges, streams every chunk straight
to disk via positional `FileChannel` writes, and falls back to a single GET when the server
doesn't advertise `Accept-Ranges`.

```
Tests:    114 unit/integration + 8 stress     |    Coverage gate: 90% line / 85% branch
Runtime:  JDK 17, kotlinx-coroutines (only)   |    Static analysis: detekt
```

> Full architecture, design patterns, concurrency model, failure taxonomy, resume protocol,
> and test matrix live in **[docs/DESIGN.md](docs/DESIGN.md)**. This README is the outline.

---

## Demo

50 MiB file, 8 ranged GETs at parallelism 8, fetched from a local Apache `httpd`:

```bash
./gradlew installDist
./build/install/parallel-downloader/bin/parallel-downloader \
    --chunk-size 4MiB --parallelism 8 \
    http://127.0.0.1:8888/medium.bin /tmp/dl-medium.bin
```

```
downloading...    4.0 /   50.0 MiB    8.0%   30.09 MiB/s    0.13s
downloading...   14.0 /   50.0 MiB   28.0%   43.37 MiB/s    0.32s
downloading...   50.0 /   50.0 MiB  100.0%  138.71 MiB/s    0.36s
✓ saved 52428800 bytes to /tmp/dl-medium.bin in 394.986083ms

c18c07...463b  /tmp/demo-files/medium.bin
c18c07...463b  /tmp/dl-medium.bin           # SHAs match
```

Reproducer (Apache `httpd` in Docker) is in [docs/DESIGN.md#demo-reproducer](docs/DESIGN.md#demo-reproducer).
A [VHS](https://github.com/charmbracelet/vhs) script for regenerating a GIF lives at [docs/demo.tape](docs/demo.tape).

## Quick start

```bash
./gradlew build         # compile, detekt, 114 tests, JaCoCo gate
./gradlew test          # 114 tests, ~2s warm
./gradlew stressTest    # 8 heavy scenarios under -Xmx256m, ~30s
./gradlew installDist   # builds ./build/install/parallel-downloader/bin/parallel-downloader
```

CLI flags: `--chunk-size SIZE` (default `8MiB`), `--parallelism N` (default `8`),
`--retries N` (default `3`). Exit codes: `0` success, `1` HTTP, `2` I/O, `64` usage, `130`
cancelled. Progress to stderr at ~10 Hz.

## Architecture

[![Architecture](docs/architecture.png)](docs/architecture.svg)

> Click for the [SVG](docs/architecture.svg). Source: [docs/architecture.d2](docs/architecture.d2).

| Component                  | Pattern         | What it does |
| -------------------------- | --------------- | ------------ |
| `FileDownloader`           | Template Method | Orchestrates `validate -> probe -> preallocate -> executeChunks -> verifyLength`. |
| `HttpRangeFetcher` (port)  | Adapter         | Sole HTTP seam. `JdkHttpRangeFetcher` is the only production adapter. |
| `RetryingHttpRangeFetcher` | Decorator       | Wraps any `HttpRangeFetcher`, applies a `RetryPolicy`. |
| `RetryPolicy`              | Strategy        | `ExponentialBackoffRetry` (default) or `NoRetry`. |
| `DownloadConfig`           | Builder DSL     | `downloadConfig { chunkSize = 8.MiB; resume = true }`. |
| `ProgressListener` / `downloadAsFlow` | Observer | Push (callback) and pull (`Flow<ProgressEvent>`) reporting. |
| `DownloadResult`           | Sealed result   | `Success` / `HttpError` / `LengthMismatch` / `IoFailure` / `Cancelled` / `RangeNotSupported`. |

Concurrency in one block:

```kotlin
coroutineScope {
    val limited = Dispatchers.IO.limitedParallelism(config.parallelism)
    plan.map { chunk -> async(limited) { fetchAndWriteChunk(chunk) } }.awaitAll()
}
```

Disk writes use `FileChannel.write(ByteBuffer, position)` - thread-safe for positional writes, so
disjoint chunks don't need a lock; memory is `O(parallelism)` not `O(file size)`.

## Resume

```kotlin
FileDownloader(fetcher).download(url, dest, downloadConfig { resume = true })
```

Sidecar at `<dest>.partial`, validated against the server's current ETag (or `Last-Modified`). On
validator mismatch, the sidecar and partial file are discarded - splicing two file versions would
be silent corruption. Format and protocol: [docs/DESIGN.md#resume-protocol](docs/DESIGN.md#resume-protocol).

## Limitations

- Per-chunk retry replays bytes from chunk start. `If-Range` is on by default; mid-download file
  change fails loudly instead of splicing two versions.
- Single-GET fallback is not retried (servers without `Accept-Ranges` are reliable or fundamentally
  broken; retry rarely helps).
- HTTPS uses JDK defaults - no custom cert / hostname verification hooks.
- `CancellationException` is rethrown to suspend callers; listener-based UIs see a synthetic
  `Finished(Cancelled)` event.

---

**Read next:** [docs/DESIGN.md](docs/DESIGN.md) — design patterns, concurrency model, design forks,
failure taxonomy, observer surfaces, resume protocol, test matrix, coverage gate.
