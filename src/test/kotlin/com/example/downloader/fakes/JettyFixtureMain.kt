package com.example.downloader.fakes

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.AbstractHandler
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Standalone CLI fixture for the comparison benchmark. Brings up a Jetty server on a fixed
 * port with the same `firstByteLatencyMillis` knob the WAN-latency benchmark uses, against
 * which curl / aria2c / wget / parallel-downloader can be compared.
 *
 * Run via:
 * ```
 * ./gradlew jettyFixture --args="--port 8090 --latency 20 --root /tmp/comparison"
 * ```
 *
 * Lives in the test source set because the production runtime classpath stays
 * `kotlinx-coroutines-core` only; Jetty is `testImplementation`.
 */
object JettyFixtureMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var port = DEFAULT_PORT
        var latencyMillis = 0L
        var rootDir: Path? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--port" -> { port = args[i + 1].toInt(); i += 2 }
                "--latency" -> { latencyMillis = args[i + 1].toLong(); i += 2 }
                "--root" -> { rootDir = Paths.get(args[i + 1]); i += 2 }
                else -> {
                    System.err.println("Unknown arg: ${args[i]}")
                    System.exit(USAGE_EXIT_CODE)
                    return
                }
            }
        }
        val root = rootDir
        require(root != null && Files.isDirectory(root)) {
            "--root must be an existing directory; got $rootDir"
        }
        val server = ComparisonFixtureServer(root, port, latencyMillis)
        Runtime.getRuntime().addShutdownHook(
            Thread { runCatching { server.close() } },
        )
        System.err.println("Jetty fixture listening on ${server.baseUrl}")
        System.err.println("  root=${root.toAbsolutePath()} latency=${latencyMillis}ms")
        Thread.currentThread().join()
    }

    private const val DEFAULT_PORT = 8090
    private const val USAGE_EXIT_CODE = 64
}

/**
 * Static-file server bound to a fixed port. Mirrors the small handler in [JettyFileServer]
 * (which uses an ephemeral port for tests); duplication is preferable to threading a "fixed
 * port" knob through a class whose other callers want ephemeral ports.
 */
internal class ComparisonFixtureServer(
    directory: Path,
    port: Int,
    firstByteLatencyMillis: Long = 0L,
) : AutoCloseable {

    private val server = Server()
    val baseUrl: URL

    init {
        val connector = ServerConnector(server)
        connector.host = "127.0.0.1"
        connector.port = port
        server.addConnector(connector)
        server.handler = ComparisonHandler(directory, firstByteLatencyMillis)
        server.start()
        baseUrl = URL("http://127.0.0.1:${connector.localPort}/")
    }

    override fun close() {
        server.stop()
    }
}

private class ComparisonHandler(
    private val baseDir: Path,
    private val firstByteLatencyMillis: Long,
) : AbstractHandler() {

    override fun handle(
        target: String,
        baseRequest: Request,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        if (firstByteLatencyMillis > 0L) Thread.sleep(firstByteLatencyMillis)
        val relative = target.removePrefix("/")
        val file = baseDir.resolve(relative)
        if (!Files.isRegularFile(file)) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            baseRequest.isHandled = true
            return
        }
        val totalLength = Files.size(file)
        response.setHeader("Accept-Ranges", "bytes")
        response.setHeader("Content-Type", "application/octet-stream")

        val rangeHeader = request.getHeader("Range")
        if (rangeHeader != null) {
            handleRange(request, response, file, totalLength, rangeHeader)
        } else {
            response.status = HttpServletResponse.SC_OK
            response.setContentLengthLong(totalLength)
            if (request.method != "HEAD") streamFile(file, 0L, totalLength, response)
        }
        baseRequest.isHandled = true
    }

    private fun handleRange(
        request: HttpServletRequest,
        response: HttpServletResponse,
        file: Path,
        totalLength: Long,
        rangeHeader: String,
    ) {
        val m = RANGE_REGEX.matchEntire(rangeHeader)
        if (m == null) {
            response.status = HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE
            return
        }
        val start = m.groupValues[1].toLong()
        val end = m.groupValues[2].toLong()
        val length = end - start + 1
        response.status = HttpServletResponse.SC_PARTIAL_CONTENT
        response.setHeader("Content-Range", "bytes $start-$end/$totalLength")
        response.setContentLengthLong(length)
        if (request.method != "HEAD") streamFile(file, start, length, response)
    }

    private fun streamFile(file: Path, offset: Long, length: Long, response: HttpServletResponse) {
        val out = response.outputStream
        val buf = ByteArray(STREAM_BUFFER_SIZE)
        Files.newByteChannel(file).use { channel ->
            channel.position(offset)
            val byteBuf = ByteBuffer.wrap(buf)
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
    }
}
