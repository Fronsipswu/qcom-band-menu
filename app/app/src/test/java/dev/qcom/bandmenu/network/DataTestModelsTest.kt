package dev.qcom.bandmenu.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Regulatory boundaries and report accounting, independent of Android transports. */
class DataTestModelsTest {
    private val mondayMorning = Instant.parse("2026-10-19T01:00:00Z").toEpochMilli()
    private val defaultConfig = DataTestConfig(host = "192.0.2.1", referenceServerConfirmed = true)

    private fun attempt(
        outcome: AttemptOutcome = AttemptOutcome.SUCCESS,
        measurement: AttemptMeasurement = AttemptMeasurement(rttMs = 20.0),
        startedAtMs: Long = mondayMorning
    ) = DataTestAttempt(1, startedAtMs, 1_000, outcome, measurement)

    private fun report(
        config: DataTestConfig = defaultConfig,
        attempts: List<DataTestAttempt> = listOf(attempt())
    ) = DataTestReport("session-1", config, mondayMorning, mondayMorning + 60_000,
        "cellular", attempts, environment = "Test handset")

    @Test
    fun nsa2600Uses4gLatencyAndMediaTargetsWhileSa2600UsesSaTargets() {
        val nsa = NbtcProfile.NR_NSA_2600
        val sa = NbtcProfile.NR_SA_2600
        assertEquals(150.0, nsa.rttLimitMs, 0.0)
        assertEquals(60, nsa.webTimeoutSeconds)
        assertEquals(720, nsa.videoHeight)
        assertEquals(85.0, nsa.streamingTarget, 0.0)
        assertEquals(110.0, sa.rttLimitMs, 0.0)
        assertEquals(25, sa.webTimeoutSeconds)
        assertEquals(1080, sa.videoHeight)
        assertEquals(90.0, sa.streamingTarget, 0.0)
        for (profile in listOf(nsa, sa)) {
            assertEquals(500_000_000L, profile.fileBytes(false))
            assertEquals(100_000_000L, profile.fileBytes(true))
            assertEquals(5.0, profile.bitrateMbps(false), 0.0)
            assertEquals(1.25, profile.bitrateMbps(true), 0.0)
        }
    }

    @Test
    fun nbtcParametersOverrideInstrumentSettingsAndCustomModeKeepsThem() {
        val config = defaultConfig.copy(kind = DataTestKind.FTP_UPLOAD, profile = NbtcProfile.UMTS,
            timeoutSeconds = 9, transferBytes = 123, pingPayloadBytes = 512,
            videoHeight = 1440, videoMinimumSeconds = 45)
        assertEquals(600, config.effectiveTimeoutSeconds)
        assertEquals(2_000_000L, config.effectiveTransferBytes)
        assertEquals(32, config.effectivePingPayloadBytes)
        assertEquals(360, config.effectiveVideoHeight)
        assertEquals(180, config.effectiveVideoMinimumSeconds)
        val custom = config.copy(custom = true)
        assertEquals(9, custom.effectiveTimeoutSeconds)
        assertEquals(123L, custom.effectiveTransferBytes)
        assertEquals(512, custom.effectivePingPayloadBytes)
        assertEquals(1440, custom.effectiveVideoHeight)
        assertEquals(45, custom.effectiveVideoMinimumSeconds)
    }

    @Test
    fun rttThresholdIsStrictAndTimeoutsRemainInDenominator() {
        for (profile in listOf(NbtcProfile.UMTS, NbtcProfile.LTE, NbtcProfile.NR_SA_2600)) {
            val samples = List(3) { attempt(measurement = AttemptMeasurement(rttMs = profile.rttLimitMs - 0.001)) } +
                attempt(measurement = AttemptMeasurement(rttMs = profile.rttLimitMs)) +
                attempt(AttemptOutcome.TIMED_OUT, AttemptMeasurement())
            val metrics = report(defaultConfig.copy(profile = profile), samples).assess()
            assertEquals(60.0, metrics[0].value!!, 0.0)
            assertEquals("BELOW SESSION TARGET", metrics[0].status)
            assertEquals(20.0, metrics[2].value!!, 0.0)
        }
    }

    @Test
    fun exactEightyPercentRttTargetPassesAndCancelledProbeIsExcluded() {
        val samples = List(4) { attempt(measurement = AttemptMeasurement(rttMs = 149.0)) } +
            attempt(measurement = AttemptMeasurement(rttMs = 150.0)) +
            attempt(AttemptOutcome.CANCELLED, AttemptMeasurement())
        val metrics = report(attempts = samples).assess()
        assertEquals(80.0, metrics[0].value!!, 0.0)
        assertEquals("MEETS SESSION TARGET", metrics[0].status)
        assertEquals(149.2, metrics[1].value!!, 0.00001)
        assertEquals(0.0, metrics[2].value!!, 0.0)
    }

    @Test
    fun ftpBitrateUsesSuccessfulTransfersAndSuccessRateUsesAllAttempts() {
        val config = defaultConfig.copy(kind = DataTestKind.FTP_DOWNLOAD)
        val samples = listOf(2.5, 3.0, 4.0, 2.499).map {
            attempt(measurement = AttemptMeasurement(throughputMbps = it))
        } + attempt(AttemptOutcome.FAILED, AttemptMeasurement(throughputMbps = 99.0)) +
            attempt(AttemptOutcome.CANCELLED, AttemptMeasurement(throughputMbps = 99.0))
        val metrics = report(config, samples).assess()
        assertEquals(80.0, metrics[0].value!!, 0.0)
        assertEquals("MEETS SESSION TARGET", metrics[0].status)
        assertEquals(75.0, metrics[1].value!!, 0.0)
        assertEquals("MEETS SESSION TARGET", metrics[1].status)
        assertEquals(2.99975, metrics[2].value!!, 0.00001)
    }

    @Test
    fun allFailedTransfersHaveZeroSuccessAndNoBitrateVerdict() {
        val result = report(defaultConfig.copy(kind = DataTestKind.FTP_UPLOAD), listOf(
            attempt(AttemptOutcome.FAILED, AttemptMeasurement()),
            attempt(AttemptOutcome.TIMED_OUT, AttemptMeasurement())
        )).assess()
        assertEquals(0.0, result[0].value!!, 0.0)
        assertEquals("BELOW SESSION TARGET", result[0].status)
        assertNull(result[1].value)
        assertEquals("NO DATA", result[1].status)
        assertNull(result[2].value)
    }

    @Test
    fun emptyOrCancelledSessionsCannotProducePassingRttOrFtpResults() {
        for (kind in listOf(DataTestKind.PING, DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD)) {
            for (samples in listOf(emptyList(), listOf(attempt(AttemptOutcome.CANCELLED)))) {
                val result = report(defaultConfig.copy(kind = kind), samples).assess()
                assertNull(result[0].value)
                assertEquals("NO DATA", result[0].status)
            }
        }
    }

    @Test
    fun averageFtpTargetsApplyOnlyTo2600Profiles() {
        for (profile in NbtcProfile.entries) {
            val download = report(defaultConfig.copy(kind = DataTestKind.FTP_DOWNLOAD, profile = profile),
                listOf(attempt(measurement = AttemptMeasurement(throughputMbps = 20.0)))).assess().last()
            val upload = report(defaultConfig.copy(kind = DataTestKind.FTP_UPLOAD, profile = profile),
                listOf(attempt(measurement = AttemptMeasurement(throughputMbps = 5.0)))).assess().last()
            if (profile in listOf(NbtcProfile.NR_NSA_2600, NbtcProfile.NR_SA_2600)) {
                assertEquals(">= 20.0 Mbps", download.target)
                assertEquals(">= 5.0 Mbps", upload.target)
                assertEquals("MEETS SESSION TARGET", download.status)
                assertEquals("MEETS SESSION TARGET", upload.status)
            } else {
                assertEquals("", download.target)
                assertEquals("MEASURED", download.status)
                assertEquals("", upload.target)
            }
        }
    }

    @Test
    fun streamingUsesAttemptedThenAccessedDenominatorsAndExcludesCancellation() {
        val samples = listOf(
            attempt(measurement = AttemptMeasurement(playbackStarted = true, playbackCompleted = true,
                playbackDurationMs = 45_000, freezeDurationMs = 5_000)),
            attempt(AttemptOutcome.FAILED, AttemptMeasurement(playbackStarted = true,
                playbackDurationMs = 50_000)),
            attempt(AttemptOutcome.TIMED_OUT, AttemptMeasurement()),
            attempt(AttemptOutcome.CANCELLED, AttemptMeasurement(playbackStarted = true,
                playbackCompleted = true, freezeDurationMs = 100_000))
        )
        val metrics = report(defaultConfig.copy(kind = DataTestKind.STREAMING), samples).assess()
        assertEquals(200.0 / 3, metrics[0].value!!, 0.00001)
        assertEquals(50.0, metrics[1].value!!, 0.0)
        assertEquals(5.0, metrics[2].value!!, 0.0)
        assertEquals("MEETS SESSION TARGET", metrics[2].status)
    }

    @Test
    fun noPlaybackHasNoCompletionOrFreezingData() {
        val metrics = report(defaultConfig.copy(kind = DataTestKind.STREAMING),
            listOf(attempt(AttemptOutcome.FAILED, AttemptMeasurement()))).assess()
        assertEquals(0.0, metrics[0].value!!, 0.0)
        assertNull(metrics[1].value)
        assertEquals("NO DATA", metrics[1].status)
        assertNull(metrics[2].value)
        assertEquals("NO DATA", metrics[2].status)
    }

    @Test
    fun unverifiedContentAndCustomRunsCannotReceiveNbtcPassingStatus() {
        val sample = attempt(measurement = AttemptMeasurement(throughputMbps = 30.0, contentVerified = false))
        val config = defaultConfig.copy(kind = DataTestKind.FTP_DOWNLOAD, profile = NbtcProfile.NR_SA_2600)
        assertTrue(report(config, listOf(sample)).assess().all { it.status == "UNVERIFIED" })
        assertTrue(report(config.copy(custom = true), listOf(sample)).assess().all { it.status == "CUSTOM" })
        assertTrue(report(config, listOf(sample)).limitations().any { it.contains("could not be verified") })
    }

    @Test
    fun sampleRequirementsRespectAreaFiveGAndStreaming() {
        assertEquals(120, defaultConfig.minimumSamples)
        assertEquals(60, defaultConfig.copy(area = SamplingArea.COMMUNITY).minimumSamples)
        assertEquals(40, defaultConfig.copy(area = SamplingArea.SITE).minimumSamples)
        assertEquals(40, defaultConfig.copy(profile = NbtcProfile.NR_NSA, area = SamplingArea.SITE).minimumSamples)
        assertEquals(100, defaultConfig.copy(kind = DataTestKind.STREAMING).minimumSamples)
        val config = defaultConfig.copy(area = SamplingArea.SITE)
        val short = List(39) { attempt() } + attempt(AttemptOutcome.CANCELLED)
        assertTrue(report(config, short).limitations().any { it.startsWith("Insufficient samples") })
        assertFalse(report(config, List(40) { attempt() }).limitations().any { it.startsWith("Insufficient samples") })
    }

    @Test
    fun samplingWindowUsesBangkokWeekdaysAndExcludesEightPm() {
        fun allowed(utc: String) = withinNbtcWindow(Instant.parse(utc).toEpochMilli())
        assertFalse(allowed("2026-10-19T00:59:59Z"))
        assertTrue(allowed("2026-10-19T01:00:00Z"))
        assertTrue(allowed("2026-10-23T12:59:59Z"))
        assertFalse(allowed("2026-10-23T13:00:00Z"))
        assertFalse(allowed("2026-10-24T05:00:00Z"))
        val saturday = Instant.parse("2026-10-24T05:00:00Z").toEpochMilli()
        assertTrue(report(attempts = listOf(attempt(startedAtMs = saturday))).limitations().any { it.contains("Samples outside") })
    }

    @Test
    fun unfinishedAndUnconfirmedRunsKeepTheirLimitations() {
        val unfinished = report(defaultConfig.copy(referenceServerConfirmed = false)).copy(endedAtMs = null)
        assertTrue(unfinished.limitations().any { it.contains("has not been confirmed") })
        assertTrue(unfinished.limitations().any { it.contains("has not finished") })
        assertTrue(unfinished.limitations().any { it.contains("not an operator compliance certification") })
    }

    @Test
    fun configAndReportRoundTripOmitPasswordEvenIfImportedJsonContainsIt() {
        val config = defaultConfig.copy(kind = DataTestKind.FTP_UPLOAD, profile = NbtcProfile.NR_NSA_2600,
            repetitions = 31, intervalSeconds = 3, timeoutSeconds = 12, port = 2121, username = "tester",
            password = "unique-secret-password", path = "/incoming", transferBytes = 42,
            operator = "Operator", locationLabel = "Bangkok", sessionLabel = "Morning")
        val configJson = config.toJson()
        assertFalse(configJson.has("password"))
        configJson.put("password", "imported-secret")
        assertEquals(config.copy(password = ""), DataTestConfig.fromJson(configJson))
        val original = report(config, listOf(attempt(measurement = AttemptMeasurement(bytes = 123,
            durationMs = 250.0, throughputMbps = 3.5, httpStatus = 200, detail = "completed"))))
        val json = original.toJson().toString()
        assertFalse(json.contains("unique-secret-password"))
        assertEquals(original.copy(config = config.copy(password = "")), DataTestReport.fromJson(JSONObject(json)))
        assertFalse(original.toCsv().contains("unique-secret-password"))
    }

    @Test
    fun csvQuotesEmbeddedDelimitersAndNeutralizesSpreadsheetFormulas() {
        assertEquals("\"A,\"\"B\"\"\r\nC\"", csvCell("A,\"B\"\r\nC"))
        for (value in listOf("=1+1", "+1", "-1", "@SUM(A1)", "  =1+1", "\t=1+1")) {
            assertEquals("\"'$value\"", csvCell(value))
        }
        assertEquals("\"\"", csvCell(null))
        val csv = report(defaultConfig.copy(operator = "=1+1", locationLabel = "A,\"B\"\r\nC")).toCsv()
        assertTrue(csv.contains("\"'=1+1\""))
        assertTrue(csv.contains("\"A,\"\"B\"\"\r\nC\""))
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun endpointsRejectSchemesCredentialsFragmentsPortsAndCommandFlagsWhereInappropriate() {
        for (host in listOf("", "https://192.0.2.1", "192.0.2.1:21", "-c", "host name", "user@host", "host/path")) {
            assertTrue(host, defaultConfig.copy(host = host).validationErrors().isNotEmpty())
        }
        assertTrue(defaultConfig.copy(host = "example.com").validationErrors().isNotEmpty())
        assertTrue(defaultConfig.copy(custom = true, host = "example.com").validationErrors().isEmpty())
        assertTrue(defaultConfig.copy(host = "2001:db8::1").validationErrors().isEmpty())
        for (url in listOf("ftp://example.com/file", "https://user:secret@example.com", "https://example.com/#fragment",
            "https://example.com:0", "https://example.com:65536", "https:///file", "javascript:alert(1)")) {
            assertTrue(url, defaultConfig.copy(kind = DataTestKind.WEB_PAGE, url = url).validationErrors().isNotEmpty())
        }
        assertTrue(defaultConfig.copy(kind = DataTestKind.WEB_PAGE, url = "https://example.com/page?q=1").validationErrors().isEmpty())
    }

    @Test
    fun ftpRejectsControlCharactersAndUnsafeOrMissingConnectionParameters() {
        val config = defaultConfig.copy(kind = DataTestKind.FTP_UPLOAD, path = "/uploads")
        assertTrue(config.validationErrors().isEmpty())
        for (bad in listOf(config.copy(path = "relative"), config.copy(username = ""), config.copy(port = 0),
            config.copy(port = 65536), config.copy(password = "secret\r\nDELE /file"), config.copy(path = "/upload\u0000file"))) {
            assertTrue(bad.validationErrors().isNotEmpty())
        }
    }

    @Test
    fun videoAndRunLimitsRejectInvalidCustomSettings() {
        val config = defaultConfig.copy(kind = DataTestKind.STREAMING, custom = true, videoId = "abcdefghijk")
        assertTrue(config.validationErrors().isEmpty())
        for (bad in listOf(config.copy(videoId = "short"), config.copy(videoHeight = 999),
            config.copy(videoMinimumSeconds = 0), config.copy(repetitions = 0), config.copy(repetitions = 10001),
            config.copy(intervalSeconds = -1), config.copy(timeoutSeconds = 0))) {
            assertTrue(bad.toString(), bad.validationErrors().isNotEmpty())
        }
    }
}
