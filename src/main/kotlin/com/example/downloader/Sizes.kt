package com.example.downloader

private const val BYTES_PER_KIB = 1024L
private const val BYTES_PER_MIB = BYTES_PER_KIB * 1024L
private const val BYTES_PER_GIB = BYTES_PER_MIB * 1024L

val Int.KiB: Long get() = this.toLong() * BYTES_PER_KIB
val Int.MiB: Long get() = this.toLong() * BYTES_PER_MIB
val Int.GiB: Long get() = this.toLong() * BYTES_PER_GIB

val Long.KiB: Long get() = this * BYTES_PER_KIB
val Long.MiB: Long get() = this * BYTES_PER_MIB
val Long.GiB: Long get() = this * BYTES_PER_GIB
