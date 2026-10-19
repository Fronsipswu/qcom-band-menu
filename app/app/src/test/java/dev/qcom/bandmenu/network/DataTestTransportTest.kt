package dev.qcom.bandmenu.network

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class DataTestTransportTest {
    private class LocalIo : TestIo {
        val connectedHosts = Collections.synchronizedList(mutableListOf<String>())
        override val interfaceName = "lo"
        override fun resolve(host: String): InetAddress = InetAddress.getByName(host)
        override fun openHttp(url: URL) = url.openConnection() as HttpURLConnection
        override fun openSocket(host: String, port: Int, timeoutMs: Int): Socket {
            connectedHosts += host
            return Socket().apply {
                try { connect(InetSocketAddress(host, port), timeoutMs) }
                catch (e: Exception) { close(); throw e }
            }
        }
    }

    private class OneShotServer(script: (Socket) -> Unit) : Closeable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val port get() = socket.localPort
        private val finished: Future<*> = executor.submit {
            socket.accept().use { client ->
                client.soTimeout = 4000
                script(client)
            }
        }
        override fun close() {
            try {
                finished.get(5, TimeUnit.SECONDS)
            } finally {
                socket.close()
                executor.shutdownNow()
            }
        }
    }

    private fun config(kind: DataTestKind, port: Int, bytes: Long = 5) = DataTestConfig(
        kind = kind, custom = true, repetitions = 1, timeoutSeconds = 3,
        host = "127.0.0.1", port = port, password = "test-secret", path = "/test.bin",
        url = "http://127.0.0.1:$port/test.bin", transferBytes = bytes)

    private fun execute(c: DataTestConfig, io: TestIo = LocalIo()): AttemptMeasurement =
        AttemptControl().use { DataTestTransport.execute(c, io, it) }

    private fun failed(block: () -> Unit): AttemptMeasurement {
        try { block(); fail("Expected measurement failure") }
        catch (e: MeasurementException) { return e.measurement }
        throw AssertionError("unreachable")
    }

    @Test fun httpDownloadReadsCompleteResource() {
        OneShotServer { s ->
            assertTrue(headers(s.getInputStream()).first().startsWith("GET /test.bin "))
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello".toByteArray())
        }.use { server ->
            val result = execute(config(DataTestKind.HTTP_DOWNLOAD, server.port))
            assertEquals(5L, result.bytes)
            assertEquals(200, result.httpStatus)
            assertTrue(result.throughputMbps!! > 0)
            assertNull(result.error)
        }
    }

    @Test fun httpDownloadRejectsStatusWithoutCountingErrorBody() {
        OneShotServer { s ->
            headers(s.getInputStream())
            s.getOutputStream().write("HTTP/1.1 503 Unavailable\r\nContent-Length: 5\r\nConnection: close\r\n\r\nerror".toByteArray())
        }.use { server ->
            val result = failed { execute(config(DataTestKind.HTTP_DOWNLOAD, server.port)) }
            assertEquals(503, result.httpStatus)
            assertEquals(0L, result.bytes)
            assertNull(result.throughputMbps)
        }
    }

    @Test fun httpDownloadRejectsTruncationWithAndWithoutContentLength() {
        for (length in listOf("Content-Length: 5\r\n", "")) {
            OneShotServer { s ->
                headers(s.getInputStream())
                s.getOutputStream().write("HTTP/1.1 200 OK\r\n${length}Connection: close\r\n\r\nabc".toByteArray())
            }.use { server ->
                val result = failed { execute(config(DataTestKind.HTTP_DOWNLOAD, server.port)) }
                assertEquals(3L, result.bytes)
                assertTrue(result.error!!.contains("Incomplete"))
                assertNull(result.throughputMbps)
            }
        }
    }

    @Test fun httpUploadSendsFixedLengthAndRequiresAcknowledgement() {
        for (status in listOf(204, 500)) {
            OneShotServer { s ->
                val request = headers(s.getInputStream())
                assertTrue(request.first().startsWith("POST /test.bin "))
                assertTrue(request.any { it.equals("Content-Length: 8193", ignoreCase = true) })
                assertEquals(8193, readExactly(s.getInputStream(), 8193).size)
                s.getOutputStream().write("HTTP/1.1 $status Result\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }.use { server ->
                val c = config(DataTestKind.HTTP_UPLOAD, server.port, 8193)
                val result = if (status == 204) execute(c) else failed { execute(c) }
                assertEquals(8193L, result.bytes)
                assertEquals(status, result.httpStatus)
                if (status == 204) assertNotNull(result.throughputMbps) else assertNull(result.throughputMbps)
            }
        }
    }

    @Test fun ftpDownloadUsesBinarySingleSessionAndFullCompletionReply() {
        OneShotServer { s ->
            val lines = ftpLogin(s)
            assertEquals("SIZE /test.bin", lines.readLine())
            reply(s, "213 5")
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { passive ->
                assertEquals("EPSV", lines.readLine())
                reply(s, "229 Entering Extended Passive Mode (|||${passive.localPort}|)")
                assertEquals("RETR /test.bin", lines.readLine())
                reply(s, "150 Starting")
                passive.accept().use { it.getOutputStream().write("hello".toByteArray()) }
                reply(s, "226 Completed")
                assertEquals("QUIT", lines.readLine())
            }
        }.use { server ->
            val result = execute(config(DataTestKind.FTP_DOWNLOAD, server.port))
            assertEquals(5L, result.bytes)
            assertTrue(result.throughputMbps!! > 0)
            assertTrue(result.detail!!.contains("Method B"))
        }
    }

    @Test fun ftpPassiveFallbackIgnoresAdvertisedHost() {
        OneShotServer { s ->
            val lines = ftpLogin(s)
            assertEquals("SIZE /test.bin", lines.readLine())
            reply(s, "213 5")
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { passive ->
                assertEquals("EPSV", lines.readLine())
                reply(s, "502 EPSV not implemented")
                assertEquals("PASV", lines.readLine())
                reply(s, "227 Entering Passive Mode (203,0,113,99,${passive.localPort / 256},${passive.localPort % 256})")
                assertEquals("RETR /test.bin", lines.readLine())
                reply(s, "150 Starting")
                passive.accept().use { it.getOutputStream().write("hello".toByteArray()) }
                reply(s, "250 Completed")
                assertEquals("QUIT", lines.readLine())
            }
        }.use { server ->
            val io = LocalIo()
            val result = execute(config(DataTestKind.FTP_DOWNLOAD, server.port), io)
            assertEquals(5L, result.bytes)
            assertEquals(listOf("127.0.0.1", "127.0.0.1"), io.connectedHosts)
        }
    }

    @Test fun ftpRejectsWrongSizeBeforeStartingTransfer() {
        OneShotServer { s ->
            val lines = ftpLogin(s)
            assertEquals("SIZE /test.bin", lines.readLine())
            reply(s, "213 4")
            assertNull(lines.readLine())
        }.use { server ->
            val result = failed { execute(config(DataTestKind.FTP_DOWNLOAD, server.port)) }
            assertEquals(0L, result.bytes)
            assertTrue(result.error!!.contains("expected 5"))
        }
    }

    @Test fun ftpRejectsTruncatedDownloadEvenWithPositiveCompletion() {
        OneShotServer { s ->
            val lines = ftpLogin(s)
            assertEquals("SIZE /test.bin", lines.readLine())
            reply(s, "213 5")
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { passive ->
                assertEquals("EPSV", lines.readLine())
                reply(s, "229 Entering Extended Passive Mode (|||${passive.localPort}|)")
                assertEquals("RETR /test.bin", lines.readLine())
                reply(s, "150 Starting")
                passive.accept().use { it.getOutputStream().write("abc".toByteArray()) }
                runCatching { reply(s, "226 Completed") }
            }
        }.use { server ->
            val result = failed { execute(config(DataTestKind.FTP_DOWNLOAD, server.port)) }
            assertEquals(3L, result.bytes)
            assertTrue(result.error!!.contains("Incomplete FTP"))
            assertNull(result.throughputMbps)
        }
    }

    @Test fun ftpRejectsFailureAfterAllPayloadBytesArrive() {
        OneShotServer { s ->
            val lines = ftpLogin(s)
            assertEquals("SIZE /test.bin", lines.readLine())
            reply(s, "213 5")
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { passive ->
                assertEquals("EPSV", lines.readLine())
                reply(s, "229 Entering Extended Passive Mode (|||${passive.localPort}|)")
                assertEquals("RETR /test.bin", lines.readLine())
                reply(s, "150 Starting")
                passive.accept().use { it.getOutputStream().write("hello".toByteArray()) }
                reply(s, "426 Connection failed")
            }
        }.use { server ->
            val result = failed { execute(config(DataTestKind.FTP_DOWNLOAD, server.port)) }
            assertEquals(5L, result.bytes)
            assertTrue(result.error!!.contains("completion failed (426)"))
            assertNull(result.throughputMbps)
        }
    }

    @Test fun ftpUploadsUniqueFileInConfiguredDirectoryAndChecksServerAck() {
        val storedPaths = mutableListOf<String>()
        repeat(2) {
            OneShotServer { s ->
                val lines = ftpLogin(s)
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { passive ->
                    assertEquals("EPSV", lines.readLine())
                    reply(s, "229 Entering Extended Passive Mode (|||${passive.localPort}|)")
                    val command = lines.readLine()
                    assertTrue(command.startsWith("STOR /incoming/qbm-"))
                    storedPaths += command.removePrefix("STOR ")
                    reply(s, "150 Starting")
                    passive.accept().use { assertEquals(5, it.getInputStream().readBytes().size) }
                    reply(s, "226 Completed")
                    assertEquals("QUIT", lines.readLine())
                }
            }.use { server ->
                val result = execute(config(DataTestKind.FTP_UPLOAD, server.port).copy(path = "/incoming/"))
                assertEquals(5L, result.bytes)
                assertTrue(result.detail!!.contains(storedPaths.last()))
            }
        }
        assertNotEquals(storedPaths.first(), storedPaths.last())
    }

    @Test fun malformedPassiveResponsesAndControlGreetingAreRejected() {
        for (value in listOf("229 (|||0|)", "229 (|||65536|)", "229 (||12|)", "229 no port")) {
            try { DataTestTransport.parseEpsvPort(value); fail(value) } catch (_: java.io.IOException) {}
        }
        for (value in listOf("227 (1,2,3,4,0,0)", "227 (1,2,3,4,256,1)", "227 malformed")) {
            try { DataTestTransport.parsePasvPort(value); fail(value) } catch (_: java.io.IOException) {}
        }
        OneShotServer { s -> reply(s, "not an FTP greeting") }.use { server ->
            val result = failed { execute(config(DataTestKind.FTP_DOWNLOAD, server.port)) }
            assertEquals("Malformed FTP response", result.error)
        }
    }

    @Test fun ftpUploadRateIncludesServerCompletionAcknowledgement() {
        OneShotServer { s ->
            val lines = ftpLogin(s)
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { passive ->
                assertEquals("EPSV", lines.readLine())
                reply(s, "229 Entering Extended Passive Mode (|||${passive.localPort}|)")
                assertTrue(lines.readLine().startsWith("STOR /incoming/qbm-"))
                reply(s, "150 Starting")
                passive.accept().use { assertEquals(5, it.getInputStream().readBytes().size) }
                Thread.sleep(150) // Payload can be buffered locally before a slow uplink delivers it.
                reply(s, "226 Completed")
                assertEquals("QUIT", lines.readLine())
            }
        }.use { server ->
            val result = execute(config(DataTestKind.FTP_UPLOAD, server.port).copy(path = "/incoming"))
            assertTrue("duration=${result.durationMs}", result.durationMs >= 140.0)
            assertEquals(5 * 8.0 / (result.durationMs * 1000), result.throughputMbps!!, 0.00000001)
            assertTrue(result.detail!!.contains("server completion reply"))
        }
    }

    @Test fun cancellingBlockedTransferClosesOwnedSocketsAndDoesNotAffectNextAttempt() {
        val waiting = CountDownLatch(1)
        val done = Executors.newSingleThreadExecutor()
        val control = AttemptControl()
        try {
            OneShotServer { s ->
                val lines = ftpLogin(s)
                assertEquals("SIZE /test.bin", lines.readLine())
                reply(s, "213 5")
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { passive ->
                    assertEquals("EPSV", lines.readLine())
                    reply(s, "229 Entering Extended Passive Mode (|||${passive.localPort}|)")
                    assertEquals("RETR /test.bin", lines.readLine())
                    reply(s, "150 Starting")
                    passive.accept().use { data ->
                        data.soTimeout = 4000
                        waiting.countDown()
                        assertEquals(-1, data.getInputStream().read())
                    }
                }
            }.use { server ->
                val result = done.submit<AttemptMeasurement> {
                    failed { DataTestTransport.execute(config(DataTestKind.FTP_DOWNLOAD, server.port), LocalIo(), control) }
                }
                assertTrue(waiting.await(3, TimeUnit.SECONDS))
                control.cancel()
                assertNotNull(result.get(2, TimeUnit.SECONDS).error)
                assertTrue(control.isCancelled)
            }
            OneShotServer { s ->
                headers(s.getInputStream())
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello".toByteArray())
            }.use { server -> assertEquals(5L, execute(config(DataTestKind.HTTP_DOWNLOAD, server.port)).bytes) }
        } finally {
            control.cancel()
            done.shutdownNow()
        }
    }

    @Test fun registeringAfterCancellationImmediatelyClosesResource() {
        val control = AttemptControl()
        control.cancel()
        var closed = false
        try { control.track(Closeable { closed = true }); fail("Must reject resource") }
        catch (_: java.io.InterruptedIOException) {}
        assertTrue(closed)
    }

    @Test fun parsesIpUtilsAndToyboxPingAndSeparatesLossFromExecutionFailure() {
        val iputils = "64 bytes from 1.2.3.4: icmp_seq=1 ttl=55 time=12.345 ms\n" +
            "1 packets transmitted, 1 received, 0% packet loss\n" +
            "rtt min/avg/max/mdev = 12.345/12.345/12.345/0.000 ms\n"
        val toybox = "40 bytes from 1.2.3.4: icmp_seq=1 ttl=54 time=2.34 ms\n" +
            "1 packets transmitted, 1 packets received, 0% packet loss\n" +
            "round-trip min/avg/max = 2.34/2.34/2.34 ms\n"
        assertEquals(12.345, DataTestTransport.parsePingOutput(iputils, 0, 32).rttMs!!, 0.0001)
        assertEquals(2.34, DataTestTransport.parsePingOutput(toybox, 0, 32).rttMs!!, 0.0001)
        val lost = failed { DataTestTransport.parsePingOutput("1 packets transmitted, 0 received, 100% packet loss", 1, 32) }
        assertTrue(lost.error!!.contains("packet lost"))
        assertTrue(lost.contentVerified)
        val failed = failed { DataTestTransport.parsePingOutput("ping: socket: Operation not permitted", 2, 32) }
        assertTrue(failed.error!!.contains("command failed"))
        assertFalse(failed.contentVerified)
    }

    @Test fun icmpArgumentsSupportLegacyIpv4AndBothIpv6Executables() {
        val ipv4 = DataTestTransport.pingArguments("192.0.2.10", false, "rmnet_data0", 32, 5, false)
        assertEquals("/system/bin/ping", ipv4.first())
        assertFalse(ipv4.contains("-4"))
        assertFalse(ipv4.contains("-6"))
        assertEquals(listOf("-I", "rmnet_data0", "192.0.2.10"), ipv4.takeLast(3))
        val dedicated = DataTestTransport.pingArguments("2001:db8::1", true, "rmnet_data0", 32, 5, true)
        assertEquals("/system/bin/ping6", dedicated.first())
        assertFalse(dedicated.contains("-6"))
        val shared = DataTestTransport.pingArguments("2001:db8::1", true, "rmnet_data0", 32, 5, false)
        assertEquals(listOf("/system/bin/ping", "-6"), shared.take(2))
        assertEquals("2001:db8::1", shared.last())
    }

    private fun ftpLogin(socket: Socket): BufferedReader {
        reply(socket, "220-Welcome\r\nTest FTP server\r\n220 Ready")
        val input = socket.getInputStream().bufferedReader()
        assertEquals("USER anonymous", input.readLine())
        reply(socket, "331 Password required")
        assertEquals("PASS test-secret", input.readLine())
        reply(socket, "230 Logged in")
        assertEquals("TYPE I", input.readLine())
        reply(socket, "200 Binary mode")
        return input
    }

    private fun reply(socket: Socket, text: String) {
        socket.getOutputStream().write((text + "\r\n").toByteArray())
        socket.getOutputStream().flush()
    }

    /** No BufferedReader here: it could read ahead into an HTTP request's binary body. */
    private fun headers(input: InputStream): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        while (true) {
            val next = input.read()
            if (next < 0) throw AssertionError("Request ended before headers")
            if (next == '\n'.code) {
                val line = current.toString().removeSuffix("\r")
                if (line.isEmpty()) return result
                result += line
                current = StringBuilder()
            } else current.append(next.toChar())
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw AssertionError("Request body was truncated")
            offset += count
        }
        return bytes
    }
}
