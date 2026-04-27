package com.example.downloader

import com.example.downloader.fakes.Bytes
import com.example.downloader.fakes.FileOptions
import com.example.downloader.fakes.TestHttpServer
import com.example.downloader.http.JdkHttpRangeFetcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertIs

/**
 * Asserts the privacy claims documented in PRIVACY.md against real production code paths. These
 * are the executable form of that policy: each claim there has a test here that fails the build
 * when the claim regresses.
 */
class AnonymityTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `probe and ranged GET requests carry no identifying headers`() = runTest {
        val payload = Bytes.deterministic(32 * 1024, seed = 7)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload)
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig { chunkSize = 4096L; parallelism = 4 }

            val result = downloader.download(server.url("/file.bin"), dest, cfg)
            assertIs<DownloadResult.Success>(result)

            val recorded = server.requests
            // HEAD probe + at least one ranged GET must have been made.
            assertTrue(recorded.any { it.method == "HEAD" }, "no probe recorded")
            assertTrue(recorded.any { it.method == "GET" && it.rangeHeader != null }, "no ranged GET recorded")

            for (req in recorded) {
                assertHeaderAbsentOrEmpty(req, "user-agent")
                assertHeaderAbsent(req, "referer")
                assertHeaderAbsent(req, "cookie")
                assertHeaderAbsent(req, "authorization")
                assertHeaderAbsent(req, "from")
            }
        }
    }

    @Test
    fun `single-GET fallback also carries no identifying headers`() = runTest {
        val payload = Bytes.deterministic(8 * 1024, seed = 11)
        TestHttpServer().use { server ->
            server.serve("/file.bin", payload, FileOptions(acceptsRanges = false))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")

            val result = downloader.download(server.url("/file.bin"), dest)
            assertIs<DownloadResult.Success>(result)

            for (req in server.requests) {
                assertHeaderAbsentOrEmpty(req, "user-agent")
                assertHeaderAbsent(req, "referer")
                assertHeaderAbsent(req, "cookie")
                assertHeaderAbsent(req, "authorization")
                assertHeaderAbsent(req, "from")
            }
        }
    }

    @Test
    fun `resume sidecar contains only documented keys - no hostname username or path`() {
        val state = ResumeState(
            totalBytes = 1_048_576L,
            chunkSize = 65_536L,
            entityValidator = "W/\"abc-123\"",
            completedChunks = setOf(0, 1, 5, 7),
        )
        val dest = tempDir.resolve("file.bin")
        Files.createFile(dest)
        ResumeSidecar.save(dest, state)

        val text = ResumeSidecar.pathFor(dest).readText()

        // Every line is `key=value`. Collect the keys; they must be a subset of the documented set.
        val keys = text.lineSequence()
            .filter { it.isNotBlank() }
            .map { it.substringBefore('=') }
            .toSet()
        val allowed = setOf("version", "total", "chunkSize", "validator", "completed")
        val unexpected = keys - allowed
        assertTrue(unexpected.isEmpty(), "unexpected keys in sidecar: $unexpected")

        // Negative checks: the categorical PII patterns are not present in the serialized form.
        val home = System.getProperty("user.home")
        assertFalse(text.contains(home), "sidecar leaks user.home: $text")
        val hostname = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        if (!hostname.isNullOrBlank()) {
            assertFalse(text.contains(hostname), "sidecar leaks hostname: $text")
        }
        val username = System.getProperty("user.name")
        if (!username.isNullOrBlank()) {
            assertFalse(text.contains(username), "sidecar leaks user.name: $text")
        }
    }

    @Test
    fun `IoFailure error message reflects the destination the caller passed and nothing else`() = runTest {
        val downloader = FileDownloader(JdkHttpRangeFetcher())
        // Trigger checkEnvironment's "parent does not exist" failure deterministically.
        val dest = tempDir.resolve("does-not-exist").resolve("out.bin")
        TestHttpServer().use { server ->
            server.serve("/file.bin", Bytes.deterministic(1024, seed = 13))
            val result = downloader.download(server.url("/file.bin"), dest)
            val failure = assertIs<DownloadResult.IoFailure>(result)
            val message = failure.cause.message.orEmpty()

            // The cause SHOULD reference the destination the caller chose - otherwise the caller
            // can't act on the failure. What it must NOT do is smuggle in things the caller
            // didn't pass: user.home outside the dest path, the cwd, or env-var values.
            assertTrue(
                message.contains(dest.parent.toString()),
                "cause should reference the missing parent the caller passed: $message",
            )
            // tempDir may sit under user.home on some platforms; scope the check to the part
            // of the message that ISN'T the caller-passed destination.
            val residual = message.replace(dest.parent.toString(), "[DEST_PARENT]")
                .replace(dest.toString(), "[DEST]")
            val home = System.getProperty("user.home")
            assertFalse(
                residual.contains(home),
                "cause leaks user.home outside the caller-passed dest: $message",
            )
        }
    }

    @Test
    fun `production source does not read identifying environment or hostname APIs`() {
        // Static check across src/main/kotlin. PiiScanner (T1.3) generalizes this; the check
        // here keeps the privacy-property assertion local to the privacy test file.
        val mainSrc = Path.of("src/main/kotlin").toAbsolutePath()
        assertTrue(Files.isDirectory(mainSrc), "src/main/kotlin not found from cwd ${Path.of(".").toAbsolutePath()}")

        val forbiddenPatterns = listOf(
            Regex("""System\.getenv\("USER"\)"""),
            Regex("""System\.getenv\("HOSTNAME"\)"""),
            Regex("""System\.getenv\("USERNAME"\)"""),
            Regex("""System\.getProperty\("user\.name"\)"""),
            Regex("""InetAddress\.getLocalHost\("""),
        )
        val findings = scanFiles(mainSrc, forbiddenPatterns)
        assertTrue(findings.isEmpty(), "production source leaks identifying env/host: $findings")
    }

    @Test
    fun `resume sidecar lives next to the destination - no global state directory`() {
        val state = ResumeState(
            totalBytes = 1024L, chunkSize = 64L, entityValidator = null, completedChunks = emptySet(),
        )
        val dest = tempDir.resolve("nested").resolve("file.bin")
        Files.createDirectories(dest.parent)
        Files.createFile(dest)
        ResumeSidecar.save(dest, state)

        val sidecar = ResumeSidecar.pathFor(dest)
        assertEquals(dest.parent, sidecar.parent, "sidecar should live next to destination")
        assertEquals("file.bin.partial", sidecar.fileName.toString())
    }

    @Test
    fun `recorded If-Range header carries only the validator the server sent - never an identifier`() = runTest {
        val payload = Bytes.deterministic(8 * 1024, seed = 17)
        TestHttpServer().use { server ->
            // Server advertises an ETag; downloader will reflect it back as If-Range.
            server.serve("/file.bin", payload, FileOptions(etag = "\"v1\""))
            val downloader = FileDownloader(JdkHttpRangeFetcher())
            val dest = tempDir.resolve("out.bin")
            val cfg = downloadConfig { chunkSize = 1024L; parallelism = 4 }
            assertIs<DownloadResult.Success>(downloader.download(server.url("/file.bin"), dest, cfg))

            val rangedGets = server.requests.filter { it.method == "GET" && it.rangeHeader != null }
            assertTrue(rangedGets.isNotEmpty(), "no ranged GETs recorded")
            for (req in rangedGets) {
                assertEquals(
                    "\"v1\"",
                    req.ifRangeHeader,
                    "If-Range echoes server validator only, not anything client-side",
                )
            }
        }
    }

    @Test
    fun `production source does not embed local user paths`() {
        val mainSrc = Path.of("src/main/kotlin").toAbsolutePath()
        // Bare prefixes /Users/ and /home/ alone are too noisy to flag (they may appear in
        // comments or doc URLs); we want path-shaped hits with a concrete user segment.
        val patterns = listOf(
            Regex("""/Users/[a-zA-Z][a-zA-Z0-9_.-]*/"""),
            Regex("""/home/[a-zA-Z][a-zA-Z0-9_.-]*/"""),
        )
        val findings = scanFiles(mainSrc, patterns)
        assertTrue(findings.isEmpty(), "production source embeds user paths: $findings")
    }

    private fun scanFiles(root: Path, patterns: List<Regex>): List<String> {
        val out = mutableListOf<String>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { file -> collectMatches(file, patterns, out) }
        }
        return out
    }

    private fun collectMatches(file: Path, patterns: List<Regex>, out: MutableList<String>) {
        val text = file.readText()
        for (pattern in patterns) {
            if (pattern.containsMatchIn(text)) out += "$file: ${pattern.pattern}"
        }
    }

    private fun assertHeaderAbsent(req: com.example.downloader.fakes.RecordedRequest, key: String) {
        assertNull(
            req.headers[key]?.firstOrNull()?.takeIf { it.isNotEmpty() },
            "${req.method} ${req.path} carries unexpected $key: ${req.headers[key]}",
        )
    }

    /**
     * The JDK HttpClient won't let us *un-set* the User-Agent header - `setHeader("User-Agent",
     * "")` emits the header with an empty value, which is what we want as a privacy property
     * (no JDK-version fingerprint on the wire). Some JDKs may also drop empty headers entirely.
     * Either outcome is acceptable: the header must be absent OR present with no non-empty value.
     */
    private fun assertHeaderAbsentOrEmpty(req: com.example.downloader.fakes.RecordedRequest, key: String) {
        val values = req.headers[key].orEmpty()
        val nonEmpty = values.filter { it.isNotEmpty() }
        assertTrue(
            nonEmpty.isEmpty(),
            "${req.method} ${req.path} carries non-empty $key: $nonEmpty",
        )
    }
}
