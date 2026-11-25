package com.nodeloc.app.feature.profile

import com.nodeloc.app.core.util.runCatchingCancellable
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.model.PointsHistoryEntry
import com.nodeloc.app.core.model.UserActionItem
import com.nodeloc.app.core.model.UserBookmark
import com.nodeloc.app.core.model.UserProfile
import com.nodeloc.app.core.model.UserSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The profile page's activity tabs. `filter` is Discourse's UserAction type.
 *
 * Bookmarks and energy have none: neither is a user action, and both are
 * fetched from somewhere else entirely. Bookmarks used to claim type 3, which
 * Discourse deleted along with the rows behind it — the request succeeded and
 * returned nothing, so the tab was empty and looked broken rather than wrong.
 */
enum class ActivityTab(@StringRes val labelRes: Int, val filter: Int?) {
    Topics(R.string.profile_topics, 4),
    Posts(R.string.profile_posts, 5),
    Likes(R.string.profile_likes, 1),
    Bookmarks(R.string.profile_bookmarks, null),
    Points(R.string.profile_energy, null),
    Achievements(R.string.me2_tab_achievements, null),
    About(R.string.me2_tab_about, null),
    ;

    /** Drawn from what the header already loaded; there is nothing to fetch. */
    val isLocal: Boolean get() = this == Achievements || this == About

    companion object {
        /**
         * Bookmarks and energy are your own business — `/api/v1/saved` only
         * ever answers with the caller's, so on someone else's page that tab
         * would have shown *your* bookmarks under *their* name.
         */
        val own = listOf(Topics, Posts, Likes, Bookmarks, Points)
        val public = listOf(Topics, Posts, Achievements, About)
    }
}

data class ProfileState(
    val username: String = "",
    val profile: UserProfile? = null,
    val summary: UserSummary? = null,
    val pointsTotal: Int? = null,
    val tab: ActivityTab = ActivityTab.Topics,
    val activity: Map<ActivityTab, List<UserActionItem>> = emptyMap(),
    /**
     * Whether each tab has more behind it.
     *
     * The server hands back thirty rows and stops; asking once was all this
     * screen ever did, so every tab looked capped at thirty no matter how much
     * the account had. Absent means "not loaded yet", which is not the same as
     * "nothing more".
     */
    val activityHasMore: Map<ActivityTab, Boolean> = emptyMap(),
    val isLoadingMoreTab: Boolean = false,
    val points: List<PointsHistoryEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingTab: Boolean = false,
    /**
     * A failed tab fetch is not an empty tab. Without this the two render
     * identically, and because the empty list is cached as a loaded result the
     * tab then stays wrong until a pull-to-refresh.
     *
     * The throwable, not a flag: the screen tells "no network" apart from
     * "that did not work", and only the error itself knows which it was.
     */
    val tabError: Throwable? = null,
    val isFollowing: Boolean = false,
    /** Whether this page is the signed-in account's own; decides what is fetched. */
    val isOwn: Boolean = false,
    /** Own page only: reputation, tier and the climb to the next one. */
    val reputation: ApiRepSummary? = null,
    /** Someone else's reputation, when they have not hidden it. */
    val publicReputation: Long? = null,
    /** Own page only: from the achievement overview. */
    val achievementsUnlocked: Int? = null,
    val achievementsTotal: Int? = null,
    /** Own page: the latest unlocks for the header row. Someone else's: what they pinned. */
    val achievementBadges: List<ApiAchItem> = emptyList(),
    val tiers: List<ApiTierInfo> = emptyList(),
    /**
     * This account on the energy board, when it made the first page the server
     * serves: the only public source of someone else's balance and rank.
     */
    val boardRow: ApiLeaderRow? = null,
    /** The day the account last checked in, as the server counted it. */
    val checkinDate: String? = null,
    val isCheckingIn: Boolean = false,
    val error: Throwable? = null,
) {
    val currentActivity: List<UserActionItem> get() = activity[tab].orEmpty()

    val currentTabHasMore: Boolean get() = activityHasMore[tab] == true

    /**
     * Compared against the device's day, not the site's.
     *
     * The two only differ for someone whose phone is outside the site's
     * timezone, and then only for a few hours around midnight — the same
     * bargain the web client makes. Getting it wrong costs one attempt and an
     * "already checked in" answer, not a lost check-in.
     */
    val checkedInToday: Boolean get() = checkinDate != null && checkinDate == LocalDate.now().toString()
}

/**
 * One profile — yours or someone else's.
 *
 * Activity tabs load lazily and are cached per tab, so flipping back and forth
 * doesn't re-hit the network for a list already on the device.
 */
class ProfileViewModel : ViewModel() {

    private companion object {
        /**
         * What `user_actions.json` returns when asked for no limit, and what
         * the bookmark list returns per page. A page shorter than this is the
         * last one — neither endpoint says so directly.
         */
        const val ACTIVITY_PAGE = 30
    }

    private val services = ServiceLocator.get
    private val client = services.client
    private val session = services.session

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private var boundUsername: String? = null

    private val growth = GrowthApi.shared

    init {
        viewModelScope.launch {
            services.preferences.checkinDate.collect { date ->
                _state.value = _state.value.copy(checkinDate = date)
            }
        }
    }

    fun bind(username: String?) {
        val target = username ?: session.username
        if (target == null) {
            // Signed in with no name to load — the session's own user refresh
            // has not landed, or failed. Saying so leaves a retry; returning
            // quietly leaves the screen waiting on a call nobody made.
            _state.value = _state.value.copy(
                isLoading = false,
                error = IllegalStateException("no account bound"),
            )
            return
        }
        if (boundUsername == target) return
        boundUsername = target
        load(target)
    }

    /** Retry after a failed first load — including one that never started. */
    fun retry() {
        val target = boundUsername ?: session.username
        if (target == null) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = IllegalStateException("no account bound"),
            )
            return
        }
        boundUsername = target
        load(target)
    }

    fun load(username: String = boundUsername.orEmpty()) {
        if (username.isEmpty()) return
        val own = session.username?.equals(username, ignoreCase = true) == true
        viewModelScope.launch {
            _state.value = _state.value.copy(username = username, isOwn = own, isLoading = true, error = null)
            val profileJob = async { runCatchingCancellable { client.user(username) } }
            val summaryJob = async { runCatchingCancellable { client.userSummary(username) }.getOrNull() }
            // Everything below is optional: a failure costs the header a "--"
            // or a row, never the page. The energy balance, reputation and
            // achievement overview are `/me`-only endpoints, so they are asked
            // for on the account's own page alone.
            val pointsJob = async { if (own) runCatchingCancellable { client.pointsTotal(username) }.getOrNull() else null }
            val repJob = async { if (own) runCatchingCancellable { growth.reputation() }.getOrNull() else null }
            val achJob = async { if (own) runCatchingCancellable { growth.achievements() }.getOrNull() else null }
            val showcaseJob = async { if (!own) runCatchingCancellable { growth.showcase(username) }.getOrNull() else null }
            val checkinJob = async { if (own) runCatchingCancellable { growth.checkinStatus() }.getOrNull() else null }
            val tiersJob = async { TierDirectory.get() }
            // One request for the whole first page, not one per profile field:
            // the board is the same for every profile opened.
            val boardJob = async { runCatchingCancellable { growth.leaderboard() }.getOrNull() }

            val outcome = profileJob.await()
            val profile = outcome.getOrNull()?.user
            val overview = achJob.await()
            val showcase = showcaseJob.await()
            // The server's word on today beats the remembered date: a check-in
            // made on the website was otherwise invisible here until tomorrow.
            checkinJob.await()?.let { status ->
                val today = LocalDate.now().toString()
                if (status.checkedInToday && _state.value.checkinDate != today) services.preferences.setCheckinDate(today)
            }
            _state.value = _state.value.copy(
                profile = profile,
                summary = summaryJob.await()?.userSummary,
                pointsTotal = pointsJob.await()?.totalScores,
                reputation = repJob.await(),
                publicReputation = showcase?.reputation,
                achievementsUnlocked = overview?.unlockedCount,
                achievementsTotal = overview?.total,
                achievementBadges = overview?.achievements
                    ?.filter { it.unlocked }
                    ?.sortedByDescending { it.lastAt ?: it.unlockedAt.orEmpty() }
                    ?.take(4)
                    ?: showcase?.showcase.orEmpty(),
                tiers = tiersJob.await(),
                boardRow = boardJob.await()?.firstOrNull { it.username.equals(username, ignoreCase = true) },
                isFollowing = profile?.isFollowed == true,
                isLoading = false,
                // The transport error itself, not a stand-in for it. The screen
                // branches on `isOfflineError()` to say "no network" and offer a
                // retry, and swapping in a synthetic exception made that branch
                // unreachable — every failure, offline included, read as the
                // generic "操作失败".
                error = when {
                    profile != null -> null
                    else -> outcome.exceptionOrNull() ?: IllegalStateException("profile unavailable")
                },
            )
            val tabs = if (own) ActivityTab.own else ActivityTab.public
            selectTab(_state.value.tab.takeIf { it in tabs } ?: ActivityTab.Topics, force = true)
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(
            activity = emptyMap(),
            activityHasMore = emptyMap(),
            points = emptyList(),
        )
        load()
    }

    /**
     * Claims today's energy.
     *
     * Guarded twice on purpose. The endpoint counts every attempt against a
     * rate limit that only a *successful* claim clears, so a button that fires
     * while one call is in flight — or after the day is already known to be
     * spent — would burn the account's remaining attempts on answers it
     * already has.
     */
    fun checkin() {
        val current = _state.value
        if (current.isCheckingIn || current.checkedInToday) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isCheckingIn = true)
            val outcome = runCatchingCancellable { client.checkin() }
            val response = outcome.getOrNull()
            when {
                response == null -> outcome.exceptionOrNull()?.let { ToastCenter.showError(it) }

                response.checkedIn -> {
                    // The server's own day, not the device's: it is the one the
                    // next attempt will be judged against.
                    val date = response.userDate ?: LocalDate.now().toString()
                    services.preferences.setCheckinDate(date)
                    _state.value = _state.value.copy(checkinDate = date)
                    response.points?.let { ToastCenter.show(R.string.checkin_success, it) }
                        ?: ToastCenter.show(R.string.checkin_done)
                    // The energy total on this very page is now stale.
                    refreshPoints()
                }

                else -> {
                    // Already claimed — a 200, and the only way to find out.
                    // Recording it stops the next tap spending another attempt.
                    val date = response.userDate ?: LocalDate.now().toString()
                    services.preferences.setCheckinDate(date)
                    _state.value = _state.value.copy(checkinDate = date)
                    response.message?.let { ToastCenter.show(it) } ?: ToastCenter.show(R.string.checkin_done)
                }
            }
            _state.value = _state.value.copy(isCheckingIn = false)
        }
    }

    private fun refreshPoints() {
        val username = boundUsername ?: return
        viewModelScope.launch {
            val total = runCatchingCancellable { client.pointsTotal(username) }.getOrNull()?.totalScores
            if (total != null) _state.value = _state.value.copy(pointsTotal = total)
            // The energy tab lists the entries the check-in just added to.
            if (_state.value.tab == ActivityTab.Points) selectTab(ActivityTab.Points, force = true)
        }
    }

    fun selectTab(tab: ActivityTab, force: Boolean = false) {
        _state.value = _state.value.copy(tab = tab)
        if (tab.isLocal) {
            _state.value = _state.value.copy(isLoadingTab = false, tabError = null)
            return
        }
        val username = _state.value.username.ifEmpty { return }
        if (!force) {
            val alreadyLoaded = if (tab == ActivityTab.Points) {
                _state.value.points.isNotEmpty()
            } else {
                _state.value.activity.containsKey(tab)
            }
            if (alreadyLoaded) return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingTab = true, tabError = null)
            if (tab == ActivityTab.Points) {
                runCatchingCancellable { client.pointsHistory(username) }
                    .onSuccess {
                        _state.value = _state.value.copy(
                            points = it.pointsHistory.orEmpty(),
                            isLoadingTab = false,
                        )
                    }
                    .onFailure {
                        _state.value = _state.value.copy(isLoadingTab = false, tabError = it)
                    }
            } else if (tab == ActivityTab.Bookmarks) {
                // Its own endpoint, not a user-action filter — see
                // DiscourseClient.bookmarks. Shaped into the same row the other
                // tabs draw so the list below does not have to know.
                runCatchingCancellable { client.bookmarks(username) }
                    .onSuccess { response ->
                        val rows = response.userBookmarkList.bookmarks.map { it.asActivity() }
                        _state.value = _state.value.copy(
                            activity = _state.value.activity + (tab to rows),
                            activityHasMore = _state.value.activityHasMore +
                                (tab to (response.userBookmarkList.moreBookmarksUrl != null)),
                            isLoadingTab = false,
                        )
                    }
                    .onFailure {
                        _state.value = _state.value.copy(isLoadingTab = false, tabError = it)
                    }
            } else {
                val filter = tab.filter ?: return@launch
                runCatchingCancellable { client.userActions(username, filter) }
                    .onSuccess {
                        val rows = it.userActions.orEmpty()
                        _state.value = _state.value.copy(
                            activity = _state.value.activity + (tab to rows),
                            activityHasMore = _state.value.activityHasMore +
                                (tab to (rows.size >= ACTIVITY_PAGE)),
                            isLoadingTab = false,
                        )
                    }
                    .onFailure {
                        _state.value = _state.value.copy(isLoadingTab = false, tabError = it)
                    }
            }
        }
    }

    /**
     * The next page of the tab on screen.
     *
     * Safe to call repeatedly — the list sentinel fires more than once as it
     * settles, and a second call while one is in flight would duplicate rows
     * rather than extend them.
     */
    fun loadMoreActivity() {
        val current = _state.value
        val tab = current.tab
        if (tab == ActivityTab.Points || tab.isLocal) return
        if (current.isLoadingTab || current.isLoadingMoreTab) return
        if (!current.currentTabHasMore) return
        val username = current.username.ifEmpty { return }
        val loaded = current.activity[tab].orEmpty()
        if (loaded.isEmpty()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMoreTab = true)
            val fetched = if (tab == ActivityTab.Bookmarks) {
                // Bookmarks page; user actions offset. Same idea, two dialects.
                runCatchingCancellable { client.bookmarks(username, loaded.size / ACTIVITY_PAGE) }
                    .getOrNull()
                    ?.let { it.userBookmarkList.bookmarks.map { bm -> bm.asActivity() } to
                        (it.userBookmarkList.moreBookmarksUrl != null) }
            } else {
                val filter = tab.filter
                if (filter == null) {
                    null
                } else {
                    runCatchingCancellable { client.userActions(username, filter, loaded.size) }
                        .getOrNull()
                        ?.let { it.userActions.orEmpty() to (it.userActions.orEmpty().size >= ACTIVITY_PAGE) }
                }
            }
            if (fetched == null) {
                _state.value = _state.value.copy(isLoadingMoreTab = false)
                return@launch
            }
            val (rows, more) = fetched
            _state.value = _state.value.copy(
                activity = _state.value.activity + (tab to loaded + rows),
                activityHasMore = _state.value.activityHasMore + (tab to (more && rows.isNotEmpty())),
                isLoadingMoreTab = false,
            )
        }
    }

    /**
     * The chat channel with this person, opened once the server says which one
     * it is — Discourse creates it on first ask and returns the existing one
     * after that, so the caller need not know which case it is in.
     */
    fun openChatWith(onOpen: (Int) -> Unit) {
        val username = _state.value.username.ifEmpty { return }
        viewModelScope.launch {
            runCatchingCancellable { client.openDirectMessageChannel(username) }
                .onSuccess { it.channel?.id?.let(onOpen) ?: ToastCenter.show(R.string.error_action_failed) }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    /** `normal`, `mute` or `ignore`, as Discourse names them. */
    fun setNotificationLevel(level: String) {
        val username = _state.value.username.ifEmpty { return }
        viewModelScope.launch {
            runCatchingCancellable {
                // `ignore` is the one level that will not save without a
                // deadline, so it goes through the call that sends one. This
                // menu had been posting it without, which the server answers
                // by failing on `Time.parse(nil)`.
                when (level) {
                    "ignore" -> client.ignoreUser(username)
                    else -> client.setUserNotificationLevel(username, level)
                }
            }
                .onSuccess {
                    // The lists filter on this set, and ignoring somebody from
                    // here should empty them of him too.
                    if (level == "ignore") services.blockedUsers.add(username)
                    else services.blockedUsers.remove(username)
                    ToastCenter.show(R.string.profile_notify_saved)
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    /** Optimistic follow toggle (discourse-follow). */
    fun toggleFollow() {
        val username = _state.value.username.ifEmpty { return }
        val following = !_state.value.isFollowing
        _state.value = _state.value.copy(isFollowing = following)
        viewModelScope.launch {
            runCatchingCancellable { if (following) client.follow(username) else client.unfollow(username) }
                .onFailure {
                    _state.value = _state.value.copy(isFollowing = !following)
                    ToastCenter.showError(it)
                }
        }
    }
}

/**
 * A bookmark drawn as an activity row.
 *
 * `linked_post_number` rather than a post number of its own: a bookmark points
 * at a post, and that is the number the reader needs to land on it.
 */
private fun UserBookmark.asActivity() = UserActionItem(
    title = title,
    excerpt = excerpt,
    createdAt = createdAt,
    avatarTemplate = user?.avatarTemplate,
    username = user?.username,
    name = user?.name,
    categoryId = categoryId,
    topicId = topicId,
    postNumber = linkedPostNumber,
    postId = postId,
)
