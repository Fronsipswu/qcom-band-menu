package dev.qcom.bandmenu

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** The app's language: the phone's own ("") or one of the languages in
 *  app/i18n/strings.txt. Kept in SharedPreferences, which is read synchronously
 *  while the activity's context is being created, before the first screen. */
object AppLanguage {
    private const val PREFS = "app_language"
    private const val KEY = "tag"

    /** The chosen language tag, or "" for the phone's language. */
    fun saved(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun save(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** [context] with the chosen language applied; unchanged for the phone's language. */
    fun wrap(context: Context): Context {
        val tag = saved(context)
        if (tag.isEmpty()) return context
        return context.createConfigurationContext(configFor(context, tag))
    }

    /** The languages the translations offer, English first. */
    fun supported(context: Context): List<String> =
        context.resources.getStringArray(R.array.supported_language_tags).toList()

    /** The language's name in that language itself, e.g. "ไทย" for th. */
    fun name(context: Context, tag: String): String =
        context.createConfigurationContext(configFor(context, tag)).getString(R.string.language_name)

    private fun configFor(context: Context, tag: String): Configuration {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return config
    }
}
