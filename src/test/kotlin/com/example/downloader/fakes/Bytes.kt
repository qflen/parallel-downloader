package com.example.downloader.fakes

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.SplittableRandom

/**
 * Test helpers for synthesizing deterministic byte streams and verifying file integrity by
 * SHA-256 (the spec calls out SHA-256 explicitly: "Verify file integrity by SHA-256, never by
 * length alone").
 */
object Bytes {

    /** Generates a deterministic pseudo-random byte array of [size] bytes seeded by [seed]. */
    fun deterministic(size: Int, seed: Long = 0xC0FFEEL): ByteArray {
        val out = ByteArray(size)
        val rng = SplittableRandom(seed)
        var i = 0
        // Fill 8 bytes at a time for speed, then any tail.
        while (i + Long.SIZE_BYTES <= size) {
            val v = rng.nextLong()
            out[i] = (v ushr 0).toByte()
            out[i + 1] = (v ushr Byte.SIZE_BITS).toByte()
            out[i + 2] = (v ushr (2 * Byte.SIZE_BITS)).toByte()
            out[i + 3] = (v ushr (3 * Byte.SIZE_BITS)).toByte()
            out[i + 4] = (v ushr (4 * Byte.SIZE_BITS)).toByte()
            out[i + 5] = (v ushr (5 * Byte.SIZE_BITS)).toByte()
            out[i + 6] = (v ushr (6 * Byte.SIZE_BITS)).toByte()
            out[i + 7] = (v ushr (7 * Byte.SIZE_BITS)).toByte()
            i += Long.SIZE_BYTES
        }
        if (i < size) {
            val v = rng.nextLong()
            for (k in 0 until size - i) {
                out[i + k] = (v ushr (k * Byte.SIZE_BITS)).toByte()
            }
        }
        return out
    }

    fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * Streams [totalLength] bytes of deterministic pseudo-random content into [path] without
     * ever materializing the full payload in memory. Computes the SHA-256 of the content along
     * the way and returns it. Used by stress tests that need a 1 GiB source file under a 256 MiB
     * heap cap.
     */
    fun writeDeterministicFile(path: Path, totalLength: Long, seed: Long = 0xC0FFEEL): String {
        val md = MessageDigest.getInstance("SHA-256")
        val rng = SplittableRandom(seed)
        val buffer = ByteArray(64 * 1024)
        Files.newOutputStream(path).use { out ->
            var written = 0L
            while (written < totalLength) {
                val toWrite = minOf(buffer.size.toLong(), totalLength - written).toInt()
                fillRandomly(buffer, toWrite, rng)
                out.write(buffer, 0, toWrite)
                md.update(buffer, 0, toWrite)
                written += toWrite
            }
        }
        return md.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun fillRandomly(buffer: ByteArray, length: Int, rng: SplittableRandom) {
        var i = 0
        while (i + Long.SIZE_BYTES <= length) {
            val v = rng.nextLong()
            for (b in 0 until Long.SIZE_BYTES) {
                buffer[i + b] = (v ushr (b * Byte.SIZE_BITS)).toByte()
            }
            i += Long.SIZE_BYTES
        }
        if (i < length) {
            val v = rng.nextLong()
            for (k in 0 until length - i) {
                buffer[i + k] = (v ushr (k * Byte.SIZE_BITS)).toByte()
            }
        }
    }
}
