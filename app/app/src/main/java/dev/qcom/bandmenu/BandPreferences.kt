package dev.qcom.bandmenu

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object BandPreferences {
    private val RAT_SIM1 = stringPreferencesKey("rat_sim1")
    private val GSM_SIM1 = stringSetPreferencesKey("gsm_sim1")
    private val WCDMA_SIM1 = stringSetPreferencesKey("wcdma_sim1")
    private val LTE_SIM1 = stringSetPreferencesKey("lte_sim1")
    private val NR_NSA_SIM1 = stringSetPreferencesKey("nr_nsa_sim1")
    private val NR_SA_SIM1 = stringSetPreferencesKey("nr_sa_sim1")
    private val NR_MODE_SIM1 = stringPreferencesKey("nr_mode_sim1")

    private val RAT_SIM2 = stringPreferencesKey("rat_sim2")
    private val GSM_SIM2 = stringSetPreferencesKey("gsm_sim2")
    private val WCDMA_SIM2 = stringSetPreferencesKey("wcdma_sim2")
    private val LTE_SIM2 = stringSetPreferencesKey("lte_sim2")
    private val NR_NSA_SIM2 = stringSetPreferencesKey("nr_nsa_sim2")
    private val NR_SA_SIM2 = stringSetPreferencesKey("nr_sa_sim2")
    private val NR_MODE_SIM2 = stringPreferencesKey("nr_mode_sim2")
    private val DEBUG_LOGGING = booleanPreferencesKey("debug_logging")
    private val NR_INDEPENDENT_SUPPORTED = booleanPreferencesKey("nr_independent_supported")

    internal val BAND_FILTER_ENABLED = booleanPreferencesKey("band_filter_enabled")
    internal val BAND_FILTER_GSM_SIM1 = stringSetPreferencesKey("band_filter_gsm_sim1")
    internal val BAND_FILTER_WCDMA_SIM1 = stringSetPreferencesKey("band_filter_wcdma_sim1")
    internal val BAND_FILTER_LTE_SIM1 = stringSetPreferencesKey("band_filter_lte_sim1")
    internal val BAND_FILTER_NR_NSA_SIM1 = stringSetPreferencesKey("band_filter_nr_nsa_sim1")
    internal val BAND_FILTER_NR_SA_SIM1 = stringSetPreferencesKey("band_filter_nr_sa_sim1")
    internal val BAND_FILTER_GSM_SIM2 = stringSetPreferencesKey("band_filter_gsm_sim2")
    internal val BAND_FILTER_WCDMA_SIM2 = stringSetPreferencesKey("band_filter_wcdma_sim2")
    internal val BAND_FILTER_LTE_SIM2 = stringSetPreferencesKey("band_filter_lte_sim2")
    internal val BAND_FILTER_NR_NSA_SIM2 = stringSetPreferencesKey("band_filter_nr_nsa_sim2")
    internal val BAND_FILTER_NR_SA_SIM2 = stringSetPreferencesKey("band_filter_nr_sa_sim2")

    fun getSimState(dataStore: DataStore<Preferences>, sim: Int): Flow<SimState> {
        return dataStore.data.map { prefs ->
            if (sim == 1) prefsToSimState(
                prefs[RAT_SIM1],
                prefs[GSM_SIM1],
                prefs[WCDMA_SIM1],
                prefs[LTE_SIM1],
                prefs[NR_NSA_SIM1],
                prefs[NR_SA_SIM1],
                prefs[NR_MODE_SIM1]
            ) else prefsToSimState(
                prefs[RAT_SIM2],
                prefs[GSM_SIM2],
                prefs[WCDMA_SIM2],
                prefs[LTE_SIM2],
                prefs[NR_NSA_SIM2],
                prefs[NR_SA_SIM2],
                prefs[NR_MODE_SIM2]
            )
        }
    }

    suspend fun saveSimState(dataStore: DataStore<Preferences>, sim: Int, state: SimState) {
        dataStore.edit { prefs ->
            if (sim == 1) {
                prefs[RAT_SIM1] = state.ratMask.joinToString(",") { it.name }
                prefs[GSM_SIM1] = state.gsmBands.map { it.toString() }.toSet()
                prefs[WCDMA_SIM1] = state.wcdmaBands.map { it.toString() }.toSet()
                prefs[LTE_SIM1] = state.lteBands.map { it.toString() }.toSet()
                prefs[NR_NSA_SIM1] = state.nrNsaBands.map { it.toString() }.toSet()
                prefs[NR_SA_SIM1] = state.nrSaBands.map { it.toString() }.toSet()
                prefs[NR_MODE_SIM1] = state.nrMode.name
            } else {
                prefs[RAT_SIM2] = state.ratMask.joinToString(",") { it.name }
                prefs[GSM_SIM2] = state.gsmBands.map { it.toString() }.toSet()
                prefs[WCDMA_SIM2] = state.wcdmaBands.map { it.toString() }.toSet()
                prefs[LTE_SIM2] = state.lteBands.map { it.toString() }.toSet()
                prefs[NR_NSA_SIM2] = state.nrNsaBands.map { it.toString() }.toSet()
                prefs[NR_SA_SIM2] = state.nrSaBands.map { it.toString() }.toSet()
                prefs[NR_MODE_SIM2] = state.nrMode.name
            }
        }
    }

    private fun prefsToSimState(
        rat: String?, gsm: Set<String>?, wcdma: Set<String>?,
        lte: Set<String>?, nrNsa: Set<String>?, nrSa: Set<String>?,
        nrMode: String?
    ): SimState {
        return SimState(
            ratMask = rat?.split(",")?.mapNotNull { name ->
                runCatching { RatType.valueOf(name.trim()) }.getOrNull()
            }?.toSet() ?: emptySet(),
            gsmBands = gsm?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            wcdmaBands = wcdma?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            lteBands = lte?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            nrNsaBands = nrNsa?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            nrSaBands = nrSa?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            nrMode = nrMode?.let { runCatching { NrMode.valueOf(it) }.getOrNull() } ?: NrMode.BOTH
        )
    }

    fun getDebugLogging(dataStore: DataStore<Preferences>): Flow<Boolean> {
        return dataStore.data.map { it[DEBUG_LOGGING] ?: false }
    }

    fun getBandFilter(dataStore: DataStore<Preferences>): Flow<BandFilterState> {
        return dataStore.data.map { prefs -> prefsToBandFilter(prefs) }
    }

    suspend fun saveBandFilter(dataStore: DataStore<Preferences>, state: BandFilterState) {
        dataStore.edit { prefs -> writeBandFilter(prefs, state) }
    }

    suspend fun clearBandFilter(dataStore: DataStore<Preferences>) {
        dataStore.edit { prefs -> clearBandFilterIn(prefs) }
    }

    internal fun writeBandFilter(prefs: androidx.datastore.preferences.core.MutablePreferences, state: BandFilterState) {
        prefs[BAND_FILTER_ENABLED] = true
        prefs[BAND_FILTER_GSM_SIM1] = state.sim1.gsm.map { it.toString() }.toSet()
        prefs[BAND_FILTER_WCDMA_SIM1] = state.sim1.wcdma.map { it.toString() }.toSet()
        prefs[BAND_FILTER_LTE_SIM1] = state.sim1.lte.map { it.toString() }.toSet()
        prefs[BAND_FILTER_NR_NSA_SIM1] = state.sim1.nrNsa.map { it.toString() }.toSet()
        prefs[BAND_FILTER_NR_SA_SIM1] = state.sim1.nrSa.map { it.toString() }.toSet()
        prefs[BAND_FILTER_GSM_SIM2] = state.sim2.gsm.map { it.toString() }.toSet()
        prefs[BAND_FILTER_WCDMA_SIM2] = state.sim2.wcdma.map { it.toString() }.toSet()
        prefs[BAND_FILTER_LTE_SIM2] = state.sim2.lte.map { it.toString() }.toSet()
        prefs[BAND_FILTER_NR_NSA_SIM2] = state.sim2.nrNsa.map { it.toString() }.toSet()
        prefs[BAND_FILTER_NR_SA_SIM2] = state.sim2.nrSa.map { it.toString() }.toSet()
    }

    internal fun clearBandFilterIn(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs[BAND_FILTER_ENABLED] = false
    }

    internal fun prefsToBandFilter(prefs: Preferences): BandFilterState {
        return BandFilterState(
            enabled = prefs[BAND_FILTER_ENABLED] ?: false,
            sim1 = prefsToSimBandFilter(
                prefs[BAND_FILTER_GSM_SIM1],
                prefs[BAND_FILTER_WCDMA_SIM1],
                prefs[BAND_FILTER_LTE_SIM1],
                prefs[BAND_FILTER_NR_NSA_SIM1],
                prefs[BAND_FILTER_NR_SA_SIM1]
            ),
            sim2 = prefsToSimBandFilter(
                prefs[BAND_FILTER_GSM_SIM2],
                prefs[BAND_FILTER_WCDMA_SIM2],
                prefs[BAND_FILTER_LTE_SIM2],
                prefs[BAND_FILTER_NR_NSA_SIM2],
                prefs[BAND_FILTER_NR_SA_SIM2]
            )
        )
    }

    internal fun prefsToSimBandFilter(
        gsm: Set<String>?, wcdma: Set<String>?, lte: Set<String>?,
        nrNsa: Set<String>?, nrSa: Set<String>?
    ): SimBandFilter {
        return SimBandFilter(
            gsm = gsm?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            wcdma = wcdma?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            lte = lte?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            nrNsa = nrNsa?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            nrSa = nrSa?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        )
    }

    suspend fun setDebugLogging(dataStore: DataStore<Preferences>, enabled: Boolean) {
        dataStore.edit { it[DEBUG_LOGGING] = enabled }
    }

    fun getNrIndependentSupported(dataStore: DataStore<Preferences>): Flow<Boolean?> {
        return dataStore.data.map { it[NR_INDEPENDENT_SUPPORTED] }
    }

    suspend fun setNrIndependentSupported(dataStore: DataStore<Preferences>, supported: Boolean) {
        dataStore.edit { it[NR_INDEPENDENT_SUPPORTED] = supported }
    }
}
