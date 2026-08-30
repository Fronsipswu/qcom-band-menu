package dev.qcom.bandmenu

import org.json.JSONArray
import org.json.JSONObject

enum class RatType { GSM, WCDMA, LTE, NR }

enum class NrMode { SA, NSA, BOTH, UNKNOWN }

data class SimState(
    val ratMask: Set<RatType> = emptySet(),
    val gsmBands: Set<Int> = emptySet(),
    val wcdmaBands: Set<Int> = emptySet(),
    val lteBands: Set<Int> = emptySet(),
    val nrNsaBands: Set<Int> = emptySet(),
    val nrSaBands: Set<Int> = emptySet(),
    val nrMode: NrMode = NrMode.BOTH
)

data class SimBandFilter(
    val gsm: Set<Int> = emptySet(),
    val wcdma: Set<Int> = emptySet(),
    val lte: Set<Int> = emptySet(),
    val nrNsa: Set<Int> = emptySet(),
    val nrSa: Set<Int> = emptySet()
) {
    companion object {
        fun visibleBands(hardware: Set<Int>, filter: Set<Int>): Set<Int> =
            if (filter.isEmpty()) hardware else hardware.intersect(filter)
    }
}

data class BandFilterState(
    val enabled: Boolean = false,
    val sim1: SimBandFilter = SimBandFilter(),
    val sim2: SimBandFilter = SimBandFilter()
)

data class HardwareBands(
    val gsm: Set<Int> = emptySet(),
    val wcdma: Set<Int> = emptySet(),
    val lte: Set<Int> = emptySet(),
    val nr: Set<Int> = emptySet()
)

data class LteCellLockEntry(
    val pci: Int = 0,
    val earfcn: Int = 0
)

data class LteCellLockState(
    val valid: Boolean = false,
    val locks: List<LteCellLockEntry> = emptyList()
)

data class NrPciLock(
    val pci: Int = 0,
    val scsKhz: Int = 0,
    val arfcn: Int = 0,
    val bands: Set<Int> = emptySet()
)

data class NrArfcnLockEntry(
    val arfcn: Int = 0,
    val scsKhz: Int = 0
)

data class NrGnbAllowlist(
    val gnbIds: List<Int> = emptyList(),
    val idBits: Int? = null
)

data class NrCellLockState(
    val valid: Boolean = false,
    val type: String = "none",
    val typeRaw: Int = 2,
    val pciLock: NrPciLock? = null,
    val arfcnLock: List<NrArfcnLockEntry>? = null,
    val multiPciLock: Boolean = false,
    val gnbAllowlist: NrGnbAllowlist? = null
)

data class CellLockState(
    val lte: LteCellLockState = LteCellLockState(),
    val nr: NrCellLockState = NrCellLockState()
)

data class PlmnLockedPlmn(
    val mcc: Int = 0,
    val mnc: Int = 0,
    val mncIncludesPcsDigit: Boolean = false,
    val mncDisplay: String = ""
)

data class PlmnLockState(
    val valid: Boolean = false,
    val mode: String? = null,
    val mcc: Int? = null,
    val mnc: Int? = null,
    val lockedPlmn: PlmnLockedPlmn? = null
)

data class NrIndependentCapability(
    val checked: Boolean = false,
    val independentLockSupported: Boolean? = null
)

data class ModemState(
    val sim1: SimState = SimState(),
    val sim2: SimState = SimState(),
    val hardware: HardwareBands = HardwareBands(),
    val binaryInstalled: Boolean = false,
    val sim1CellLock: CellLockState = CellLockState(),
    val sim2CellLock: CellLockState = CellLockState(),
    val sim1PlmnLock: PlmnLockState = PlmnLockState(),
    val sim2PlmnLock: PlmnLockState = PlmnLockState(),
    val nrIndependentSupported: Boolean? = null
)

object BandConstants {
    val GSM_BANDS = setOf(850, 900, 1800, 1900)
    val WCDMA_RANGE = 1..19
    val LTE_RANGE = 1..256
    val NR_RANGE = 1..512
    val ALL_RAT_TYPES = setOf(RatType.GSM, RatType.WCDMA, RatType.LTE, RatType.NR)
}

data class DaemonResponse(
    val id: Int?,
    val cmd: String,
    val ok: Boolean,
    val error: DaemonError?,
    val simState: SimState?,
    val hardware: HardwareBands?,
    val cellLockState: CellLockState?,
    val plmnLockState: PlmnLockState? = null,
    val nrIndependentCapability: NrIndependentCapability?,
    val sim: Int,
    val status: String
)

data class DaemonError(
    val stage: String,
    val message: String,
    val result: Int?,
    val code: Int?,
    val label: String?,
    val rejectedBands: Set<Int>?
)

object JsonRequestBuilder {

    fun query(): JSONObject = JSONObject().put("cmd", "query")

    fun refresh(): JSONObject = JSONObject().put("cmd", "refresh")

    fun simSet(sim: Int): JSONObject = JSONObject().put("cmd", "sim_set").put("sim", sim)

    fun ratSet(rats: Set<RatType>): JSONObject {
        val ratStr = if (rats == BandConstants.ALL_RAT_TYPES) "auto"
            else rats.sortedBy { it.ordinal }.joinToString(",") { it.name.lowercase() }
        return JSONObject().put("cmd", "rat_set").put("rat", ratStr)
    }

    fun gsmSet(bands: Set<Int>): JSONObject = bandSet("gsm_set", bands)
    fun wcdmaSet(bands: Set<Int>): JSONObject = bandSet("wcdma_set", bands)
    fun lteSet(bands: Set<Int>): JSONObject = bandSet("lte_set", bands)
    fun nrSaSet(bands: Set<Int>): JSONObject = bandSet("nr_sa_set", bands)
    fun nrNsaSet(bands: Set<Int>): JSONObject = bandSet("nr_nsa_set", bands)
    fun nrSet(bands: Set<Int>): JSONObject = bandSet("nr_set", bands)

    private fun bandSet(cmd: String, bands: Set<Int>): JSONObject {
        val req = JSONObject().put("cmd", cmd)
        if (bands.isEmpty()) {
            req.put("bands", "none")
        } else {
            val arr = JSONArray()
            bands.sorted().forEach { arr.put(it) }
            req.put("bands", arr)
        }
        return req
    }

    fun modeSet(mode: NrMode): JSONObject {
        val modeStr = when (mode) {
            NrMode.SA -> "sa"
            NrMode.NSA -> "nsa"
            NrMode.BOTH, NrMode.UNKNOWN -> "both"
        }
        return JSONObject().put("cmd", "mode_set").put("mode", modeStr)
    }

    fun reset(): JSONObject = JSONObject().put("cmd", "reset")

    fun shutdown(): JSONObject = JSONObject().put("cmd", "shutdown")

    fun verboseSet(verbose: Boolean): JSONObject =
        JSONObject().put("cmd", "verbose_set").put("verbose", verbose)

    fun lteCellLockSet(earfcn: Int, pci: Int): JSONObject =
        JSONObject().put("cmd", "lte_cell_lock_set").put("earfcn", earfcn).put("pci", pci)

    fun lteCellLockMultiPciSet(earfcn: Int, pciList: List<Int>): JSONObject {
        val arr = JSONArray()
        pciList.forEach { arr.put(it) }
        return JSONObject().put("cmd", "lte_cell_lock_multi_pci_set")
            .put("earfcn", earfcn).put("pci_list", arr)
    }

    fun lteCellLockClear(): JSONObject = JSONObject().put("cmd", "lte_cell_lock_clear")

    fun nrCellLockPciSet(arfcn: Int, pci: Int, scsKhz: Int, band: Int): JSONObject =
        JSONObject().put("cmd", "nr_cell_lock_pci_set")
            .put("arfcn", arfcn).put("pci", pci).put("scs_khz", scsKhz).put("band", band)

    fun nrCellLockArfcnSet(arfcn: Int, scsKhz: Int): JSONObject =
        JSONObject().put("cmd", "nr_cell_lock_arfcn_set")
            .put("arfcn", arfcn).put("scs_khz", scsKhz)

    fun nrCellLockMultiPciSet(arfcn: Int, scsKhz: Int, band: Int, pciList: List<Int>): JSONObject {
        val arr = JSONArray()
        pciList.forEach { arr.put(it) }
        return JSONObject().put("cmd", "nr_cell_lock_multi_pci_set")
            .put("arfcn", arfcn).put("scs_khz", scsKhz).put("band", band).put("pci_list", arr)
    }

    fun nrCellLockGnbSet(idBits: Int, gnbIds: List<Int>): JSONObject {
        val arr = JSONArray()
        gnbIds.forEach { arr.put(it) }
        return JSONObject().put("cmd", "nr_cell_lock_gnb_set")
            .put("id_bits", idBits).put("gnb_ids", arr)
    }

    fun nrCellLockClear(): JSONObject = JSONObject().put("cmd", "nr_cell_lock_clear")

    fun queryLteCellLock(): JSONObject = JSONObject().put("cmd", "query_lte_cell_lock")

    fun queryNrCellLock(): JSONObject = JSONObject().put("cmd", "query_nr_cell_lock")

    fun plmnLockSet(mcc: Int, mnc: Int): JSONObject =
        JSONObject().put("cmd", "plmn_lock_set").put("mcc", mcc).put("mnc", mnc)

    fun plmnLockClear(): JSONObject = JSONObject().put("cmd", "plmn_lock_clear")
}

object JsonStateParser {

    fun parseResponse(response: JSONObject): DaemonResponse {
        val stateJson = response.optJSONObject("state")
        val errorJson = response.optJSONObject("error")
        return DaemonResponse(
            id = if (response.isNull("id")) null else response.optInt("id", 0),
            cmd = response.optString("cmd", ""),
            ok = response.optBoolean("ok", false),
            error = if (errorJson != null) parseError(errorJson) else null,
            simState = if (stateJson != null) parseSimState(stateJson) else null,
            hardware = if (stateJson != null) parseHardware(stateJson) else null,
            cellLockState = if (stateJson != null) parseCellLockState(stateJson) else null,
            plmnLockState = if (stateJson != null) parsePlmnLockState(stateJson) else null,
            nrIndependentCapability = if (stateJson != null) parseNrIndependentCapability(stateJson) else null,
            sim = if (stateJson != null) stateJson.optInt("sim", 1) else 1,
            status = if (stateJson != null) stateJson.optString("status", "") else ""
        )
    }

    fun parseSimState(state: JSONObject): SimState {
        if (!state.optBoolean("valid", false)) return SimState()

        val ratObj = state.optJSONObject("rat")
        val ratMask = if (ratObj != null) {
            val mask = mutableSetOf<RatType>()
            if (ratObj.optBoolean("gsm", false)) mask.add(RatType.GSM)
            if (ratObj.optBoolean("wcdma", false)) mask.add(RatType.WCDMA)
            if (ratObj.optBoolean("lte", false)) mask.add(RatType.LTE)
            if (ratObj.optBoolean("nr", false)) mask.add(RatType.NR)
            mask.toSet()
        } else emptySet()

        val nrMode = when (state.optString("nr_mode", "both")) {
            "sa" -> NrMode.SA
            "nsa" -> NrMode.NSA
            "unknown" -> NrMode.UNKNOWN
            else -> NrMode.BOTH
        }

        return SimState(
            ratMask = ratMask,
            gsmBands = parseIntArray(state, "gsm"),
            wcdmaBands = parseIntArray(state, "wcdma"),
            lteBands = parseIntArray(state, "lte"),
            nrNsaBands = parseIntArray(state, "nr_nsa"),
            nrSaBands = parseIntArray(state, "nr_sa"),
            nrMode = nrMode
        )
    }

    fun parseHardware(state: JSONObject): HardwareBands {
        val hw = state.optJSONObject("hardware") ?: return HardwareBands()
        return HardwareBands(
            gsm = parseIntArray(hw, "gsm"),
            wcdma = parseIntArray(hw, "wcdma"),
            lte = parseIntArray(hw, "lte"),
            nr = parseIntArray(hw, "nr")
        )
    }

    fun parseCellLockState(state: JSONObject): CellLockState {
        val lteJson = state.optJSONObject("lte_cell_lock")
        val nrJson = state.optJSONObject("nr_cell_lock")
        return CellLockState(
            lte = if (lteJson != null) parseLteCellLock(lteJson) else LteCellLockState(),
            nr = if (nrJson != null) parseNrCellLock(nrJson) else NrCellLockState()
        )
    }

    fun parsePlmnLockState(state: JSONObject): PlmnLockState {
        val plmnJson = state.optJSONObject("plmn_lock") ?: return PlmnLockState()
        val lockedJson = plmnJson.optJSONObject("locked_plmn")
        val lockedPlmn = if (lockedJson != null) {
            PlmnLockedPlmn(
                mcc = lockedJson.optInt("mcc", 0),
                mnc = lockedJson.optInt("mnc", 0),
                mncIncludesPcsDigit = lockedJson.optBoolean("mnc_includes_pcs_digit", false),
                mncDisplay = lockedJson.optString("mnc_display", "")
            )
        } else null
        return PlmnLockState(
            valid = plmnJson.optBoolean("valid", false),
            mode = if (plmnJson.has("mode") && !plmnJson.isNull("mode")) plmnJson.optString("mode") else null,
            mcc = if (plmnJson.has("mcc") && !plmnJson.isNull("mcc")) plmnJson.optInt("mcc") else null,
            mnc = if (plmnJson.has("mnc") && !plmnJson.isNull("mnc")) plmnJson.optInt("mnc") else null,
            lockedPlmn = lockedPlmn
        )
    }

    fun parseNrIndependentCapability(state: JSONObject): NrIndependentCapability {
        val cap = state.optJSONObject("nr_independent_capability") ?: return NrIndependentCapability()
        val checked = cap.optBoolean("checked", false)
        val supported = if (cap.has("independent_lock_supported") && !cap.isNull("independent_lock_supported"))
            cap.optBoolean("independent_lock_supported") else null
        return NrIndependentCapability(checked = checked, independentLockSupported = supported)
    }

    private fun parseLteCellLock(json: JSONObject): LteCellLockState {
        val valid = json.optBoolean("valid", false)
        val locksArr = json.optJSONArray("locks")
        val locks = mutableListOf<LteCellLockEntry>()
        if (locksArr != null) {
            for (i in 0 until locksArr.length()) {
                val lockObj = locksArr.optJSONObject(i)
                if (lockObj != null) {
                    locks.add(LteCellLockEntry(
                        pci = lockObj.optInt("pci", 0),
                        earfcn = lockObj.optInt("earfcn", 0)
                    ))
                }
            }
        }
        return LteCellLockState(valid = valid, locks = locks)
    }

    private fun parseNrCellLock(json: JSONObject): NrCellLockState {
        val valid = json.optBoolean("valid", false)
        val type = json.optString("type", "none")
        val typeRaw = json.optInt("type_raw", 2)

        val pciLockJson = json.optJSONObject("pci_lock")
        val pciLock = if (pciLockJson != null) {
            NrPciLock(
                pci = pciLockJson.optInt("pci", 0),
                scsKhz = pciLockJson.optInt("scs_khz", 0),
                arfcn = pciLockJson.optInt("arfcn", 0),
                bands = parseIntArray(pciLockJson, "bands")
            )
        } else null

        val arfcnLockArr = json.optJSONArray("arfcn_lock")
        val arfcnLock = if (arfcnLockArr != null) {
            val list = mutableListOf<NrArfcnLockEntry>()
            for (i in 0 until arfcnLockArr.length()) {
                val entry = arfcnLockArr.optJSONObject(i)
                if (entry != null) {
                    list.add(NrArfcnLockEntry(
                        arfcn = entry.optInt("arfcn", 0),
                        scsKhz = entry.optInt("scs_khz", 0)
                    ))
                }
            }
            list
        } else null

        val multiPciLockJson = json.optJSONObject("multi_pci_lock")
        val multiPciLock = multiPciLockJson?.optBoolean("present", false) ?: false

        val gnbJson = json.optJSONObject("gnb_allowlist")
        val gnbAllowlist = if (gnbJson != null) {
            NrGnbAllowlist(
                gnbIds = parseIntArray(gnbJson, "gnb_ids").toList(),
                idBits = if (gnbJson.has("id_bits") && !gnbJson.isNull("id_bits"))
                    gnbJson.optInt("id_bits") else null
            )
        } else null

        return NrCellLockState(
            valid = valid,
            type = type,
            typeRaw = typeRaw,
            pciLock = pciLock,
            arfcnLock = arfcnLock,
            multiPciLock = multiPciLock,
            gnbAllowlist = gnbAllowlist
        )
    }

    private fun parseError(error: JSONObject): DaemonError {
        return DaemonError(
            stage = error.optString("stage", "daemon"),
            message = error.optString("message", "Unknown error"),
            result = if (error.has("result") && !error.isNull("result")) error.optInt("result") else null,
            code = if (error.has("code") && !error.isNull("code")) error.optInt("code") else null,
            label = if (error.has("label") && !error.isNull("label")) error.optString("label") else null,
            rejectedBands = if (error.has("rejected_bands") && !error.isNull("rejected_bands"))
                parseIntArray(error, "rejected_bands") else null
        )
    }

    private fun parseIntArray(json: JSONObject, key: String): Set<Int> {
        if (json.isNull(key)) return emptySet()
        val arr = json.optJSONArray(key) ?: return emptySet()
        val result = mutableSetOf<Int>()
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, -1)
            if (v > 0) result.add(v)
        }
        return result.toSet()
    }
}

object BandValidator {

    fun validateGsm(bands: Set<Int>, hw: HardwareBands): Set<Int> = bands.intersect(hw.gsm)

    fun validateWcdma(bands: Set<Int>, hw: HardwareBands): Set<Int> = bands.intersect(hw.wcdma)

    fun validateLte(bands: Set<Int>, hw: HardwareBands): Set<Int> = bands.intersect(hw.lte)

    fun validateNr(bands: Set<Int>, hw: HardwareBands): Set<Int> = bands.intersect(hw.nr)

    fun validateSimState(state: SimState, hw: HardwareBands): SimState =
        state.copy(
            gsmBands = validateGsm(state.gsmBands, hw),
            wcdmaBands = validateWcdma(state.wcdmaBands, hw),
            lteBands = validateLte(state.lteBands, hw),
            nrNsaBands = validateNr(state.nrNsaBands, hw),
            nrSaBands = validateNr(state.nrSaBands, hw)
        )
}
