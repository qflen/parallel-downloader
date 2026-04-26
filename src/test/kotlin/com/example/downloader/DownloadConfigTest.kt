package com.example.downloader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DownloadConfigTest {

    @Test
    fun `no-arg construction uses documented defaults`() {
        val cfg = DownloadConfig()
        assertEquals(8L * 1024L * 1024L, cfg.chunkSize)
        assertEquals(8, cfg.parallelism)
        assertSame(ProgressListener.NoOp, cfg.progressListener)
        assertTrue(cfg.overwriteExisting)
    }

    @Test
    fun `DSL builder sets all fields`() {
        val recorder = object : ProgressListener {}
        val cfg = downloadConfig {
            chunkSize = 1.MiB
            parallelism = 16
            progressListener = recorder
            overwriteExisting = false
        }
        assertEquals(1024L * 1024L, cfg.chunkSize)
        assertEquals(16, cfg.parallelism)
        assertSame(recorder, cfg.progressListener)
        assertEquals(false, cfg.overwriteExisting)
    }

    @Test
    fun `Builder validates chunkSize is positive`() {
        assertThrows<IllegalArgumentException> { downloadConfig { chunkSize = 0L } }
        assertThrows<IllegalArgumentException> { downloadConfig { chunkSize = -1L } }
    }

    @Test
    fun `Builder validates parallelism is positive`() {
        assertThrows<IllegalArgumentException> { downloadConfig { parallelism = 0 } }
        assertThrows<IllegalArgumentException> { downloadConfig { parallelism = -4 } }
    }
}
