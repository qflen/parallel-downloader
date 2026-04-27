package com.example.downloader

private const val BYTES_PER_KIB = 1024L
private const val BYTES_PER_MIB = BYTES_PER_KIB * 1024L
private const val BYTES_PER_GIB = BYTES_PER_MIB * 1024L
private const val BYTES_PER_TIB = BYTES_PER_GIB * 1024L

val Int.KiB: Long get() = this.toLong() * BYTES_PER_KIB
val Int.MiB: Long get() = this.toLong() * BYTES_PER_MIB
val Int.GiB: Long get() = this.toLong() * BYTES_PER_GIB

val Long.KiB: Long get() = this * BYTES_PER_KIB
val Long.MiB: Long get() = this * BYTES_PER_MIB
val Long.GiB: Long get() = this * BYTES_PER_GIB

/**
 * Format [bytes] as a human-readable IEC binary string, picking the largest unit that
 * keeps the integer part below 1024. Examples: `1023 -> "1023 B"`, `1024 -> "1.0 KiB"`,
 * `52_428_800 -> "50.0 MiB"`. Negative values render with a leading minus sign.
 */
fun humanBytes(bytes: Long): String {
    val abs = if (bytes < 0L) -bytes else bytes
    val sign = if (bytes < 0L) "-" else ""
    return when {
        abs < BYTES_PER_KIB -> "$sign$abs B"
        abs < BYTES_PER_MIB -> "%s%.1f KiB".format(sign, abs.toDouble() / BYTES_PER_KIB)
        abs < BYTES_PER_GIB -> "%s%.1f MiB".format(sign, abs.toDouble() / BYTES_PER_MIB)
        abs < BYTES_PER_TIB -> "%s%.2f GiB".format(sign, abs.toDouble() / BYTES_PER_GIB)
        else -> "%s%.2f TiB".format(sign, abs.toDouble() / BYTES_PER_TIB)
    }
}
