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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.qcom.bandmenu.AppLanguage
import dev.qcom.bandmenu.BuildConfig
import dev.qcom.bandmenu.R
import android.app.Activity
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

@Composable
fun InfoScreen(
    contentPadding: PaddingValues = PaddingValues(),
    debugEnabled: Boolean = false,
    onDebugToggle: () -> Unit = {},
    onDetach: () -> Unit = {},
    onAttach: () -> Unit = {},
    onReattach: () -> Unit = {},
    navbarHidden: Boolean = false,
    onNavbarHiddenToggle: (Boolean) -> Unit = {},
    /** The app opens on the Network page (from the next launch). */
    networkAtStart: Boolean = false,
    onNetworkAtStartToggle: (Boolean) -> Unit = {}
) {
    val density = LocalDensity.current
    val navbarSpace = navbarSpace()
    val statusBarInset = WindowInsets.statusBars.asPaddingValues(density).calculateTopPadding()
    val context = LocalContext.current
    var showLanguage by remember { mutableStateOf(false) }
    val language = remember { AppLanguage.saved(context) }
    val languages = remember { AppLanguage.supported(context) }
    val names = remember(languages) { languages.associateWith { AppLanguage.name(context, it) } }

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

            SmallTitle(stringResource(R.string.info_about))
            Text(
                "Qualcomm Band Menu",
                style = MiuixTheme.textStyles.title2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.info_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.info_package, BuildConfig.APPLICATION_ID),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            SmallTitle(stringResource(R.string.info_credits))
            Text(
                "@pasapong",
                style = MiuixTheme.textStyles.title2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            SmallTitle(stringResource(R.string.info_powered_by))
            Text(
                "miuix UI framework\nlibsu\nAndroidLiquidGlass\nasn1c (Lev Walkin; mouse07410 fork)",
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
                            text = stringResource(R.string.info_debug_logging),
                            selected = debugEnabled,
                            onClick = { onDebugToggle() }
                        )
                    )
                ),
                DropdownEntry(
                    items = listOf(
                        DropdownItem(stringResource(R.string.info_detach), onClick = onDetach),
                        DropdownItem(stringResource(R.string.info_attach), onClick = onAttach),
                        DropdownItem(stringResource(R.string.info_reattach), onClick = onReattach)
                    )
                ),
                DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = stringResource(R.string.info_hide_navbar), selected = navbarHidden,
                            onClick = { onNavbarHiddenToggle(!navbarHidden) }
                        ),
                        DropdownItem(
                            text = stringResource(R.string.info_network_at_start), selected = networkAtStart,
                            onClick = { onNetworkAtStartToggle(!networkAtStart) }
                        )
                    )
                ),
                DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = stringResource(R.string.info_language),
                            onClick = { showLanguage = true }
                        )
                    )
                )
            ),
            collapseOnSelection = false,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = statusBarInset + 8.dp, end = 8.dp)
        ) {
            Icon(
                imageVector = MiuixIcons.More,
                contentDescription = stringResource(R.string.info_menu),
                tint = MiuixTheme.colorScheme.onBackground
            )
        }

        // Second level: the languages, the phone's own first. Picking one saves
        // it and recreates the activity so every text is drawn in it.
        WindowListPopup(
            show = showLanguage,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showLanguage = false }
        ) {
            val options = listOf("" to stringResource(R.string.info_language_system)) +
                languages.map { it to names.getValue(it) }
            ListPopupColumn {
                options.forEachIndexed { index, (tag, text) ->
                    DropdownImpl(
                        item = DropdownItem(text = text),
                        optionSize = options.size,
                        isSelected = tag == language,
                        index = index,
                        onSelectedIndexChange = {
                            showLanguage = false
                            if (tag != language) {
                                AppLanguage.save(context, tag)
                                (context as? Activity)?.recreate()
                            }
                        }
                    )
                }
            }
        }
    }
}
