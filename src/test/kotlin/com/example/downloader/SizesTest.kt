package com.example.downloader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SizesTest {

    @Test
    fun `Int extensions produce correct byte counts`() {
        assertEquals(1024L, 1.KiB)
        assertEquals(1024L * 1024L, 1.MiB)
        assertEquals(1024L * 1024L * 1024L, 1.GiB)
        assertEquals(8L * 1024L * 1024L, 8.MiB)
    }

    @Test
    fun `Long extensions produce correct byte counts`() {
        assertEquals(2048L, 2L.KiB)
        assertEquals(64L * 1024L * 1024L, 64L.MiB)
        assertEquals(2L * 1024L * 1024L * 1024L, 2L.GiB)
    }

    @Test
    fun `0 KiB is 0 bytes`() {
        assertEquals(0L, 0.KiB)
        assertEquals(0L, 0L.MiB)
    }

    @Test
    fun `humanBytes picks the largest unit that keeps the integer part below 1024`() {
        assertEquals("0 B", humanBytes(0L))
        assertEquals("1023 B", humanBytes(1023L))
        assertEquals("1.0 KiB", humanBytes(1024L))
        assertEquals("1.5 KiB", humanBytes(1024L + 512L))
        assertEquals("1023.0 KiB", humanBytes(1024L * 1023L))
        assertEquals("1.0 MiB", humanBytes(1024L * 1024L))
        assertEquals("50.0 MiB", humanBytes(50L * 1024L * 1024L))
        assertEquals("1.00 GiB", humanBytes(1024L * 1024L * 1024L))
        assertEquals("2.50 GiB", humanBytes((2L * 1024L + 512L) * 1024L * 1024L))
        assertEquals("1.00 TiB", humanBytes(1024L * 1024L * 1024L * 1024L))
    }

    @Test
    fun `humanBytes formats negative bytes with a leading minus sign`() {
        assertEquals("-1.0 KiB", humanBytes(-1024L))
        assertEquals("-50.0 MiB", humanBytes(-50L * 1024L * 1024L))
    }
}
