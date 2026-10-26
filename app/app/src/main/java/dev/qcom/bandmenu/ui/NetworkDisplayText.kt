package dev.qcom.bandmenu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.qcom.bandmenu.R

// Snapshot strings are stable model values used by existing logic. Translate
// only when rendering; never feed localized text back into those comparisons.
@Composable
internal fun displayNetworkText(value: String): String = when (value) {
    "Band" -> stringResource(R.string.display_band)
    "Bandwidth" -> stringResource(R.string.display_bandwidth)
    "Frequency" -> stringResource(R.string.display_frequency)
    "Cell ID" -> stringResource(R.string.display_cell_id)
    "gNB / Cell" -> stringResource(R.string.display_gnb_cell)
    "LTE primary cell" -> stringResource(R.string.display_lte_primary)
    "NR primary cell" -> stringResource(R.string.display_nr_primary)
    "WCDMA serving cell" -> stringResource(R.string.display_wcdma_serving)
    "GSM serving cell" -> stringResource(R.string.display_gsm_serving)
    "Carrier aggregation" -> stringResource(R.string.display_carrier_aggregation)
    "NR aggregation" -> stringResource(R.string.display_nr_aggregation)
    "Primary" -> stringResource(R.string.display_primary)
    "Active" -> stringResource(R.string.display_active)
    "Configured" -> stringResource(R.string.display_configured)
    "Connected" -> stringResource(R.string.display_connected)
    "Unavailable" -> stringResource(R.string.display_unavailable)
    "No service" -> stringResource(R.string.display_no_service)
    "Limited service" -> stringResource(R.string.display_limited_service)
    "QMI only reports frequency data for NR Scells" -> stringResource(R.string.display_qmi_nr_note)
    "Null" -> stringResource(R.string.rrc_null)
    "Idle (any cell)" -> stringResource(R.string.rrc_idle_any)
    "Idle" -> stringResource(R.string.rrc_idle)
    "Connecting" -> stringResource(R.string.rrc_connecting)
    "Releasing" -> stringResource(R.string.rrc_releasing)
    "Inactive" -> stringResource(R.string.rrc_inactive)
    "Idle (not camped)" -> stringResource(R.string.rrc_idle_not_camped)
    "Suspended" -> stringResource(R.string.rrc_suspended)
    "IRAT to LTE" -> stringResource(R.string.rrc_irat)
    else -> value
}
