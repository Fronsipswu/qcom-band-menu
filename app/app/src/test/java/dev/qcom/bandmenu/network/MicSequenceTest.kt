package dev.qcom.bandmenu.network

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL

class MicSequenceTest {
    private val io = object : TestIo {
        override val interfaceName = "test"
        override fun openSocket(host: String, port: Int, timeoutMs: Int): Socket = error("No network expected")
        override fun openHttp(url: URL): HttpURLConnection = error("No network expected")
        override fun resolve(host: String): InetAddress = error("No network expected")
    }
    private val config = defaultDataTestConfig(TestRegime.MIC, DataTestKind.MIC_POINT)
    private fun transfer() = MicTransferResult(List(3) { MicStreamResult(it, 10, 1.0, 100, 1.0, true) })

    @Test fun phaseFailureDoesNotDiscardOtherResultsOrSuppressUdp() {
        val calls = mutableListOf<DataTestKind>()
        val result = runMicPoint(config, io, AttemptControl(), execute = { c, _, _ ->
            calls += c.kind
            when (c.kind) {
                DataTestKind.PARALLEL_DOWNLOAD -> throw MeasurementException(AttemptMeasurement(error = "download failed"))
                DataTestKind.PARALLEL_UPLOAD -> AttemptMeasurement(mic = MicMeasurement(upload = transfer()))
                else -> AttemptMeasurement(mic = MicMeasurement(udp = MicUdpResult(60, 60, List(60) { 10.0 }, 0)))
            }
        })
        assertEquals(listOf(DataTestKind.PARALLEL_DOWNLOAD, DataTestKind.PARALLEL_UPLOAD, DataTestKind.UDP_LATENCY), calls)
        assertNull(result.mic!!.download!!.aggregateMbps)
        assertNotNull(result.mic!!.upload!!.aggregateMbps)
        assertEquals(60, result.mic!!.udp!!.received)
        assertNotNull(result.error)
    }

    @Test fun cancellationKeepsFinishedDirectionAndStopsRemainingPhases() {
        val parent = AttemptControl()
        var calls = 0
        try {
            runMicPoint(config, io, parent, execute = { c, _, _ ->
                calls++
                if (c.kind == DataTestKind.PARALLEL_UPLOAD) { parent.cancel(); throw java.io.IOException("closed") }
                AttemptMeasurement(mic = MicMeasurement(download = transfer()))
            })
            fail("Expected cancellation snapshot")
        } catch (e: MeasurementException) {
            assertNotNull(e.measurement.mic!!.download!!.aggregateMbps)
            assertNull(e.measurement.mic!!.udp)
            assertEquals(2, calls)
        }
    }

    @Test fun phaseDeadlineCancelsOnlyThatPhaseAndAllowsFollowingItems() {
        val parent = AttemptControl()
        val result = runMicPoint(config.copy(timeoutSeconds = 1), io, parent, execute = { c, _, phase ->
            if (c.kind == DataTestKind.PARALLEL_DOWNLOAD) {
                val end = System.nanoTime() + 3_000_000_000L
                while (!phase.isCancelled && System.nanoTime() < end) Thread.sleep(5)
                phase.check()
            }
            if (c.kind == DataTestKind.PARALLEL_UPLOAD) AttemptMeasurement(mic = MicMeasurement(upload = transfer()))
            else AttemptMeasurement(mic = MicMeasurement(udp = MicUdpResult(1, 1, listOf(1.0), 0)))
        })
        assertFalse(parent.isCancelled)
        assertTrue(result.mic!!.download!!.error!!.contains("timed out"))
        assertNotNull(result.mic!!.upload!!.aggregateMbps)
        assertNotNull(result.mic!!.udp)
    }

    @Test fun slowResourceCleanupDoesNotInvalidateTimelyMeasurement() {
        val result = runMicPoint(config.copy(timeoutSeconds = 1), io, AttemptControl(), execute = { c, _, phase ->
            when (c.kind) {
                DataTestKind.PARALLEL_DOWNLOAD -> {
                    phase.track(java.io.Closeable { Thread.sleep(1100) })
                    AttemptMeasurement(mic = MicMeasurement(download = transfer()))
                }
                DataTestKind.PARALLEL_UPLOAD -> AttemptMeasurement(mic = MicMeasurement(upload = transfer()))
                else -> AttemptMeasurement(mic = MicMeasurement(udp = MicUdpResult(1, 1, listOf(1.0), 0)))
            }
        })
        assertNull(result.error)
        assertNotNull(result.mic!!.download!!.aggregateMbps)
    }
}
