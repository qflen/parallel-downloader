package com.example.downloader

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/**
 * Direct unit tests for the chunk-bounds enforcement in [makeChunkSink]. The bounds check is
 * a paranoia guard against a misbehaving fetcher — it can't easily be triggered through the
 * real HTTP integration path because [com.example.downloader.http.JdkHttpRangeFetcher]
 * validates `Content-Range` first. Test here directly.
 */
class MakeChunkSinkTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writes within bounds succeed and land at the requested offset`() {
        val dest = tempDir.resolve("ok.bin")
        FileChannel.open(dest, WRITE, CREATE, TRUNCATE_EXISTING).use { channel ->
            val chunk = Chunk(index = 0, start = 100L, endInclusive = 200L)
            val sink = makeChunkSink(channel, chunk)
            val payload = ByteArray(10) { (it + 1).toByte() }
            runBlocking {
                sink.write(100L, ByteBuffer.wrap(payload))
            }
            // Read back the bytes we wrote.
            val readBack = ByteArray(10)
            FileChannel.open(dest).use { rc ->
                rc.position(100L)
                val buf = ByteBuffer.wrap(readBack)
                while (buf.hasRemaining()) rc.read(buf)
            }
            assertEquals(payload.toList(), readBack.toList())
        }
    }

    @Test
    fun `write before chunk start throws IllegalArgumentException`() {
        val dest = tempDir.resolve("under.bin")
        FileChannel.open(dest, WRITE, CREATE, TRUNCATE_EXISTING).use { channel ->
            val sink = makeChunkSink(channel, Chunk(0, 100L, 200L))
            val ex = assertThrows<IllegalArgumentException> {
                runBlocking {
                    sink.write(50L, ByteBuffer.wrap(ByteArray(10)))
                }
            }
            assertEquals(true, ex.message!!.contains("before chunk start"))
        }
    }

    @Test
    fun `write extending past chunk end throws IllegalArgumentException`() {
        val dest = tempDir.resolve("over.bin")
        FileChannel.open(dest, WRITE, CREATE, TRUNCATE_EXISTING).use { channel ->
            val sink = makeChunkSink(channel, Chunk(0, 100L, 200L))
            // Writing 10 bytes starting at 195 covers 195..204, which is past 200.
            val ex = assertThrows<IllegalArgumentException> {
                runBlocking {
                    sink.write(195L, ByteBuffer.wrap(ByteArray(10)))
                }
            }
            assertEquals(true, ex.message!!.contains("extends beyond chunk end"))
        }
    }
}
