package dev.qcom.bandmenu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.qcom.bandmenu.network.NetworkArchive
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun ArchiveLogDialog(archive: NetworkArchive, onDismiss: () -> Unit) {
    val status by archive.status.collectAsState()
    var packets by remember { mutableStateOf(if (status.active) status.packetCapture else true) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) archive.selectFolder(uri)
    }
    WindowDialog(show = true, title = "Compressed network logs", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a local folder or SD-card subfolder. Each recording produces numbered .jsonl.gz segments for the PC viewer.", style = MiuixTheme.textStyles.body2)
            Text(status.folder?.let { android.net.Uri.decode(it.substringAfterLast('/')) } ?: "No destination selected", style = MiuixTheme.textStyles.body2)
            TextButton("Choose folder", onClick = { folderPicker.launch(status.folder?.let(android.net.Uri::parse)) },
                enabled = !status.active && !status.busy, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(state = ToggleableState(packets), onClick = { packets = !packets }, enabled = !status.active && !status.busy)
                Text("Include timestamped Qualcomm packets (starts DIAG)", style = MiuixTheme.textStyles.body2)
            }
            Text("UI snapshots are always included. Packet capture records actual modem log arrivals and supports finer replay. Existing one-hour replay settings are independent.", style = MiuixTheme.textStyles.body2)
            Text(status.message, style = MiuixTheme.textStyles.body2)
            Text("${status.segments} segments · ${status.records} records · ${status.dropped} dropped", style = MiuixTheme.textStyles.body2)
            status.error?.let { Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary) }
            Text("Recording continues while the app is in the background and ends when you close it. Keep every segment of a session together. An interrupted final segment may be incomplete; earlier segments remain usable.",
                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(if (status.active) "Stop and finish logs" else "Start recording",
                onClick = { if (status.active) archive.stop() else archive.start(packets) },
                enabled = !status.busy && status.folder != null, modifier = Modifier.fillMaxWidth())
            TextButton("Close", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
