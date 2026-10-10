package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Card status TLV 0x10 as read on the device (2026-10-11), SIM 1 active in
 * every case. Only TLVs 0x02 and 0x10 are kept, as the daemon returns them
 * (the modem's other TLVs carry ICCIDs).
 */
class UimCardStatusTest {

    private fun frame(tlv10: String): QmiFrame {
        val body = "02040000000000" + "10" + "%02x%02x".format(tlv10.length / 2 and 0xFF, tlv10.length / 2 shr 8) + tlv10
        return QmiFrame.parseHex("0201002f00" + "%02x%02x".format(body.length / 2 and 0xFF, body.length / 2 shr 8) + body)
    }

    private val sim1 = "020100000000010207020b000010a0000000871002ff45ff0189060300000003030a01030a"

    @Test
    fun sim2Enabled() {
        val s = decodeCardStatus(frame("0000ffff0001ffff" + sim1 +
            "0100000000010207020b00000ca0000000871002ffffffff890003030a01030a"))!!
        assertEquals(SimSlotStatus.ACTIVE, s.statusOf(1))
        assertEquals(SimSlotStatus.ACTIVE, s.statusOf(2))
    }

    @Test
    fun sim2TurnedOffInSettings() {
        // Card present, USIM only "detected", no secondary session.
        val s = decodeCardStatus(frame("0000ffffffffffff" + sim1 +
            "0100000000010201000b00000ca0000000871002ffffffff8900000000000000"))!!
        assertEquals(SimSlotStatus.ACTIVE, s.statusOf(1))
        assertEquals(SimSlotStatus.DISABLED, s.statusOf(2))
    }

    @Test
    fun sim2Removed() {
        // Card error 3 (no ATR), no apps.
        val s = decodeCardStatus(frame("0000ffffffffffff" + sim1 + "020000000300"))!!
        assertEquals(SimSlotStatus.ACTIVE, s.statusOf(1))
        assertEquals(SimSlotStatus.NO_SIM, s.statusOf(2))
        assertEquals(3, s.slots[1].errorCode)
    }

    @Test
    fun missingTlvAndMissingSlot() {
        assertNull(decodeCardStatus(QmiFrame.parseHex("0201002f000700" + "02040000000000")))
        // Single-slot report: SIM 2 status unknown.
        val s = decodeCardStatus(frame("0000ffffffffffff" + sim1.replaceRange(0, 2, "01")))!!
        assertEquals(SimSlotStatus.UNKNOWN, s.statusOf(2))
    }
}
