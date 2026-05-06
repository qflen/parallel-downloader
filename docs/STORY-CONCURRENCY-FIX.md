# The benchmark that wouldn't bend: a concurrency-bound that wasn't

> A debugging story. The whole project pivoted on one line of code.

The downloader's headline claim is simple: *N parallel ranged GETs hide latency.* Eight in
flight, eight times the latency budget, eight times the throughput when each request pays a
20 ms round-trip. That's the whole pitch.

The first version of `WanLatencyBenchmark` printed a flat line.

## The flat curve

```
Parallelism | Time (ms) | Throughput (MiB/s)
1           | ~810      | ~123
4           | ~810      | ~123
8           | ~810      | ~123
16          | ~810      | ~123
32          | ~810      | ~123
```

Same number, every row. The Jetty handler had `firstByteLatencyMillis = 20`, the file was
100 MiB, the chunks were 4 MiB - so 25 ranged GETs, each delayed by 20 ms server-side. At
parallelism 1 we'd serialize them: 25 × ~20 ms ≈ 500 ms of waiting plus body transfer.
At parallelism 25+, all 25 should share one ~20 ms RTT. The throughput should rise by an
order of magnitude.

It didn't move.

## The line of code that was wrong

```kotlin
withContext(Dispatchers.IO.limitedParallelism(config.parallelism)) {
    plan.map { chunk -> async { fetchAndWriteChunk(chunk) } }.awaitAll()
}
```

`Dispatchers.IO.limitedParallelism(N)` *looks* like it caps in-flight work. It doesn't.
What it caps is **dispatcher-slot occupancy**: the number of coroutines actively running
on a CPU thread at once. The moment a coroutine suspends - on `delay`, on a network read,
on any blocking I/O routed through `runInterruptible` - its slot is released. The next
queued coroutine dispatches immediately.

For network I/O, that's the wrong abstraction. A `fetchRange` call spends 95% of its
wall-clock time suspended on the HTTP body read. So `limitedParallelism(1)` lets one
coroutine *start* a request, and as soon as that request's body read suspends, the slot
is free and the next coroutine starts another request. With 25 chunks queued and one
"slot," all 25 GETs go out the door in microseconds. They all share the same 20 ms RTT.
`p=1` and `p=32` end up doing the exact same work.

The benchmark wasn't broken. The implementation was. The design claim ("bounded in-flight
chunks") was not what the runtime was doing.

## The fix

```kotlin
coroutineScope {
    val gate = Semaphore(config.parallelism)
    plan.map { chunk ->
        async(Dispatchers.IO) {
            gate.withPermit { fetchAndWriteChunk(chunk) }
        }
    }.awaitAll()
}
```

A `Semaphore` permit, unlike a dispatcher slot, is held **for the duration of the
suspending body** - including across the network read. The coroutine suspends, but it
keeps the permit. The next chunk waits at `acquire`. The bound now applies to the work
that actually costs: open sockets, in-flight requests, server-side load.

## The corrected curve

```
Parallelism | Time (ms)       | Throughput (MiB/s)
1           | 794.2 ± 34.5    | 126
4           | 267.1 ± 16.0    | 374
8           | 189.3 ± 26.4    | 528
16          | 148.0 ± 26.0    | 676
32          | 123.1 ± 21.8    | 812
```

`p=32` is ~6.5× faster than `p=1`, and the curve is monotonically decreasing across the
sweep. With 25 chunks and `p=1`, the download serializes through 25 × ~20 ms RTT plus
body transfer ≈ 794 ms; at `p=32` (capped to chunk count) all 25 chunks share one ~20 ms
RTT plus transfer ≈ 123 ms. That is the curve the design predicts and the curve the
implementation finally delivers.

A regression guard sits in `ConcurrencyTest`: a download with a slow server is asserted
to keep more than `parallelism` chunks in flight at no point. If a future "cleanup" puts
`limitedParallelism` back, the test fails before the benchmark would.

## The lesson

`limitedParallelism` and `Semaphore` look interchangeable in the docs. They aren't. They
solve different problems:

| Tool | Bounds | Use it for |
|------|--------|------------|
| `Dispatchers.IO.limitedParallelism(N)` | concurrent CPU-bound work scheduled on the dispatcher | bounding a thread-pool slice for compute work that doesn't suspend |
| `Semaphore(N).withPermit { ... }` | concurrent occupancy of a critical region, including across suspensions | bounding **in-flight resources** (open sockets, file descriptors, server-side load) |

A second-order lesson: **benchmark before you ship the claim.** The flat curve was the
single piece of evidence that contradicted the design doc. Without
`WanLatencyBenchmark`, the `limitedParallelism` line would have shipped, the docs would
have read "bounded in-flight chunks", and a future user trying to debug "why does
parallelism not help?" would have lost a day to it. The benchmark earned its place by
catching the one thing the design doc got wrong.

The same shape of bug - a concurrency primitive that was *almost* the right one - is the
class of mistake `Lincheck` is meant to catch sooner. See
[`RateLimiterLincheckTest`](../src/test/kotlin/com/example/downloader/RateLimiterLincheckTest.kt)
and
[`ResumeTrackerLincheckTest`](../src/test/kotlin/com/example/downloader/ResumeTrackerLincheckTest.kt)
for the modeled invariants the project verifies before shipping.

## See also

- [DESIGN.md - Concurrency model](DESIGN.md#concurrency-model)
- [DESIGN.md - Parallelism scaling under 20 ms server-side latency](DESIGN.md#parallelism-scaling-under-20-ms-server-side-latency-100-mib-4-mib-chunks)
- [`ConcurrencyTest.kt`](../src/test/kotlin/com/example/downloader/ConcurrencyTest.kt) - the regression guard
- [`WanLatencyBenchmark.kt`](../src/bench/kotlin/com/example/downloader/bench/WanLatencyBenchmark.kt) - the benchmark that surfaced the bug
