package dev.qcom.bandmenu.network

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MicDataTransportTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private class LocalIo(private val sendBuffer: Int? = null) : TestIo {
        override val interfaceName = "lo"
        override fun openSocket(host: String, port: Int, timeoutMs: Int): Socket = Socket().apply {
            sendBuffer?.let { sendBufferSize = it }
            try { connect(InetSocketAddress(host, port), timeoutMs) }
            catch (e: Exception) { close(); throw e }
        }
        override fun openHttp(url: URL) = url.openConnection() as HttpURLConnection
        override fun resolve(host: String): InetAddress = InetAddress.getByName(host)
    }

    private class HttpServer(count: Int, script: (Socket) -> Unit) : Closeable {
        private val server = ServerSocket(0, count, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        private val executor = Executors.newFixedThreadPool(count + 1)
        val port get() = server.localPort
        private val finished = executor.submit {
            val handlers = (1..count).map {
                val client = server.accept().apply { soTimeout = 4000 }
                executor.submit { client.use(script) }
            }
            handlers.forEach { it.get(5, TimeUnit.SECONDS) }
        }
        override fun close() {
            try { finished.get(7, TimeUnit.SECONDS) }
            finally { server.close(); executor.shutdownNow() }
        }
    }

    private inner class UdpServer(script: (DatagramSocket) -> Unit) : Closeable {
        private val socket = DatagramSocket(0, loopback).apply { soTimeout = 3000 }
        private val executor = Executors.newSingleThreadExecutor()
        val port get() = socket.localPort
        private val finished = executor.submit { script(socket) }
        override fun close() {
            try { finished.get(4, TimeUnit.SECONDS) }
            finally { socket.close(); executor.shutdownNow() }
        }
    }

    private fun config(kind: DataTestKind, port: Int, count: Int = 1) = DataTestConfig(kind = kind,
        regime = TestRegime.CUSTOM, custom = true, parallelConnections = count, timeoutSeconds = 4,
        warmupSeconds = 1, warmupBytes = 20, measureSeconds = 1, measureBytes = 50,
        url = "http://127.0.0.1:$port/download", uploadUrl = "http://127.0.0.1:$port/upload",
        host = "127.0.0.1", port = port, udpCount = 4, udpIntervalMs = 20, udpTimeoutMs = 80)

    private fun execute(c: DataTestConfig, io: TestIo = LocalIo()) = AttemptControl().use { MicDataTransport.execute(c, io, it) }

    @Test fun parallelDownloadUsesThreeConcurrentConnectionsAndSumsIndividualRates() {
        val allConnected = CountDownLatch(3)
        HttpServer(3) { socket ->
            assertEquals("GET /download HTTP/1.1", headers(socket.getInputStream()).first())
            allConnected.countDown()
            assertTrue("Requests must run concurrently", allConnected.await(2, TimeUnit.SECONDS))
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + "x".repeat(100)).toByteArray())
        }.use { server ->
            val result = execute(config(DataTestKind.PARALLEL_DOWNLOAD, server.port, 3))
            assertNull(result.error)
            val transfer = result.mic!!.download!!
            assertEquals(3, transfer.streams.size)
            assertTrue(transfer.streams.all { it.warmupBytes == 21L && it.bytes == 50L && it.completed })
            assertEquals(213L, result.bytes)
            assertEquals(transfer.streams.sumOf { it.rateMbps!! }, transfer.aggregateMbps!!, 0.0000001)
            assertEquals(transfer.aggregateMbps, result.throughputMbps)
        }
    }

    @Test fun noHttpRequestStartsUntilTheDelayedThirdConnectionIsReady() {
        val thirdReady = AtomicBoolean(false)
        val connected = AtomicInteger()
        val delegate = LocalIo()
        val io = object : TestIo by delegate {
            override fun openSocket(host: String, port: Int, timeoutMs: Int): Socket {
                val socket = delegate.openSocket(host, port, timeoutMs)
                if (connected.incrementAndGet() == 3) {
                    Thread.sleep(150) // Emulates the third connection's slow TCP/TLS setup.
                    thirdReady.set(true)
                }
                return socket
            }
        }
        HttpServer(3) { socket ->
            headers(socket.getInputStream())
            assertTrue("No stream may send its request before all connections are ready", thirdReady.get())
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + "x".repeat(100)).toByteArray())
        }.use { server ->
            val result = execute(config(DataTestKind.PARALLEL_DOWNLOAD, server.port, 3), io)
            assertNull(result.error)
            assertEquals(3, result.mic!!.download!!.streams.count { it.completed })
        }
    }

    @Test fun failedConnectionReleasesBarrierSiblingsWithoutSendingRequests() {
        val opened = AtomicInteger()
        val delegate = LocalIo()
        val io = object : TestIo by delegate {
            override fun openSocket(host: String, port: Int, timeoutMs: Int): Socket {
                if (opened.incrementAndGet() == 3) throw java.io.IOException("Test connection failed")
                return delegate.openSocket(host, port, timeoutMs)
            }
        }
        HttpServer(2) { socket ->
            assertEquals("A partial connection set must never start HTTP sampling", -1, socket.getInputStream().read())
        }.use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<AttemptMeasurement> { execute(config(DataTestKind.PARALLEL_DOWNLOAD, server.port, 3), io) }
                    .get(2, TimeUnit.SECONDS)
                assertNotNull(result.error)
                assertNull(result.throughputMbps)
                assertTrue(result.mic!!.download!!.streams.none { it.completed })
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun unicodeHttpPathsAndQueriesArePercentEncoded() {
        HttpServer(1) { socket ->
            assertEquals("GET /%E6%B8%AC%E5%AE%9A?q=%E9%80%9F%E5%BA%A6 HTTP/1.1", headers(socket.getInputStream()).first())
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + "x".repeat(100)).toByteArray())
        }.use { server ->
            val result = execute(config(DataTestKind.PARALLEL_DOWNLOAD, server.port).copy(url = "http://127.0.0.1:${server.port}/測定?q=速度"))
            assertNull(result.error)
        }
    }

    @Test fun micDownloadRequiresTheDocumentedReferenceObjectSize() {
        HttpServer(3) { socket ->
            headers(socket.getInputStream())
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 42\r\n\r\n".toByteArray())
        }.use { server ->
            val result = execute(config(DataTestKind.PARALLEL_DOWNLOAD, server.port, 3).copy(regime = TestRegime.MIC, custom = false))
            assertNull(result.throughputMbps)
            assertTrue(result.error!!.contains("100 MB reference object"))
            assertFalse(result.measurementAvailable)
        }
    }

    @Test fun shortBodyAndHttpFailureNeverBecomeMeasuredZero() {
        for (status in listOf(200, 503)) {
            HttpServer(1) { socket ->
                headers(socket.getInputStream())
                socket.getOutputStream().write(("HTTP/1.1 $status Result\r\nContent-Length: 20\r\n\r\n" + "x".repeat(20)).toByteArray())
            }.use { server ->
                val result = execute(config(DataTestKind.PARALLEL_DOWNLOAD, server.port))
                val stream = result.mic!!.download!!.streams.single()
                assertNotNull(result.error)
                assertNull(result.throughputMbps)
                assertFalse(stream.completed)
                assertEquals(status, stream.httpStatus)
            }
        }
    }

    @Test fun conflictingHttpFramingCannotVerifyAReferenceObject() {
        HttpServer(1) { socket ->
            headers(socket.getInputStream())
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
        }.use { server ->
            val result = execute(config(DataTestKind.PARALLEL_DOWNLOAD, server.port))
            assertTrue(result.error!!.contains("Ambiguous HTTP response framing"))
            assertNull(result.throughputMbps)
        }
    }

    @Test fun stalledEstablishedDownloadProducesAFullWindowZeroAfterWarmup() {
        HttpServer(1) { socket ->
            headers(socket.getInputStream())
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx".toByteArray())
            socket.getOutputStream().flush()
            assertEquals(-1, socket.getInputStream().read())
        }.use { server ->
            val result = execute(config(DataTestKind.PARALLEL_DOWNLOAD, server.port).copy(warmupBytes = 0))
            val stream = result.mic!!.download!!.streams.single()
            assertNull(result.error)
            assertTrue(stream.completed)
            assertEquals(0L, stream.bytes)
            assertEquals(1000.0, stream.durationMs, 0.001)
            assertEquals(0.0, stream.rateMbps!!, 0.0)
            assertEquals("TIME_LIMIT", stream.stopReason)
        }
    }

    @Test fun noFirstPayloadCannotStartWarmupOrReportZeroSpeed() {
        HttpServer(1) { socket ->
            headers(socket.getInputStream())
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            assertEquals(-1, socket.getInputStream().read())
        }.use { server ->
            val result = execute(config(DataTestKind.PARALLEL_DOWNLOAD, server.port).copy(timeoutSeconds = 1))
            assertNotNull(result.error)
            assertNull(result.throughputMbps)
            assertEquals(0L, result.mic!!.download!!.streams.single().warmupBytes)
        }
    }

    @Test fun chunkHeaderStateSurvivesPollingTimeoutsInCustomMode() {
        HttpServer(1) { socket ->
            headers(socket.getInputStream())
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n3".toByteArray())
            socket.getOutputStream().flush()
            Thread.sleep(300)
            socket.getOutputStream().write(("2\r\n" + "z".repeat(50) + "\r\n0\r\n\r\n").toByteArray())
        }.use { server ->
            val result = execute(config(DataTestKind.PARALLEL_DOWNLOAD, server.port).copy(warmupBytes = 0))
            assertNull(result.error)
            assertEquals(50L, result.mic!!.download!!.streams.single().bytes)
        }
    }

    @Test fun uploadSendsCleanChunkedRequestAndIncludesServerReceiptTime() {
        HttpServer(1) { socket ->
            val input = socket.getInputStream()
            val request = headers(input)
            assertEquals("POST /upload HTTP/1.1", request.first())
            assertTrue(request.contains("Transfer-Encoding: chunked"))
            assertEquals(71L, readChunks(input))
            Thread.sleep(120)
            socket.getOutputStream().write("HTTP/1.1 204 Complete\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }.use { server ->
            val result = execute(config(DataTestKind.PARALLEL_UPLOAD, server.port))
            val stream = result.mic!!.upload!!.streams.single()
            assertNull(result.error)
            assertEquals(21L, stream.warmupBytes)
            assertEquals(50L, stream.bytes)
            assertTrue(stream.durationMs >= 110.0)
            assertEquals(50 * 8.0 / (stream.durationMs * 1000), result.throughputMbps!!, 0.000001)
        }
    }

    @Test fun rejectedUploadPreservesBytesButHasNoRate() {
        HttpServer(1) { socket ->
            val input = socket.getInputStream()
            headers(input)
            readChunks(input)
            socket.getOutputStream().write("HTTP/1.1 500 Rejected\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }.use { server ->
            val result = execute(config(DataTestKind.PARALLEL_UPLOAD, server.port))
            assertNotNull(result.error)
            assertEquals(71L, result.bytes)
            assertNull(result.throughputMbps)
            assertFalse(result.mic!!.upload!!.streams.single().completed)
        }
    }

    @Test fun cancellingBlockedDownloadClosesItsSocketPromptly() {
        val waiting = CountDownLatch(1)
        HttpServer(1) { socket ->
            headers(socket.getInputStream())
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx".toByteArray())
            socket.getOutputStream().flush()
            waiting.countDown()
            assertEquals(-1, socket.getInputStream().read())
        }.use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val control = AttemptControl()
            try {
                val result = executor.submit<AttemptMeasurement> { MicDataTransport.execute(
                    config(DataTestKind.PARALLEL_DOWNLOAD, server.port).copy(warmupBytes = 0, measureSeconds = 10), LocalIo(), control) }
                assertTrue(waiting.await(2, TimeUnit.SECONDS))
                control.cancel()
                val measurement = result.get(2, TimeUnit.SECONDS)
                assertNotNull(measurement.error)
                assertNull(measurement.throughputMbps)
            } finally { control.cancel(); executor.shutdownNow() }
        }
    }

    @Test fun cancellingBackpressuredUploadNeverReportsBufferedBytesAsDelivered() {
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        HttpServer(1) { socket ->
            val input = socket.getInputStream()
            headers(input)
            waiting.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            val buffer = ByteArray(8192)
            while (input.read(buffer) >= 0) { /* drain queued bytes until the cancelled client closes */ }
        }.use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val control = AttemptControl()
            try {
                val result = executor.submit<AttemptMeasurement> { MicDataTransport.execute(
                    config(DataTestKind.PARALLEL_UPLOAD, server.port).copy(warmupBytes = 0, measureSeconds = 10, measureBytes = 10_000_000),
                    LocalIo(sendBuffer = 1024), control) }
                assertTrue(waiting.await(2, TimeUnit.SECONDS))
                Thread.sleep(50)
                control.cancel()
                release.countDown()
                val measurement = result.get(2, TimeUnit.SECONDS)
                assertNotNull(measurement.error)
                assertNull(measurement.throughputMbps)
                assertFalse(measurement.mic!!.upload!!.streams.single().completed)
            } finally { release.countDown(); control.cancel(); executor.shutdownNow() }
        }
    }

    @Test fun udpAcceptsOutOfOrderRepliesButRejectsDuplicatesWrongPayloadWrongPeerAndLateReplies() {
        UdpServer { socket ->
            val first = receive(socket)
            val second = receive(socket)
            assertEquals(16, first.length)
            assertEquals(0L, ByteBuffer.wrap(first.data).getLong(8) and 0xffffffffL)
            echo(socket, second)
            echo(socket, first)
            echo(socket, first)
            val third = receive(socket)
            val corrupt = third.data.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            socket.send(DatagramPacket(corrupt, corrupt.size, third.address, third.port))
            DatagramSocket().use { wrongPeer -> echo(wrongPeer, third) }
            val fourth = receive(socket)
            Thread.sleep(110)
            echo(socket, fourth)
        }.use { server ->
            val result = execute(config(DataTestKind.UDP_LATENCY, server.port))
            val udp = result.mic!!.udp!!
            assertNull(result.error)
            assertEquals(4, udp.sent)
            assertEquals(2, udp.received)
            assertEquals(2, udp.lost)
            assertEquals(2, udp.rttsMs.size)
            assertEquals(listOf("REPLIED", "REPLIED", "LOST", "LOST"), udp.probes.map { it.outcome })
            assertTrue(udp.probes.zipWithNext().all { (a, b) -> b.sentAtNanos - a.sentAtNanos >= 19_000_000 })
        }
    }

    @Test fun unansweredUdpProbesAreValidLossAfterTheirIndividualDeadlines() {
        UdpServer { socket ->
            repeat(3) { receive(socket) }
            Thread.sleep(100)
        }.use { server ->
            val result = execute(config(DataTestKind.UDP_LATENCY, server.port).copy(udpCount = 3, udpIntervalMs = 10, udpTimeoutMs = 40))
            val udp = result.mic!!.udp!!
            assertNull(result.error)
            assertTrue(result.measurementAvailable)
            assertEquals(3, udp.lost)
            assertEquals(100.0, udp.lossPercent!!, 0.0)
            assertNull(udp.averageRttMs)
        }
    }

    @Test fun cancelledUdpRecordsOnlyActuallySentProbesWithoutInventingLoss() {
        val sent = CountDownLatch(1)
        val finishServer = CountDownLatch(1)
        UdpServer { socket ->
            receive(socket)
            sent.countDown()
            assertTrue(finishServer.await(2, TimeUnit.SECONDS))
        }.use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val control = AttemptControl()
            try {
                val result = executor.submit<AttemptMeasurement> { MicDataTransport.execute(
                    config(DataTestKind.UDP_LATENCY, server.port).copy(udpCount = 60, udpIntervalMs = 500, udpTimeoutMs = 2000), LocalIo(), control) }
                assertTrue(sent.await(2, TimeUnit.SECONDS))
                control.cancel()
                val udp = result.get(2, TimeUnit.SECONDS).mic!!.udp!!
                assertEquals(1, udp.sent)
                assertEquals(0, udp.lost)
                assertEquals("CANCELLED", udp.probes.single().outcome)
                assertNotNull(udp.error)
            } finally { finishServer.countDown(); control.cancel(); executor.shutdownNow() }
        }
    }

    @Test fun samplerStartsOnPayloadAndExcludesStrictWarmupThreshold() {
        val sample = MicTransferWindow(5_000_000_000L, 20, 20_000_000_000L, 50)
        sample.advance(99_000_000_000L)
        assertFalse(sample.started)
        sample.accept(20, 100_000_000_000L)
        assertFalse(sample.measuring)
        sample.accept(1, 101_000_000_000L)
        assertTrue(sample.measuring)
        sample.accept(50, 102_000_000_000L)
        val result = sample.result(1, 200)
        assertEquals(21L, result.warmupBytes)
        assertEquals(1000.0, result.warmupMs, 0.0)
        assertEquals(50L, result.bytes)
        assertEquals(1000.0, result.durationMs, 0.0)
        assertEquals("BYTE_LIMIT", result.stopReason)
    }

    @Test fun samplerStopsAtExactTimeBoundaryAndIgnoresLaterPayload() {
        val sample = MicTransferWindow(5_000_000_000L, 2_500_000, 20_000_000_000L, 20_000_000)
        sample.accept(1, 1_000_000_000L)
        sample.advance(6_000_000_000L)
        sample.accept(100, 7_000_000_000L)
        sample.accept(900, 26_000_000_000L)
        val result = sample.result(1, 200)
        assertEquals(1L, result.warmupBytes)
        assertEquals(100L, result.bytes)
        assertEquals(20_000.0, result.durationMs, 0.0)
        assertEquals("TIME_LIMIT", result.stopReason)
    }

    private fun receive(socket: DatagramSocket): DatagramPacket = DatagramPacket(ByteArray(256), 256).also {
        socket.receive(it)
        it.data = it.data.copyOfRange(it.offset, it.offset + it.length)
        it.length = it.data.size
    }
    private fun echo(socket: DatagramSocket, packet: DatagramPacket) =
        socket.send(DatagramPacket(packet.data, packet.length, packet.address, packet.port))

    private fun line(input: InputStream): String {
        val result = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw AssertionError("Unexpected EOF")
            if (byte == 10) return result.toString().removeSuffix("\r")
            result.append(byte.toChar())
        }
    }
    private fun headers(input: InputStream): List<String> {
        val result = mutableListOf<String>()
        while (true) { val next = line(input); if (next.isEmpty()) return result; result += next }
    }
    private fun readChunks(input: InputStream): Long {
        var count = 0L
        while (true) {
            val size = line(input).toInt(16)
            if (size == 0) { assertEquals("", line(input)); return count }
            var remaining = size
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(remaining, buffer.size))
                assertTrue(read > 0)
                remaining -= read
                count += read
            }
            assertEquals("", line(input))
        }
    }
}
