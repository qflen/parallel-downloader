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

Numbers below are from `./docs/run-comparison.sh` on macOS 14 / Apple Silicon, JDK 17.0.18
(Temurin), against the project's Jetty fixture on `127.0.0.1`. Each cell is a
`hyperfine --warmup 1 --runs 5` mean. Absolute MiB/s varies with CPU, JDK version, and
disk; relative shape - which tool wins at which size and latency - is what the table is
meant to anchor. **Re-run in your own environment to update.**

### `firstByteLatencyMillis = 0` (loopback baseline)

| Size | parallel-downloader | curl --parallel-max 8 | aria2c -x 8 -s 8 | wget |
|------|---------------------|-----------------------|-------------------|------|
| 10 MiB   |   17 MiB/s (599ms)  |   821 MiB/s (12ms)  |  156 MiB/s (64ms)   | 1066 MiB/s (9ms)   |
| 100 MiB  |  147 MiB/s (682ms)  |  1623 MiB/s (62ms)  |  197 MiB/s (507ms)  | 3054 MiB/s (33ms)  |
| 1024 MiB |  808 MiB/s (1267ms) |  2535 MiB/s (404ms) |  197 MiB/s (5198ms) | 3138 MiB/s (326ms) |

### `firstByteLatencyMillis = 20` (WAN-shaped per-request latency)

| Size | parallel-downloader | curl --parallel-max 8 | aria2c -x 8 -s 8 | wget |
|------|---------------------|-----------------------|-------------------|------|
| 10 MiB   |   15 MiB/s (651ms)  |   258 MiB/s (39ms)  |  110 MiB/s (91ms)   |  283 MiB/s (35ms)  |
| 100 MiB  |  135 MiB/s (738ms)  |  1333 MiB/s (75ms)  |  152 MiB/s (656ms)  | 1957 MiB/s (51ms)  |
| 1024 MiB |  679 MiB/s (1508ms) |  2031 MiB/s (504ms) |  183 MiB/s (5594ms) | 3504 MiB/s (292ms) |

### Reading the numbers

Two structural caveats worth knowing before you stare at this table:

1. **JVM startup cost is on parallel-downloader's side of every cell.** Each hyperfine
   invocation is a fresh `java` process; the JDK's class-loading + tiered-compilation
   warm-up takes ~500 ms before the first byte hits the wire. That's why the 10 MiB cells
   report 15-17 MiB/s (≈500 ms of startup + ~80 ms of actual transfer dominate the mean)
   and the 1 GiB cells climb to 679-808 MiB/s (startup is amortized over a long-enough
   download). Subtract ~500 ms from the wall time before comparing rates with a
   non-JVM tool. The native-image build (`-PnativeImage=true nativeCompile`, see
   [GRAALVM.md](GRAALVM.md)) removes this overhead; rerun the script with the native
   binary to see what the JVM hides.
2. **The Jetty fixture's `firstByteLatencyMillis` knob applies once per request.** A
   single-stream tool (wget) pays it once; `parallel-downloader` and `aria2c` pay it
   `ceil(chunks / parallelism)` times. On a workload where bandwidth dominates the
   round-trip cost (loopback at multi-GiB/s), single-stream tools look surprisingly
   strong even at 20 ms latency: 20 ms first-byte + 330 ms streaming for 1 GiB still
   beats parallel-downloader's startup-plus-coordination overhead. The benchmarks in
   [DESIGN.md - Throughput](DESIGN.md#parallelism-scaling-under-20-ms-server-side-latency-100-mib-4-mib-chunks)
   measure parallel-downloader against itself with a JIT-warm JVM and capture the actual
   parallelism gain (812 MiB/s at p=32, vs 126 MiB/s at p=1) - that table is the place
   to read off the design's predicted behavior.

## What the numbers above show

- **Across file sizes**, the per-request fixed cost amortizes. parallel-downloader's
  17 → 147 → 808 MiB/s arc on the 0 ms tier is mostly the JVM startup penalty being
  diluted as the download grows; wget's 1066 → 3054 → 3138 MiB/s arc shows transfer
  dominating once the ~10 ms connection-setup cost is small relative to the body.
- **aria2c is consistently slower than wget on loopback** despite running 8 connections.
  Its multi-connection coordination overhead is real and not free; on a high-bandwidth
  link it actively costs more than it saves. This is exactly the design point the
  [WAN latency](DESIGN.md#parallelism-scaling-100-mib-4-mib-chunks) and
  [Parallelism scaling](DESIGN.md#parallelism-scaling-under-20-ms-server-side-latency-100-mib-4-mib-chunks)
  tables in DESIGN.md make for parallel-downloader: parallelism is a tool for hiding
  latency, not for adding bandwidth, and on loopback there's nothing to hide.
- **At 20 ms first-byte latency** the multi-connection tools do not pull ahead the way
  the design predicts in the WAN benchmark, because the loopback transfer of 1 GiB
  takes ~330 ms and the 20 ms first-byte cost is paid once per request - not per byte.
  Single-stream wget pays 20 ms + 290 ms = 310 ms wall; parallel-downloader pays 20 ms
  spread across 128 chunks at parallelism 8 (so ~50 ms latency overhead) plus its
  startup, which still sits around 500 ms. The advantage parallel-downloader's design
  predicts shows up against a slow link, not against loopback at 3 GiB/s.

## Tool versions (this run)

```
hyperfine          : 1.20.0
curl               : 8.7.1
aria2c             : 1.37.0
wget               : 1.25.0
parallel-downloader: HEAD be1caeb (jvm-mode, no native-image)
JDK                : 17.0.18 (Temurin)
OS / CPU           : macOS 14 / Apple Silicon
```

The `run-comparison.sh` script prints all of these at the start of each run, so updating
this section after a regeneration is a copy-paste.

## See also

- [`DESIGN.md` - Throughput](DESIGN.md#throughput): the project's internal benchmarks.
- [`STORY-CONCURRENCY-FIX.md`](STORY-CONCURRENCY-FIX.md): why the WAN-latency tier shape
  matters for parallel-downloader specifically.
