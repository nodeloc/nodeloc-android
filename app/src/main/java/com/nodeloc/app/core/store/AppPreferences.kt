package com.nodeloc.app.core.store

import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import androidx.datastore.preferences.core.emptyPreferences
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nodeloc.app.core.model.NodeReadingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nodeloc")

/**
 * Every persisted preference in the app.
 *
 * Deliberately DataStore + cache files only: the architecture decision is *no
 * database*. Nothing here is relational, and a Room dependency would buy
 * migrations and a compiler plugin for a handful of scalars.
 */
class AppPreferences(context: Context) {
    private val store = context.applicationContext.dataStore

    object Keys {
        val readingMode = stringPreferencesKey("community_view_mode")
        val readingModeIsDeviceChoice = booleanPreferencesKey("community_view_mode_device")
        val colorMode = stringPreferencesKey("color_mode")
        val defaultHome = stringPreferencesKey("default_home")

        val pushEnabled = booleanPreferencesKey("push_enabled")
        val pushReplies = booleanPreferencesKey("push_replies")
        val pushLikes = booleanPreferencesKey("push_likes")
        val pushMessages = booleanPreferencesKey("push_messages")
        val pushOther = booleanPreferencesKey("push_other")

        /** Highest notification id already seen; the poll's watermark. */
        val pushWatermark = intPreferencesKey("push_watermark")
        val pushBaselineDone = booleanPreferencesKey("push_baseline_done")

        val searchHistory = stringPreferencesKey("search_history")

        /** Interest nodes chosen at signup, joined after email activation. */
        val pendingInterestNodes = stringSetPreferencesKey("pending_interest_nodes")

        val readTopics = stringSetPreferencesKey("read_topics")
        val videoMuted = booleanPreferencesKey("video_muted")

        /**
         * The day the account last checked in, as the server counted it.
         *
         * Kept on the device because the plugin has nowhere to ask: `GET
         * /checkin` is a stub, so the only way to learn today's state would be
         * to spend an attempt on it — and attempts are rate limited whether
         * they succeed or not. The web client keeps the same record in
         * localStorage.
         */
        val checkinDate = stringPreferencesKey("checkin_date")

        /**
         * How much of this handset the account has agreed to show on a post.
         * The server owns the setting; this is the copy the composer reads,
         * because a post must not wait on a preference fetch to be sent.
         */
        val postSourceLevel = intPreferencesKey("post_source_level")

        /**
         * The FCM token this account is currently registered under.
         *
         * Kept so sign-out can tell the server which address to forget. Without
         * it the row survives, and the site keeps writing this person's private
         * messages to a phone somebody else is now signed in on.
         */
        val pushToken = stringPreferencesKey("push_token")

        /**
         * Wiped on sign-out. Device choices are deliberately absent: colour
         * mode, default tab and video mute describe this phone, not whoever is
         * holding it.
         */
        /**
         * The unposted topic, as JSON. One slot: a phone composes one thing at
         * a time, and a list of drafts to choose between is a feature nobody
         * asked for on the way to not losing the one they were writing.
         */
        val composerDraft = stringPreferencesKey("composer_draft")

        val accountScoped = listOf(
            pushEnabled, pushReplies, pushLikes, pushMessages, pushOther,
            pushWatermark, pushBaselineDone,
            searchHistory, pendingInterestNodes, readTopics, checkinDate, postSourceLevel,
            pushToken, composerDraft,
        )
    }

    // Every preference flow in the app is built on this, so a corrupt store
    // would otherwise throw into each of their collectors rather than falling
    // back to defaults.
    private fun <T> flow(read: (Preferences) -> T): Flow<T> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(read)

    /**
     * Everything one account wrote here, at sign-out.
     *
     * Dropping the push baseline is the load-bearing part: without it the next
     * account's first poll believes it has already caught up, and either misses
     * its notifications or — if the watermark is lower than their ids — dumps
     * the whole unread backlog into the shade at once.
     */
    suspend fun clearAccountScoped() {
        store.edit { prefs ->
            Keys.accountScoped.forEach { prefs -= it }
            // Shared key: the account wrote it unless this device overrode it.
            if (prefs[Keys.readingModeIsDeviceChoice] != true) prefs -= Keys.readingMode
        }
    }

    // ------------------------------------------------------- reading mode

    /**
     * Precedence mirrors the web plugin's `community-view-mode` service:
     * this device's own choice, then the account preference, then compact.
     * The device wins on purpose — picking a mode on a phone shouldn't be
     * undone by a desktop preference (localStorage outranks the server there
     * too).
     */
    /**
     * @param signedIn decides only the fallback: a stored choice — this
     *   device's, or the account preference copied into it — always wins.
     */
    fun readingMode(signedIn: Flow<Boolean>): Flow<NodeReadingMode> =
        combine(flow { prefs -> NodeReadingMode.from(prefs[Keys.readingMode]) }, signedIn) { stored, isSignedIn ->
            stored ?: NodeReadingMode.default(isSignedIn)
        }

    suspend fun selectReadingMode(mode: NodeReadingMode) {
        store.edit {
            it[Keys.readingMode] = mode.key
            it[Keys.readingModeIsDeviceChoice] = true
        }
    }

    /** Applies `user_option.community_view_mode`; ignored once chosen here. */
    suspend fun applyAccountReadingMode(raw: String?) {
        val mode = NodeReadingMode.from(raw) ?: return
        store.edit { prefs ->
            if (prefs[Keys.readingModeIsDeviceChoice] != true) prefs[Keys.readingMode] = mode.key
        }
    }

    // ---------------------------------------------------------- appearance

    val colorMode: Flow<String> = flow { it[Keys.colorMode] ?: "system" }

    suspend fun setColorMode(value: String) {
        store.edit { it[Keys.colorMode] = value }
    }

    val defaultHome: Flow<String> = flow { it[Keys.defaultHome] ?: "best" }

    suspend fun setDefaultHome(value: String) {
        store.edit { it[Keys.defaultHome] = value }
    }

    // ---------------------------------------------------------------- push

    data class PushSettings(
        val enabled: Boolean = false,
        val replies: Boolean = true,
        val likes: Boolean = true,
        val messages: Boolean = true,
        val other: Boolean = true,
    )

    val pushSettings: Flow<PushSettings> = flow { prefs ->
        PushSettings(
            enabled = prefs[Keys.pushEnabled] ?: false,
            replies = prefs[Keys.pushReplies] ?: true,
            likes = prefs[Keys.pushLikes] ?: true,
            messages = prefs[Keys.pushMessages] ?: true,
            other = prefs[Keys.pushOther] ?: true,
        )
    }

    suspend fun setPushSettings(settings: PushSettings) {
        store.edit {
            it[Keys.pushEnabled] = settings.enabled
            it[Keys.pushReplies] = settings.replies
            it[Keys.pushLikes] = settings.likes
            it[Keys.pushMessages] = settings.messages
            it[Keys.pushOther] = settings.other
        }
    }

    suspend fun pushWatermark(): Pair<Int, Boolean> {
        // `edit` is a write transaction with an fsync, run every fifteen
        // minutes purely to read two integers.
        // Through the same fallback as every other read: a corrupt store here
        // would otherwise throw out of the worker into a permanent retry.
        val prefs = flow { it }.first()
        return (prefs[Keys.pushWatermark] ?: 0) to (prefs[Keys.pushBaselineDone] ?: false)
    }

    /**
     * Advance to the highest id in this batch — **including** ids filtered out
     * by category, or turning a category on later would replay old items.
     */
    suspend fun setPushWatermark(value: Int, baselineDone: Boolean = true) {
        store.edit {
            it[Keys.pushWatermark] = value
            it[Keys.pushBaselineDone] = baselineDone
        }
    }

    // ------------------------------------------------------ search history

    val searchHistory: Flow<List<String>> = flow { prefs ->
        prefs[Keys.searchHistory].orEmpty().split("\n").filter { it.isNotBlank() }
    }

    /**
     * Search runs while the term is still being typed, so "vp" lands a moment
     * before "vps". Only the newest entry is collapsed into the new one, and
     * only when the new one extends it — an older deliberate search for a short
     * term survives a later, longer one that happens to start the same way.
     */
    suspend fun addSearchTerm(term: String) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        store.edit { prefs ->
            val existing = prefs[Keys.searchHistory].orEmpty().split("\n").filter { it.isNotBlank() }
            val kept = existing.filterIndexed { index, previous ->
                previous != trimmed && !(index == 0 && trimmed.startsWith(previous, ignoreCase = true))
            }
            prefs[Keys.searchHistory] = (listOf(trimmed) + kept).take(20).joinToString("\n")
        }
    }

    suspend fun removeSearchTerm(term: String) {
        store.edit { prefs ->
            val existing = prefs[Keys.searchHistory].orEmpty().split("\n").filter { it.isNotBlank() }
            prefs[Keys.searchHistory] = existing.filter { it != term }.joinToString("\n")
        }
    }

    suspend fun clearSearchHistory() {
        store.edit { it[Keys.searchHistory] = "" }
    }

    // -------------------------------------------------- pending interests

    val pendingInterestNodes: Flow<Set<Int>> = flow { prefs ->
        prefs[Keys.pendingInterestNodes].orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
    }

    suspend fun setPendingInterestNodes(ids: Set<Int>) {
        store.edit { it[Keys.pendingInterestNodes] = ids.map(Int::toString).toSet() }
    }

    suspend fun clearPendingInterestNodes() {
        store.edit { it.remove(Keys.pendingInterestNodes) }
    }

    // -------------------------------------------------------- local reads

    /** Local read marks, so the unread dot clears the moment a topic opens. */
    val readTopics: Flow<Set<Int>> = flow { prefs ->
        prefs[Keys.readTopics].orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
    }

    suspend fun markTopicRead(id: Int) {
        store.edit { prefs ->
            val existing = prefs[Keys.readTopics].orEmpty()
            // Bounded: the server is the real source of read state; this only
            // has to survive until the list refreshes.
            prefs[Keys.readTopics] = (listOf(id.toString()) + existing).take(400).toSet()
        }
    }

    // ------------------------------------------------------------- media

    val videoMuted: Flow<Boolean> = flow { it[Keys.videoMuted] ?: true }

    suspend fun setVideoMuted(value: Boolean) {
        store.edit { it[Keys.videoMuted] = value }
    }

    // ---------------------------------------------------------- check-in

    val checkinDate: Flow<String?> = flow { it[Keys.checkinDate] }

    /** @param date the server's `user_date`, which is the day that counted. */
    suspend fun setCheckinDate(date: String) {
        store.edit { it[Keys.checkinDate] = date }
    }

    suspend fun composerDraft(): String? = flow { it[Keys.composerDraft] }.first()

    suspend fun setComposerDraft(json: String?) {
        store.edit { if (json == null) it -= Keys.composerDraft else it[Keys.composerDraft] = json }
    }

    suspend fun pushToken(): String? = flow { it[Keys.pushToken] }.first()

    suspend fun setPushToken(token: String?) {
        store.edit { if (token == null) it -= Keys.pushToken else it[Keys.pushToken] = token }
    }

    // ------------------------------------------------------- post source

    /**
     * The model, until the account says otherwise.
     *
     * The default has to live on both sides or it does nothing: the server can
     * decide that an account which never chose shows its model, but the fields
     * it would print only arrive if the client sent them, and the client only
     * sends them for a level above zero. A default of 0 here would leave the
     * server's default describing a post it received nothing about.
     *
     * A stored 0 still means 0 — DataStore returns what was written, and only
     * an absent key falls through to this.
     */
    val postSourceLevel: Flow<Int> = flow { it[Keys.postSourceLevel] ?: DEFAULT_POST_SOURCE_LEVEL }

    /** Read once, off the main thread, by whatever is about to send a post. */
    suspend fun currentPostSourceLevel(): Int = postSourceLevel.first()

    suspend fun setPostSourceLevel(level: Int) {
        store.edit { it[Keys.postSourceLevel] = level }
    }
}

/**
 * Mirrors `DiscourseMobile::PostSource::DEFAULT` on the server. Both have to
 * agree: the server decides what an account that never chose is treated as, and
 * the client decides whether the fields that default describes get sent at all.
 */
const val DEFAULT_POST_SOURCE_LEVEL = 4
