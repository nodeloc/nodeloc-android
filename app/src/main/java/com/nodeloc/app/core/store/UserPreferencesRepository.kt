package com.nodeloc.app.core.store

import com.nodeloc.app.core.util.runCatchingCancellable
import androidx.annotation.StringRes
import com.nodeloc.app.R
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.model.UserPreferences
import com.nodeloc.app.core.network.DiscourseClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Account preferences (`user_option`).
 *
 * The raw values are Discourse's own integers from `user_option.rb` — they are
 * part of the wire format, so renumbering any of them silently changes what the
 * server stores.
 */
enum class InterfaceColorMode(val value: Int, @StringRes val labelRes: Int) {
    Auto(1, R.string.color_mode_auto),
    Light(2, R.string.color_mode_light),
    Dark(3, R.string.color_mode_dark),
    ;

    companion object {
        fun from(value: Int?) = entries.firstOrNull { it.value == value } ?: Auto
    }
}

/**
 * `text_size`. Note that `normal` is 0, not the middle of the range, so raw
 * order is not display order — [ordered] is.
 */
enum class TextSize(val value: Int, @StringRes val labelRes: Int) {
    Smallest(4, R.string.text_size_smallest),
    Smaller(3, R.string.text_size_smaller),
    Normal(0, R.string.text_size_normal),
    Larger(1, R.string.text_size_larger),
    Largest(2, R.string.text_size_largest),
    ;

    companion object {
        val ordered = listOf(Smallest, Smaller, Normal, Larger, Largest)
        fun from(value: Int?) = entries.firstOrNull { it.value == value } ?: Normal
    }
}

enum class EmailLevel(val value: Int, @StringRes val labelRes: Int) {
    Always(0, R.string.email_level_always),
    OnlyWhenAway(1, R.string.email_level_away),
    Never(2, R.string.email_level_never),
    ;

    companion object {
        fun from(value: Int?) = entries.firstOrNull { it.value == value } ?: OnlyWhenAway
    }
}

enum class PreviousRepliesLevel(val value: Int, @StringRes val labelRes: Int) {
    Always(0, R.string.previous_replies_always),
    UnlessEmailed(1, R.string.previous_replies_unless),
    Never(2, R.string.previous_replies_never),
    ;

    companion object {
        fun from(value: Int?) = entries.firstOrNull { it.value == value } ?: Always
    }
}

enum class LikeNotificationFrequency(val value: Int, @StringRes val labelRes: Int) {
    Always(0, R.string.like_freq_always),
    FirstTimeAndDaily(1, R.string.like_freq_first_daily),
    FirstTime(2, R.string.like_freq_first),
    Never(3, R.string.like_freq_never),
    ;

    companion object {
        fun from(value: Int?) = entries.firstOrNull { it.value == value } ?: Always
    }
}

enum class NewTopicDuration(val minutes: Int, @StringRes val labelRes: Int) {
    LastVisit(-1, R.string.duration_last_visit),
    OneDay(1440, R.string.duration_one_day),
    TwoDays(2880, R.string.duration_two_days),
    OneWeek(10080, R.string.duration_one_week),
    TwoWeeks(20160, R.string.duration_two_weeks),
    ;

    companion object {
        fun from(value: Int?) = entries.firstOrNull { it.minutes == value } ?: LastVisit
    }
}

/**
 * Reads and writes the account preference bag.
 *
 * Every write is optimistic and rolls back on failure, because a settings row
 * that shows a value the server rejected is worse than no row at all.
 */
class UserPreferencesRepository(
    private val client: DiscourseClient,
    private val session: SessionRepository,
) : AccountScoped {
    private val _preferences = MutableStateFlow(UserPreferences())
    val preferences: StateFlow<UserPreferences> = _preferences.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // Volatile: the sign-out purge runs on IO while a load may be in flight.
    @Volatile
    private var loaded = false

    /**
     * Without this, `loaded` stayed true across a sign-out and `load()`
     * early-returned — so the next account was shown the previous one's
     * settings, and saving wrote them onto their own profile.
     */
    override suspend fun resetForSignOut() {
        loaded = false
        _preferences.value = UserPreferences()
        _isSaving.value = false
    }

    /** Preferences are only serialized for their owner. */
    suspend fun load(force: Boolean = false) {
        if (!client.auth.isAuthenticated) return
        if (loaded && !force) return
        val user = session.refresh(force = force) ?: return
        user.userOption?.let {
            _preferences.value = it
            loaded = true
        }
    }

    /** Adopts a payload the app already fetched, avoiding a second request. */
    fun apply(option: UserPreferences?) {
        option?.let {
            _preferences.value = it
            loaded = true
        }
    }

    suspend fun save(items: List<Pair<String, String>>, mutate: (UserPreferences) -> UserPreferences) {
        val username = session.username ?: return
        val snapshot = _preferences.value
        _preferences.value = mutate(snapshot)
        _isSaving.value = true
        // finally, not a trailing assignment: this repository outlives the
        // screen that called it, so a cancelled save would otherwise leave
        // every preference row spinning until the process restarts.
        try {
            runCatchingCancellable { client.updatePreferences(username, items) }
                .onFailure {
                    _preferences.value = snapshot
                    ToastCenter.showError(it)
                }
        } finally {
            _isSaving.value = false
        }
    }

    suspend fun saveBoolean(wireKey: String, value: Boolean, mutate: (UserPreferences, Boolean) -> UserPreferences) {
        save(listOf(wireKey to value.toString())) { mutate(it, value) }
    }

    suspend fun saveInt(wireKey: String, value: Int, mutate: (UserPreferences, Int) -> UserPreferences) {
        save(listOf(wireKey to value.toString())) { mutate(it, value) }
    }
}
