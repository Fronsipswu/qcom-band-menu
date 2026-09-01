package dev.qcom.bandmenu

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandFilterTest {

    private fun SimBandFilter.prefsValues(): Map<androidx.datastore.preferences.core.Preferences.Key<Set<String>>, Set<String>> {
        val out = mutableMapOf<androidx.datastore.preferences.core.Preferences.Key<Set<String>>, Set<String>>()
        if (gsm.isNotEmpty()) out[BandPreferences.BAND_FILTER_GSM_SIM1] = gsm.map { it.toString() }.toSet()
        if (wcdma.isNotEmpty()) out[BandPreferences.BAND_FILTER_WCDMA_SIM1] = wcdma.map { it.toString() }.toSet()
        if (lte.isNotEmpty()) out[BandPreferences.BAND_FILTER_LTE_SIM1] = lte.map { it.toString() }.toSet()
        if (nrNsa.isNotEmpty()) out[BandPreferences.BAND_FILTER_NR_NSA_SIM1] = nrNsa.map { it.toString() }.toSet()
        if (nrSa.isNotEmpty()) out[BandPreferences.BAND_FILTER_NR_SA_SIM1] = nrSa.map { it.toString() }.toSet()
        return out
    }

    @Test
    fun visibleBands_emptyFilter_passthrough() {
        val hardware = setOf(1, 3, 7, 28)
        assertEquals(hardware, SimBandFilter.visibleBands(hardware, emptySet()))
    }

    @Test
    fun visibleBands_intersectsFilterWithHardware() {
        val hardware = setOf(1, 3, 7, 28)
        assertEquals(setOf(3, 7), SimBandFilter.visibleBands(hardware, setOf(3, 7, 99)))
    }

    @Test
    fun visibleBands_filterOutsideHardware_yieldsEmpty() {
        val hardware = setOf(1, 3)
        assertEquals(emptySet<Int>(), SimBandFilter.visibleBands(hardware, setOf(41)))
    }

    @Test
    fun prefsToSimBandFilter_mapsBandNumbers() {
        val filter = BandPreferences.prefsToSimBandFilter(
            gsm = setOf("850", "900"),
            wcdma = setOf("1"),
            lte = setOf("3", "7"),
            nrNsa = setOf("1"),
            nrSa = setOf("8", "28")
        )
        assertEquals(setOf(850, 900), filter.gsm)
        assertEquals(setOf(1), filter.wcdma)
        assertEquals(setOf(3, 7), filter.lte)
        assertEquals(setOf(1), filter.nrNsa)
        assertEquals(setOf(8, 28), filter.nrSa)
    }

    @Test
    fun prefsToSimBandFilter_nullOrEmpty_defaultsToEmptySet() {
        val filter = BandPreferences.prefsToSimBandFilter(
            gsm = null,
            wcdma = emptySet(),
            lte = null,
            nrNsa = emptySet(),
            nrSa = null
        )
        assertEquals(SimBandFilter(), filter)
    }

    @Test
    fun prefsToSimBandFilter_ignoresNonNumericValues() {
        val filter = BandPreferences.prefsToSimBandFilter(
            gsm = setOf("850", "x"),
            wcdma = emptySet(),
            lte = emptySet(),
            nrNsa = emptySet(),
            nrSa = emptySet()
        )
        assertEquals(setOf(850), filter.gsm)
    }

    @Test
    fun prefsToBandFilter_emptyPrefs_disabledWithEmptySims() {
        val state = BandPreferences.prefsToBandFilter(mutablePreferencesOf())
        assertEquals(BandFilterState(), state)
        assertFalse(state.enabled)
    }

    @Test
    fun bandFilterState_prefsRoundtrip_bothSims() {
        val state = BandFilterState(
            enabled = true,
            sim1 = SimBandFilter(gsm = setOf(850), wcdma = setOf(1), lte = setOf(3, 7), nrNsa = setOf(1), nrSa = setOf(8, 28)),
            sim2 = SimBandFilter(lte = setOf(28))
        )
        val prefs = mutablePreferencesOf()
        prefs[BandPreferences.BAND_FILTER_ENABLED] = true
        state.sim1.prefsValues().forEach { (k, v) -> prefs[k] = v }
        prefs[BandPreferences.BAND_FILTER_LTE_SIM2] = state.sim2.lte.map { it.toString() }.toSet()

        assertEquals(state, BandPreferences.prefsToBandFilter(prefs))
    }

    @Test
    fun writeBandFilter_writesEnabledAndAllSets() {
        val state = BandFilterState(
            enabled = false,
            sim1 = SimBandFilter(gsm = setOf(850), lte = setOf(3)),
            sim2 = SimBandFilter(nrNsa = setOf(1), nrSa = setOf(1))
        )
        val prefs = mutablePreferencesOf()
        BandPreferences.writeBandFilter(prefs, state)

        assertTrue(prefs[BandPreferences.BAND_FILTER_ENABLED] == true)
        assertEquals(setOf("850"), prefs[BandPreferences.BAND_FILTER_GSM_SIM1])
        assertEquals(emptySet<String>(), prefs[BandPreferences.BAND_FILTER_WCDMA_SIM1])
        assertEquals(setOf("3"), prefs[BandPreferences.BAND_FILTER_LTE_SIM1])
        assertEquals(setOf("1"), prefs[BandPreferences.BAND_FILTER_NR_NSA_SIM2])
        assertEquals(setOf("1"), prefs[BandPreferences.BAND_FILTER_NR_SA_SIM2])
    }

    @Test
    fun visibleBands_clearedFilterWithStaleSets_passthrough() {
        val hardware = setOf(1, 3, 7, 28)
        val cleared = BandPreferences.prefsToBandFilter(mutablePreferencesOf()).let {
            BandFilterState(enabled = false, sim1 = it.sim1, sim2 = it.sim2)
        }
        val staleFilter = BandFilterState(
            enabled = false,
            sim1 = SimBandFilter(lte = setOf(3)),
            sim2 = SimBandFilter(lte = setOf(7))
        )
        assertEquals(hardware, SimBandFilter.visibleBands(hardware, if (cleared.enabled) cleared.sim1.lte else emptySet()))
        assertEquals(hardware, SimBandFilter.visibleBands(hardware, if (staleFilter.enabled) staleFilter.sim1.lte else emptySet()))
        assertEquals(setOf(3), SimBandFilter.visibleBands(hardware, staleFilter.sim1.lte))
    }

    @Test
    fun clearBandFilterIn_disablesAndKeepsBandSets() {
        val prefs = mutablePreferencesOf()
        prefs[BandPreferences.BAND_FILTER_ENABLED] = true
        prefs[BandPreferences.BAND_FILTER_GSM_SIM1] = setOf("850", "900")
        prefs[BandPreferences.BAND_FILTER_LTE_SIM2] = setOf("28")
        BandPreferences.clearBandFilterIn(prefs)

        assertFalse(prefs[BandPreferences.BAND_FILTER_ENABLED] == true)
        assertEquals(setOf("850", "900"), prefs[BandPreferences.BAND_FILTER_GSM_SIM1])
        assertEquals(setOf("28"), prefs[BandPreferences.BAND_FILTER_LTE_SIM2])
    }

    @Test
    fun copyForOtherSim_copiesAllRatSetsVerbatim() {
        val source = SimBandFilter(
            gsm = setOf(850, 900),
            wcdma = setOf(1),
            lte = setOf(3, 7, 28),
            nrNsa = setOf(1, 8),
            nrSa = setOf(41, 78)
        )
        assertEquals(source, source.copyForOtherSim())
    }

    @Test
    fun copyForOtherSim_emptySetsStayEmpty_notPrefilledFromHardware() {
        val source = SimBandFilter(lte = setOf(3))
        val copied = source.copyForOtherSim()
        assertEquals(SimBandFilter(lte = setOf(3)), copied)
        assertEquals(emptySet<Int>(), copied.gsm)
        assertEquals(emptySet<Int>(), copied.wcdma)
        assertEquals(emptySet<Int>(), copied.nrNsa)
        assertEquals(emptySet<Int>(), copied.nrSa)
    }

    @Test
    fun copyForOtherSim_createsIndependentSetInstances() {
        val source = SimBandFilter(
            gsm = setOf(850, 900),
            wcdma = setOf(1, 8),
            lte = setOf(3, 7),
            nrNsa = setOf(1, 8),
            nrSa = setOf(41, 78)
        )
        val copied = source.copyForOtherSim()
        assertFalse(copied.gsm === source.gsm)
        assertFalse(copied.wcdma === source.wcdma)
        assertFalse(copied.lte === source.lte)
        assertFalse(copied.nrNsa === source.nrNsa)
        assertFalse(copied.nrSa === source.nrSa)
    }

    @Test
    fun copyForOtherSim_mergedNrSelection_copiesNsaAndSaVerbatim() {
        val nr = setOf(1, 8, 28, 41)
        val source = SimBandFilter(lte = setOf(3), nrNsa = nr, nrSa = nr)
        val copied = source.copyForOtherSim()
        assertEquals(nr, copied.nrNsa)
        assertEquals(nr, copied.nrSa)
    }
}
