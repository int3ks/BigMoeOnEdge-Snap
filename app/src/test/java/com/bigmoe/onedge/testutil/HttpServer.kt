package com.bigmoe.onedge.testutil

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * A tiny HTTP/1.1 test server with the slice of com.sun.net.httpserver's API the tests use. The JDK's own server is
 * not on the Android unit-test compile classpath (android.jar), so the tests that need a real local server use this.
 * One request per connection (Connection: close); the response is buffered and sent when the body is closed.
 */
class HttpServer private constructor(private val socket: ServerSocket) {
    fun interface Handler { fun handle(ex: Exchange) }

    class Headers {
        private val map = LinkedHashMap<String, MutableList<String>>()
        fun add(name: String, value: String) { map.getOrPut(name.lowercase()) { ArrayList() }.add(value) }
        fun getFirst(name: String): String? = map[name.lowercase()]?.firstOrNull()
        internal fun entries(): List<Pair<String, String>> = map.flatMap { (k, v) -> v.map { k to it } }
    }

    class Exchange internal constructor(
        val requestMethod: String,
        val requestURI: URI,
        val requestHeaders: Headers,
        val requestBody: InputStream,
        private val out: OutputStream,
        private val onDone: () -> Unit
    ) {
        val responseHeaders = Headers()
        private var status = 200
        private var sent = false
        private val buffer = ByteArrayOutputStream()
        val responseBody: OutputStream = object : OutputStream() {
            override fun write(b: Int) = buffer.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)
            override fun close() = this@Exchange.close()
        }

        fun sendResponseHeaders(code: Int, @Suppress("UNUSED_PARAMETER") length: Long) { status = code }

        fun close() {
            if (sent) return
            sent = true
            val body = buffer.toByteArray()
            val head = StringBuilder("HTTP/1.1 $status X\r\n")
            for ((k, v) in responseHeaders.entries()) head.append(k).append(": ").append(v).append("\r\n")
            head.append("Content-Length: ").append(body.size).append("\r\nConnection: close\r\n\r\n")
            try {
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            } finally {
                onDone()
            }
        }
    }

    private val contexts = ConcurrentHashMap<String, Handler>()
    @Volatile private var running = false

    val address: InetSocketAddress get() = InetSocketAddress(socket.inetAddress, socket.localPort)

    fun createContext(path: String, handler: Handler) { contexts[path] = handler }

    fun start() {
        running = true
        thread(isDaemon = true, name = "test-http") {
            while (running) {
                val s = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    fun stop(@Suppress("UNUSED_PARAMETER") delay: Int) {
        running = false
        socket.close()
    }

    private fun serve(s: Socket) {
        s.use { sock ->
            val input = sock.getInputStream().buffered()
            fun line(): String {
                val b = ByteArrayOutputStream()
                while (true) {
                    val c = input.read()
                    if (c < 0 || c == '\n'.code) break
                    if (c != '\r'.code) b.write(c)
                }
                return b.toString(Charsets.ISO_8859_1.name())
            }
            val request = line().split(' ')
            if (request.size < 2) return
            val headers = Headers()
            while (true) {
                val h = line()
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers.add(h.substring(0, i).trim(), h.substring(i + 1).trim())
            }
            val body = when {
                headers.getFirst("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true -> {
                    val all = ByteArrayOutputStream()
                    while (true) {
                        val size = line().substringBefore(';').trim().toIntOrNull(16) ?: 0
                        if (size == 0) { line(); break }
                        val chunk = ByteArray(size)
                        var read = 0
                        while (read < size) { val n = input.read(chunk, read, size - read); if (n < 0) break; read += n }
                        all.write(chunk, 0, read)
                        line()
                    }
                    all.toByteArray()
                }
                else -> {
                    val len = headers.getFirst("Content-Length")?.toIntOrNull() ?: 0
                    val b = ByteArray(len)
                    var read = 0
                    while (read < len) { val n = input.read(b, read, len - read); if (n < 0) break; read += n }
                    b
                }
            }
            val uri = URI(request[1])
            val handler = contexts.entries.filter { uri.path.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
            val done = java.util.concurrent.CountDownLatch(1)
            val ex = Exchange(request[0], uri, headers, ByteArrayInputStream(body), sock.getOutputStream()) { done.countDown() }
            if (handler == null) { ex.sendResponseHeaders(404, -1); ex.close(); return }
            try { handler.handle(ex) } finally { ex.close() }
            done.await()
        }
    }

    companion object {
        fun create(addr: InetSocketAddress, @Suppress("UNUSED_PARAMETER") backlog: Int): HttpServer =
            HttpServer(ServerSocket(addr.port, 50, addr.address))
    }
}
