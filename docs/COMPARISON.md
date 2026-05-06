# Comparison: parallel-downloader vs curl, aria2c, wget

The numbers in [`DESIGN.md`'s throughput tables](DESIGN.md#throughput) describe the project's
internal scaling: parallelism, chunk size, ranged-vs-fallback, WAN latency. They're
parallel-downloader against itself. This document anchors those MiB/s numbers in something
a reader recognizes - the same workload run with three popular off-the-shelf tools:

- **`curl --parallel-max 8`**: curl's built-in fan-out. Reliable, ubiquitous, single-process.
- **`aria2c -x 8 -s 8`**: aria2c's multi-connection ranged-GET mode. The most direct apples-
  to-apples comparison; like parallel-downloader, it issues N ranged GETs in parallel.
- **`wget`**: the unparallelized baseline. One connection, no range parallelism. Sets the
  floor.

All four are pointed at the same Jetty fixture (see [`run-comparison.sh`](run-comparison.sh)
and the `:jettyFixture` Gradle task), so differences between rows reflect tool behavior, not
network noise. The fixture's `firstByteLatencyMillis` knob is the same one
[`WanLatencyBenchmark`](../src/bench/kotlin/com/example/downloader/bench/WanLatencyBenchmark.kt)
uses, so a 20 ms tier here corresponds to the same WAN-shaped workload the design's
parallelism-scaling table inverts under.

## Methodology

```bash
./gradlew installDist                         # builds the parallel-downloader CLI
./docs/run-comparison.sh                      # runs the matrix; prints a markdown table
./docs/run-comparison.sh --sizes 10 --latency 0   # smaller sweep for iteration
```

The script:
1. Generates random test files of 10 / 100 / 1024 MiB under a tmpdir.
2. Brings up the Jetty fixture (port 8090, configurable latency) via `./gradlew jettyFixture`.
3. For each (size, latency) cell, runs `hyperfine --warmup 1 --runs 5` over the four tools.
4. Tears down the fixture and prints a markdown table.

Each cell shows mean throughput (MiB/s) and mean wall time. hyperfine's `--warmup 1` lets the
JDK HttpClient (used by parallel-downloader) and Jetty warm up before measurement; `--runs 5`
gives a usable mean without dominating wall-clock time.

## Results

> **The table below is a placeholder.** Run `./docs/run-comparison.sh` in your environment
> to populate it with real numbers, then paste them here. Absolute MiB/s varies with CPU,
> JDK version, and disk; the relative shape - which tools win at which file sizes - is what
> the comparison is meant to anchor. We do not commit hand-fabricated numbers.

### `firstByteLatencyMillis = 0` (loopback baseline)

| Size | parallel-downloader | curl --parallel-max 8 | aria2c -x 8 -s 8 | wget |
|------|---------------------|-----------------------|-------------------|------|
| 10 MiB   | _run script_ | _run script_ | _run script_ | _run script_ |
| 100 MiB  | _run script_ | _run script_ | _run script_ | _run script_ |
| 1024 MiB | _run script_ | _run script_ | _run script_ | _run script_ |

### `firstByteLatencyMillis = 20` (WAN-shaped per-request latency)

| Size | parallel-downloader | curl --parallel-max 8 | aria2c -x 8 -s 8 | wget |
|------|---------------------|-----------------------|-------------------|------|
| 10 MiB   | _run script_ | _run script_ | _run script_ | _run script_ |
| 100 MiB  | _run script_ | _run script_ | _run script_ | _run script_ |
| 1024 MiB | _run script_ | _run script_ | _run script_ | _run script_ |

## What to look for

- **At loopback (0 ms latency)** the contest is mostly about per-tool overhead: connection
  pool sizing, write-syscall efficiency, and how each tool buffers. The single-stream tools
  (wget, curl without parallel-max) often look surprisingly competitive because there's no
  latency to hide. The design's own
  [parallelism-scaling table](DESIGN.md#parallelism-scaling-100-mib-4-mib-chunks) is the
  loopback view of parallel-downloader against itself.
- **At 20 ms first-byte latency** the multi-connection tools should pull ahead by roughly
  N×, where N is the parallelism. parallel-downloader and aria2c at parallelism 8 should
  both beat curl's `--parallel-max 8` (which only parallelizes across separate URLs, not
  across ranged GETs of one URL) and dominate wget. This tier is the same shape as the
  WAN-latency benchmark's curve; if parallel-downloader doesn't track aria2c here, that's
  the regression to investigate.
- **Across file sizes**, the per-request fixed cost amortizes - the 1 GiB row should look
  more like the asymptote, while the 10 MiB row reflects startup overhead in each tool. A
  large gap on 10 MiB that closes at 1 GiB usually means a slow startup path
  (connection pool warm-up, JIT, etc.).

## Tool versions (populate after running)

```
hyperfine          : <output of `hyperfine --version`>
curl               : <output of `curl --version | head -1`>
aria2c             : <output of `aria2c --version | head -1`>
wget               : <output of `wget --version | head -1`>
parallel-downloader: HEAD <git rev-parse --short HEAD>
JDK                : <output of `java -version 2>&1 | head -1`>
OS / CPU           : <e.g., macOS 14.4 / Apple M2>
```

The `run-comparison.sh` script prints all of these at the start of each run, so updating
this section after a regeneration is a copy-paste.

## See also

- [`DESIGN.md` - Throughput](DESIGN.md#throughput): the project's internal benchmarks.
- [`STORY-CONCURRENCY-FIX.md`](STORY-CONCURRENCY-FIX.md): why the WAN-latency tier shape
  matters for parallel-downloader specifically.
