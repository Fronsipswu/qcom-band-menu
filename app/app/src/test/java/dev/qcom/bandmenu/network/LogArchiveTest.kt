package dev.qcom.bandmenu.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

class LogArchiveTest {
    private fun header() = JSONObject().put("type", "header").put("format", "qbm-log").put("version", 1)
        .put("sessionId", "synthetic-cross-layer").put("startedUtcMs", 1_760_000_000_000L)
        .put("startedElapsedNs", "1000000000").put("clock", "boottime-arrival").put("packetCapture", true)
        .put("device", JSONObject().put("model", "Synthetic fixture")).put("appVersion", "test-fixture").put("synthetic", true)

    private fun lines(bytes: ByteArray) = GZIPInputStream(bytes.inputStream()).bufferedReader().use { reader ->
        reader.readLines().map(::JSONObject)
    }
    private fun ui(time: Long) = JSONObject().put("type", "ui").put("tNs", time.toString()).put("sim", 1)

    @Test fun sizeRotationCreatesIndependentGzipFilesWithOrderedArchiveSequences() {
        val files = ConcurrentHashMap<String, ByteArrayOutputStream>()
        val writer = LogArchiveWriter(header(), { name -> ByteArrayOutputStream().also { files[name] = it } },
            { 1_000_000_000L }, segmentBytes = 700)
        repeat(20) { assertTrue(writer.append(ui(1_000_000_000L + it).put("padding", "x".repeat(200)))) }
        writer.finish("Test complete")
        assertTrue(writer.awaitClosed())
        assertTrue(files.size > 1)
        val all = files.toSortedMap().values.flatMap { lines(it.toByteArray()) }
        assertEquals(20, all.count { it.optString("type") == "ui" })
        assertEquals(files.size, all.count { it.optString("type") == "footer" })
        val sequence = all.map { it.getLong("archiveSeq") }
        assertEquals(sequence.sorted(), sequence)
        assertEquals(sequence.size, sequence.distinct().size)
        files.values.forEach { file ->
            val part = lines(file.toByteArray())
            assertEquals("header", part.first().getString("type"))
            assertEquals("footer", part.last().getString("type"))
            assertTrue(part.all { it.getInt("segment") == part.first().getInt("segment") })
        }
    }

    @Test fun overflowIsExplicitAndUsesLostObservationTimeRatherThanLaterWriterTime() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        val writer = LogArchiveWriter(header(), { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); bytes },
            { 5_000_000_000L }, queueCapacity = 1)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertTrue(writer.append(ui(1_100_000_000)))
        assertFalse(writer.append(ui(1_200_000_000)))
        writer.finish("Stop")
        release.countDown()
        assertTrue(writer.awaitClosed())
        val records = lines(bytes.toByteArray())
        val gap = records.single { it.optString("type") == "gap" }
        assertEquals("1200000000", gap.getString("tNs"))
        assertEquals("1200000000", gap.getString("endNs"))
        assertEquals("all", gap.getString("source"))
        assertEquals(1, gap.getInt("dropped"))
        assertEquals(1, records.last().getInt("dropped"))
    }

    @Test fun storageFailureIsReportedAndProducerStopsAcceptingRecords() {
        val failure = AtomicReference<String>()
        val writer = LogArchiveWriter(header(), { throw IOException("SD card removed") }, { 0L }, onFailure = failure::set)
        assertTrue(writer.awaitClosed())
        assertTrue(failure.get().contains("SD card removed"))
        assertFalse(writer.append(ui(1)))
        assertNotNull(writer.error)
    }

    @Test fun finishDrainsAcceptedRecordsBeforeFooterAndRejectsLaterEvents() {
        val bytes = ByteArrayOutputStream()
        val writer = LogArchiveWriter(header(), { bytes }, { 1_000_000_000L }, queueCapacity = 200)
        repeat(100) { assertTrue(writer.append(ui(1_000_000_000L + it))) }
        writer.finish("Operator stop")
        assertFalse(writer.append(ui(9999)))
        assertTrue(writer.awaitClosed())
        val records = lines(bytes.toByteArray())
        assertEquals(100, records.count { it.optString("type") == "ui" })
        assertEquals("footer", records.last().getString("type"))
        assertEquals("Operator stop", records.last().getString("reason"))
    }

    @Test fun serializerRetainsHeldPortsNullsEveryLogicalSectionAndProducesCrossLayerFixture() {
        val diag = DiagStats.parse(JSONObject("""{"ms":1000,"carriers":[{"slot":0,"earfcn":1300,"pci":10,"band":3,"bw":20,"state":2,"rsrp":-95.25,"mcs":17.5,"mbps_sched":12.3,"tx":4}]}"""))
        val carrier = Carrier("PCC", "B3", "1300", 20.0, "Active", 10, -95.25, null, 12.0, diag.carriers.single())
        val snapshot = NetworkSnapshot(1, 1000, 50, ServiceMode.LTE, "LTE", Plmn("520", "01"), false,
            listOf(Field("LTE RRC", "Connected")), listOf(CellCard("Serving cell", Rat.LTE,
                listOf(Field("Cell", "123 / 4")), listOf(Metric("RSRP", MetricKind.RSRP, Rat.LTE, -95.25, "dBm")))),
            listOf(CarrierGroup("LTE aggregation", "20 MHz", listOf(carrier), table = true, diag = true)),
            neighbors = listOf(Neighbor(Rat.LTE, "EARFCN", 1300, "PCI", "11", emptyList(), Band(Rat.LTE, 3, "B3"))),
            antennas = listOf(AntennaGroup(Rat.LTE, listOf(RxChain(0, true, -95.0, null, null, -95.25, null)), true, 10.0)),
            failedReads = listOf("0052"))
        val encoded = LogSnapshot.encode(snapshot)
        assertEquals(4, encoded.getJSONArray("carrierGroups").getJSONObject(0).getJSONArray("carriers").getJSONObject(0).getJSONObject("diag").getInt("txAntennas"))
        assertTrue(encoded.getJSONArray("carrierGroups").getJSONObject(0).getJSONArray("carriers").getJSONObject(0).isNull("rsrq"))
        assertEquals(1, encoded.getJSONArray("neighbors").length())
        assertEquals(1, encoded.getJSONArray("antennas").length())
        val updates = LogSnapshot.updates(snapshot)
        assertTrue((0 until updates.length()).any { updates.getJSONObject(it).optString("entity") == "lte:0" && updates.getJSONObject(it).optString("metric") == "rsrp" })
        assertTrue((0 until updates.length()).any { updates.getJSONObject(it).optString("label") == "LTE RSRP" })
        val qmiFallback = snapshot.copy(carrierGroups = listOf(snapshot.carrierGroups.single().copy(carriers = listOf(carrier.copy(rsrp = -101.0)))))
        val aliases = LogSnapshot.updates(qmiFallback)
        val actual = (0 until aliases.length()).map { aliases.getJSONObject(it) }.single {
            it.optString("entity") == "lte:0" && it.optString("metric") == "rsrp"
        }
        assertEquals(-101.0, actual.getDouble("value"), 0.0)
        assertFalse(actual.getBoolean("aggregateEligible"))
        val bytes = ByteArrayOutputStream()
        val writer = LogArchiveWriter(header(), { bytes }, { 1_100_000_000L })
        writer.append(ui(1_050_000_000).put("snapshot", encoded).put("updates", updates).put("operatorName", "Synthetic operator")
            .put("frozen", false).put("diagEnabled", true).put("windowMs", 1000).put("aggregates", JSONArray()))
        writer.append(JSONObject().put("type", "packet").put("tNs", "1060000000").put("monoNs", "1060000000")
            .put("modemTs", "0123456789abcdef").put("captureId", "synthetic-capture").put("seq", 1).put("sim", 1)
            .put("simTagged", true).put("code", 0xB193).put("rawhex", "0c0093b10123456789abcdef00").put("resets", JSONArray())
            .put("updates", JSONArray().put(JSONObject().put("entity", "lte:0").put("identity", "1300:10").put("metric", "rsrp")
                .put("value", -94.0).put("unit", "dBm").put("aggregation", "mean").put("sum", -188).put("count", 2))))
        writer.append(JSONObject().put("type", "gap").put("source", "packet").put("tNs", "1070000000").put("endNs", "1080000000")
            .put("dropped", 2).put("reason", "Synthetic test gap"))
        writer.finish("Synthetic fixture complete")
        assertTrue(writer.awaitClosed())
        assertEquals(listOf("header", "ui", "packet", "gap", "footer"), lines(bytes.toByteArray()).map { it.getString("type") })
        File("build/test-generated/cross-layer.qbm.ndjson.gz").apply { parentFile.mkdirs(); writeBytes(bytes.toByteArray()) }
    }
}
