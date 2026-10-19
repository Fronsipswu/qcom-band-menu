package dev.qcom.bandmenu.network

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.UUID
import kotlin.random.Random

/** All network operations are supplied by the runner and bound to its cellular network. */
interface TestIo {
    fun openSocket(host: String, port: Int, timeoutMs: Int): Socket
    fun openHttp(url: URL): HttpURLConnection
    fun resolve(host: String): InetAddress
    val interfaceName: String
}

/** An attempt owns its resources; stopping an old attempt cannot close a newer one's sockets. */
class AttemptControl : Closeable {
    private val lock = Any()
    private val resources = ArrayList<Closeable>()
    @Volatile var isCancelled: Boolean = false
        private set

    fun check() {
        if (isCancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("Test cancelled")
    }

    fun <T : Closeable> track(resource: T): T {
        synchronized(lock) {
            if (!isCancelled) {
                resources += resource
                return resource
            }
        }
        runCatching { resource.close() }
        throw InterruptedIOException("Test cancelled")
    }

    fun track(connection: HttpURLConnection): HttpURLConnection {
        track(Closeable { connection.disconnect() })
        return connection
    }

    fun track(process: Process): Process {
        track(Closeable {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        })
        return process
    }

    fun cancel() {
        val owned = synchronized(lock) {
            if (isCancelled) return
            isCancelled = true
            resources.toList().also { resources.clear() }
        }
        // Close the data connection before the FTP control connection.
        owned.asReversed().forEach { runCatching { it.close() } }
    }

    override fun close() = cancel()
}

/** Preserves partial byte counts and timings without treating an interrupted transfer as success. */
class MeasurementException(val measurement: AttemptMeasurement, cause: Throwable? = null) :
    IOException(measurement.error ?: "Measurement failed", cause)

/** Blocking transports. A separate watchdog must cancel [AttemptControl] at the attempt deadline. */
object DataTestTransport {
    fun execute(c: DataTestConfig, io: TestIo, control: AttemptControl): AttemptMeasurement {
        control.check()
        return when (c.kind) {
            DataTestKind.PING -> ping(c, io, control)
            DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD -> ftp(c, io, control)
            DataTestKind.HTTP_DOWNLOAD, DataTestKind.HTTP_UPLOAD -> http(c, io, control)
            else -> throw IOException("This test requires the foreground browser or media player")
        }
    }

    private fun timeout(c: DataTestConfig): Int =
        (c.effectiveTimeoutSeconds.toLong() * 1000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

    private fun http(c: DataTestConfig, io: TestIo, control: AttemptControl): AttemptMeasurement {
        val start = System.nanoTime()
        var bytes = 0L
        var status: Int? = null
        var connection: HttpURLConnection? = null
        try {
            val conn = control.track(io.openHttp(URL(c.url)))
            connection = conn
            conn.connectTimeout = timeout(c)
            conn.readTimeout = timeout(c)
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Cache-Control", "no-cache, no-store")
            conn.setRequestProperty("Pragma", "no-cache")
            val upload = c.kind == DataTestKind.HTTP_UPLOAD
            val buffer = ByteArray(BUFFER_SIZE)
            if (upload) {
                val expected = c.effectiveTransferBytes
                if (expected <= 0) throw IOException("Upload size must be positive")
                Random.nextBytes(buffer)
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setFixedLengthStreamingMode(expected)
                conn.outputStream.use { out ->
                    while (bytes < expected) {
                        control.check()
                        val count = minOf(buffer.size.toLong(), expected - bytes).toInt()
                        out.write(buffer, 0, count)
                        bytes += count
                    }
                    out.flush()
                }
            } else {
                conn.requestMethod = "GET"
            }
            control.check()
            status = conn.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            if (!upload) {
                val configuredBytes = c.effectiveTransferBytes
                if (configuredBytes <= 0) throw IOException("Expected download size must be positive")
                val lengthHeader = conn.getHeaderField("Content-Length")
                val expected = lengthHeader?.trim()?.toLongOrNull()
                if (lengthHeader != null && (expected == null || expected < 0))
                    throw IOException("Invalid HTTP Content-Length")
                if (expected != null && expected != configuredBytes)
                    throw IOException("HTTP response size is $expected bytes; expected $configuredBytes")
                conn.inputStream.use { input ->
                    while (true) {
                        control.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        bytes += count
                        if (bytes > configuredBytes) throw IOException("HTTP response exceeded expected $configuredBytes bytes")
                    }
                }
                if (expected != null && bytes != expected)
                    throw IOException("Incomplete HTTP response: $bytes of $expected bytes")
                if (bytes != configuredBytes)
                    throw IOException("Incomplete HTTP response: $bytes of $configuredBytes bytes")
            }
            control.check()
            val duration = elapsed(start)
            return AttemptMeasurement(bytes = bytes, durationMs = duration,
                throughputMbps = mbps(bytes, duration), httpStatus = status)
        } catch (e: Exception) {
            throw MeasurementException(AttemptMeasurement(bytes = bytes, durationMs = elapsed(start),
                httpStatus = status, error = safeError(e, c)), e)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun ftp(c: DataTestConfig, io: TestIo, control: AttemptControl): AttemptMeasurement {
        val attemptStart = System.nanoTime()
        var transferStart = 0L
        var lastByteAt = 0L
        var bytes = 0L
        var socket: Socket? = null
        var data: Socket? = null
        val upload = c.kind == DataTestKind.FTP_UPLOAD
        val remotePath = if (upload) uploadPath(c.path) else c.path
        try {
            listOf(c.username, c.password, remotePath).forEach {
                if (it.any { ch -> ch == '\r' || ch == '\n' || ch == '\u0000' })
                    throw IOException("FTP credentials and path cannot contain control characters")
            }
            val expected = c.effectiveTransferBytes
            if (expected <= 0) throw IOException("FTP transfer size must be positive")
            val conn = control.track(io.openSocket(c.host, c.port, timeout(c)))
            socket = conn
            conn.soTimeout = timeout(c)
            val protocol = FtpControl(conn, control)
            var greeting = protocol.readReply()
            if (greeting.code == 120) greeting = protocol.readReply()
            protocol.expect(greeting, setOf(220), "greeting")
            val user = protocol.command("USER ${c.username}")
            if (user.code == 331) protocol.expect(protocol.command("PASS ${c.password}"), setOf(230), "login")
            else protocol.expect(user, setOf(230), "login")
            protocol.expect(protocol.command("TYPE I"), setOf(200), "binary mode")
            if (!upload) {
                val size = protocol.command("SIZE $remotePath")
                protocol.expect(size, setOf(213), "file size")
                val actual = size.text.substringAfter(' ', "").trim().toLongOrNull()
                    ?: throw IOException("Invalid FTP SIZE response")
                if (actual != expected) throw IOException("FTP file size is $actual bytes; expected $expected")
            }
            val epsv = protocol.command("EPSV")
            val port = if (epsv.code == 229) parseEpsvPort(epsv.text) else {
                if (conn.inetAddress is Inet6Address) throw IOException("FTP server must support EPSV for IPv6")
                val pasv = protocol.command("PASV")
                protocol.expect(pasv, setOf(227), "passive mode")
                parsePasvPort(pasv.text)
            }
            // Use the control peer, never the server-provided PASV address (NAT and FTP bounce safety).
            val transfer = control.track(io.openSocket(conn.inetAddress.hostAddress!!, port, timeout(c)))
            data = transfer
            transfer.soTimeout = timeout(c)
            // Application approximation of ETSI Method B. UL waits for server acknowledgement:
            // the last local socket write can precede delivery by an entire kernel send buffer.
            transferStart = System.nanoTime()
            protocol.expect(protocol.command("${if (upload) "STOR" else "RETR"} $remotePath"), setOf(125, 150), "transfer start")
            val buffer = ByteArray(BUFFER_SIZE)
            if (upload) {
                Random.nextBytes(buffer)
                transfer.getOutputStream().use { out ->
                    while (bytes < expected) {
                        control.check()
                        val count = minOf(buffer.size.toLong(), expected - bytes).toInt()
                        out.write(buffer, 0, count)
                        bytes += count
                        lastByteAt = System.nanoTime()
                    }
                    out.flush()
                    lastByteAt = System.nanoTime()
                }
            } else {
                transfer.getInputStream().use { input ->
                    while (true) {
                        control.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        bytes += count
                        lastByteAt = System.nanoTime()
                        if (bytes > expected) throw IOException("FTP transfer exceeded expected $expected bytes")
                    }
                }
            }
            transfer.close()
            data = null
            if (bytes != expected) throw IOException("Incomplete FTP transfer: $bytes of $expected bytes")
            protocol.expect(protocol.readReply(), setOf(226, 250), "transfer completion")
            val completedAt = System.nanoTime()
            control.check()
            val duration = (((if (upload) completedAt else lastByteAt) - transferStart) / 1e6).coerceAtLeast(0.001)
            // QUIT is courtesy cleanup; its response cannot invalidate an acknowledged transfer.
            runCatching { protocol.send("QUIT") }
            return AttemptMeasurement(bytes = bytes, durationMs = duration,
                throughputMbps = mbps(bytes, duration), detail = "Remote path: $remotePath; application Method B timing; " +
                    if (upload) "UL through server completion reply" else "DL through last payload byte")
        } catch (e: Exception) {
            val duration = elapsed(if (transferStart > 0) transferStart else attemptStart)
            throw MeasurementException(AttemptMeasurement(bytes = bytes, durationMs = duration,
                detail = "Remote path: $remotePath; application Method B timing", error = safeError(e, c)), e)
        } finally {
            runCatching { data?.close() }
            runCatching { socket?.close() }
        }
    }

    private fun ping(c: DataTestConfig, io: TestIo, control: AttemptControl): AttemptMeasurement {
        val start = System.nanoTime()
        try {
            if (io.interfaceName.isBlank()) throw IOException("Cellular interface is unavailable for ICMP")
            val address = io.resolve(c.host)
            control.check()
            val base = pingArguments(address.hostAddress!!, address is Inet6Address, io.interfaceName,
                c.effectivePingPayloadBytes, c.effectiveTimeoutSeconds, File("/system/bin/ping6").canExecute())
            var result = runPing(base.toMutableList().also { it.add(1, "-n") }, control)
            // Toybox variants omit -n; a literal destination still avoids reverse lookup configuration.
            if (result.first != 0 && unsupportedNumericOption(result.second)) result = runPing(base, control)
            control.check()
            val measurement = parsePingOutput(result.second, result.first, c.effectivePingPayloadBytes)
            return measurement.copy(durationMs = elapsed(start))
        } catch (e: MeasurementException) {
            throw MeasurementException(e.measurement.copy(durationMs = elapsed(start)), e)
        } catch (e: Exception) {
            throw MeasurementException(AttemptMeasurement(durationMs = elapsed(start), contentVerified = false, measurementAvailable = false,
                error = "ICMP execution failed: ${safeError(e, c)}"), e)
        }
    }

    private fun runPing(arguments: List<String>, control: AttemptControl): Pair<Int, String> {
        control.check()
        val process = control.track(ProcessBuilder(arguments).redirectErrorStream(true).start())
        try {
            val output = process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(1024)
                val text = StringBuilder()
                while (true) {
                    control.check()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > 32_768) throw IOException("Unexpectedly long ICMP command output")
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            val exit = process.waitFor()
            control.check()
            return exit to output
        } finally {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    internal fun parsePingOutput(output: String, exit: Int, payload: Int): AttemptMeasurement {
        val counts = Regex("(\\d+) packets transmitted,\\s*(\\d+) (?:packets )?received").find(output)
        val sent = counts?.groupValues?.get(1)?.toIntOrNull()
        val received = counts?.groupValues?.get(2)?.toIntOrNull()
        if (sent != 1 || received == null || received !in 0..1)
            throw MeasurementException(AttemptMeasurement(contentVerified = false, measurementAvailable = false,
                error = "ICMP command failed (exit $exit): ${output.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "no output"}"))
        if (received == 0)
            throw MeasurementException(AttemptMeasurement(error = "ICMP packet lost (1 sent, 0 received)"))
        if (exit != 0) throw MeasurementException(AttemptMeasurement(contentVerified = false, measurementAvailable = false, error = "ICMP command failed (exit $exit)"))
        val summary = Regex("(?:rtt|round-trip)[^=]*=\\s*[0-9.]+/([0-9.]+)/").find(output)
        val reply = Regex("time=\\s*([0-9.]+)\\s*ms").find(output)
        val rtt = (summary?.groupValues?.get(1) ?: reply?.groupValues?.get(1))?.toDoubleOrNull()
        if (rtt == null || !rtt.isFinite() || rtt < 0)
            throw MeasurementException(AttemptMeasurement(contentVerified = false, measurementAvailable = false, error = "ICMP response has no measurable round-trip time"))
        return AttemptMeasurement(bytes = payload.toLong(), rttMs = rtt)
    }

    private fun unsupportedNumericOption(output: String): Boolean =
        Regex("(?i)(?:invalid|unknown|unrecognized|bad) option[^\\n]*['\" ]-?n(?:['\" :]|$)").containsMatchIn(output)

    internal fun pingArguments(address: String, ipv6: Boolean, iface: String, payload: Int,
        timeoutSeconds: Int, ping6Available: Boolean): List<String> = buildList {
        add(if (ipv6 && ping6Available) "/system/bin/ping6" else "/system/bin/ping")
        // Older Android iputils ping has no -4 switch. A literal IPv4 address is sufficient.
        if (ipv6 && !ping6Available) add("-6")
        addAll(listOf("-c", "1", "-s", payload.toString(), "-W", timeoutSeconds.toString(), "-I", iface, address))
    }

    internal fun parseEpsvPort(reply: String): Int {
        val body = reply.substringAfterLast('(', "").substringBefore(')', "")
        if (body.length < 5 || body[0] != body[1] || body[0] != body[2] || body[0] != body.last())
            throw IOException("Invalid FTP EPSV response")
        return body.substring(3, body.length - 1).toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IOException("Invalid FTP EPSV port")
    }

    internal fun parsePasvPort(reply: String): Int {
        val fields = reply.substringAfterLast('(', "").substringBefore(')', "").split(',')
        val numbers = fields.map { it.trim().toIntOrNull() }
        if (numbers.size != 6 || numbers.any { it == null || it !in 0..255 })
            throw IOException("Invalid FTP PASV response")
        return (numbers[4]!! * 256 + numbers[5]!!).takeIf { it in 1..65535 }
            ?: throw IOException("Invalid FTP PASV port")
    }

    private fun uploadPath(directory: String): String =
        directory.trimEnd('/') + "/qbm-${System.currentTimeMillis()}-${UUID.randomUUID()}.bin"

    private fun elapsed(start: Long): Double = ((System.nanoTime() - start) / 1e6).coerceAtLeast(0.001)
    private fun mbps(bytes: Long, durationMs: Double) = bytes * 8.0 / (durationMs * 1000.0)

    private fun safeError(e: Exception, c: DataTestConfig): String {
        var message = e.message ?: e.javaClass.simpleName
        if (c.password.isNotEmpty()) message = message.replace(c.password, "[redacted]")
        return message.take(300)
    }

    private data class FtpReply(val code: Int, val text: String)

    private class FtpControl(socket: Socket, private val control: AttemptControl) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())

        fun send(command: String) {
            control.check()
            output.write((command + "\r\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        fun command(command: String): FtpReply {
            send(command)
            return readReply()
        }

        fun expect(reply: FtpReply, codes: Set<Int>, operation: String) {
            // Do not echo server text, credentials, or the full command into reports.
            if (reply.code !in codes) throw IOException("FTP $operation failed (${reply.code})")
        }

        fun readReply(): FtpReply {
            val first = line(input)
            val code = first.take(3).toIntOrNull()
            if (code == null || code !in 100..599 || first.length < 4 || first[3] !in " -")
                throw IOException("Malformed FTP response")
            if (first[3] == ' ') return FtpReply(code, first)
            repeat(200) {
                val next = line(input)
                if (next.startsWith("$code ")) return FtpReply(code, next)
            }
            throw IOException("FTP multiline response exceeded limit")
        }

        private fun line(input: InputStream): String {
            val result = StringBuilder()
            while (result.length < 16_384) {
                control.check()
                val byte = input.read()
                if (byte < 0) throw IOException("FTP control connection closed")
                if (byte == '\n'.code) return result.toString().removeSuffix("\r")
                result.append(byte.toChar())
            }
            throw IOException("FTP response line exceeded limit")
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
}
