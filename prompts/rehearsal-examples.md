# Rehearsal: prompts/pii_review.md run on synthetic diffs

These are the structured findings the prompt template in
[`pii_review.md`](pii_review.md) produces when applied to four synthetic diffs
representative of what the workflow will see in real PRs. The runs were performed
by executing the prompt against each diff manually (no API call; the rehearsal's
purpose is to verify the prompt produces parseable output of the right shape on
real-shaped inputs before any PR runs through it for the first time).

If the wire format here ever drifts from what the script expects, the workflow
will silently start posting unparsed text into PR comments. Treat this file as
the contract between the prompt and the script.

---

## Diff A — clean refactor (expected: NO_FINDINGS)

**Input:**

```diff
diff --git a/src/main/kotlin/com/example/downloader/ChunkPlan.kt b/src/main/kotlin/com/example/downloader/ChunkPlan.kt
@@ -40,8 +40,9 @@ internal fun planChunks(totalBytes: Long, chunkSize: Long): List<Chunk> {
     val fullChunks = totalBytes / chunkSize
     val hasRemainder = totalBytes % chunkSize != 0L
-    val totalChunks = fullChunks + if (hasRemainder) 1L else 0L
+    val remainderChunks = if (hasRemainder) 1L else 0L
+    val totalChunks = fullChunks + remainderChunks
     require(totalChunks <= Int.MAX_VALUE.toLong()) {
         "Chunk plan too large: $totalChunks chunks (chunkSize=$chunkSize, totalBytes=$totalBytes)"
     }
```

**Output:**

```
NO_FINDINGS
```

**Why this is the right call:** the change is a local variable extraction. No
new identifiers, no env reads, no log statements, no I/O. The error message in
`require` reflects only the inputs the caller passed (`totalChunks`, `chunkSize`,
`totalBytes`) — those values are not PII.

---

## Diff B — `System.getenv("USER")` introduced

**Input:**

```diff
diff --git a/src/main/kotlin/com/example/downloader/Main.kt b/src/main/kotlin/com/example/downloader/Main.kt
@@ -36,6 +36,8 @@ fun main(args: Array<String>) {
     val cli = parseArgs(args) ?: exitProcess(EXIT_USAGE)

+    val sessionUser = System.getenv("USER") ?: "anonymous"
+    System.err.println("starting download for $sessionUser")
     val fetcher = buildFetcher(cli.retries)
     val downloader = FileDownloader(fetcher)
```

**Output:**

```
| File | Line | Quote | Risk | Remediation |
|------|------|-------|------|-------------|
| src/main/kotlin/com/example/downloader/Main.kt | 38 | `val sessionUser = System.getenv("USER") ?: "anonymous"` | Reads the `USER` environment variable in production code. PRIVACY.md commits to no env reads beyond the allowlist (user.home, java.io.tmpdir, os.name, file.separator, line.separator, user.dir); USER is not on it. PiiScanner would catch this regex-side too, but the surrounding context (printing the value) raises the severity. | Remove both lines. The downloader does not need a user identity to fetch a file. If a future CLI mode genuinely needs one, take it as an explicit flag so the caller controls disclosure. |
| src/main/kotlin/com/example/downloader/Main.kt | 39 | `System.err.println("starting download for $sessionUser")` | Writes the (env-derived) user identity to stderr. Even allowlisted reads should not be logged from library / CLI code. | Remove. |
```

---

## Diff C — hardcoded `/Users/<name>/` path

**Input:**

```diff
diff --git a/src/main/kotlin/com/example/downloader/Resume.kt b/src/main/kotlin/com/example/downloader/Resume.kt
@@ -33,6 +33,9 @@ internal object ResumeSidecar {
     private const val VERSION = "1"

+    // Cached secrets path for legacy fallback
+    private val LEGACY_FALLBACK = Path.of("/Users/jane/secrets/resume-fallback.dat")
+
     fun pathFor(destination: Path): Path =
         destination.resolveSibling("${destination.fileName}.partial")
```

**Output:**

```
| File | Line | Quote | Risk | Remediation |
|------|------|-------|------|-------------|
| src/main/kotlin/com/example/downloader/Resume.kt | 36 | `private val LEGACY_FALLBACK = Path.of("/Users/jane/secrets/resume-fallback.dat")` | Hardcoded macOS user path with a concrete username (`jane`) and a directory named `secrets/` baked into production code. Even if the constant is unused at runtime, it ships with the artifact and would surface in any decompiled / inspected jar. | Delete the line. If a fallback path is genuinely needed it should derive from `System.getProperty("user.home")` (allowlisted) and live in a per-OS-appropriate cache directory, not a hardcoded user-specific subtree. |
```

---

## Diff D — log statement leaking URL host

**Input:**

```diff
diff --git a/src/main/kotlin/com/example/downloader/FileDownloader.kt b/src/main/kotlin/com/example/downloader/FileDownloader.kt
@@ -89,6 +89,7 @@ class FileDownloader(...) {

         config.progressListener.onStarted(probe.contentLength ?: UNKNOWN_LENGTH)

+        System.err.println("downloading from ${probe.finalUrl.host} into ${destination}")
         val totalBytes = probe.contentLength
         val result: DownloadResult = try {
             when {
```

**Output:**

```
| File | Line | Quote | Risk | Remediation |
|------|------|-------|------|-------------|
| src/main/kotlin/com/example/downloader/FileDownloader.kt | 92 | `System.err.println("downloading from ${probe.finalUrl.host} into ${destination}")` | Writes the URL host AND the destination file path to stderr from inside the library. Bypasses the `Telemetry` boundary documented in PRIVACY.md and DESIGN.md, which is explicitly typed to forbid these values. The CLI's own progress printer already covers user-facing reporting; library code doing its own logging is a regression. | Remove. If a per-download signal is needed for observability, fire `config.telemetry.onChunkComplete(chunkIndex, chunkBytes)` or `onDownloadComplete(...)` — those take counters and indices, not URL or path strings. |
```

---

## What this rehearsal proves

- The prompt's "output exactly `NO_FINDINGS` and nothing else" instruction holds
  on a clean diff (Diff A).
- The structured-table output is parseable with the same grammar across all
  three findings (Diffs B–D): one row per concern, columns `File | Line | Quote
  | Risk | Remediation`.
- Multi-finding cases (Diff B has two findings in one file) emit one row each,
  not one merged row — important for the script's downstream aggregation.
- The prompt distinguishes regex-detectable issues (env reads, hardcoded paths)
  from shape-detectable ones (logging that pulls URL host or destination
  through interpolation), which is the value the LLM adds over the
  static-only `PiiScanner`.

If a future change to `pii_review.md` would alter the wire format (e.g. switch
to JSON), update this file in the same commit so the contract stays in sync.
