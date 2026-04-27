package com.example.downloader

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * SHA-256 helpers used by the `--sha256` CLI flag and by callers who want to verify
 * a download's content out-of-band. Streams the file in a fixed-size buffer (64 KiB)
 * so verification of multi-GiB downloads doesn't hold the whole content in memory.
 */
object Checksum {

    private const val SHA256_HEX_LENGTH = 64
    private const val READ_BUFFER_BYTES = 64 * 1024
    private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")

    /** Lowercase hex SHA-256 of [path]'s contents. */
    fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buf = ByteArray(READ_BUFFER_BYTES)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** True iff [s] is exactly 64 hex characters (case-insensitive). */
    fun isValidSha256Hex(s: String): Boolean =
        s.length == SHA256_HEX_LENGTH && HEX_REGEX.matches(s)
}
