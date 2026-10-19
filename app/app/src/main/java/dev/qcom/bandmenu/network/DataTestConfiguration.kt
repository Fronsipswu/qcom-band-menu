package dev.qcom.bandmenu.network

internal fun configurationKey(regime: TestRegime, kind: DataTestKind) = "config_${regime.name}_${kind.name}"
internal fun supports(regime: TestRegime, kind: DataTestKind) = when (regime) {
    TestRegime.NBTC -> kind.nbtc
    TestRegime.MIC -> kind.mic
    TestRegime.CUSTOM -> true
}

internal fun defaultDataTestConfig(regime: TestRegime, kind: DataTestKind): DataTestConfig = DataTestConfig(
    kind = kind, regime = regime, custom = regime == TestRegime.CUSTOM,
    repetitions = if (kind.mic) 3 else 120, intervalSeconds = if (kind.mic) 5 else 1,
    timeoutSeconds = when (kind) {
        DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD -> 600
        DataTestKind.STREAMING -> 300
        DataTestKind.PING -> 5
        else -> 60
    }, port = if (kind in listOf(DataTestKind.MIC_POINT, DataTestKind.UDP_LATENCY)) 0 else 21
)

/** Adds regulator-scoped slots without overwriting any existing new-format slot. */
internal fun migrateRegimeConfigurations(values: Map<String, String>): Map<String, String> = buildMap {
    for (kind in DataTestKind.entries) {
        val old = values["config_${kind.name}"] ?: continue
        val config = runCatching { DataTestConfig.fromJson(org.json.JSONObject(old)) }.getOrNull() ?: continue
        val key = configurationKey(config.effectiveRegime, kind)
        if (key !in values) put(key, config.copy(regime = config.effectiveRegime, custom = config.effectiveRegime == TestRegime.CUSTOM).toJson().toString())
    }
    if ("selected_regime" !in values) {
        val kind = enumValueOr(values["selected_kind"].orEmpty(), DataTestKind.PING)
        val config = values["config_${kind.name}"]?.let { runCatching { DataTestConfig.fromJson(org.json.JSONObject(it)) }.getOrNull() }
        val regime = config?.effectiveRegime ?: if (kind.nbtc) TestRegime.NBTC else TestRegime.CUSTOM
        put("selected_regime", regime.name)
        if ("selected_kind_${regime.name}" !in values) put("selected_kind_${regime.name}", kind.name)
    }
}

/** MIC campaign evidence must not disappear after twenty points. NBTC/custom history keeps its existing limit. */
internal fun retainedDataTestReports(reports: List<DataTestReport>): List<DataTestReport> {
    var ordinary = 0
    return reports.distinctBy { it.id }.sortedByDescending { it.startedAtMs }
        .filter { it.config.isMic || ordinary++ < 20 }
}
