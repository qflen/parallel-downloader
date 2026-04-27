package com.example.downloader.fakes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

class JettyFileServerSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `Jetty serves a simple file via HEAD and GET`() {
        Files.writeString(tempDir.resolve("hi.txt"), "hello world")
        JettyFileServer(tempDir).use { server ->
            val client = HttpClient.newBuilder().build()
            val headReq = HttpRequest.newBuilder(URI.create(server.url("hi.txt").toString()))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            val headResp = client.send(headReq, HttpResponse.BodyHandlers.discarding())
            System.err.println("[smoke] HEAD ${server.url("hi.txt")} -> ${headResp.statusCode()} ${headResp.headers().map()}")
            assertEquals(200, headResp.statusCode())

            val getReq = HttpRequest.newBuilder(URI.create(server.url("hi.txt").toString())).GET().build()
            val getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString())
            assertEquals("hello world", getResp.body())
        }
    }
}
