package dev.qcom.bandmenu.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The helper's stats JSON: "lte_ta" and "nr_ta" carry the raw timing-advance
 * index of the last random access (the RAR) and are omitted while no value is
 * known (helper start, cell change) — the card then shows "–".
 */
class DiagStatsTaTest {

    @Test
    fun timingAdvanceParsedWhenTheHelperHasOne() {
        val stats = DiagStats.parse(JSONObject("""{"ms":1000,"carriers":[],"nr":[],"lte_ta":28,"nr_ta":31}"""))
        assertEquals(28, stats.lteTimingAdvance)
        assertEquals(31, stats.nrTimingAdvance)
    }

    @Test
    fun timingAdvanceNullWhenAbsent() {
        val stats = DiagStats.parse(JSONObject("""{"ms":1000,"carriers":[],"nr":[]}"""))
        assertNull(stats.lteTimingAdvance)
        assertNull(stats.nrTimingAdvance)
    }
}
