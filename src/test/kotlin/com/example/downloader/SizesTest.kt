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
}
