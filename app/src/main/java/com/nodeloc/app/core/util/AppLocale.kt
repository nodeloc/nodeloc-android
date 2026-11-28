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
 * The system's, unless one was picked in Settings → 偏好 → 语言. (1.x also
 * adopted the account's forum language; the 2.0 site has none to give, so
 * the pick is the only override and it outlives sign-out.)
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
        _serverTag.value = prefs(context).getString(KEY_SERVER, null)?.takeIf { it in pickable }
            // An app language picked before the server list existed is its own server language.
            ?: _tag.value?.takeIf { it in pickable }
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

    /**
     * The choice made in Settings → 偏好 → 语言; null follows the system again.
     * Survives sign-out: the site no longer has an account language to hand
     * back to, so the only thing stored here is what the user picked.
     */
    fun choose(newTag: String?) = set(newTag?.takeIf { it in supported })

    private fun set(newTag: String?) {
        val context = appContext ?: return
        if (newTag == _tag.value) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (newTag == null) remove(KEY_TAG) else putString(KEY_TAG, newTag)
        }
        _tag.value = newTag
        // wrap() only touches the default when there is an override, so going
        // back to the system has to undo the last one itself — or dates and
        // Accept-Language keep the old language until the process dies.
        if (newTag == null) Locale.setDefault(android.content.res.Resources.getSystem().configuration.locales[0])
    }

    /**
     * Each language in its own name. Not string resources: an endonym is the
     * same in every locale, which is the point — a reader stuck in a language
     * they cannot read still finds their own.
     */
    val endonyms: Map<String, String> = linkedMapOf(
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "en" to "English",
        "ja" to "日本語",
        "vi" to "Tiếng Việt",
        "id" to "Bahasa Indonesia",
        "ru" to "Русский",
        "uk" to "Українська",
        "ar" to "العربية",
        "fa" to "فارسی",
    )

    /**
     * The languages the server speaks — `internal/locale/locale.go`'s `all`,
     * in its order — plus the two this app has strings for that the server
     * does not (Persian, Ukrainian). No endpoint lists them; `POST
     * /api/v1/locale` only checks against that table.
     */
    val serverLanguages: Map<String, String> = linkedMapOf(
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "ru" to "Русский",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "pt" to "Português",
        "it" to "Italiano",
        "tr" to "Türkçe",
        "vi" to "Tiếng Việt",
        "id" to "Bahasa Indonesia",
        "th" to "ไทย",
        "ar" to "العربية",
    )

    /** App-only: there are strings for them here, but the server cannot be asked for them. */
    private val appOnly = linkedMapOf("uk" to "Українська", "fa" to "فارسی")

    /** What the language picker offers: the server's list, then the app-only ones. */
    val pickable: Map<String, String> get() = serverLanguages + appOnly

    private const val KEY_SERVER = "server_locale"
    private const val KEY_SYNCED = "server_locale_synced"

    private val _serverTag = MutableStateFlow<String?>(null)

    /** The language chosen in Settings, as the picker shows it (may be one with no app strings, such as 한국어). */
    val serverTag: StateFlow<String?> = _serverTag.asStateFlow()

    /** What Accept-Language should ask for: the chosen server language when there is one, else the app's. */
    fun requestLanguage(): String = _serverTag.value ?: Locale.getDefault().toLanguageTag()

    /** The closest language this app has its own strings for. */
    private fun appTagFor(code: String): String = when {
        code in supported -> code
        code.startsWith("zh") -> "zh-CN"
        else -> fromDiscourse(code) ?: "en"
    }

    /**
     * 设置 › 偏好 › 语言: a language from [pickable], or null to follow the
     * system. The app's own words switch at once (to the closest language it
     * has strings in); the server is told, which also keeps it on a signed-in
     * account. A refusal puts everything back and returns the failure.
     */
    suspend fun chooseLanguage(code: String?, tellServer: suspend (String) -> Unit): Throwable? {
        val context = appContext ?: return null
        val previousServer = _serverTag.value
        val previousApp = _tag.value
        setServer(context, code)
        set(code?.let(::appTagFor))
        if (code != null && code in serverLanguages) {
            try {
                tellServer(code)
                // What the account holds now, so the next sign-in's `user.locale`
                // reads as "in agreement" rather than "changed elsewhere".
                prefs(context).edit { putString(KEY_SYNCED, code) }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                setServer(context, previousServer)
                set(previousApp)
                return error
            }
        }
        return null
    }

    /**
     * The language saved on the account (`user.locale`).
     *
     * Empty says nothing — the choice on this device stays. The same code this
     * device last sent is agreement. A different one was changed on the web or
     * another phone, and the account wins.
     */
    fun adoptAccountLocale(raw: String?) {
        val context = appContext ?: return
        val code = raw?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return
        if (code.equals(prefs(context).getString(KEY_SYNCED, null), ignoreCase = true)) return
        val match = serverLanguages.keys.firstOrNull { it.equals(code, ignoreCase = true) }
            ?: serverLanguages.keys.firstOrNull { it.take(2).equals(code.take(2), ignoreCase = true) }
            ?: return
        prefs(context).edit { putString(KEY_SYNCED, code) }
        if (_serverTag.value == match) return
        setServer(context, match)
        set(appTagFor(match))
    }

    private fun setServer(context: Context, code: String?) {
        prefs(context).edit { if (code == null) remove(KEY_SERVER) else putString(KEY_SERVER, code) }
        _serverTag.value = code
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
