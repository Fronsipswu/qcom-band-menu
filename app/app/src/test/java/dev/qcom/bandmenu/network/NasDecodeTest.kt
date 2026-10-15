package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-built frames (no device data), so these run everywhere, including CI. */
class NasDecodeTest {

    private fun tlv(tag: Int, vararg value: Int) =
        byteArrayOf(tag.toByte(), (value.size and 0xFF).toByte(), (value.size shr 8).toByte()) +
            ByteArray(value.size) { value[it].toByte() }

    private fun frame(msg: Int, vararg tlvs: ByteArray, result: Int = 0, error: Int = 0): ByteArray {
        val body = tlv(0x02, result and 0xFF, result shr 8, error and 0xFF, error shr 8) +
            tlvs.fold(ByteArray(0)) { a, b -> a + b }
        return byteArrayOf(2, 1, 0, (msg and 0xFF).toByte(), (msg shr 8).toByte(),
            (body.size and 0xFF).toByte(), (body.size shr 8).toByte()) + body
    }

    private fun le16(v: Int) = intArrayOf(v and 0xFF, (v shr 8) and 0xFF)

    @Test
    fun frameValidation() {
        val good = frame(NasMsg.SIG_INFO)
        assertTrue(QmiFrame.parse(good).ok)
        // Truncated: body length larger than the frame.
        assertThrows(QmiFormatException::class.java) { QmiFrame.parse(good.copyOf(good.size - 1)) }
        // Trailing byte not covered by body length.
        assertThrows(QmiFormatException::class.java) { QmiFrame.parse(good + 0) }
        // TLV length overruns the body.
        val overrun = byteArrayOf(2, 1, 0, 0x4F, 0, 10, 0, 0x02, 4, 0, 0, 0, 0, 0, 0x10, 9, 0)
        assertThrows(QmiFormatException::class.java) { QmiFrame.parse(overrun) }
        // Missing result TLV.
        assertThrows(QmiFormatException::class.java) { QmiFrame.parse(byteArrayOf(2, 1, 0, 0x4F, 0, 0, 0)) }
        // Request flag, not a response.
        assertThrows(QmiFormatException::class.java) { QmiFrame.parse(byteArrayOf(0) + good.copyOfRange(1, good.size)) }
    }

    @Test
    fun qmiErrorIsKeptDistinctFromData() {
        val f = QmiFrame.parse(frame(NasMsg.CELL_LOCATION, result = 1, error = 0x5D))
        assertFalse(f.ok)
        assertEquals(0x5D, f.error)
    }

    @Test
    fun sigInfoSentinelsBecomeNull() {
        // NR SIG_INFO: rsrp -32768 (invalid), snr 125 (12.5 dB); rsrq -32768.
        val f = QmiFrame.parse(frame(NasMsg.SIG_INFO,
            tlv(0x17, *le16(0x8000), *le16(125)),
            tlv(0x18, *le16(0x8000)),
            tlv(0x14, 0xC4, 0xF6, *le16(-95 and 0xFFFF), *le16(0x8000))))
        val s = decodeSigInfo(f)
        assertNull(s.nrRsrp); assertNull(s.nrRsrq); assertEquals(12.5, s.nrSnr!!, 1e-9)
        assertEquals(-60, s.lteRssi); assertEquals(-10, s.lteRsrq); assertEquals(-95, s.lteRsrp)
        assertNull("LTE SNR sentinel", s.lteSnr)
    }

    @Test
    fun txPowerOnlyWhileInTraffic() {
        val chain = intArrayOf(1, *le32(-610), *le32(Int.MIN_VALUE), *le32(Int.MIN_VALUE), *le32(-905), *le32(-1))
        val idle = decodeTxRxInfo(QmiFrame.parse(frame(NasMsg.TX_RX_INFO, tlv(0x10, *chain), tlv(0x12, 0, *le32(123)))), NasRadioIf.LTE)
        assertEquals(false, idle.inTraffic); assertNull(idle.txPower)
        assertEquals(-61.0, idle.chains[0].rxPower!!, 1e-9); assertEquals(-90.5, idle.chains[0].rsrp!!, 1e-9)
        assertNull("ECIO is not valid for LTE", idle.chains[0].ecio)
        assertNull("phase UINT_MAX", idle.chains[0].phaseDeg)
        val traffic = decodeTxRxInfo(QmiFrame.parse(frame(NasMsg.TX_RX_INFO, tlv(0x12, 1, *le32(123)))), NasRadioIf.LTE)
        assertEquals(12.3, traffic.txPower!!, 1e-9)
        val sentinel = decodeTxRxInfo(QmiFrame.parse(frame(NasMsg.TX_RX_INFO, tlv(0x12, 1, *le32(Int.MIN_VALUE)))), NasRadioIf.LTE)
        assertNull(sentinel.txPower)
    }

    private fun le32(v: Int) = intArrayOf(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, (v ushr 24) and 0xFF)

    @Test
    fun plmnKeepsLeadingZerosAndThreeDigitMnc() {
        // MCC 520, MNC 04 -> BCD 25 F0 40.
        assertEquals(Plmn("520", "04"), bcdPlmn(byteArrayOf(0x25, 0xF0.toByte(), 0x40)))
        // MCC 310, MNC 260 -> BCD 13 00 62.
        assertEquals(Plmn("310", "260"), bcdPlmn(byteArrayOf(0x13, 0x00, 0x62)))
        assertEquals("01040008020400110100" + "00", plmnNamePayload(Plmn("520", "04")))
        assertTrue(plmnNamePayload(Plmn("310", "260")).endsWith("01"))
    }

    @Test
    fun bandEnums() {
        assertEquals("B1", NasBands.label(120)); assertEquals("B3", NasBands.label(122))
        assertEquals("B28", NasBands.label(158)); assertEquals("B66", NasBands.label(161))
        assertEquals("n28", NasBands.label(257)); assertEquals("n41", NasBands.label(259))
        assertEquals("n78", NasBands.label(269)); assertEquals("n53", NasBands.label(296))
        assertEquals("B8", NasBands.label(87)); assertEquals("1800", NasBands.label(47))
        assertEquals("raw 999", NasBands.label(999))
        assertNull(NasBandwidth.lteMhz(9)); assertEquals(100.0, NasBandwidth.rfMhz(17)!!, 0.0)
    }

    @Test
    fun absentNeighborListIsNotAnEmptyList() {
        val a = decodeArfcnList(QmiFrame.parse(frame(NasMsg.ARFCN_LIST, tlv(0x1C, 1, *le32(529950)))))
        assertEquals(listOf(529950L), a.nrServing)
        assertNull(a.nrNeighbor)
        val b = decodeArfcnList(QmiFrame.parse(frame(NasMsg.ARFCN_LIST, tlv(0x1D, 0))))
        assertEquals(emptyList<Long>(), b.nrNeighbor)
    }

    @Test
    fun bandsDerivedFromChannels() {
        // 529950 (2649.75 MHz) is in n7 and n41; the modem's supported bands decide.
        assertEquals(41, nrBandForArfcn(529950, setOf(1, 28, 40, 41)))
        assertEquals(7, nrBandForArfcn(529950, setOf(7)))
        assertEquals(28, nrBandForArfcn(153630))
        assertEquals(78, nrBandForArfcn(636000, setOf(78)))
        assertEquals(3432.0, nrFrequencyForArfcn(628800)!!, 1e-9)
    }

    @Test
    fun reportedNrBandMustFitTheArfcn() {
        assertTrue(nrBandFitsArfcn(77, 627360))      // n77 at 3410.4 MHz (B823 on an S22 Ultra)
        assertTrue(nrBandFitsArfcn(78, 627360))
        assertFalse(nrBandFitsArfcn(78, 667296))     // 4009 MHz is above n78
        assertFalse(nrBandFitsArfcn(7, 647328))
        assertTrue(nrBandFitsArfcn(999, 647328))     // not in the table: not ruled out
    }

    @Test
    fun nestedNrBandsAreLabelledAsPairs() {
        // n78 inside n77, n38 inside n41, n5 inside n26: the ARFCN can't tell them apart.
        assertEquals("n77/78", nrBandLabelForArfcn(628800))
        assertEquals("n77/78", nrBandLabelForArfcn(653333, setOf(77, 78)))
        assertEquals("n77", nrBandLabelForArfcn(660000))
        assertEquals("n38/41", nrBandLabelForArfcn(520000))
        assertEquals("n5/26", nrBandLabelForArfcn(175000))
        // The modem's supported bands can rule one out.
        assertEquals("n78", nrBandLabelForArfcn(636000, setOf(1, 28, 78)))
        assertEquals("n41", nrBandLabelForArfcn(520000, setOf(28, 41)))
        // Outside the nested ranges: as before.
        assertEquals("n41", nrBandLabelForArfcn(529950, setOf(1, 28, 40, 41)))
        assertEquals("n26", nrBandLabelForArfcn(172500, setOf(26)))
        assertEquals("n28", nrBandLabelForArfcn(153630))
        // DIAG mode (pairs = false): an overlap gives no band; one band only is shown.
        assertNull(nrBandLabelForArfcn(647328, setOf(77, 78), pairs = false))
        assertEquals("n77", nrBandLabelForArfcn(667296, setOf(77, 78), pairs = false))
        assertEquals("n78", nrBandLabelForArfcn(636000, setOf(1, 28, 78), pairs = false))
        assertEquals("n41", nrBandLabelForArfcn(529950, pairs = false))
        assertNull(nrBandForArfcn(5))
        assertEquals(8, lteBandForEarfcn(3750)); assertEquals(3, lteBandForEarfcn(1275))
        assertEquals(1, wcdmaBandForUarfcn(10563)); assertEquals(8, wcdmaBandForUarfcn(2987))
        assertEquals(937.4, wcdmaFrequencyForUarfcn(2987)!!, 1e-9)
        assertEquals(2112.6, wcdmaFrequencyForUarfcn(10563)!!, 1e-9)
        assertEquals(877.4, wcdmaFrequencyForUarfcn(712)!!, 1e-9)
        assertEquals("900", gsmBandForArfcn(122)?.label)
        assertNull("1800/1900 overlap", gsmBandForArfcn(600))
    }
}
