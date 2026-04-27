package com.example.downloader.internal

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * Static check for PII patterns bleeding into source code: email addresses, IP literals beyond
 * the loopback fixtures, identifying environment / hostname reads, hardcoded `/Users/<name>/`
 * or `/home/<name>/` paths.
 *
 * Pure: takes a list of root directories and a list of allowlisted relative paths, returns a
 * list of [Finding]s. Wired into `gradle check` via a small `JavaExec` task that dispatches
 * to [PiiScannerCli].
 *
 * Adding a new finding category: extend [PATTERNS]. Keep the regex narrow enough that it
 * actually matches a PII shape, not just any string containing the relevant keyword - the
 * scanner only earns its keep if its findings are real.
 */
object PiiScanner {

    data class Finding(val file: Path, val line: Int, val pattern: String, val snippet: String) {
        override fun toString(): String = "$file:$line: [$pattern] $snippet"
    }

    /**
     * @property name human-readable category - what the scanner thinks it found.
     * @property regex matches one form of PII or its proxy.
     * @property allowedSubstrings if a match also contains one of these substrings, suppress
     *   the finding. Used for legitimate fixture values like `127.0.0.1` (loopback) which
     *   look like IP literals but aren't user-identifying.
     */
    data class Pattern(
        val name: String,
        val regex: Regex,
        val allowedSubstrings: List<String> = emptyList(),
    )

    /**
     * @property allowedFiles list of project-relative paths whose contents are exempted from
     *   scanning entirely. Reserved for test files that exercise PII patterns intentionally
     *   (e.g. AnonymityTest scans for these patterns to assert their absence in src/main).
     * @property allowedSystemReads the only `System.getenv` / `System.getProperty` keys
     *   permitted in production code. Anything else is flagged.
     */
    data class AllowList(
        val allowedFiles: Set<String> = DEFAULT_ALLOWED_FILES,
        val allowedSystemReads: Set<String> = DEFAULT_ALLOWED_SYSTEM_READS,
    )

    private val EMAIL = Pattern(
        name = "email",
        regex = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"""),
    )

    // IPv4 dotted-quad, with loopback / wildcard explicitly allowlisted; those appear in
    // fixtures as 127.0.0.1 or 0.0.0.0 and aren't user-identifying.
    private val IPV4 = Pattern(
        name = "ipv4-literal",
        regex = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""),
        allowedSubstrings = listOf("127.0.0.1", "0.0.0.0", "255.255.255.255"),
    )

    // IPv6 - require at least 4 colon-separated groups so HH:MM:SS time strings (like the
    // HTTP-date "07:28:00 GMT" that appears in If-Range fixtures) don't false-positive. Real
    // IPv6 has 8 groups; we accept 4+ to cover :: shortening at the cost of some recall on
    // the very-shortened forms (which would be the loopback ::1, allowlisted anyway).
    private val IPV6 = Pattern(
        name = "ipv6-literal",
        regex = Regex("""\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\b"""),
        allowedSubstrings = listOf("::1"),
    )

    private val GET_LOCAL_HOST = Pattern(
        name = "InetAddress.getLocalHost",
        regex = Regex("""InetAddress\.getLocalHost\("""),
    )

    // /Users/<name>/ and /home/<name>/ - path-shaped hits with a concrete user segment. The
    // bare /Users/ or /home/ prefix is too noisy (could be a doc URL or comment).
    private val USER_PATH_MAC = Pattern(
        name = "macos-user-path",
        regex = Regex("""/Users/[a-zA-Z][a-zA-Z0-9_.-]*/"""),
    )
    private val USER_PATH_LINUX = Pattern(
        name = "linux-home-path",
        regex = Regex("""/home/[a-zA-Z][a-zA-Z0-9_.-]*/"""),
    )

    val PATTERNS: List<Pattern> = listOf(
        EMAIL, IPV4, IPV6, GET_LOCAL_HOST, USER_PATH_MAC, USER_PATH_LINUX,
    )

    /** `System.getProperty("X")` and `System.getenv("X")` keys that don't identify a user. */
    val DEFAULT_ALLOWED_SYSTEM_READS: Set<String> = setOf(
        "user.home",
        "java.io.tmpdir",
        "os.name",
        "file.separator",
        "line.separator",
        "kotlin.code.style",
        "user.dir",
    )

    val DEFAULT_ALLOWED_FILES: Set<String> = setOf(
        // AnonymityTest deliberately reads getLocalHost / user.name as part of negative
        // assertions ("the sidecar must not contain these"). Allowlisting the file is the
        // honest move - the scan would otherwise force us to obscure the very property the
        // test asserts.
        "src/test/kotlin/com/example/downloader/AnonymityTest.kt",
        // PiiScanner itself contains the regex strings that look like the things they match.
        "src/test/kotlin/com/example/downloader/internal/PiiScanner.kt",
        // PiiScannerTest stages dirty fixtures inline (an email, getenv, getProperty calls);
        // those are the test inputs, not production code.
        "src/test/kotlin/com/example/downloader/internal/PiiScannerTest.kt",
        // Test fixtures are deliberate counterexamples for PiiScannerTest; not production.
        "src/test/resources/piiscanner-fixtures/clean.txt",
        "src/test/resources/piiscanner-fixtures/dirty.txt",
    )

    /**
     * Scan every `*.kt` / `*.kts` / `*.java` file under [roots], producing one [Finding] per
     * match (deduped per file+line+pattern). [projectRoot] is used to compute the relative
     * path against [AllowList.allowedFiles].
     */
    fun scan(
        projectRoot: Path,
        roots: List<Path>,
        allowList: AllowList = AllowList(),
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (root in roots) {
            if (Files.isDirectory(root)) scanRoot(projectRoot, root, allowList, findings)
        }
        return findings
    }

    private fun scanRoot(
        projectRoot: Path,
        root: Path,
        allowList: AllowList,
        findings: MutableList<Finding>,
    ) {
        Files.walk(root).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { it.fileName.toString().endsWithAny(SCAN_EXTENSIONS) }
                .forEach { file -> scanIfNotAllowed(projectRoot, file, allowList, findings) }
        }
    }

    private fun scanIfNotAllowed(
        projectRoot: Path,
        file: Path,
        allowList: AllowList,
        findings: MutableList<Finding>,
    ) {
        val rel = projectRoot.relativize(file).toString().replace('\\', '/')
        if (rel !in allowList.allowedFiles) scanFile(file, allowList, findings)
    }

    private fun scanFile(file: Path, allowList: AllowList, sink: MutableList<Finding>) {
        val text = try { file.readText() } catch (_: java.io.IOException) { return }
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            for (pattern in PATTERNS) {
                addMatchesIfAny(file, index + 1, line, pattern, sink)
            }
            scanSystemReads(file, index + 1, line, allowList, sink)
        }
    }

    private fun addMatchesIfAny(
        file: Path,
        lineNumber: Int,
        line: String,
        pattern: Pattern,
        sink: MutableList<Finding>,
    ) {
        for (match in pattern.regex.findAll(line)) {
            val v = match.value
            if (pattern.allowedSubstrings.any { v.contains(it) }) continue
            sink += Finding(file, lineNumber, pattern.name, line.trim())
        }
    }

    private fun scanSystemReads(
        file: Path,
        lineNumber: Int,
        line: String,
        allowList: AllowList,
        sink: MutableList<Finding>,
    ) {
        for (match in SYSTEM_READ_REGEX.findAll(line)) {
            val key = match.groupValues[2]
            if (key in allowList.allowedSystemReads) continue
            val func = match.groupValues[1]
            sink += Finding(file, lineNumber, "System.$func(\"$key\")", line.trim())
        }
    }

    private val SCAN_EXTENSIONS = listOf(".kt", ".kts", ".java")
    private val SYSTEM_READ_REGEX = Regex("""System\.(getenv|getProperty)\("([^"]+)"\)""")

    private fun String.endsWithAny(suffixes: List<String>): Boolean =
        suffixes.any { endsWith(it) }
}

/**
 * Gradle entry point. Args are project-relative root directories to scan. First arg may be
 * `--project-root <path>` to set the project root explicitly (used to compute the
 * allowlist-file paths). Exits non-zero with a printed report if any findings exist.
 */
object PiiScannerCli {
    @JvmStatic
    fun main(args: Array<String>) {
        var projectRoot: Path = Path.of("").toAbsolutePath()
        val rootArgs = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--project-root" -> { projectRoot = Path.of(args[i + 1]).toAbsolutePath(); i += 2 }
                else -> { rootArgs += args[i]; i++ }
            }
        }
        if (rootArgs.isEmpty()) {
            System.err.println("usage: pii-scan [--project-root <path>] <dir>...")
            exitProcess(USAGE_EXIT_CODE)
        }
        val roots = rootArgs.map { projectRoot.resolve(it) }
        val findings = PiiScanner.scan(projectRoot, roots)
        if (findings.isEmpty()) {
            System.err.println("PII scan: clean (${roots.size} root(s))")
            return
        }
        System.err.println("PII scan: ${findings.size} finding(s)")
        for (f in findings) System.err.println("  $f")
        exitProcess(FINDINGS_EXIT_CODE)
    }

    private const val USAGE_EXIT_CODE = 64
    private const val FINDINGS_EXIT_CODE = 1
}
