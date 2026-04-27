package com.example.downloader.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class PiiScannerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `clean fixture produces zero findings`() {
        val root = stageFromResource("piiscanner-fixtures/clean.txt", "Clean.kt")
        val findings = PiiScanner.scan(tempDir, listOf(root))
        assertTrue(findings.isEmpty(), "expected no findings, got: $findings")
    }

    @Test
    fun `dirty fixture flags every category at least once`() {
        val root = stageFromResource("piiscanner-fixtures/dirty.txt", "Dirty.kt")
        val findings = PiiScanner.scan(tempDir, listOf(root))
        val byPattern = findings.map { it.pattern }.toSet()

        // Each line of dirty.txt is a distinct category.
        assertTrue("email" in byPattern, "expected email finding, got $byPattern")
        assertTrue("ipv4-literal" in byPattern, "expected ipv4-literal finding, got $byPattern")
        assertTrue(byPattern.any { it.startsWith("System.getenv(") }, "expected env read finding")
        assertTrue(byPattern.any { it.startsWith("System.getProperty(") }, "expected property read finding")
        assertTrue("InetAddress.getLocalHost" in byPattern, "expected getLocalHost finding")
        assertTrue("macos-user-path" in byPattern, "expected macOS user-path finding")
        assertTrue("linux-home-path" in byPattern, "expected linux home-path finding")
    }

    @Test
    fun `loopback IP literals are allowlisted`() {
        val src = tempDir.resolve("Loopback.kt")
        src.writeText(
            """
            val a = "127.0.0.1"
            val b = "0.0.0.0"
            val c = "::1"
            """.trimIndent(),
        )
        val findings = PiiScanner.scan(tempDir, listOf(tempDir))
        assertTrue(findings.isEmpty(), "loopback addresses should be allowlisted, got $findings")
    }

    @Test
    fun `allowlisted system reads do not produce findings`() {
        val src = tempDir.resolve("Allowed.kt")
        src.writeText(
            """
            val a = System.getProperty("user.home")
            val b = System.getenv("JAVA_HOME")
            """.trimIndent(),
        )
        // JAVA_HOME isn't in the default allowlist, but user.home is. Configure both.
        val custom = PiiScanner.AllowList(
            allowedSystemReads = PiiScanner.DEFAULT_ALLOWED_SYSTEM_READS + "JAVA_HOME",
        )
        val findings = PiiScanner.scan(tempDir, listOf(tempDir), custom)
        assertTrue(findings.isEmpty(), "expected no findings with permissive allowlist, got $findings")
    }

    @Test
    fun `unknown system property is flagged`() {
        val src = tempDir.resolve("Unknown.kt")
        src.writeText("""val x = System.getProperty("user.name")""")
        val findings = PiiScanner.scan(tempDir, listOf(tempDir))
        assertEquals(1, findings.size, "expected one finding, got $findings")
        assertTrue(
            findings[0].pattern.startsWith("System.getProperty"),
            "expected getProperty pattern, got ${findings[0].pattern}",
        )
    }

    @Test
    fun `allowed file is not scanned`() {
        val src = tempDir.resolve("AllowedFile.kt")
        src.writeText("""val email = "leaks@example.com"""")
        val custom = PiiScanner.AllowList(allowedFiles = setOf("AllowedFile.kt"))
        val findings = PiiScanner.scan(tempDir, listOf(tempDir), custom)
        assertTrue(findings.isEmpty(), "expected allowlisted file to be skipped, got $findings")
    }

    private fun stageFromResource(resource: String, destName: String): Path {
        val text = checkNotNull(this::class.java.classLoader.getResourceAsStream(resource)) {
            "missing resource: $resource"
        }.bufferedReader().use { it.readText() }
        // Stage as a .kt file so the scanner picks it up via its extension filter.
        val dest = tempDir.resolve(destName)
        Files.writeString(dest, text)
        return tempDir
    }
}
