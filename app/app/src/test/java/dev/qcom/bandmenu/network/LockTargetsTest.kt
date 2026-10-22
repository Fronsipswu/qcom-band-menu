package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Cell-Lock page's Live rows: which cells a round offers, and the exact
 * lock each one sends. Hand-built frames, so these run everywhere.
 */
class LockTargetsTest {

    private fun tlv(tag: Int, vararg value: Int) =
        byteArrayOf(tag.toByte(), (value.size and 0xFF).toByte(), (value.size shr 8).toByte()) +
            ByteArray(value.size) { value[it].toByte() }

    private fun frame(msg: Int, vararg tlvs: ByteArray): ByteArray {
        val body = tlv(0x02, 0, 0, 0, 0) + tlvs.fold(ByteArray(0)) { a, b -> a + b }
        return byteArrayOf(2, 1, 0, (msg and 0xFF).toByte(), (msg shr 8).toByte(),
            (body.size and 0xFF).toByte(), (body.size shr 8).toByte()) + body
    }

    private fun le16(v: Int) = intArrayOf(v and 0xFF, (v shr 8) and 0xFF)
    private fun le32(v: Long) = intArrayOf((v and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(),
        ((v shr 16) and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt())

    private fun le64(v: Long) = le32(v) + le32(v shr 32)

    /** One CELL_LOCATION measurement: PCI, RSRQ, RSRP, RSSI, Srxlev (tenths). */
    private fun meas(pci: Int, rsrp: Int, rsrq: Int) =
        le16(pci) + le16(rsrq) + le16(rsrp) + le16(1024) + le16(0)

    private fun sysInfo(vararg tlvs: ByteArray) = ReadOutcome.Ok(QmiFrame.parse(frame(NasMsg.SYS_INFO, *tlvs)))

    private fun read(msg: Int, vararg tlvs: ByteArray) = ReadOutcome.Ok(QmiFrame.parse(frame(msg, *tlvs)))

    private fun builder(vararg reads: Pair<String, ReadOutcome>) =
        SnapshotBuilder(reads.toMap(), nrHardwareBands = setOf(41, 78)).build(1, 0, 0)

    private fun List<LockTarget>.serving() = filter { it.role == LockRole.SERVING }
    private fun List<LockTarget>.neighbours() = filter { it.role == LockRole.NEIGHBOUR }

    @Test
    fun lteServingCellAndNeighboursGetTheCellLock() {
        // 3GPP TS 24.008 PLMN: MCC 244, MNC 91 (the digits are swapped per nibble).
        val plmn = intArrayOf(0x42, 0x94, 0x19)
        val serving = tlv(
            0x13,
            0, *plmn, *le16(120), *le32(0x1234567), *le16(1300), *le16(42), // idle, plmn, tac, eci, earfcn, pci
            0, 0, 0, 0,                                                     // reselection priority + thresholds
            3, *meas(42, -952, -124), *meas(40, -1010, -150), *meas(300, -1130, -186)
        )
        val inter = tlv(
            0x14,
            0, 1,                                                           // (reserved), 1 frequency
            *le16(6300), 0, 0, 0, 2, *meas(11, -1000, -140), *meas(12, -1060, -170)
        )
        val snap = builder(
            NetworkReads.SYS to sysInfo(tlv(0x14, NasServiceStatus.SERVICE, NasServiceStatus.SERVICE)),
            NetworkReads.LOC to read(NasMsg.CELL_LOCATION, serving, inter)
        )
        assertEquals(ServiceMode.LTE, snap.mode)

        val all = snap.lockTargets
        val s = all.serving().single()
        assertEquals(Rat.LTE, s.rat)
        assertEquals("42", s.id)
        assertEquals(1300L, s.channel)
        assertEquals(Band(Rat.LTE, 3, "B3"), s.band)
        assertEquals(LockRequest.LteCell(1300, 42), s.lock)
        assertEquals(-95.2, s.metrics.first { it.label == "RSRP" }.value!!, 0.001)

        // The serving PCI isn't repeated as a neighbour; the other intra- and
        // inter-frequency cells are, and every one of them carries a cell lock.
        val n = all.neighbours()
        assertEquals(listOf("11", "12", "300", "40"), n.map { it.id }.sorted())
        assertEquals(listOf(1300L, 1300L, 6300L, 6300L), n.map { it.channel }.sorted())
        assertTrue(n.all { it.lock is LockRequest.LteCell })
        assertEquals(LockRequest.LteCell(1300, 300), n.first { it.id == "300" }.lock)
        // Best RSRQ first, as on the Network page.
        assertEquals("11", n.first().id)
        assertEquals(LockRequest.LteCell(6300, 11), n.first().lock)
        // 6300 sits in band 20's downlink range, not band 3's.
        assertEquals("B20", n.first { it.id == "11" }.band?.label)
        assertTrue(n.none { it.note != null })
    }

    @Test
    fun nrServingCellGetsTheCellLockAndFrequenciesTheFrequencyLock() {
        val scsEnum = 0 // 15 kHz, so a same-band neighbour can show it's reused
        val snap = builder(
            NetworkReads.SYS to sysInfo(
                tlv(0x4A, NasServiceStatus.SERVICE, NasServiceStatus.SERVICE),
                tlv(0x54, *le16(807)),
                tlv(0x60, *le32(529950)),
                tlv(0x5D, *le32(scsEnum.toLong()))
            ),
            NetworkReads.ARFCN to read(
                NasMsg.ARFCN_LIST,
                tlv(0x1C, 1, *le32(529950)),                                        // serving
                tlv(0x1D, 3, *le32(529950), *le32(530000), *le32(632000))           // neighbours
            )
        )
        assertEquals(ServiceMode.NR_SA, snap.mode)

        val all = snap.lockTargets
        val s = all.serving().single()
        assertEquals(Rat.NR, s.rat)
        assertEquals("807", s.id)
        assertEquals(529950L, s.channel)
        assertEquals(15, s.scsKhz)
        assertEquals("n41", s.band?.label)
        assertEquals(LockRequest.NrCell(529950, 807, 15, 41), s.lock)
        assertTrue(s.metrics.isEmpty() || s.metrics.all { it.value == null })

        // NR neighbours are frequencies only: no PCI, so a frequency lock. The
        // serving ARFCN is not listed again; a neighbour in the serving band
        // keeps its SCS, one in another band gets that band's default.
        val n = all.neighbours()
        assertEquals(listOf(530000L, 632000L), n.map { it.channel }.sorted())
        assertTrue(n.all { it.id == "\u2014" })
        assertEquals(LockRequest.NrFrequency(530000, 15), n.first { it.channel == 530000L }.lock)
        assertEquals(15, n.first { it.channel == 530000L }.scsKhz)
        assertEquals(LockRequest.NrFrequency(632000, 30), n.first { it.channel == 632000L }.lock)
        assertEquals("n78", n.first { it.channel == 632000L }.band?.label)
        assertTrue(n.all { it.note != null })
    }

    @Test
    fun lteNeighbourOfAnNrAnchorStillLocks() {
        // NSA: the LTE cell is the serving one, and the NR leg is pinned by PCI.
        val snap = builder(
            NetworkReads.SYS to sysInfo(
                tlv(0x14, NasServiceStatus.SERVICE, NasServiceStatus.SERVICE),
                tlv(0x54, *le16(807)),
                tlv(0x60, *le32(529950))
            ),
            NetworkReads.SIG to read(
                NasMsg.SIG_INFO,
                tlv(0x14, 0, 0, *le16(0), *le16(-950 and 0xFFFF)),                   // LTE RSRQ/RSRP
                tlv(0x17, *le16(-880 and 0xFFFF), *le16(120))                        // NR RSRP/SNR
            ),
            NetworkReads.LOC to read(
                NasMsg.CELL_LOCATION,
                tlv(0x13, 0, 0x42, 0x94, 0x19, *le16(120), *le32(0x1234567), *le16(1300), *le16(42), 0, 0, 0, 0, 1,
                    *meas(42, -952, -124)),
                tlv(0x2F, 0x42, 0x94, 0x19, 0, 0, 1, *le64(0x123456789), *le16(807),
                    *le16(-124), *le16(-880), *le16(120))
            ),
            NetworkReads.ARFCN to read(
                NasMsg.ARFCN_LIST,
                tlv(0x1B, *le32(2)),                                                 // NR RRC connected
                tlv(0x1C, 1, *le32(529950))
            )
        )
        assertEquals(ServiceMode.NR_NSA, snap.mode)

        val all = snap.lockTargets
        val serving = all.serving()
        assertEquals(listOf(Rat.LTE, Rat.NR), serving.map { it.rat })
        assertEquals(LockRequest.LteCell(1300, 42), serving[0].lock)
        assertEquals(LockRequest.NrCell(529950, 807, 30, 41), serving[1].lock)
    }

    @Test
    fun wcdmaNeighboursAreListedWithoutALock() {
        val snap = builder(
            NetworkReads.SYS to sysInfo(tlv(0x13, NasServiceStatus.SERVICE, NasServiceStatus.SERVICE)),
            NetworkReads.LOC to read(
                NasMsg.CELL_LOCATION,
                tlv(
                    0x11,
                    *le16(0x1234), 0x42, 0x94, 0x19, *le16(120), *le16(10638), *le16(100),
                    *le16(-800 and 0xFFFF), *le16(-50 and 0xFFFF),
                    1, *le16(10612), *le16(200), *le16(-900 and 0xFFFF), *le16(-80 and 0xFFFF),
                    0                                                                // no GERAN neighbours
                )
            )
        )
        assertEquals(ServiceMode.WCDMA, snap.mode)
        val n = snap.lockTargets.neighbours().single()
        assertEquals(Rat.WCDMA, n.rat)
        assertEquals("200", n.id)
        assertEquals(10612L, n.channel)
        assertNull(n.lock)
        assertNotNull(n.note)
        assertTrue(snap.lockTargets.serving().isEmpty())
    }
}
