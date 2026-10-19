package dev.qcom.bandmenu.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MicConfigurationTest {
    private fun mic() = defaultDataTestConfig(TestRegime.MIC, DataTestKind.MIC_POINT).copy(
        url = "http://example.test/100mb.bin", uploadUrl = "http://example.test/upload", host = "192.0.2.1", port = 9000,
        campaignId = "2026-Tokyo", pointId = "A1", operator = "Test operator")

    @Test fun micLocksMethodButCustomCanChangeItsParameters() {
        val c = mic().copy(repetitions = 99, parallelConnections = 8, warmupBytes = 10, warmupSeconds = 0,
            measureBytes = 100, measureSeconds = 1, udpCount = 10, udpIntervalMs = 100, udpTimeoutMs = 100)
        assertEquals(3, c.effectiveRepetitions)
        assertEquals(3, c.effectiveParallelConnections)
        assertEquals(2_500_000L, c.effectiveWarmupBytes)
        assertEquals(5, c.effectiveWarmupSeconds)
        assertEquals(20_000_000L, c.effectiveMeasureBytes)
        assertEquals(20, c.effectiveMeasureSeconds)
        assertEquals(60, c.effectiveUdpCount)
        assertEquals(500, c.effectiveUdpIntervalMs)
        assertEquals(2000, c.effectiveUdpTimeoutMs)
        assertTrue(c.validationErrors().isEmpty())
        val custom = c.copy(regime = TestRegime.CUSTOM, custom = true)
        assertEquals(99, custom.effectiveRepetitions)
        assertEquals(8, custom.effectiveParallelConnections)
        assertEquals(10L, custom.effectiveWarmupBytes)
        assertEquals(100, custom.effectiveUdpIntervalMs)
    }

    @Test fun legacyJsonAndUnknownFutureRegimesNeverBecomeMic() {
        val old = JSONObject().put("kind", "FTP_DOWNLOAD").put("custom", false)
        assertTrue(DataTestConfig.fromJson(old).isNbtc)
        old.put("custom", true)
        assertEquals(TestRegime.CUSTOM, DataTestConfig.fromJson(old).effectiveRegime)
        old.put("custom", false).put("regime", "UNKNOWN_FUTURE")
        assertEquals(TestRegime.CUSTOM, DataTestConfig.fromJson(old).effectiveRegime)
        assertFalse(DataTestConfig.fromJson(old).isMic)
    }

    @Test fun independentSlotsMigrateWithoutOverwritingSavedConfigurations() {
        val old = DataTestConfig(kind = DataTestKind.FTP_DOWNLOAD, host = "192.0.2.10")
        val custom = old.copy(custom = true, host = "custom.example")
        val newKey = configurationKey(TestRegime.NBTC, old.kind)
        val values = mapOf("config_FTP_DOWNLOAD" to old.toJson().toString(), newKey to "already saved", "selected_kind" to old.kind.name)
        val changed = migrateRegimeConfigurations(values)
        assertFalse(changed.containsKey(newKey))
        assertEquals("NBTC", changed["selected_regime"])
        val migrated = migrateRegimeConfigurations(mapOf("config_FTP_DOWNLOAD" to custom.toJson().toString(), "selected_kind" to old.kind.name))
        assertEquals("CUSTOM", migrated["selected_regime"])
        assertEquals("custom.example", DataTestConfig.fromJson(JSONObject(migrated.getValue(configurationKey(TestRegime.CUSTOM, old.kind)))).host)
        assertNotEquals(configurationKey(TestRegime.MIC, DataTestKind.PARALLEL_DOWNLOAD), configurationKey(TestRegime.CUSTOM, DataTestKind.PARALLEL_DOWNLOAD))
    }

    @Test fun campaignMetadataAndEveryRequiredEndpointAreValidated() {
        val valid = mic()
        assertTrue(valid.validationErrors().isEmpty())
        listOf(valid.copy(campaignId = ""), valid.copy(pointId = ""), valid.copy(operator = ""),
            valid.copy(declaredTechnology = ""), valid.copy(url = "ftp://example.test/file"), valid.copy(uploadUrl = ""),
            valid.copy(host = "-h"), valid.copy(port = 0), valid.copy(timeoutSeconds = 25)).forEach {
            assertTrue(it.toString(), it.validationErrors().isNotEmpty())
        }
        assertTrue(valid.copy(kind = DataTestKind.PING).validationErrors().isNotEmpty())
    }

    @Test fun micWindowUsesTokyoAndDoesNotInventAWeekdayRule() {
        val sunday = Instant.parse("2026-10-18T06:00:00Z").toEpochMilli()
        assertTrue(withinMicWindow(sunday, MicArea.BUSINESS))
        assertTrue(withinMicWindow(sunday, MicArea.RESIDENTIAL))
        assertFalse(withinMicWindow(Instant.parse("2026-10-18T09:00:00Z").toEpochMilli(), MicArea.BUSINESS))
        assertTrue(withinMicWindow(Instant.parse("2026-10-18T09:00:00Z").toEpochMilli(), MicArea.RESIDENTIAL))
    }

    @Test fun allMicPointsSurviveOrdinaryTwentySessionRetention() {
        val micReports = List(1500) { DataTestReport("mic$it", mic(), it.toLong()) }
        val ordinary = List(25) { DataTestReport("other$it", DataTestConfig(), 2000L + it) }
        val retained = retainedDataTestReports(micReports + ordinary)
        assertEquals(1520, retained.size)
        assertEquals(1500, retained.count { it.config.isMic })
        assertFalse(retained.any { it.id == "other0" })
    }

    @Test fun micEvidenceRoundTripsAndExportUsesJapanReference() {
        val udp = MicUdpResult(2, 1, listOf(10.5), 1, probes = listOf(MicUdpProbe(0, 123L, 10.5, "REPLIED"), MicUdpProbe(1, 456L, null, "LOST")))
        val measurement = AttemptMeasurement(mic = MicMeasurement(udp = udp))
        val r = DataTestReport("id", mic(), 1L, 2L, attempts = listOf(DataTestAttempt(1, 1L, 1L, AttemptOutcome.SUCCESS, measurement,
            RadioReference("LTE", "RSRP -90 dBm", 1L, 1))))
        assertEquals(r, DataTestReport.fromJson(r.toJson()))
        assertEquals("Asia/Tokyo", r.toJson().getString("timeZone"))
        assertTrue(r.toJson().getString("reference").contains("MIC Japan"))
        assertFalse(r.toJson().getString("reference").contains("NBTC"))
        assertTrue(r.toCsv().contains("udp_sent"))
        assertTrue(r.toCsv().contains("2026-Tokyo"))
        assertEquals(50.0, udp.lossPercent!!, 0.0)
        assertNull(udp.copy(error = "cancelled").lossPercent)
    }
}
