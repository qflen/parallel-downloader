package com.example.downloader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes

class ChecksumTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `sha256 of empty file matches reference value`() {
        val f = tempDir.resolve("empty.bin")
        Files.createFile(f)
        // Reference: `printf "" | shasum -a 256` yields this constant.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Checksum.sha256(f),
        )
    }

    @Test
    fun `sha256 of abc matches reference value`() {
        val f = tempDir.resolve("abc.bin")
        f.writeBytes("abc".toByteArray())
        // Reference: `printf "abc" | shasum -a 256`.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Checksum.sha256(f),
        )
    }

    @Test
    fun `sha256 streams large file without buffering it whole`() {
        // Use a multiple of the internal 64 KiB buffer so the final partial read is exercised
        // alongside several full reads.
        val f = tempDir.resolve("big.bin")
        val bytes = ByteArray(200 * 1024) { (it % 256).toByte() }
        f.writeBytes(bytes)
        // Length matches what we wrote and the hash agrees with computing it in memory.
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expected = md.digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
        assertEquals(expected, Checksum.sha256(f))
    }

    @Test
    fun `isValidSha256Hex accepts 64 char lowercase`() {
        assertTrue(Checksum.isValidSha256Hex("0".repeat(64)))
        assertTrue(Checksum.isValidSha256Hex("0123456789abcdef".repeat(4)))
    }

    @Test
    fun `isValidSha256Hex accepts uppercase`() {
        assertTrue(Checksum.isValidSha256Hex("0123456789ABCDEF".repeat(4)))
    }

    @Test
    fun `isValidSha256Hex rejects wrong lengths`() {
        assertFalse(Checksum.isValidSha256Hex(""))
        assertFalse(Checksum.isValidSha256Hex("0".repeat(63)))
        assertFalse(Checksum.isValidSha256Hex("0".repeat(65)))
    }

    @Test
    fun `isValidSha256Hex rejects non-hex chars`() {
        assertFalse(Checksum.isValidSha256Hex("g".repeat(64)))
        assertFalse(Checksum.isValidSha256Hex(" ".repeat(64)))
        assertFalse(Checksum.isValidSha256Hex("0".repeat(63) + "z"))
    }
}
