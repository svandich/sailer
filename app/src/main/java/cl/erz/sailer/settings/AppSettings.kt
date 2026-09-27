package cl.erz.sailer.settings

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager

/**
 * The user's language and theme choices, which replace the "Tema" / "Idioma"
 * pickers in u-cursos.cl's page footer (hidden by MainActivity, same as the
 * original app does). Each choice is applied both natively (app locale /
 * night mode) and to the site itself, whose own values are:
 *
 * - theme: `focus`, `focus-dark`, `classic`, `classic-dark` - set by loading
 *   any page with `?theme=<value>`, persisted by the site in a `theme` cookie.
 * - lang: `es`, `en` - set by loading any page with `?_hook=lang&lang=<value>`
 *   (needs a Referer; 302s back to that page), persisted in the server session.
 *
 * Both are readable from any page as `kernel.theme` / `kernel.lang`.
 */
object AppSettings {

    const val KEY_THEME = "theme"
    const val KEY_LANGUAGE = "language"
    private const val KEY_SITE_DEFAULT_LANGUAGE = "site_default_language"

    /** Follow the system (night mode / locale) instead of a fixed value. */
    const val AUTO = "auto"

    private const val DEFAULT_LIGHT_THEME = "focus"
    private const val DEFAULT_DARK_THEME = "focus-dark"
    private const val DEFAULT_LANGUAGE = "es"
    private val SUPPORTED_LANGUAGES = setOf("es", "en")

    fun theme(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_THEME, AUTO) ?: AUTO

    /** Kept by AppCompat itself (per-app language), so it also reflects the system's per-app setting. */
    fun language(): String =
        AppCompatDelegate.getApplicationLocales()[0]?.language?.takeIf { it in SUPPORTED_LANGUAGES } ?: AUTO

    fun applyTheme(theme: String) {
        AppCompatDelegate.setDefaultNightMode(
            when {
                theme == AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                theme.endsWith("-dark") -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }

    fun applyLanguage(language: String) {
        AppCompatDelegate.setApplicationLocales(
            if (language == AUTO) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(language)
        )
    }

    /** The u-cursos.cl theme matching the current choice, resolving [AUTO] against the UI's night mode. */
    fun siteTheme(context: Context): String {
        val theme = theme(context)
        if (theme != AUTO) return theme
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) DEFAULT_DARK_THEME else DEFAULT_LIGHT_THEME
    }

    /**
     * The language last saved as the u-cursos.cl account's default (see
     * MainActivity.syncSitePreferences), so it's only saved once per choice:
     * new sessions don't start in it, so the site offers it after every switch.
     */
    fun siteDefaultLanguage(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_SITE_DEFAULT_LANGUAGE, null)

    fun setSiteDefaultLanguage(context: Context, language: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_SITE_DEFAULT_LANGUAGE, language).apply()
    }

    /** The u-cursos.cl language matching the current choice, resolving [AUTO] against the UI's locale. */
    fun siteLanguage(context: Context): String {
        val language = language()
        if (language != AUTO) return language
        return context.resources.configuration.locales[0].language.takeIf { it in SUPPORTED_LANGUAGES }
            ?: DEFAULT_LANGUAGE
    }
}
