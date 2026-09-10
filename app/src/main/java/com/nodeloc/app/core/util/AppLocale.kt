package com.nodeloc.app.core.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which language the app speaks.
 *
 * Two sources, in this order: the system, and — once signed in — the interface
 * language chosen on the site. The account wins because a member who set the
 * forum to Vietnamese meant it, and the phone underneath may well be somebody
 * else's hand-me-down; signing out gives the system its say back.
 *
 * Done by hand rather than through AppCompatDelegate.setApplicationLocales:
 * the backport of that is an AppCompat concern, these are ComponentActivities,
 * and pulling in appcompat to move one Configuration is not a trade worth
 * making. The framework's own LocaleManager only exists from API 33, and this
 * app still runs on 26.
 */
object AppLocale {

    /**
     * The tags with a values-xx folder behind them.
     *
     * Kept in step with res/xml/locales_config.xml by hand — the system reads
     * that one for the Settings picker, this one decides what an account's
     * locale is allowed to switch us to.
     */
    val supported = listOf("en", "zh-CN", "zh-TW", "vi", "ja", "ar", "ru", "uk", "id", "fa")

    private const val PREFS = "nodeloc.locale"
    private const val KEY_TAG = "app_locale"

    private val _tag = MutableStateFlow<String?>(null)

    /** The override in force, or null while the system is being followed. */
    val tag: StateFlow<String?> = _tag.asStateFlow()

    private var appContext: Context? = null

    /**
     * Wraps a context in the chosen language.
     *
     * Called from attachBaseContext, which runs before onCreate and before
     * anything has had a chance to read a string — the whole point is that no
     * screen ever sees the system's language and then flips.
     */
    fun wrap(base: Context): Context {
        val stored = stored(base) ?: return base
        _tag.value = stored
        val locale = Locale.forLanguageTag(stored)
        // Not just for this context: Locale.getDefault feeds the Accept-Language
        // header and every date this app formats, and neither goes through a
        // Context to ask.
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        // setLocale, not just setLocales: it is what sets the layout direction,
        // which is the only thing making Arabic and Persian lay out right.
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    fun install(context: Context) {
        appContext = context.applicationContext
        _tag.value = stored(context)
    }

    /**
     * Adopts the interface language of the account that just signed in.
     *
     * A code the app has no strings for leaves the system in charge rather than
     * falling back to English: a Korean member on a Korean phone is better
     * served by the phone than by a language neither of them picked.
     */
    fun applyFromAccount(discourseCode: String?) {
        set(fromDiscourse(discourseCode))
    }

    /** Hands the system its say back. Called on sign-out. */
    fun followSystem() = set(null)

    private fun set(newTag: String?) {
        val context = appContext ?: return
        if (newTag == _tag.value) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (newTag == null) remove(KEY_TAG) else putString(KEY_TAG, newTag)
        }
        _tag.value = newTag
    }

    private fun stored(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, null)
            ?.takeIf { it in supported }

    /**
     * Takes what the site says its language is and finds ours.
     *
     * Both spellings arrive: a rendered page says `zh-CN`, a Rails i18n name
     * says `zh_CN`, and some entries carry a region we ship a single file for
     * (fa_IR is the only Persian Discourse has; en_GB is English).
     */
    fun fromDiscourse(code: String?): String? {
        val cleaned = code?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        return when (cleaned.lowercase()) {
            "zh-cn", "zh-hans" -> "zh-CN"
            "zh-tw", "zh-hk", "zh-hant" -> "zh-TW"
            "fa-ir", "fa" -> "fa"
            "en-gb", "en" -> "en"
            else -> supported.firstOrNull { it.equals(cleaned, ignoreCase = true) }
                ?: supported.firstOrNull { it.equals(cleaned.substringBefore('-'), ignoreCase = true) }
        }
    }
}
