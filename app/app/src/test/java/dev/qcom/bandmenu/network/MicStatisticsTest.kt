package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicStatisticsTest {
    private val config = DataTestConfig(kind = DataTestKind.MIC_POINT, regime = TestRegime.MIC,
        repetitions = 3, timeoutSeconds = 60, campaignId = "campaign", pointId = "point", operator = "Operator",
        url = "http://example.test/download", uploadUrl = "http://example.test/upload", host = "192.0.2.1", port = 9000,
        referenceServerConfirmed = true, stationaryConfirmed = true)

    private fun transfer(mbps: Double): MicTransferResult = MicTransferResult(List(3) { index ->
        MicStreamResult(index, 2_500_001, 10.0, if (mbps == 0.0) 0 else 1_000_000,
            if (mbps == 0.0) 1000.0 else 24_000.0 / mbps, true, 200)
    })

    private fun report(id: String = "r1", point: String = id, rates: List<Double> = listOf(10.0, 20.0, 30.0),
        c: DataTestConfig = config): DataTestReport = DataTestReport(id, c.copy(pointId = point), 1000, 10000,
        environment = "Maker Model; Android 17; SDK 37", attempts = rates.mapIndexed { index, value ->
            DataTestAttempt(index + 1, 1000 + index.toLong(), 1000 + index.toLong(), AttemptOutcome.SUCCESS,
                AttemptMeasurement(mic = MicMeasurement(download = transfer(value), upload = transfer(value / 2))))
        })

    @Test
    fun pointUsesArithmeticMeanOfAllThreeWithoutTrimmingOrMedian() {
        val point = MicStatistics.point(report(rates = listOf(0.0, 30.0, 300.0)))!!
        assertEquals(110.0, point.download!!.meanMbps!!, 1e-9)
        assertEquals(55.0, point.upload!!.meanMbps!!, 1e-9)
        assertEquals(3, point.download.validTrials)
    }

    @Test
    fun aFailedUploadDoesNotDiscardValidDownloadFromSameRound() {
        val original = report()
        val failed = original.copy(attempts = original.attempts.mapIndexed { index, attempt ->
            if (index != 1) attempt else attempt.copy(outcome = AttemptOutcome.FAILED,
                measurement = attempt.measurement.copy(measurementAvailable = true,
                    mic = attempt.measurement.mic!!.copy(upload = MicTransferResult(error = "Upload failed"))))
        })
        val point = MicStatistics.point(failed)!!
        assertEquals(20.0, point.download!!.meanMbps!!, 1e-9)
        assertNull(point.upload!!.meanMbps)
        assertEquals(2, point.upload.validTrials)
        val metrics = micSessionMetrics(failed)
        assertEquals("MIC POINT MEAN", metrics[0].status)
        assertEquals("INCOMPLETE", metrics[1].status)
        assertTrue(metrics.all { it.target.isEmpty() })
    }

    @Test
    fun incompleteTrialsAreNotReplacedByZero() {
        val point = MicStatistics.point(report(rates = listOf(10.0, 20.0)))!!
        assertNull(point.download!!.meanMbps)
        val summary = MicStatistics.campaigns(listOf(report(rates = listOf(10.0, 20.0)))).single()
        assertNull(summary.download.values)
        assertEquals(0, summary.download.completePoints)
        assertEquals(1, summary.download.incompletePoints)
    }

    @Test
    fun measuredNumericZeroIsPreservedInPointMeanAndDistribution() {
        val summary = MicStatistics.campaigns(listOf(report(rates = listOf(0.0, 0.0, 0.0)))).single()
        assertEquals(1, summary.download.completePoints)
        assertEquals(0.0, summary.download.values!!.minimum, 0.0)
        assertEquals(0.0, summary.download.values.maximum, 0.0)
    }

    @Test
    fun unfinishedExtraAndDuplicateTrialsCannotProduceAPointMean() {
        val normal = report()
        val cases = listOf(normal.copy(endedAtMs = null), report(rates = listOf(1.0, 2.0, 3.0, 4.0)),
            normal.copy(attempts = normal.attempts.map { it.copy(number = 1) }))
        cases.forEach { assertNull(MicStatistics.point(it)!!.download!!.meanMbps) }
    }

    @Test
    fun cancelledTrialsAreVisibleAndExcludedFromNumericTrials() {
        val normal = report()
        val partial = normal.copy(attempts = normal.attempts.mapIndexed { index, a ->
            if (index == 2) a.copy(outcome = AttemptOutcome.CANCELLED) else a
        })
        val point = MicStatistics.point(partial)!!
        assertEquals(2, point.attemptedTrials)
        assertEquals(1, point.cancelledTrials)
        assertEquals(2, point.download!!.validTrials)
        assertNull(point.download.meanMbps)
    }

    @Test
    fun distributionsUsePointMeansInsteadOfPoolingRawTrials() {
        val summary = MicStatistics.campaigns(listOf(
            report("a", rates = listOf(0.0, 0.0, 30.0)),
            report("b", rates = listOf(20.0, 20.0, 20.0)),
            report("c", rates = listOf(0.0, 0.0, 300.0))
        )).single().download.values!!
        assertEquals(3, summary.count)
        assertEquals(10.0, summary.minimum, 1e-9)
        assertEquals(15.0, summary.q1, 1e-9)
        assertEquals(20.0, summary.median, 1e-9)
        assertEquals(60.0, summary.q3, 1e-9)
        assertEquals(100.0, summary.maximum, 1e-9)
    }

    @Test
    fun typeSevenQuartilesInterpolateAndWhiskersRetainExtremeOutliers() {
        val summary = MicStatistics.fiveNumber(listOf(1000.0, 0.0, 20.0, 10.0))!!
        assertEquals(7.5, summary.q1, 0.0)
        assertEquals(15.0, summary.median, 0.0)
        assertEquals(265.0, summary.q3, 0.0)
        assertEquals(1000.0, summary.maximum, 0.0)
        assertNull(MicStatistics.fiveNumber(emptyList()))
        assertEquals(7.0, MicStatistics.fiveNumber(listOf(7.0))!!.q3, 0.0)
    }

    @Test
    fun duplicatePointIdentifiersExcludeEveryVisitRatherThanChooseTheBest() {
        val reports = listOf(report("a", "duplicate"), report("b", "duplicate", listOf(100.0, 100.0, 100.0)),
            report("c", "unique", listOf(5.0, 5.0, 5.0)))
        val summary = MicStatistics.campaigns(reports).single()
        assertEquals(setOf("duplicate"), summary.duplicatePointIds)
        assertEquals(3, summary.download.requestedPoints)
        assertEquals(2, summary.download.ambiguousReports)
        assertEquals(1, summary.download.completePoints)
        assertEquals(0, summary.download.incompletePoints)
        assertEquals(5.0, summary.download.values!!.median, 1e-9)
        assertEquals(listOf("a", "b", "c"), summary.sourceReportIds)
    }

    @Test
    fun sameReportPassedTwiceIsNotADuplicatePointVisit() {
        val r = report()
        val summary = MicStatistics.campaigns(listOf(r, r)).single()
        assertEquals(1, summary.download.completePoints)
        assertTrue(summary.duplicatePointIds.isEmpty())
    }

    @Test
    fun cohortSeparatesCampaignOperatorDeviceTechnologyAreaEndpointsAndSettings() {
        val base = report()
        val variants = listOf(config.copy(campaignId = "other"), config.copy(operator = "Other"),
            config.copy(declaredTechnology = "NR SA"), config.copy(micArea = MicArea.RESIDENTIAL),
            config.copy(url = "http://other.test/download"), config.copy(uploadUrl = "http://other.test/upload"),
            config.copy(host = "192.0.2.2"), config.copy(port = 9001), config.copy(timeoutSeconds = 90),
            config.copy(intervalSeconds = 5))
        for (changed in variants) {
            assertEquals(2, MicStatistics.campaigns(listOf(base, report("other", c = changed))).size)
        }
        assertEquals(2, MicStatistics.campaigns(listOf(base, report("device").copy(environment = "Other device"))).size)
        // Android network handles change between connections; they are not device/server identity.
        assertEquals(1, MicStatistics.campaigns(listOf(base, report("network").copy(network = "New transient network handle"))).size)
    }

    @Test
    fun customAndNbtcReportsAreNotIncludedInMicCampaigns() {
        assertNull(MicStatistics.point(report(c = config.copy(custom = true))))
        assertNull(MicStatistics.point(report(c = config.copy(kind = DataTestKind.PING, regime = TestRegime.NBTC))))
        assertTrue(MicStatistics.campaigns(listOf(report(c = config.copy(custom = true)))).isEmpty())
    }

    @Test
    fun incorrectParallelStreamCountCannotQualifyAsAMicTrial() {
        val normal = report()
        val malformed = normal.copy(attempts = normal.attempts.map { a ->
            a.copy(measurement = a.measurement.copy(mic = a.measurement.mic!!.copy(
                download = MicTransferResult(transfer(10.0).streams.take(1), expectedStreams = 1))))
        })
        assertNull(MicStatistics.point(malformed)!!.download!!.meanMbps)
    }

    @Test
    fun globalNetworkInvalidationExcludesDirectionValuesDespiteStoredPartialNumbers() {
        val original = report()
        val invalid = original.copy(attempts = original.attempts.mapIndexed { index, a ->
            if (index == 0) a.copy(measurement = a.measurement.copy(measurementAvailable = false)) else a
        })
        assertNull(MicStatistics.point(invalid)!!.download!!.meanMbps)
        assertEquals(2, MicStatistics.point(invalid)!!.download!!.validTrials)
    }

    @Test
    fun exportsRetainSourceIdsExclusionsSettingsAndProtectSpreadsheetCells() {
        val summary = MicStatistics.campaigns(listOf(report("source", "=SUM(A1)", c = config.copy(operator = "=1+1")))).single()
        val json = summary.toJson()
        assertEquals("source", json.getJSONArray("sourceReportIds").getString(0))
        assertEquals(3, json.getJSONObject("cohort").getInt("connections"))
        assertEquals(1, json.getJSONObject("download").getInt("completePoints"))
        assertEquals(3, json.getJSONArray("points").getJSONObject(0).getJSONArray("trials").length())
        assertTrue(json.getJSONArray("limitations").toString().contains("Type 7"))
        val csv = summary.toCsv()
        assertTrue(csv.contains("\"'=1+1\""))
        assertTrue(csv.contains("\"'=SUM(A1)\""))
        assertTrue(csv.contains("\"source\""))
        assertTrue(csv.contains("\"point_mean_mbps\""))
        assertFalse(csv.contains("MEETS SESSION TARGET"))
    }

    @Test
    fun udpSummariesWeightActualProbesAndRepliesAndRetainTotalLoss() {
        val original = report(c = config.copy(kind = DataTestKind.UDP_LATENCY))
        val udp = original.copy(attempts = listOf(
            original.attempts[0].copy(measurement = AttemptMeasurement(mic = MicMeasurement(udp = MicUdpResult(2, 2, listOf(10.0, 30.0), 0)))),
            original.attempts[1].copy(measurement = AttemptMeasurement(mic = MicMeasurement(udp = MicUdpResult(4, 0, emptyList(), 4))))
        ))
        val metrics = micSessionMetrics(udp)
        assertEquals(20.0, metrics[0].value!!, 0.0)
        assertEquals(100.0 * 4 / 6, metrics[1].value!!, 1e-9)
        assertNotNull(metrics[1].value)
    }

    @Test
    fun incompleteUdpRoundDoesNotTurnPendingProbesIntoLoss() {
        val original = report(c = config.copy(kind = DataTestKind.UDP_LATENCY))
        val partial = original.copy(attempts = listOf(original.attempts[0].copy(
            measurement = AttemptMeasurement(mic = MicMeasurement(udp = MicUdpResult(5, 1, listOf(10.0), 0, "Cancelled"))))))
        val metrics = micSessionMetrics(partial)
        assertNull(metrics[0].value)
        assertNull(metrics[1].value)
        assertTrue(MicStatistics.campaigns(listOf(partial)).single().toCsv().contains("UDP_REFERENCE"))
    }
}
