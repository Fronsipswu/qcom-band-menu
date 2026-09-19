package dev.qcom.bandmenu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRequestBuilderTest {

    @Test
    fun query() {
        val json = JsonRequestBuilder.query()
        assertEquals("query", json.getString("cmd"))
    }

    @Test
    fun simSet() {
        val json = JsonRequestBuilder.simSet(1)
        assertEquals("sim_set", json.getString("cmd"))
        assertEquals(1, json.getInt("sim"))
    }

    @Test
    fun ratSet_allRats() {
        val json = JsonRequestBuilder.ratSet(BandConstants.ALL_RAT_TYPES)
        assertEquals("rat_set", json.getString("cmd"))
        assertEquals("auto", json.getString("rat"))
    }

    @Test
    fun ratSet_partial() {
        val json = JsonRequestBuilder.ratSet(setOf(RatType.GSM, RatType.LTE))
        assertEquals("rat_set", json.getString("cmd"))
        assertEquals("gsm,lte", json.getString("rat"))
    }

    @Test
    fun gsmSet_bands() {
        val json = JsonRequestBuilder.gsmSet(setOf(850, 900))
        assertEquals("gsm_set", json.getString("cmd"))
        val bands = json.getJSONArray("bands")
        assertEquals(2, bands.length())
        assertEquals(850, bands.getInt(0))
        assertEquals(900, bands.getInt(1))
    }

    @Test
    fun gsmSet_empty() {
        val json = JsonRequestBuilder.gsmSet(emptySet())
        assertEquals("gsm_set", json.getString("cmd"))
        assertEquals("none", json.getString("bands"))
    }

    @Test
    fun modeSet_sa() {
        val json = JsonRequestBuilder.modeSet(NrMode.SA)
        assertEquals("mode_set", json.getString("cmd"))
        assertEquals("sa", json.getString("mode"))
    }

    @Test
    fun modeSet_both() {
        val json = JsonRequestBuilder.modeSet(NrMode.BOTH)
        assertEquals("mode_set", json.getString("cmd"))
        assertEquals("both", json.getString("mode"))
    }

    @Test
    fun usagePrefSet_voice() {
        val json = JsonRequestBuilder.usagePrefSet(UsageMode.VOICE)
        assertEquals("usage_pref_set", json.getString("cmd"))
        assertEquals("voice", json.getString("mode"))
    }

    @Test
    fun usagePrefSet_data() {
        val json = JsonRequestBuilder.usagePrefSet(UsageMode.DATA)
        assertEquals("usage_pref_set", json.getString("cmd"))
        assertEquals("data", json.getString("mode"))
    }

    @Test
    fun reset() {
        val json = JsonRequestBuilder.reset()
        assertEquals("reset", json.getString("cmd"))
    }

    @Test
    fun attach() {
        val json = JsonRequestBuilder.attach()
        assertEquals(1, json.length())
        assertEquals("attach", json.getString("cmd"))
        assertEquals(setOf("cmd"), json.keys().asSequence().toSet())
    }

    @Test
    fun detach() {
        val json = JsonRequestBuilder.detach()
        assertEquals(1, json.length())
        assertEquals("detach", json.getString("cmd"))
        assertEquals(setOf("cmd"), json.keys().asSequence().toSet())
    }

    @Test
    fun reattach() {
        val json = JsonRequestBuilder.reattach()
        assertEquals(1, json.length())
        assertEquals("reattach", json.getString("cmd"))
        assertEquals(setOf("cmd"), json.keys().asSequence().toSet())
    }

    @Test
    fun shutdown() {
        val json = JsonRequestBuilder.shutdown()
        assertEquals("shutdown", json.getString("cmd"))
    }

    @Test
    fun verboseSet_true() {
        val json = JsonRequestBuilder.verboseSet(true)
        assertEquals("verbose_set", json.getString("cmd"))
        assertTrue(json.getBoolean("verbose"))
    }
}
