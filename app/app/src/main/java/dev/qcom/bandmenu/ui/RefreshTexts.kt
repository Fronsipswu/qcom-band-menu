package dev.qcom.bandmenu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.qcom.bandmenu.R

/** The pull-to-refresh texts (pulling, threshold reached, refreshing, done) in the app's language. */
@Composable
fun refreshTexts(): List<String> = listOf(
    stringResource(R.string.common_pull_to_refresh),
    stringResource(R.string.common_release_to_refresh),
    stringResource(R.string.common_refreshing),
    stringResource(R.string.common_refreshed),
)
