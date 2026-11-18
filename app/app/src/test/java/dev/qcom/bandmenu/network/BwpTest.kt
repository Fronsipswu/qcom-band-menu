package dev.qcom.bandmenu.network

import dev.qcom.bandmenu.ui.bandwidthText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The NR bandwidth with the active BWP: the helper sends "bwp" only when the
 * active downlink BWP is narrower than the channel ("bw"); the tables show
 * "70(50)", the plain channel bandwidth otherwise.
 */
class BwpTest {

    private val stats = DiagStats.parse(JSONObject("""{"ms":1000,"carriers":[],
        "nr":[
          {"cc":0,"pcell":true,"pci":259,"arfcn":463970,"band":40,"bw":70,"bwp":50,"scs":30,"tb":3,"mbps":5.0},
          {"cc":1,"pcell":false,"pci":813,"arfcn":153630,"band":28,"bw":20,"scs":15,"tb":3,"mbps":1.0}]}"""))

    private fun carrier(nr: DiagNrCarrier) =
        Carrier(role = "P", band = "n${nr.band}", channel = nr.arfcn.toString(), bandwidthMhz = nr.bandwidthMhz?.toDouble(),
            state = null, pci = nr.pci, nr = nr)

    @Test
    fun bwpParsedOnlyWhenSent() {
        assertEquals(50, stats.nr[0].bwpMhz)
        assertNull(stats.nr[1].bwpMhz)
    }

    @Test
    fun narrowerBwpInBrackets() {
        assertEquals("70(50)", bandwidthText(carrier(stats.nr[0])))
        assertEquals("20", bandwidthText(carrier(stats.nr[1])))
        // A BWP as wide as the channel isn't repeated.
        assertEquals("20", bandwidthText(carrier(stats.nr[1].copy(bwpMhz = 20))))
    }
}
