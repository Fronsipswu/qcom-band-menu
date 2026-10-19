package dev.qcom.bandmenu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.qcom.bandmenu.network.DataTestConfig
import dev.qcom.bandmenu.network.DataTestKind
import dev.qcom.bandmenu.network.MicArea
import dev.qcom.bandmenu.network.NbtcProfile
import dev.qcom.bandmenu.network.SamplingArea
import dev.qcom.bandmenu.network.TestRegime
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Per-function setup. An NBTC preset fixes the measurement parameters, not the modem. */
@Composable
fun TestScriptDialog(
    show: Boolean,
    config: DataTestConfig,
    onDismiss: () -> Unit,
    onSave: (DataTestConfig) -> Unit,
    onSelect: (DataTestKind) -> Unit,
    onRegimeSelect: (TestRegime) -> Unit,
    running: Boolean
) {
    var draft by remember(show, config) { mutableStateOf(config) }
    var numbers by remember(show, config) {
        mutableStateOf(mapOf(
            "repetitions" to config.repetitions.toString(), "interval" to config.intervalSeconds.toString(),
            "timeout" to config.timeoutSeconds.toString(), "port" to config.port.toString(),
            "bytes" to config.transferBytes.toString(), "payload" to config.pingPayloadBytes.toString(),
            "height" to config.videoHeight.toString(), "videoSeconds" to config.videoMinimumSeconds.toString(),
            "connections" to config.parallelConnections.toString(), "warmupSeconds" to config.warmupSeconds.toString(),
            "warmupBytes" to config.warmupBytes.toString(), "measureSeconds" to config.measureSeconds.toString(),
            "measureBytes" to config.measureBytes.toString(), "udpCount" to config.udpCount.toString(),
            "udpInterval" to config.udpIntervalMs.toString(), "udpTimeout" to config.udpTimeoutMs.toString()
        ))
    }
    var showErrors by remember(show, config) { mutableStateOf(false) }
    fun number(key: String) = numbers[key].orEmpty()
    fun setNumber(key: String, value: String) { numbers = numbers + (key to value.filter(Char::isDigit).take(12)) }
    val candidate = draft.copy(
        repetitions = number("repetitions").toIntOrNull() ?: -1,
        intervalSeconds = number("interval").toIntOrNull() ?: -1,
        timeoutSeconds = number("timeout").toIntOrNull() ?: -1,
        port = number("port").toIntOrNull() ?: -1,
        transferBytes = number("bytes").toLongOrNull() ?: -1,
        pingPayloadBytes = number("payload").toIntOrNull() ?: -1,
        videoHeight = number("height").toIntOrNull() ?: -1,
        videoMinimumSeconds = number("videoSeconds").toIntOrNull() ?: -1,
        parallelConnections = number("connections").toIntOrNull() ?: -1,
        warmupSeconds = number("warmupSeconds").toIntOrNull() ?: -1,
        warmupBytes = number("warmupBytes").toLongOrNull() ?: -1,
        measureSeconds = number("measureSeconds").toIntOrNull() ?: -1,
        measureBytes = number("measureBytes").toLongOrNull() ?: -1,
        udpCount = number("udpCount").toIntOrNull() ?: -1,
        udpIntervalMs = number("udpInterval").toIntOrNull() ?: -1,
        udpTimeoutMs = number("udpTimeout").toIntOrNull() ?: -1
    )
    val errors = candidate.validationErrors()
    val transfer = draft.kind in listOf(DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD,
        DataTestKind.WEB_PAGE, DataTestKind.HTTP_DOWNLOAD, DataTestKind.HTTP_UPLOAD)
    val parallel = draft.kind in listOf(DataTestKind.MIC_POINT, DataTestKind.PARALLEL_DOWNLOAD, DataTestKind.PARALLEL_UPLOAD)
    val udp = draft.kind in listOf(DataTestKind.MIC_POINT, DataTestKind.UDP_LATENCY)
    val kinds = DataTestKind.entries.filter { kind -> when (candidate.effectiveRegime) {
        TestRegime.NBTC -> kind.nbtc
        TestRegime.MIC -> kind.mic
        TestRegime.CUSTOM -> true
    } }

    WindowDialog(show = show, title = "Test script", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SetupLabel("Measurement profile")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TestRegime.entries.forEach { regime ->
                    ChoiceButton(regime.name, candidate.effectiveRegime == regime, !running, Modifier.weight(1f)) {
                        if (candidate.effectiveRegime != regime) onRegimeSelect(regime)
                    }
                }
            }
            SetupHint(when (candidate.effectiveRegime) {
                TestRegime.NBTC -> "Thailand: NBTC data measurements and numerical session targets."
                TestRegime.MIC -> "Japan: MIC reference methods and point statistics. No minimum-speed pass/fail target."
                TestRegime.CUSTOM -> "Editable diagnostic measurements without an NBTC or MIC assessment."
            })
            SetupHint("Choose a function. Each profile and function keeps its own saved settings.")
            kinds.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { kind ->
                        ChoiceButton(kind.title, draft.kind == kind, !running, Modifier.weight(1f)) {
                            if (draft.kind != kind) onSelect(kind)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (running) SetupHint("Stop the active test before changing its configuration.")
            if (candidate.isNbtc) {
                SetupLabel("Declared radio technology")
                NbtcProfile.entries.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { profile ->
                            ChoiceButton(profile.title, draft.profile == profile, !running, Modifier.weight(1f)) { draft = draft.copy(profile = profile) }
                        }
                    }
                }
                SetupHint("Select the technology and band used by the modem. This selection does not change its radio settings.")
                if (draft.profile == NbtcProfile.UMTS) SetupHint("3G is exempt where the operator provides a higher technology in the prescribed area.")
                SetupHint(presetDescription(candidate))
            }
            if (candidate.isMic) {
                SetupHint("Independent implementation of the published method; this app does not replace the designated MIC software, server admission or campaign process.")
                if (draft.kind == DataTestKind.MIC_POINT) SetupHint("Three trials at one stationary outdoor point. Each trial runs download, upload, then UDP. Point means require all three speed values.")
                else SetupHint("Three trials of the selected component. Use Point sequence for a complete DL + UL + UDP point record.")
                if (parallel) SetupHint("3 concurrent HTTP connections per direction. Warm-up ends at 5 s or more than 2.5 MB; each measured stream ends at 20 MB or 20 s. Rates are summed.")
                if (udp) SetupHint("UDP: 60 probes, 16-byte payload, 500 ms cadence and 2,000 ms reply deadline. Late replies count as lost.")
            }

            when (draft.kind) {
                DataTestKind.PING -> {
                    SetupTextField(if (candidate.isNbtc) "NBTC server IP address" else "Host or IP address", draft.host, !running, KeyboardType.Uri) {
                        draft = draft.copy(host = it.trim(), referenceServerConfirmed = false)
                    }
                    if (!candidate.isNbtc) SetupNumberField("ICMP payload (bytes)", number("payload"), !running) { setNumber("payload", it) }
                }
                DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD -> {
                    SetupTextField(if (candidate.isNbtc) "NBTC FTP server IP address" else "FTP host or IP address", draft.host, !running, KeyboardType.Uri) {
                        draft = draft.copy(host = it.trim(), referenceServerConfirmed = false)
                    }
                    SetupNumberField("FTP port", number("port"), !running) { setNumber("port", it) }
                    SetupTextField("FTP username", draft.username, !running) { draft = draft.copy(username = it) }
                    SetupTextField("FTP password", draft.password, !running, KeyboardType.Password, password = true) { draft = draft.copy(password = it) }
                    SetupHint("Passwords are kept in memory only; enter them again after restarting the app.")
                    SetupTextField(if (draft.upload) "Remote upload directory (absolute path)" else "Remote download file (absolute path)", draft.path, !running, KeyboardType.Uri) {
                        draft = draft.copy(path = it.trim(), referenceServerConfirmed = false)
                    }
                    if (draft.upload) SetupHint("Uploads create uniquely named files in this directory. Arrange server cleanup; this app does not delete them.")
                }
                DataTestKind.WEB_PAGE, DataTestKind.HTTP_DOWNLOAD, DataTestKind.HTTP_UPLOAD -> {
                    SetupTextField(when (draft.kind) {
                        DataTestKind.WEB_PAGE -> "Reference webpage URL"
                        DataTestKind.HTTP_UPLOAD -> "HTTP POST endpoint URL"
                        else -> "HTTP download URL"
                    }, draft.url, !running, KeyboardType.Uri) { draft = draft.copy(url = it.trim(), referenceServerConfirmed = false) }
                    SetupHint(when (draft.kind) {
                        DataTestKind.WEB_PAGE -> "Use a stable reference page with a known total size and final URLs. Redirects and resources requiring non-GET requests are unsupported."
                        DataTestKind.HTTP_UPLOAD -> "Sends a binary body to an endpoint that accepts HTTP POST uploads."
                        else -> "Enter the exact resource size below. A partial response is not a complete download."
                    })
                }
                DataTestKind.STREAMING -> {
                    SetupTextField("YouTube video ID (11 characters)", draft.videoId, !running) { draft = draft.copy(videoId = it.trim(), referenceServerConfirmed = false) }
                    if (!candidate.isNbtc) {
                        SetupNumberField("Video height (144, 240, 360, 480, 720, 1080, 1440 or 2160)", number("height"), !running) { setNumber("height", it) }
                        SetupNumberField("Minimum video length (seconds)", number("videoSeconds"), !running) { setNumber("videoSeconds", it) }
                    }
                    SetupHint("Keep the player visible and verify its actual resolution during the test. Avoid concurrent traffic.")
                }
                DataTestKind.MIC_POINT, DataTestKind.PARALLEL_DOWNLOAD, DataTestKind.PARALLEL_UPLOAD, DataTestKind.UDP_LATENCY -> {
                    if (draft.kind in listOf(DataTestKind.MIC_POINT, DataTestKind.PARALLEL_DOWNLOAD)) {
                        SetupTextField("HTTP download object URL", draft.url, !running, KeyboardType.Uri) {
                            draft = draft.copy(url = it.trim(), referenceServerConfirmed = false)
                        }
                        SetupHint(if (candidate.isMic) "Use an uncached 100,000,000-byte object with that exact Content-Length. Each connection measures only its configured window."
                            else "Use an uncached object large enough for the warm-up and measurement windows.")
                    }
                    if (draft.kind in listOf(DataTestKind.MIC_POINT, DataTestKind.PARALLEL_UPLOAD)) {
                        SetupTextField("HTTP POST upload endpoint URL", draft.uploadUrl, !running, KeyboardType.Uri) {
                            draft = draft.copy(uploadUrl = it.trim(), referenceServerConfirmed = false)
                        }
                        SetupHint("The endpoint must accept streaming binary POST bodies and acknowledge completion. Public website forms are unsuitable.")
                    }
                    if (udp) {
                        SetupTextField("UDP echo host or IP address", draft.host, !running, KeyboardType.Uri) {
                            draft = draft.copy(host = it.trim(), referenceServerConfirmed = false)
                        }
                        SetupNumberField("UDP echo port", number("port"), !running) {
                            setNumber("port", it)
                            draft = draft.copy(referenceServerConfirmed = false)
                        }
                        SetupHint("Use a cooperating endpoint that echoes the exact 16-byte payload.")
                    }
                    if (!candidate.isMic && parallel) {
                        SetupNumberField("Parallel connections", number("connections"), !running) { setNumber("connections", it) }
                        SetupNumberField("Warm-up time limit (seconds)", number("warmupSeconds"), !running) { setNumber("warmupSeconds", it) }
                        SetupNumberField("Warm-up payload threshold (bytes)", number("warmupBytes"), !running) { setNumber("warmupBytes", it) }
                        SetupNumberField("Measured time limit per connection (seconds)", number("measureSeconds"), !running) { setNumber("measureSeconds", it) }
                        SetupNumberField("Measured payload limit per connection (bytes)", number("measureBytes"), !running) { setNumber("measureBytes", it) }
                    }
                    if (!candidate.isMic && udp) {
                        SetupNumberField("UDP probe count", number("udpCount"), !running) { setNumber("udpCount", it) }
                        SetupNumberField("UDP probe interval (milliseconds)", number("udpInterval"), !running) { setNumber("udpInterval", it) }
                        SetupNumberField("UDP reply deadline (milliseconds)", number("udpTimeout"), !running) { setNumber("udpTimeout", it) }
                    }
                }
            }
            if (transfer) {
                if (candidate.isNbtc) SetupHint("Expected complete content: ${candidate.effectiveTransferBytes} bytes (${dataVolume(candidate.effectiveTransferBytes)}).")
                else SetupNumberField(if (draft.kind == DataTestKind.WEB_PAGE) "Expected complete webpage size (bytes)" else "Transfer / expected resource size (bytes)", number("bytes"), !running) { setNumber("bytes", it) }
            }
            if (draft.kind == DataTestKind.UDP_LATENCY) {
                SetupHint("UDP batch budget includes the sending interval and final reply deadlines.")
            } else if (!candidate.isNbtc || draft.kind == DataTestKind.PING) {
                SetupNumberField(if (parallel) "HTTP phase timeout including setup (seconds)" else "Timeout per attempt (seconds)", number("timeout"), !running) { setNumber("timeout", it) }
            } else SetupHint("Preset timeout: ${candidate.effectiveTimeoutSeconds} seconds per attempt.")

            if (candidate.isNbtc) {
                ConfirmationRow(if (draft.kind == DataTestKind.STREAMING) "I have selected the campaign's reference YouTube video."
                    else "This is the designated NBTC reference server and content.", draft.referenceServerConfirmed, !running) {
                    draft = draft.copy(referenceServerConfirmed = it)
                }
                if (!draft.referenceServerConfirmed) SetupHint("Unconfirmed reference content is noted in results.")
                if (draft.kind != DataTestKind.STREAMING) {
                    SetupLabel("Sampling area")
                    SamplingArea.entries.forEach { area ->
                        ChoiceButton(area.title, draft.area == area, !running, Modifier.fillMaxWidth()) { draft = draft.copy(area = area) }
                    }
                }
                TextButton(text = "Use ${candidate.minimumSamples} samples for this area/profile", onClick = {
                    setNumber("repetitions", candidate.minimumSamples.toString())
                }, enabled = !running, modifier = Modifier.fillMaxWidth())
                SetupHint("Campaign hours: Mon-Fri, 08:00-20:00 Thailand time. Geographic coverage must be checked separately.")
            }
            if (candidate.isMic) {
                SetupLabel("District / measurement window")
                MicArea.entries.forEach { area ->
                    ChoiceButton("${area.title}: ${area.startHour}:00-${area.endHour}:00 JST", draft.micArea == area,
                        !running, Modifier.fillMaxWidth()) { draft = draft.copy(micArea = area) }
                }
                ConfirmationRow("I am measuring outdoors at one stationary point.", draft.stationaryConfirmed, !running) {
                    draft = draft.copy(stationaryConfirmed = it)
                }
                ConfirmationRow("The shared Japan IX server, sufficient bandwidth and server admission arrangements have been verified.",
                    draft.referenceServerConfirmed, !running) { draft = draft.copy(referenceServerConfirmed = it) }
                SetupHint("These declarations are recorded with results. The app cannot independently verify placement or server admission.")
                SetupLabel("Repetitions: 3 per point")
            } else SetupNumberField("Repetitions", number("repetitions"), !running) { setNumber("repetitions", it) }
            SetupNumberField("Idle interval between attempts (seconds)", number("interval"), !running) { setNumber("interval", it) }
            if ((transfer || parallel) && errors.isEmpty() && candidate.expectedTransferBytes > 0) {
                SetupLabel("Planned payload: ${dataVolume(candidate.expectedTransferBytes)} across ${candidate.effectiveRepetitions} trials")
                SetupHint("Network overhead and failed traffic add to this amount.")
            }
            if (draft.kind.mic) {
                SetupTextField(if (candidate.isMic) "Campaign ID" else "Campaign ID (optional)", draft.campaignId, !running) { draft = draft.copy(campaignId = it.trim()) }
                SetupTextField(if (candidate.isMic) "Point ID" else "Point ID (optional)", draft.pointId, !running) { draft = draft.copy(pointId = it.trim()) }
                SetupTextField(if (candidate.isMic) "Declared radio technology" else "Declared radio technology (optional)", draft.declaredTechnology, !running) {
                    draft = draft.copy(declaredTechnology = it.trim())
                }
                SetupHint("Use a unique point ID for each physical location. Keep campaign IDs consistent only for comparable measurements.")
            }
            SetupTextField(if (candidate.isMic) "Operator" else "Operator (optional)", draft.operator, !running) { draft = draft.copy(operator = it) }
            SetupTextField("Site / route label (optional)", draft.locationLabel, !running) { draft = draft.copy(locationLabel = it) }
            SetupTextField("Session label (optional)", draft.sessionLabel, !running) { draft = draft.copy(sessionLabel = it) }
            if (showErrors) errors.forEach { SetupHint(it) }
        }
        Spacer(Modifier.height(16.dp))
        if (showErrors && errors.isNotEmpty()) {
            SetupHint(errors.first())
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(text = "Save", enabled = !running, onClick = {
                showErrors = true
                if (errors.isEmpty()) onSave(candidate)
            }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

private fun presetDescription(c: DataTestConfig): String = when (c.kind) {
    DataTestKind.PING -> "32-byte ICMP. At least 80% of attempts must have RTT below ${c.profile.rttLimitMs.toInt()} ms."
    DataTestKind.FTP_DOWNLOAD, DataTestKind.FTP_UPLOAD ->
        "Single FTP session. Success target: ${if (c.upload) 70 else 80}%. At least 75% of completed transfers must reach ${c.profile.bitrateMbps(c.upload)} Mbps." +
            (if (c.profile.band2600) " Mean bitrate target: ${if (c.upload) 5 else 20} Mbps." else "")
    DataTestKind.WEB_PAGE -> "Complete 3.5 MB webpage within ${c.effectiveTimeoutSeconds} seconds for at least 90% of attempts."
    DataTestKind.STREAMING -> "${c.effectiveVideoHeight}p video, at least 180 seconds. Accessibility and reproduction targets: ${c.profile.streamingTarget.toInt()}%. Freezing time: at most 5%."
    else -> ""
}

@Composable
private fun ChoiceButton(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    TextButton(text = text, onClick = onClick, enabled = enabled, modifier = modifier,
        colors = if (selected) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
}

@Composable
private fun SetupTextField(title: String, value: String, enabled: Boolean, keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SetupLabel(title)
        TextField(value = value, onValueChange = onChange, label = "", enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth(), visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next))
    }
}

@Composable
private fun SetupNumberField(title: String, value: String, enabled: Boolean, onChange: (String) -> Unit) =
    SetupTextField(title, value, enabled, KeyboardType.Number, onChange = onChange)

@Composable
private fun ConfirmationRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off, onClick = null, enabled = enabled)
        Text(text = text, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun SetupLabel(text: String) { Text(text, style = MiuixTheme.textStyles.body2) }

@Composable
private fun SetupHint(text: String) {
    Text(text, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

internal fun dataVolume(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.2f kB", bytes / 1_000.0)
    else -> "$bytes bytes"
}
