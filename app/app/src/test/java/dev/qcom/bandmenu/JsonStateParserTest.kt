package dev.qcom.bandmenu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonStateParserTest {

    private fun sampleState(sim: Int = 1): JSONObject {
        return JSONObject()
            .put("valid", true)
            .put("sim", sim)
            .put("rat", JSONObject()
                .put("raw", 28)
                .put("auto", false)
                .put("gsm", false)
                .put("wcdma", true)
                .put("lte", true)
                .put("nr", true))
            .put("gsm", JSONArray().put(850).put(900).put(1800).put(1900))
            .put("wcdma", JSONArray().put(1).put(5).put(8).put(19))
            .put("lte", JSONArray().put(1).put(3).put(7).put(28))
            .put("nr_sa", JSONArray().put(8).put(28))
            .put("nr_nsa", JSONArray().put(1).put(3).put(7))
            .put("nr_mode", "both")
            .put("hardware", JSONObject()
                .put("gsm", JSONArray().put(850).put(900).put(1800).put(1900))
                .put("wcdma", JSONArray().put(1).put(2).put(4).put(5).put(6).put(8).put(19))
                .put("lte", JSONArray().put(1).put(3).put(7).put(28))
                .put("nr", JSONArray().put(1).put(3).put(7).put(8).put(28)))
            .put("status", "Command accepted.")
    }

    private fun sampleResponse(cmd: String, ok: Boolean, state: JSONObject): JSONObject {
        return JSONObject()
            .put("id", 1)
            .put("cmd", cmd)
            .put("ok", ok)
            .put("error", JSONObject.NULL)
            .put("state", state)
            .put("diagnostics", JSONObject.NULL)
    }

    @Test
    fun parseResponse_success() {
        val resp = sampleResponse("query", true, sampleState(1))
        val parsed = JsonStateParser.parseResponse(resp)

        assertTrue(parsed.ok)
        assertNull(parsed.error)
        assertEquals(1, parsed.sim)
        assertEquals("Command accepted.", parsed.status)
    }

    @Test
    fun parseSimState_ratMask() {
        val resp = sampleResponse("query", true, sampleState(1))
        val parsed = JsonStateParser.parseResponse(resp)
        val state = parsed.simState!!

        assertEquals(setOf(RatType.WCDMA, RatType.LTE, RatType.NR), state.ratMask)
    }

    @Test
    fun parseSimState_bands() {
        val resp = sampleResponse("query", true, sampleState(1))
        val parsed = JsonStateParser.parseResponse(resp)
        val state = parsed.simState!!

        assertEquals(setOf(850, 900, 1800, 1900), state.gsmBands)
        assertEquals(setOf(1, 5, 8, 19), state.wcdmaBands)
        assertEquals(setOf(1, 3, 7, 28), state.lteBands)
        assertEquals(setOf(1, 3, 7), state.nrNsaBands)
        assertEquals(setOf(8, 28), state.nrSaBands)
    }

    @Test
    fun parseSimState_nrMode() {
        val resp = sampleResponse("query", true, sampleState(1))
        val parsed = JsonStateParser.parseResponse(resp)
        assertEquals(NrMode.BOTH, parsed.simState!!.nrMode)
    }

    @Test
    fun parseHardware() {
        val resp = sampleResponse("query", true, sampleState(1))
        val parsed = JsonStateParser.parseResponse(resp)
        val hw = parsed.hardware!!

        assertEquals(setOf(850, 900, 1800, 1900), hw.gsm)
        assertEquals(setOf(1, 2, 4, 5, 6, 8, 19), hw.wcdma)
        assertEquals(setOf(1, 3, 7, 28), hw.lte)
        assertEquals(setOf(1, 3, 7, 8, 28), hw.nr)
    }

    @Test
    fun parseResponse_error() {
        val resp = JSONObject()
            .put("id", 2)
            .put("cmd", "nr_sa_set")
            .put("ok", false)
            .put("error", JSONObject()
                .put("stage", "modem_rejected")
                .put("message", "Command rejected by modem.")
                .put("result", 1)
                .put("code", 1))
            .put("state", sampleState(1))
            .put("diagnostics", JSONObject.NULL)

        val parsed = JsonStateParser.parseResponse(resp)

        assertFalse(parsed.ok)
        assertEquals("modem_rejected", parsed.error!!.stage)
        assertEquals("Command rejected by modem.", parsed.error.message)
        assertEquals(1, parsed.error.result)
        assertEquals(1, parsed.error.code)
    }

    @Test
    fun parseSimState_invalid() {
        val state = JSONObject().put("valid", false).put("sim", 1)
        val resp = sampleResponse("query", false, state)
        val parsed = JsonStateParser.parseResponse(resp)

        assertEquals(SimState(), parsed.simState)
    }

    @Test
    fun parseSimState_nrModeSa() {
        val state = sampleState().put("nr_mode", "sa")
        val resp = sampleResponse("query", true, state)
        val parsed = JsonStateParser.parseResponse(resp)
        assertEquals(NrMode.SA, parsed.simState!!.nrMode)
    }

    @Test
    fun parseSimState_emptyBands() {
        val state = sampleState()
            .put("gsm", JSONObject.NULL)
            .put("wcdma", JSONArray())
            .put("lte", JSONArray())
            .put("nr_sa", JSONArray())
            .put("nr_nsa", JSONArray())
        val resp = sampleResponse("query", true, state)
        val parsed = JsonStateParser.parseResponse(resp)
        val s = parsed.simState!!

        assertTrue(s.gsmBands.isEmpty())
        assertTrue(s.wcdmaBands.isEmpty())
        assertTrue(s.lteBands.isEmpty())
        assertTrue(s.nrSaBands.isEmpty())
        assertTrue(s.nrNsaBands.isEmpty())
    }

    @Test
    fun parseSimState_nrModeNsa() {
        val state = sampleState().put("nr_mode", "nsa")
        val resp = sampleResponse("query", true, state)
        val parsed = JsonStateParser.parseResponse(resp)
        assertEquals(NrMode.NSA, parsed.simState!!.nrMode)
    }

    @Test
    fun parseSimState_nrModeUnknown() {
        val state = sampleState().put("nr_mode", "unknown")
        val resp = sampleResponse("query", true, state)
        val parsed = JsonStateParser.parseResponse(resp)
        assertEquals(NrMode.UNKNOWN, parsed.simState!!.nrMode)
    }

    @Test
    fun parseResponse_nullState() {
        val resp = JSONObject()
            .put("id", 3)
            .put("cmd", "reset")
            .put("ok", true)
            .put("error", JSONObject.NULL)
            .put("state", JSONObject.NULL)
            .put("diagnostics", JSONObject.NULL)

        val parsed = JsonStateParser.parseResponse(resp)

        assertTrue(parsed.ok)
        assertNull(parsed.simState)
        assertNull(parsed.hardware)
    }

    @Test
    fun parseResponse_errorValidation() {
        val resp = JSONObject()
            .put("id", 4)
            .put("cmd", "gsm_set")
            .put("ok", false)
            .put("error", JSONObject()
                .put("stage", "validation")
                .put("message", "Invalid band")
                .put("label", "gsm")
                .put("rejected_bands", JSONArray().put(999)))
            .put("state", JSONObject.NULL)
            .put("diagnostics", JSONObject.NULL)

        val parsed = JsonStateParser.parseResponse(resp)

        assertFalse(parsed.ok)
        assertEquals("validation", parsed.error!!.stage)
        assertEquals("Invalid band", parsed.error.message)
        assertEquals("gsm", parsed.error.label)
        assertEquals(setOf(999), parsed.error.rejectedBands)
    }

    private fun sampleUsagePref(valid: Boolean, mode: String?, modeRaw: Int?): JSONObject {
        val obj = JSONObject().put("valid", valid)
        if (mode != null) obj.put("mode", mode) else obj.put("mode", JSONObject.NULL)
        if (modeRaw != null) obj.put("mode_raw", modeRaw) else obj.put("mode_raw", JSONObject.NULL)
        return obj
    }

    @Test
    fun parseSimState_usagePrefVoice() {
        val state = sampleState()
            .put("usage_pref", sampleUsagePref(true, "voice", 1))
        val resp = sampleResponse("query", true, state)
        val parsed = JsonStateParser.parseResponse(resp)
        assertEquals(UsageMode.VOICE, parsed.simState!!.usageMode)
    }

    @Test
    fun parseSimState_usagePrefData() {
        val state = sampleState()
            .put("usage_pref", sampleUsagePref(true, "data", 2))
        val resp = sampleResponse("query", true, state)
        val parsed = JsonStateParser.parseResponse(resp)
        assertEquals(UsageMode.DATA, parsed.simState!!.usageMode)
    }

    @Test
    fun parseSimState_usagePrefRawZero_isUnknown() {
        val state = sampleState()
            .put("usage_pref", sampleUsagePref(true, "unknown", 0))
        val resp = sampleResponse("query", true, state)
        val parsed = JsonStateParser.parseResponse(resp)
        assertEquals(UsageMode.UNKNOWN, parsed.simState!!.usageMode)
    }

    @Test
    fun parseSimState_usagePrefRawOther_isUnknown() {
        val state = sampleState()
            .put("usage_pref", sampleUsagePref(true, "unrecognized", 5))
        val resp = sampleResponse("query", true, state)
        val parsed = JsonStateParser.parseResponse(resp)
        assertEquals(UsageMode.UNKNOWN, parsed.simState!!.usageMode)
    }

    @Test
    fun parseSimState_usagePrefInvalid_isUnknown() {
        val state = sampleState()
            .put("usage_pref", sampleUsagePref(false, null, null))
        val resp = sampleResponse("query", true, state)
        val parsed = JsonStateParser.parseResponse(resp)
        assertEquals(UsageMode.UNKNOWN, parsed.simState!!.usageMode)
    }

    @Test
    fun parseSimState_usagePrefMissing_isUnknown() {
        val state = sampleState()
        val resp = sampleResponse("query", true, state)
        val parsed = JsonStateParser.parseResponse(resp)
        assertEquals(UsageMode.UNKNOWN, parsed.simState!!.usageMode)
    }

    @Test
    fun parseSimState_nullRat() {
        val state = sampleState().put("rat", JSONObject.NULL)
        val resp = sampleResponse("query", true, state)
        val parsed = JsonStateParser.parseResponse(resp)
        assertTrue(parsed.simState!!.ratMask.isEmpty())
    }
}
