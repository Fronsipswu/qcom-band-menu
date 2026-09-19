package dev.qcom.bandmenu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.qcom.bandmenu.BuildConfig
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun InfoScreen(
    contentPadding: PaddingValues = PaddingValues(),
    debugEnabled: Boolean = false,
    onDebugToggle: () -> Unit = {},
    onDetach: () -> Unit = {},
    onAttach: () -> Unit = {},
    onReattach: () -> Unit = {}
) {
    val density = LocalDensity.current
    val navbarHeightDp = 64.dp
    val navInset = WindowInsets.navigationBars.asPaddingValues(density).calculateBottomPadding()
    val navbarSpace = navbarHeightDp + 16.dp + navInset
    val statusBarInset = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 16.dp)
                .padding(bottom = navbarSpace),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            SmallTitle("About")
            Text(
                "Qualcomm Band Menu",
                style = MiuixTheme.textStyles.title2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Package: ${BuildConfig.APPLICATION_ID}",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            SmallTitle("Credits")
            Text(
                "@pasapong",
                style = MiuixTheme.textStyles.title2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            SmallTitle("Powered by")
            Text(
                "miuix UI framework\nlibsu\nAndroidLiquidGlass",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        WindowIconDropdownMenu(
            entries = listOf(
                DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = "Debug logging",
                            selected = debugEnabled,
                            onClick = { onDebugToggle() }
                        )
                    )
                ),
                DropdownEntry(
                    items = listOf(
                        DropdownItem("Detach", onClick = onDetach),
                        DropdownItem("Attach", onClick = onAttach),
                        DropdownItem("Re-Attach", onClick = onReattach)
                    )
                )
            ),
            collapseOnSelection = true,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = statusBarInset + 8.dp, end = 8.dp)
        ) {
            Icon(
                imageVector = MiuixIcons.More,
                contentDescription = "Menu",
                tint = MiuixTheme.colorScheme.onBackground
            )
        }
    }
}
