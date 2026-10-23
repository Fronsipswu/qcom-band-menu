package dev.qcom.bandmenu.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The helper's NR neighbour beams ("nr_nb": [NR-ARFCN, PCI, SSB, RSRP, RSRQ]). */
class NrNeighbourParseTest {

    @Test
    fun beamsParsedInOrder() {
        val stats = DiagStats.parse(JSONObject("""{"ms":1000,"carriers":[],"nr":[],
            "nr_nb":[[630000,301,4,-95.5,-11.25],[630000,301,5,-98.0,-12.5]]}"""))
        assertEquals(listOf(
            DiagNrNeighbour(630000, 301, 4, -95.5, -11.25),
            DiagNrNeighbour(630000, 301, 5, -98.0, -12.5)), stats.nrNeighbours)
    }

    @Test
    fun noListWithoutTheKey() {
        assertNull(DiagStats.parse(JSONObject("""{"ms":1000,"carriers":[],"nr":[]}""")).nrNeighbours)
    }
}
