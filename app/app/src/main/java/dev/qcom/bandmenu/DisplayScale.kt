package dev.qcom.bandmenu

import android.content.Context

/** The app's display scale: every dp and sp is drawn at [factor]. Kept in
 *  SharedPreferences, which is read synchronously while the activity is being
 *  created, so the app doesn't start at Normal and then jump to the saved one. */
enum class DisplayScale(val factor: Float) {
    NORMAL(1f),
    LARGE(1.1f),
    SMALL(0.9f);

    companion object {
        private const val PREFS = "display_scale"
        private const val KEY = "value"

        /** The saved scale; Normal when nothing is saved. */
        fun saved(context: Context): DisplayScale {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return values().firstOrNull { it.name == name } ?: NORMAL
        }

        fun save(context: Context, scale: DisplayScale) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, scale.name).apply()
        }
    }
}
