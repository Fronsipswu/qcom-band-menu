package dev.qcom.bandmenu.ui

import androidx.compose.ui.res.stringResource
import dev.qcom.bandmenu.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.qcom.bandmenu.network.TrafficTest
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Setup for [TrafficTest]: the two URLs, parallel streams, download or
 *  upload, and how long. */
@Composable
fun TestScriptDialog(
    show: Boolean,
    config: TrafficTest.Config,
    onDismiss: () -> Unit,
    onSave: (TrafficTest.Config) -> Unit
) {
    var getUrl by remember(show) { mutableStateOf(config.getUrl) }
    var postUrl by remember(show) { mutableStateOf(config.postUrl) }
    var upload by remember(show) { mutableStateOf(config.upload) }
    var seconds by remember(show) { mutableStateOf(if (config.seconds > 0) config.seconds.toString() else "") }
    var streams by remember(show) { mutableStateOf(config.streams.coerceIn(1, TrafficTest.MAX_STREAMS)) }

    WindowDialog(
        show = show,
        title = stringResource(R.string.test_title),
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Label(stringResource(R.string.test_http_get))
            TextField(
                value = getUrl, onValueChange = { getUrl = it }, label = "https://…",
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(12.dp))
            Label(stringResource(R.string.test_http_post))
            TextField(
                value = postUrl, onValueChange = { postUrl = it }, label = "https://…",
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(12.dp))
            Label(stringResource(R.string.test_streams))
            // − n +: parallel connections (more fill a fast link).
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(text = "\u2212", onClick = { streams = (streams - 1).coerceAtLeast(1) },
                    enabled = streams > 1, modifier = Modifier.width(64.dp))
                Text("$streams", style = MiuixTheme.textStyles.title4, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f))
                TextButton(text = "+", onClick = { streams = (streams + 1).coerceAtMost(TrafficTest.MAX_STREAMS) },
                    enabled = streams < TrafficTest.MAX_STREAMS, modifier = Modifier.width(64.dp))
            }
            Spacer(Modifier.height(12.dp))
            Label(stringResource(R.string.test_mode))
            TabRowWithContour(
                tabs = listOf(stringResource(R.string.common_download), stringResource(R.string.common_upload)),
                selectedTabIndex = if (upload) 1 else 0,
                onTabSelected = { upload = it == 1 },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Label(stringResource(R.string.test_duration))
            TextField(
                value = seconds, onValueChange = { v -> seconds = v.filter(Char::isDigit).take(6) },
                label = "", singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.common_save),
                onClick = {
                    onSave(TrafficTest.Config(getUrl.trim(), postUrl.trim(), upload, seconds.toIntOrNull() ?: 0, streams))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Spacer(Modifier.height(6.dp))
}
