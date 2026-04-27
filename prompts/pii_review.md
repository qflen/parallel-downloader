# PII review prompt

You are reviewing a single file's diff from a pull request to `parallel-downloader`,
a Kotlin HTTP downloader. The project ships as a telemetry-quiet library — see
`PRIVACY.md` — which means changes that introduce **identifying or fingerprinting
data** into request paths, log statements, error messages, or persisted state
are regressions.

This complements `PiiScanner` (the static regex-based check that runs as part of
`gradle check`). Your job is to catch the things a regex can't: paraphrased PII,
URL hosts that sneak into log statements, subtly concatenated paths, validator
strings that quote user input, and similar shape-rather-than-string patterns.

## What counts as a finding

Flag a change when the diff:

1. **Adds an identifying request header.** `User-Agent`, `Referer`, `Cookie`,
   `Authorization`, `From`, `X-Forwarded-For`, `X-Real-IP`, or any custom header
   set from `System.getenv` / `getProperty` / `InetAddress`. Existing headers
   like `Range` and `If-Range` are part of the protocol — do not flag.
2. **Reads identifying environment / system data.** `System.getenv("USER")`,
   `System.getenv("HOSTNAME")`, `System.getenv("USERNAME")`,
   `System.getProperty("user.name")`, `InetAddress.getLocalHost()`. Allowed:
   `user.home`, `java.io.tmpdir`, `os.name`, `file.separator`, `line.separator`,
   `user.dir`.
3. **Logs a URL host, full URL, validator string, file path, or error message
   text.** Even if the log is stderr-only. The library has a `Telemetry`
   interface for instrumentation; reaching past it via println / logger calls
   is a regression. Counters and chunk indices are fine.
4. **Persists hostnames, usernames, paths, or URLs** to disk or a sidecar.
   `<destination>.partial` is the only persisted artifact and is documented to
   contain only chunk geometry.
5. **Embeds hardcoded user paths.** `/Users/<name>/`, `/home/<name>/`,
   `C:\Users\<name>\`. Test fixtures may use `/tmp/` — that's fine.
6. **Embeds non-loopback IP literals or email addresses** in production code.
   `127.0.0.1`, `0.0.0.0`, `::1` are loopback and OK. Anything else flag.
7. **Adds a new telemetry / observability beacon.** Outbound network calls to
   any host the user did not pass in.

## What is NOT a finding

- Header names appearing in *test assertions* (e.g. `assertHeaderAbsent("user-agent")`)
  — the test is asserting the property the policy wants.
- Regex strings that look like the things they match (the scanner itself).
- Doc comments mentioning identifying patterns by name to explain why they're
  excluded.
- Renames, refactors, formatting, comment edits without behavioral change.

## Output format

If you find one or more concerns, emit them as a Markdown table, one row per
finding:

```
| File | Line | Quote | Risk | Remediation |
|------|------|-------|------|-------------|
| path/to/File.kt | 42 | `log.info("downloading $url")` | URL host leaks to logs | Replace with `telemetry.onChunkComplete(...)` or remove |
```

Quote the smallest excerpt that justifies the finding (one or two lines of code,
truncated if longer). The Line column is the line in the *new* file (post-diff).
Use the diff's `+` markers to identify added lines.

If you find no concerns, emit exactly the literal string:

```
NO_FINDINGS
```

— and nothing else. This precise output is parsed by the calling script; any
preface or postscript breaks the integration.

## Few-shot examples

### Example 1 — clean refactor, no findings

Input diff:

```diff
diff --git a/src/main/kotlin/com/example/downloader/ChunkPlan.kt b/src/main/kotlin/com/example/downloader/ChunkPlan.kt
@@ -40,7 +40,8 @@ internal fun planChunks(totalBytes: Long, chunkSize: Long): List<Chunk> {
     val fullChunks = totalBytes / chunkSize
     val hasRemainder = totalBytes % chunkSize != 0L
-    val totalChunks = fullChunks + if (hasRemainder) 1L else 0L
+    val remainderChunks = if (hasRemainder) 1L else 0L
+    val totalChunks = fullChunks + remainderChunks
```

Expected output:

```
NO_FINDINGS
```

### Example 2 — env read introduced

Input diff:

```diff
diff --git a/src/main/kotlin/com/example/downloader/Main.kt b/src/main/kotlin/com/example/downloader/Main.kt
@@ -36,6 +36,7 @@ fun main(args: Array<String>) {
     val cli = parseArgs(args) ?: exitProcess(EXIT_USAGE)
+    val sessionUser = System.getenv("USER") ?: "anonymous"
     val fetcher = buildFetcher(cli.retries)
```

Expected output:

```
| File | Line | Quote | Risk | Remediation |
|------|------|-------|------|-------------|
| src/main/kotlin/com/example/downloader/Main.kt | 37 | `val sessionUser = System.getenv("USER") ?: "anonymous"` | Reads `USER` env var; the CLI does not need user identity to download a file. Adds re-identification surface to any downstream telemetry / log path. | Drop the line. If a user-display name is genuinely required, accept it as a CLI flag so the caller controls it. |
```

### Example 3 — log statement leaking URL host

Input diff:

```diff
diff --git a/src/main/kotlin/com/example/downloader/FileDownloader.kt b/src/main/kotlin/com/example/downloader/FileDownloader.kt
@@ -91,6 +91,7 @@ class FileDownloader(...) {
         val totalBytes = probe.contentLength
+        System.err.println("downloading from ${probe.finalUrl.host}")
         val result: DownloadResult = try {
```

Expected output:

```
| File | Line | Quote | Risk | Remediation |
|------|------|-------|------|-------------|
| src/main/kotlin/com/example/downloader/FileDownloader.kt | 93 | `System.err.println("downloading from ${probe.finalUrl.host}")` | Writes the URL host to stderr from the library; bypasses the `Telemetry` boundary documented in PRIVACY.md and logs identifying data even when no logger is configured. | Remove the println. If a chunk-completion signal is needed, fire `config.telemetry.onChunkComplete(...)` which takes only an index + byte count. |
```
