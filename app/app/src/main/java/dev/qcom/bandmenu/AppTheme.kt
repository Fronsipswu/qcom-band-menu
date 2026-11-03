package dev.qcom.bandmenu

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The app's theme: dark unless the Info page's "Light theme" is ticked. Kept
 *  in SharedPreferences and read synchronously before the first frame, as
 *  [DisplayScale], so the app doesn't start dark and then turn light. [light]
 *  is Compose state, so colours outside the miuix scheme (the chart's lines,
 *  the PHY DL / UL values) follow a change at once. */
object AppTheme {
    private const val PREFS = "app_theme"
    private const val KEY = "light"

    var light by mutableStateOf(false)
        private set

    fun load(context: Context) {
        light = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun save(context: Context, light: Boolean) {
        this.light = light
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY, light).apply()
    }
}
