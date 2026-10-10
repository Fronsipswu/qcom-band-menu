package dev.qcom.bandmenu.network

/** Radio families shown on the Network page. */
enum class Rat(val label: String) { GSM("GSM"), WCDMA("WCDMA"), LTE("LTE"), NR("NR") }

/** NAS radio interface enum (TX/RX request, PLMN name, RF band info). */
object NasRadioIf {
    const val GSM = 0x04
    const val UMTS = 0x05
    const val LTE = 0x08
    const val NR5G = 0x0C

    fun toRat(v: Int): Rat? = when (v) {
        GSM -> Rat.GSM; UMTS -> Rat.WCDMA; LTE -> Rat.LTE; NR5G -> Rat.NR; else -> null
    }
}

/** A band decoded from the NAS "active band" enum (spec Table 4-1). */
data class Band(val rat: Rat, val number: Int, val label: String)

object NasBands {
    // LTE: enum 120..175 is not in band order, so it's an explicit table.
    private val LTE = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 33, 34, 35, 36, 37, 38, 39, 40,
        18, 19, 20, 21, 24, 25, 41, 42, 43, 23, 26, 32, 125, 126, 127, 28, 29, 30, 66, 250,
        46, 27, 31, 71, 47, 48, 67, 68, 49, 85, 72, 73, 86
    )
    private val NR = intArrayOf(
        1, 2, 3, 5, 7, 8, 20, 28, 38, 41, 50, 51, 66, 70, 71, 74, 75, 76, 77, 78, 79, 80, 81,
        82, 83, 84, 85, 257, 258, 259, 260, 261, 12, 25, 34, 39, 40, 65, 86, 48, 14, 13, 18,
        26, 30, 29, 53
    )
    // WCDMA enum -> operating band number.
    private val WCDMA = mapOf(80 to 1, 81 to 2, 82 to 3, 83 to 4, 84 to 5, 85 to 6, 86 to 7, 87 to 8, 88 to 9, 90 to 11, 91 to 19)
    private val GSM = mapOf(40 to "450", 41 to "480", 42 to "750", 43 to "850", 44 to "E-900", 45 to "P-900", 46 to "R-900", 47 to "1800", 48 to "1900")

    /** Returns null for enums this table doesn't know (shown as "raw N"). */
    fun decode(enum: Int): Band? = when (enum) {
        in 120 until 120 + LTE.size -> LTE[enum - 120].let { Band(Rat.LTE, it, "B$it") }
        in 250 until 250 + NR.size -> NR[enum - 250].let { Band(Rat.NR, it, "n$it") }
        in WCDMA -> WCDMA.getValue(enum).let { Band(Rat.WCDMA, it, "B$it") }
        in GSM -> Band(Rat.GSM, 0, GSM.getValue(enum))
        else -> null
    }

    fun label(enum: Int): String = decode(enum)?.label ?: "raw $enum"
}

object NasBandwidth {
    /** LTE CA info bandwidth enum (GET_LTE_CPHY_CA_INFO, ARFCN_LIST LTE lists), in MHz. */
    fun lteMhz(enum: Int): Double? = when (enum) {
        0 -> 1.4; 1 -> 3.0; 2 -> 5.0; 3 -> 10.0; 4 -> 15.0; 5 -> 20.0; else -> null
    }

    /** RF bandwidth enum (GET_RF_BAND_INFO 0x12, PHY_CHANNEL_INFO), in MHz. */
    fun rfMhz(enum: Int): Double? = when (enum) {
        0 -> 1.4; 1 -> 3.0; 2 -> 5.0; 3 -> 10.0; 4 -> 15.0; 5 -> 20.0
        6 -> 5.0; 7 -> 10.0; 8 -> 15.0; 9 -> 20.0; 10 -> 25.0; 11 -> 30.0; 12 -> 40.0
        13 -> 50.0; 14 -> 60.0; 15 -> 80.0; 16 -> 90.0; 17 -> 100.0; 18 -> 200.0; 19 -> 400.0
        20 -> 0.2; 22 -> 5.0; 23 -> 10.0; 24 -> 70.0
        else -> null
    }
}

object NasRrc {
    // ARFCN_LIST 0x19 uses lte_rrc_state; captures only ever showed 4 while
    // connected, which matches LTE_RRC_STATE_EXT, so that table is used.
    fun lte(v: Int): String = when (v) {
        0 -> "Null"; 1 -> "Idle (any cell)"; 2 -> "Idle"; 3 -> "Connecting"; 4 -> "Connected"
        5 -> "Releasing"; 6 -> "Inactive"; 7 -> "Idle (not camped)"; 8 -> "Suspended"; 9 -> "IRAT to LTE"
        else -> "raw $v"
    }

    fun nr(v: Int): String = when (v) {
        0 -> "Idle"; 1 -> "Connected"; 2 -> "Inactive"; else -> "raw $v"
    }

    fun wcdma(v: Int): String = when (v) {
        0 -> "Idle"; 1 -> "CELL_PCH"; 2 -> "URA_PCH"; 3 -> "CELL_FACH"; 4 -> "CELL_DCH"; else -> "raw $v"
    }
}

/** SYS_INFO service status enum. */
object NasServiceStatus {
    const val NO_SERVICE = 0
    const val LIMITED = 1
    const val SERVICE = 2
    const val LIMITED_REGIONAL = 3
    const val POWER_SAVE = 4

    fun label(v: Int): String = when (v) {
        NO_SERVICE -> "No service"; LIMITED -> "Limited"; SERVICE -> "In service"
        LIMITED_REGIONAL -> "Limited (regional)"; POWER_SAVE -> "Power save"; else -> "raw $v"
    }
}

/** NR subcarrier spacing enum -> kHz. */
fun nrScsKhz(enum: Int): Int? = when (enum) { 0 -> 15; 1 -> 30; 2 -> 60; 3 -> 120; 4 -> 240; else -> null }
