package dev.qcom.bandmenu.network

/** UIM GET_CARD_STATUS (service 0x0B, message 0x002F). */
const val UIM_GET_CARD_STATUS = 0x002F

/** What the card status says about a SIM slot's subscription. */
enum class SimSlotStatus {
    /** Subscription active: its USIM/SIM app is ready. */
    ACTIVE,
    /** Card present but the subscription isn't active (e.g. turned off in Settings). */
    DISABLED,
    /** No card in the slot (the modem reports a card error, e.g. no ATR). */
    NO_SIM,
    /** Card status didn't say (no such slot, or an unrecognised state). */
    UNKNOWN
}

data class UimApp(val type: Int, val state: Int)
data class UimSlot(val cardState: Int, val errorCode: Int, val apps: List<UimApp>)

/**
 * Card status TLV 0x10. Session indexes are `slot << 8 | app` (0-based),
 * 0xFFFF when there's no session. GW = 3GPP (SIM/USIM) subscriptions.
 */
data class UimCardStatus(
    val gwPrimary: Int,
    val gwSecondary: Int,
    val slots: List<UimSlot>
) {
    /**
     * Status of the subscription for [sim] (1 or 2). A subscription is
     * active when its GW session points at an app in the ready state; a
     * present card without one is disabled; a card error or absent card
     * means no SIM.
     */
    fun statusOf(sim: Int): SimSlotStatus {
        val session = if (sim == 1) gwPrimary else gwSecondary
        if (session != NO_SESSION) {
            val app = slots.getOrNull(session shr 8)?.apps?.getOrNull(session and 0xFF)
            if (app?.state == APP_READY) return SimSlotStatus.ACTIVE
        }
        // Without a ready session, look at the SIM's own slot (SIM n = slot n).
        val slot = slots.getOrNull(sim - 1) ?: return SimSlotStatus.UNKNOWN
        return when (slot.cardState) {
            CARD_PRESENT -> SimSlotStatus.DISABLED
            CARD_ABSENT, CARD_ERROR -> SimSlotStatus.NO_SIM
            else -> SimSlotStatus.UNKNOWN
        }
    }

    companion object {
        const val NO_SESSION = 0xFFFF
        const val CARD_ABSENT = 0
        const val CARD_PRESENT = 1
        const val CARD_ERROR = 2
        const val APP_READY = 7
    }
}

/** Decodes TLV 0x10 of a GET_CARD_STATUS response; null if it's missing. */
fun decodeCardStatus(f: QmiFrame): UimCardStatus? = f.tlv(0x10)?.let {
    val r = TlvReader(it)
    val gwPrimary = r.u16()
    r.u16() // 1x primary
    val gwSecondary = r.u16()
    r.u16() // 1x secondary
    val slots = List(r.u8()) {
        val cardState = r.u8()
        r.skip(3) // UPIN state and retries
        val error = r.u8()
        val apps = List(r.u8()) {
            val type = r.u8()
            val state = r.u8()
            r.skip(4) // perso state, feature and retries
            r.skip(r.u8()) // AID
            r.skip(7) // universal PIN flag, PIN1/PUK1/PIN2/PUK2 states and retries
            UimApp(type, state)
        }
        UimSlot(cardState, error, apps)
    }
    r.done()
    UimCardStatus(gwPrimary, gwSecondary, slots)
}
