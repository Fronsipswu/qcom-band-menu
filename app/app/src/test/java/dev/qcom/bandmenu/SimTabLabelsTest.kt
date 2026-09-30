package dev.qcom.bandmenu

import org.junit.Assert.assertEquals
import org.junit.Test

class SimTabLabelsTest {

    @Test
    fun bothNull_returnsPlainLabels() {
        assertEquals(listOf("SIM 1", "SIM 2"), simTabLabels(listOf(null, null)))
    }

    @Test
    fun namesPresent_appendsOperatorNames() {
        assertEquals(
            listOf("SIM 1 - vodafone.de", "SIM 2 - Telekom"),
            simTabLabels(listOf("vodafone.de", "Telekom"))
        )
    }

    @Test
    fun blankName_treatedAsMissing() {
        assertEquals(listOf("SIM 1", "SIM 2 - Telekom"), simTabLabels(listOf("", "Telekom")))
    }

    @Test
    fun shortList_plainLabelForMissingEntry() {
        assertEquals(listOf("SIM 1 - vodafone.de", "SIM 2"), simTabLabels(listOf("vodafone.de")))
        assertEquals(listOf("SIM 1", "SIM 2"), simTabLabels(emptyList()))
    }

    @Test
    fun extraEntries_ignored() {
        assertEquals(
            listOf("SIM 1 - A", "SIM 2 - B"),
            simTabLabels(listOf("A", "B", "C", "D"))
        )
    }
}
