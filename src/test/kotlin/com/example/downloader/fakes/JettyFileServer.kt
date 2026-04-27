package com.example.downloader.fakes

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.AbstractHandler
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

/**
 * Embedded Jetty server that serves a single directory of static files with native HTTP/1.1
 * range support. Used by stress scenarios that exercise the chunkSize=8 MiB / parallelism=16
 * geometry, which the stdlib `com.sun.net.httpserver.HttpServer` deadlocks on under load.
 *
 * Implementation is a small custom handler rather than Jetty's `ResourceHandler` because the
 * latter has finicky setup requirements (`ResourceFactory` lifecycle, `ContextHandler`
 * wrapping) that varied between Jetty 11 patch releases. The 60 lines of custom handler code
 * below are easier to reason about than the right combination of `ResourceHandler`
 * configuration knobs.
 *
 * Compared to [TestHttpServer]: this is purely a static-file server with no fault injection,
 * so it can't cover failure-mode scenarios. It only exists to validate the streaming property
 * under realistic upstream behavior.
 */
class JettyFileServer(
    directory: Path,
    advertiseAcceptRanges: Boolean = true,
) : AutoCloseable {

    private val server = Server()

    val baseUrl: URL

    init {
        val connector = ServerConnector(server)
        connector.host = "127.0.0.1"
        connector.port = 0
        server.addConnector(connector)
        server.handler = StaticFileHandler(directory, advertiseAcceptRanges)
        server.start()
        baseUrl = URL("http://127.0.0.1:${connector.localPort}/")
    }

    fun url(fileName: String): URL = URL(baseUrl, fileName.removePrefix("/"))

    override fun close() {
        server.stop()
    }
}

private class StaticFileHandler(
    private val baseDir: Path,
    private val advertiseAcceptRanges: Boolean,
) : AbstractHandler() {

    override fun handle(
        target: String,
        baseRequest: Request,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val relative = target.removePrefix("/")
        val file = baseDir.resolve(relative)
        if (!Files.isRegularFile(file)) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            baseRequest.isHandled = true
            return
        }
        val totalLength = Files.size(file)
        if (advertiseAcceptRanges) response.setHeader("Accept-Ranges", "bytes")
        response.setHeader("Content-Type", "application/octet-stream")

        val rangeHeader = request.getHeader("Range")
        if (rangeHeader != null) {
            handleRangedGet(request, response, file, totalLength, rangeHeader)
        } else {
            response.status = HttpServletResponse.SC_OK
            response.setContentLengthLong(totalLength)
            if (request.method == "HEAD") {
                baseRequest.isHandled = true
                return
            }
            streamFile(file, 0L, totalLength, response)
        }
        baseRequest.isHandled = true
    }

    private fun handleRangedGet(
        request: HttpServletRequest,
        response: HttpServletResponse,
        file: Path,
        totalLength: Long,
        rangeHeader: String,
    ) {
        val parsed = parseRange(rangeHeader, totalLength)
        if (parsed == null) {
            response.status = HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE
            return
        }
        val (start, end) = parsed
        val length = end - start + 1
        response.status = HttpServletResponse.SC_PARTIAL_CONTENT
        response.setHeader("Content-Range", "bytes $start-$end/$totalLength")
        response.setContentLengthLong(length)
        if (request.method == "HEAD") return
        streamFile(file, start, length, response)
    }

    private fun streamFile(file: Path, offset: Long, length: Long, response: HttpServletResponse) {
        val out = response.outputStream
        val buf = ByteArray(STREAM_BUFFER_SIZE)
        Files.newByteChannel(file).use { channel ->
            channel.position(offset)
            val byteBuf = java.nio.ByteBuffer.wrap(buf)
            var remaining = length
            while (remaining > 0) {
                byteBuf.clear()
                val cap = minOf(remaining, buf.size.toLong()).toInt()
                byteBuf.limit(cap)
                val read = channel.read(byteBuf)
                if (read < 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
        }
    }

    private companion object {
        const val STREAM_BUFFER_SIZE = 64 * 1024
        val RANGE_REGEX = Regex("""^\s*bytes\s*=\s*(\d+)-(\d+)\s*$""")

        fun parseRange(header: String, totalLength: Long): Pair<Long, Long>? {
            val m = RANGE_REGEX.matchEntire(header) ?: return null
            val start = m.groupValues[1].toLongOrNull()
            val end = m.groupValues[2].toLongOrNull()
            val ok = start != null && end != null &&
                start in 0L..<totalLength && end in start..<totalLength
            return if (ok) start!! to end!! else null
        }
    }
}
