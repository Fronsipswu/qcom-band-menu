package dev.qcom.bandmenu.network

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

/**
 * Independent implementation of MIC's sampling method. HTTP uploads require an endpoint that
 * consumes the complete chunked request before acknowledging it. UDP requires a full opaque echo;
 * neither endpoint protocol claims compatibility with undisclosed MIC server software.
 */
object MicDataTransport {
    fun execute(c: DataTestConfig, io: TestIo, control: AttemptControl): AttemptMeasurement = when (c.kind) {
        DataTestKind.PARALLEL_DOWNLOAD -> parallel(c, io, control, false)
        DataTestKind.PARALLEL_UPLOAD -> parallel(c, io, control, true)
        DataTestKind.UDP_LATENCY -> udp(c, io, control)
        else -> throw IOException("MIC point rounds must dispatch download, upload and UDP phases separately")
    }

    private fun parallel(c: DataTestConfig, io: TestIo, control: AttemptControl, upload: Boolean): AttemptMeasurement {
        val start = System.nanoTime()
        val count = c.effectiveParallelConnections
        require(count in 1..16) { "Parallel connection count must be 1–16" }
        val executor = Executors.newFixedThreadPool(count) { runnable ->
            Thread(runnable, "MIC HTTP stream").apply { isDaemon = true }
        }
        control.track(Closeable { executor.shutdownNow() })
        val startGate = ParallelStart(count)
        val futures = (1..count).map { index -> executor.submit(Callable { stream(c, io, control, upload, index, startGate) }) }
        val streams = try {
            futures.mapIndexed { index, future ->
                try { future.get() } catch (e: Exception) {
                    MicStreamResult(index + 1, 0, 0.0, 0, 0.0, false, error = safeError(e), stopReason = "INTERRUPTED")
                }
            }
        } finally {
            executor.shutdownNow()
        }
        val error = streams.firstOrNull { !it.completed || it.error != null }?.let {
            "Stream ${it.index}: ${it.error ?: "measurement did not complete"}"
        }
        val transfer = MicTransferResult(streams = streams, error = error, expectedStreams = count)
        return AttemptMeasurement(bytes = streams.sumOf { it.warmupBytes + it.bytes }, durationMs = elapsedMs(start),
            throughputMbps = transfer.aggregateMbps, error = error, measurementAvailable = error == null,
            mic = if (upload) MicMeasurement(upload = transfer) else MicMeasurement(download = transfer),
            detail = if (upload) "Independent chunked HTTP POST; each stream's rate includes its final server acknowledgement; sum of individual rates"
                else "Independent HTTP GET; first-payload warm-up excluded; sum of individual stream rates")
    }

    private fun stream(c: DataTestConfig, io: TestIo, parent: AttemptControl, upload: Boolean, index: Int,
        startGate: ParallelStart): MicStreamResult {
        val control = AttemptControl()
        var status: Int? = null
        var passedStartGate = false
        val window = MicTransferWindow(c.effectiveWarmupSeconds * NS_PER_SECOND, c.effectiveWarmupBytes,
            c.effectiveMeasureSeconds * NS_PER_SECOND, c.effectiveMeasureBytes)
        try {
            parent.track(control)
            parent.check()
            val uri = URI(URI(if (upload) c.uploadUrl else c.url).toASCIIString())
            val socket = openHttpSocket(uri, io, control, timeoutMs(c))
            startGate.await(parent)
            passedStartGate = true
            val input = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
            val output = socket.getOutputStream()
            sendHeaders(uri, output, upload)
            if (upload) {
                val buffer = ByteArray(BUFFER_SIZE).also { SecureRandom().nextBytes(it) }
                while (true) {
                    parent.check()
                    val now = System.nanoTime()
                    window.advance(now)
                    if (window.done) break
                    val size = window.nextBytes(buffer.size)
                    output.write((size.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII))
                    window.begin(System.nanoTime())
                    output.write(buffer, 0, size)
                    output.write(CRLF)
                    window.accept(size, System.nanoTime())
                }
                // The terminating chunk ends the request cleanly; aborting the connection is not a receipt.
                output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                val response = readResponse(input)
                status = response.status
                if (response.status !in 200..299) throw IOException("HTTP ${response.status}")
                parent.check()
                // Local socket writes can finish while bytes still sit in the send buffer.
                return window.result(index, status, acknowledgementAtNs = System.nanoTime())
            }
            val response = readResponse(input)
            status = response.status
            if (response.status !in 200..299) throw IOException("HTTP ${response.status}")
            if (response.headers["content-encoding"]?.lowercase()?.let { it != "identity" } == true)
                throw IOException("Compressed content cannot be used for MIC payload accounting")
            val contentLength = response.headers["content-length"]?.toLongOrNull()
            if (response.headers.containsKey("content-length") && (contentLength == null || contentLength < 0))
                throw IOException("Invalid HTTP Content-Length")
            if (response.headers.containsKey("content-length") && response.headers.containsKey("transfer-encoding"))
                throw IOException("Ambiguous HTTP response framing")
            if (c.isMic && contentLength != REFERENCE_BYTES)
                throw IOException("Configure a 100 MB reference object with Content-Length: $REFERENCE_BYTES")
            val body = when {
                response.headers["transfer-encoding"]?.equals("chunked", true) == true -> ChunkedBody(input)
                response.headers.containsKey("transfer-encoding") -> throw IOException("Unsupported HTTP transfer encoding")
                else -> FixedBody(input, contentLength)
            }
            val buffer = ByteArray(BUFFER_SIZE)
            // The warm-up does not start until the first payload byte. Polling a real socket timeout
            // allows a stalled, established transfer to produce a full-window measured zero.
            while (true) {
                parent.check()
                window.advance(System.nanoTime())
                if (window.done) break
                socket.soTimeout = if (window.started) window.readTimeoutMs(System.nanoTime()) else timeoutMs(c)
                try {
                    val read = body.read(buffer, 0, window.nextBytes(buffer.size))
                    if (read < 0) throw EOFException(if (window.measuring) "HTTP body ended before the measurement limit" else "HTTP body ended before warm-up completed")
                    if (read > 0) window.accept(read, System.nanoTime())
                } catch (e: SocketTimeoutException) {
                    if (!window.started) throw IOException("No payload arrived before warm-up could start", e)
                    window.advance(System.nanoTime())
                }
            }
            parent.check()
            return window.result(index, status)
        } catch (e: Exception) {
            if (!passedStartGate) startGate.fail(safeError(e))
            val now = System.nanoTime()
            window.advance(now)
            return window.result(index, status, error = safeError(e), acknowledgementAtNs = if (upload) now else null)
        } finally {
            control.cancel()
        }
    }

    /** No request may start sampling while another nominally parallel connection is still opening. */
    private class ParallelStart(count: Int) {
        private val ready = CountDownLatch(count)
        private val failure = AtomicReference<String?>(null)
        fun await(control: AttemptControl) {
            ready.countDown()
            while (!ready.await(50, TimeUnit.MILLISECONDS)) {
                control.check()
                failure.get()?.let { throw IOException("Parallel connection setup failed: $it") }
            }
            control.check()
            failure.get()?.let { throw IOException("Parallel connection setup failed: $it") }
        }
        fun fail(reason: String) {
            failure.compareAndSet(null, reason)
            while (ready.count > 0) ready.countDown()
        }
    }

    private fun openHttpSocket(uri: URI, io: TestIo, control: AttemptControl, timeout: Int): Socket {
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.fragment != null)
            throw IOException("A valid HTTP(S) URL without credentials or fragment is required")
        val port = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        val socket = control.track(io.openSocket(uri.host, port, timeout))
        socket.tcpNoDelay = true
        socket.soTimeout = timeout
        if (uri.scheme != "https") return socket
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(socket, uri.host, port, true) as SSLSocket
        // Closing the underlying TCP socket first also interrupts a blocked TLS write/handshake;
        // an orderly TLS close alone may wait for that writer's lock.
        control.track(Closeable { try { socket.close() } finally { tls.close() } })
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.soTimeout = timeout
        tls.startHandshake()
        return tls
    }

    private fun sendHeaders(uri: URI, output: OutputStream, upload: Boolean) {
        val path = uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"
        val target = path + (uri.rawQuery?.let { "?$it" } ?: "")
        val host = if (':' in uri.host && !uri.host.startsWith('[')) "[${uri.host}]" else uri.host
        val authority = host + if (uri.port >= 0) ":${uri.port}" else ""
        val headers = buildString {
            append(if (upload) "POST" else "GET").append(' ').append(target).append(" HTTP/1.1\r\n")
            append("Host: ").append(authority).append("\r\nConnection: close\r\nAccept-Encoding: identity\r\nCache-Control: no-cache, no-store\r\n")
            if (upload) append("Transfer-Encoding: chunked\r\nContent-Type: application/octet-stream\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private data class Response(val status: Int, val headers: Map<String, String>)
    private fun readResponse(input: InputStream): Response {
        val lines = HttpLineReader(input)
        repeat(6) {
            val statusLine = lines.read()
            val match = Regex("HTTP/1\\.[01] ([0-9]{3})(?: .*)?").matchEntire(statusLine)
                ?: throw IOException("Malformed HTTP status line")
            val status = match.groupValues[1].toInt()
            val headers = linkedMapOf<String, String>()
            var total = statusLine.length
            var count = 0
            while (true) {
                val line = lines.read()
                if (line.isEmpty()) break
                total += line.length
                if (++count > 128 || total > 65_536) throw IOException("HTTP response headers exceeded limit")
                val split = line.indexOf(':')
                if (split <= 0) throw IOException("Malformed HTTP response header")
                val key = line.substring(0, split).trim().lowercase()
                val value = line.substring(split + 1).trim()
                if (key in headers && headers[key] != value && key in listOf("content-length", "transfer-encoding"))
                    throw IOException("Conflicting HTTP framing headers")
                headers[key] = value
            }
            if (status == 100 || status == 102 || status == 103) return@repeat
            return Response(status, headers)
        }
        throw IOException("Too many provisional HTTP responses")
    }

    /** Retains a partial line across read timeouts, including chunk header/trailer reads. */
    private class HttpLineReader(private val input: InputStream) {
        private val line = StringBuilder()
        fun read(): String {
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("HTTP stream ended in a line")
                if (b == 10) return line.toString().removeSuffix("\r").also { line.setLength(0) }
                if (line.length >= 16_384) throw IOException("HTTP line exceeded limit")
                line.append(b.toChar())
            }
        }
    }

    private class FixedBody(private val input: InputStream, private var remaining: Long?) : InputStream() {
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val result = input.read(buffer, offset, min(length.toLong(), remaining ?: Long.MAX_VALUE).toInt())
            if (result > 0) remaining = remaining?.minus(result)
            return result
        }
    }

    private class ChunkedBody(private val input: InputStream) : InputStream() {
        private val lines = HttpLineReader(input)
        private var remaining = 0L
        private var delimiter = false
        private var finished = false
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (finished) return -1
            if (remaining == 0L) {
                if (delimiter) {
                    if (lines.read().isNotEmpty()) throw IOException("Invalid HTTP chunk delimiter")
                    delimiter = false
                }
                val size = lines.read().substringBefore(';').trim().toLongOrNull(16)
                if (size == null || size < 0) throw IOException("Invalid HTTP chunk size")
                if (size == 0L) { finished = true; return -1 }
                remaining = size
            }
            val read = input.read(buffer, offset, min(length.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("HTTP chunk was truncated")
            remaining -= read
            if (remaining == 0L) delimiter = true
            return read
        }
    }

    private fun udp(c: DataTestConfig, io: TestIo, control: AttemptControl): AttemptMeasurement {
        val start = System.nanoTime()
        val probes = ArrayList<UdpProbe>()
        val owned = AttemptControl()
        var failure: String? = null
        try {
            control.track(owned)
            val address = io.resolve(c.host)
            val socket = owned.track(io.openDatagram())
            socket.connect(address, c.port)
            val nonce = SecureRandom().nextInt().toLong() and 0xffffffffL
            var nextSend = System.nanoTime()
            val intervalNs = c.effectiveUdpIntervalMs * 1_000_000L
            val deadlineNs = c.effectiveUdpTimeoutMs * 1_000_000L
            val receivedBuffer = ByteArray(256)
            while (true) {
                control.check()
                var now = System.nanoTime()
                if (probes.size < c.effectiveUdpCount && now >= nextSend) {
                    val index = probes.size
                    val payload = ByteBuffer.allocate(16).putLong(now).putLong((nonce shl 32) or index.toLong()).array()
                    socket.send(DatagramPacket(payload, payload.size, address, c.port))
                    probes += UdpProbe(index, now, now + deadlineNs, payload)
                    // Do not send catch-up bursts after scheduler delays.
                    nextSend = now + intervalNs
                }
                now = System.nanoTime()
                probes.filter { it.outcome == null && now >= it.deadlineNs }.forEach { it.outcome = "LOST" }
                if (probes.size == c.effectiveUdpCount && probes.all { it.outcome != null }) break
                val nextDeadline = probes.filter { it.outcome == null }.minOfOrNull { it.deadlineNs } ?: Long.MAX_VALUE
                val nextEvent = min(nextDeadline, if (probes.size < c.effectiveUdpCount) nextSend else Long.MAX_VALUE)
                socket.soTimeout = ((nextEvent - now + 999_999L) / 1_000_000L).coerceIn(1, 1000).toInt()
                val packet = DatagramPacket(receivedBuffer, receivedBuffer.size)
                try {
                    socket.receive(packet)
                    val at = System.nanoTime()
                    if (packet.address != address || packet.port != c.port || packet.length != 16) continue
                    val raw = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    val sequence = ByteBuffer.wrap(raw).getLong(8)
                    val index = (sequence and 0xffffffffL).toInt()
                    val sent = probes.getOrNull(index) ?: continue
                    if (sent.outcome != null || at > sent.deadlineNs || !raw.contentEquals(sent.payload)) continue
                    sent.rttMs = (at - sent.sentAtNs) / 1e6
                    sent.outcome = "REPLIED"
                } catch (_: SocketTimeoutException) {
                    // The next iteration sends a due probe and expires individual deadlines.
                }
            }
        } catch (e: Exception) {
            failure = safeError(e)
            probes.filter { it.outcome == null }.forEach { it.outcome = if (control.isCancelled) "CANCELLED" else "ERROR" }
        } finally {
            owned.cancel()
        }
        val rtts = probes.mapNotNull { it.rttMs }
        val received = rtts.size
        val result = MicUdpResult(sent = probes.size, received = received, rttsMs = rtts,
            lost = probes.count { it.outcome == "LOST" }, error = failure,
            probes = probes.map { MicUdpProbe(it.index, it.sentAtNs, it.rttMs, it.outcome ?: "CANCELLED") })
        return AttemptMeasurement(bytes = received * 16L, durationMs = elapsedMs(start), rttMs = result.averageRttMs,
            error = failure, measurementAvailable = failure == null, mic = MicMeasurement(udp = result),
            detail = "Independent 16-byte UDP echo: big-endian timestamp64 + unique sequence64; full payload and peer must match before each deadline")
    }

    private class UdpProbe(val index: Int, val sentAtNs: Long, val deadlineNs: Long, val payload: ByteArray,
        var rttMs: Double? = null, var outcome: String? = null)

    private fun timeoutMs(c: DataTestConfig) = (c.timeoutSeconds.toLong() * 1000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    private fun elapsedMs(start: Long) = ((System.nanoTime() - start) / 1e6).coerceAtLeast(0.001)
    private fun safeError(e: Exception) = (e.message ?: e.javaClass.simpleName).take(300)
    private val CRLF = byteArrayOf(13, 10)
    private const val BUFFER_SIZE = 64 * 1024
    private const val REFERENCE_BYTES = 100_000_000L
    private const val NS_PER_SECOND = 1_000_000_000L
}

/** Payload-only sampler: all boundaries are monotonic and warm-up bytes never enter the rate. */
internal class MicTransferWindow(private val warmupNs: Long, private val warmupLimit: Long,
    private val measureNs: Long, private val measureLimit: Long) {
    private var firstByteAt: Long? = null
    private var measureAt: Long? = null
    private var endedAt: Long? = null
    private var observedAt: Long? = null
    private var warmBytes = 0L
    private var measuredBytes = 0L
    private var reason = ""
    val started get() = firstByteAt != null
    val measuring get() = measureAt != null
    val done get() = endedAt != null

    fun begin(now: Long) { if (firstByteAt == null) firstByteAt = now; observedAt = now }

    fun advance(now: Long) {
        observedAt = now
        val first = firstByteAt ?: return
        if (measureAt == null && now >= first + warmupNs) measureAt = first + warmupNs
        val start = measureAt ?: return
        if (endedAt == null && now >= start + measureNs) {
            endedAt = start + measureNs
            reason = "TIME_LIMIT"
        }
    }

    fun accept(count: Int, now: Long) {
        begin(now)
        advance(now)
        if (done) return
        var left = count.toLong()
        if (measureAt == null) {
            val warm = min(left, (warmupLimit + 1 - warmBytes).coerceAtLeast(0))
            warmBytes += warm
            left -= warm
            if (warmBytes > warmupLimit) measureAt = now
        }
        if (measureAt != null) {
            measuredBytes += min(left, (measureLimit - measuredBytes).coerceAtLeast(0))
            if (measuredBytes >= measureLimit) { endedAt = now; reason = "BYTE_LIMIT" }
        }
    }

    fun nextBytes(maximum: Int): Int = min(maximum.toLong(), if (measureAt == null)
        (warmupLimit + 1 - warmBytes).coerceAtLeast(1) else (measureLimit - measuredBytes).coerceAtLeast(1)).toInt()

    fun readTimeoutMs(now: Long): Int {
        val next = measureAt?.plus(measureNs) ?: firstByteAt?.plus(warmupNs) ?: return 250
        return ((next - now + 999_999L) / 1_000_000L).coerceIn(1, 250).toInt()
    }

    fun result(index: Int, status: Int?, error: String? = null, acknowledgementAtNs: Long? = null): MicStreamResult {
        val measurementStart = measureAt
        val end = if (acknowledgementAtNs != null && done) maxOf(endedAt!!, acknowledgementAtNs) else endedAt ?: observedAt
        val duration = if (measurementStart != null && end != null) ((end - measurementStart) / 1e6).coerceAtLeast(0.001) else 0.0
        val warmEnd = measurementStart ?: observedAt
        val warmDuration = if (firstByteAt != null && warmEnd != null) (warmEnd - firstByteAt!!) / 1e6 else 0.0
        return MicStreamResult(index, warmBytes, warmDuration, measuredBytes, duration,
            completed = done && error == null, httpStatus = status, error = error,
            stopReason = if (error != null) "FAILED: ${reason.ifEmpty { if (started) "WARMUP_OR_TRANSFER" else "NO_PAYLOAD" }}" else reason)
    }
}
