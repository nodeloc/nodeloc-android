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
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.network.NodelocApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The profile page's tabs, in the board's order.
 *
 * 概览 and 评论 read the same list — the account's posts, which include the
 * openers of its topics — so they share one fetch. 赞同过 is not here: the
 * backend serves it only to the website's own pages, with no JSON route, and a
 * tab that could only ever be empty would read as broken.
 */
enum class ActivityTab(@StringRes val labelRes: Int) {
    Overview(R.string.me3_tab_overview),
    Topics(R.string.profile_posts),
    Comments(R.string.me3_tab_comments),
    Points(R.string.me3_tab_points),
    Bookmarks(R.string.me3_tab_saved),
    Achievements(R.string.me2_tab_achievements),
    About(R.string.me2_tab_about),
    ;

    /** Drawn from what the header already loaded; there is nothing to fetch. */
    val isLocal: Boolean get() = this == Achievements || this == About

    /**
     * Energy and saves are the owner's business: `/api/v1/saved` and the
     * ledger only ever answer with the caller's own, so on someone else's
     * page those tabs show the board's "only visible to them" state instead
     * of the reader's own list under the other person's name.
     */
    fun isPrivateFor(own: Boolean): Boolean = !own && (this == Points || this == Bookmarks)

    companion object {
        // Only 帖子 and 评论, as on iOS: saves have their own page (侧栏 › 我的收藏),
        // energy its ledger, achievements the strip in the header.
        val own = listOf(Topics, Comments)
        val public = listOf(Topics, Comments)
    }
}

data class ProfileState(
    val username: String = "",
    val profile: UserProfile? = null,
    val summary: UserSummary? = null,
    val pointsTotal: Int? = null,
    val tab: ActivityTab = ActivityTab.Topics,
    /** Topics the person started, in the backend's own shape. */
    val topics: List<ApiUserTopicRow> = emptyList(),
    val topicsCursor: String? = null,
    val topicsLoaded: Boolean = false,
    /** Everything they posted, openers included; newest first. */
    val posts: List<ApiUserPostRow> = emptyList(),
    val postsCursor: String? = null,
    val postsLoaded: Boolean = false,
    val bookmarks: List<UserActionItem> = emptyList(),
    val bookmarksHasMore: Boolean = false,
    val bookmarksLoaded: Boolean = false,
    /** The reader's votes on openers, by topic id: seeded from the rows, then optimistic. */
    val votes: Map<Int, TopicVote> = emptyMap(),
    /** Saves the reader made or undid here, by post id; the rows do not say what was saved before. */
    val saved: Map<Int, Boolean> = emptyMap(),
    val isLoadingMoreTab: Boolean = false,
    val points: List<PointsHistoryEntry> = emptyList(),
    val pointsLoaded: Boolean = false,
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
    /** Own page only: the nodes the account has joined, for the 常去节点 card. */
    val joinedNodes: List<com.nodeloc.app.core.model.NodeSummary>? = null,
    /** Someone else's page: every node by slug, to draw the ones their activity names. */
    val nodeDirectory: Map<String, com.nodeloc.app.core.model.NodeSummary> = emptyMap(),
    /**
     * This account on the energy board, when it made the first page the server
     * serves: the one API source of someone else's rank.
     */
    val boardRow: ApiLeaderRow? = null,
    /** Someone else's balance, read off the website's hover card; see [ProfileViewModel.cardEnergy]. */
    val publicEnergy: Long? = null,
    /** The day the account last checked in, as the server counted it. */
    val checkinDate: String? = null,
    val isCheckingIn: Boolean = false,
    val error: Throwable? = null,
) {
    /** Topic ids this person is known to have opened: from 帖子, or an opener met in 概览. */
    val openedTopics: Set<Int>
        get() = topics.mapTo(HashSet()) { it.id } + posts.filter { it.postNumber == 1 }.map { it.topicId }

    val topicsById: Map<Int, ApiUserTopicRow> get() = topics.associateBy { it.id }

    val currentTabHasMore: Boolean
        get() = when (tab) {
            ActivityTab.Overview, ActivityTab.Comments -> postsCursor != null
            ActivityTab.Topics -> topicsCursor != null
            ActivityTab.Bookmarks -> bookmarksHasMore
            else -> false
        }

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
 * Lists load lazily and are kept once loaded, so flipping between tabs doesn't
 * re-hit the network for a list already on the device.
 */
class ProfileViewModel : ViewModel() {

    private val services = ServiceLocator.get
    private val client = services.client
    private val session = services.session

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private var boundUsername: String? = null

    private val growth = GrowthApi.shared
    private val activity = ProfileActivityApi(client.api)
    private var tabJob: Job? = null

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
            // Someone else's page asks too, when signed in: their own count
            // comes with the profile, but the "/ total" beside it only from here.
            val achJob = async { if (own || session.isSignedIn.value) runCatchingCancellable { growth.achievements() }.getOrNull() else null }
            val showcaseJob = async { if (!own) runCatchingCancellable { growth.showcase(username) }.getOrNull() else null }
            val checkinJob = async { if (own) runCatchingCancellable { growth.checkinStatus() }.getOrNull() else null }
            val tiersJob = async { TierDirectory.get() }
            val nodesJob = async {
                if (own) runCatchingCancellable { client.sidebarNodes().communities.orEmpty().map(com.nodeloc.app.feature.node.NodeSummaryFactory::summary) }.getOrNull() else null
            }
            // Someone else's 常去节点 are worked out from their activity, which
            // names nodes by slug alone; the directory (cached) gives them faces.
            val directoryJob = async {
                if (own) {
                    emptyMap()
                } else {
                    runCatchingCancellable {
                        client.nodeDirectory().associate { it.slug to com.nodeloc.app.feature.node.NodeSummaryFactory.summary(com.nodeloc.app.core.network.Legacy.category(it)) }
                    }.getOrDefault(emptyMap())
                }
            }
            // One request for the whole first page, not one per profile field:
            // the board is the same for every profile opened.
            val boardJob = async { runCatchingCancellable { growth.leaderboard() }.getOrNull() }
            val energyJob = async { if (own) null else cardEnergy(username) }

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
                achievementsUnlocked = if (own) overview?.unlockedCount else profile?.achievementCount,
                achievementsTotal = overview?.total?.takeIf { it > 0 },
                achievementBadges = overview?.takeIf { own }?.achievements
                    ?.filter { it.unlocked }
                    ?.sortedByDescending { it.lastAt ?: it.unlockedAt.orEmpty() }
                    ?.take(3)
                    ?: showcase?.showcase.orEmpty(),
                tiers = tiersJob.await(),
                joinedNodes = nodesJob.await(),
                nodeDirectory = directoryJob.await(),
                boardRow = boardJob.await()?.firstOrNull { it.username.equals(username, ignoreCase = true) },
                publicEnergy = energyJob.await(),
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
            topics = emptyList(), topicsCursor = null, topicsLoaded = false,
            posts = emptyList(), postsCursor = null, postsLoaded = false,
            bookmarks = emptyList(), bookmarksHasMore = false, bookmarksLoaded = false,
            points = emptyList(), pointsLoaded = false,
            votes = emptyMap(), saved = emptyMap(),
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
        val current = _state.value
        _state.value = current.copy(tab = tab)
        if (tab.isLocal || tab.isPrivateFor(current.isOwn)) {
            _state.value = _state.value.copy(isLoadingTab = false, tabError = null)
            return
        }
        val username = current.username.ifEmpty { return }
        if (!force) {
            val loaded = when (tab) {
                ActivityTab.Overview -> current.postsLoaded
                ActivityTab.Comments -> current.postsLoaded
                ActivityTab.Topics -> current.topicsLoaded
                ActivityTab.Bookmarks -> current.bookmarksLoaded
                ActivityTab.Points -> current.pointsLoaded
                else -> true
            }
            if (loaded) {
                _state.value = _state.value.copy(isLoadingTab = false, tabError = null)
                // Reached from 评论, the posts are here but the openers'
                // numbers are not; fetched quietly, the cards fill in.
                if (tab == ActivityTab.Overview && !current.topicsLoaded) {
                    viewModelScope.launch {
                        runCatchingCancellable { activity.topics(username, null) }.getOrNull()?.let(::applyTopics)
                    }
                }
                return
            }
        }
        tabJob?.cancel()
        tabJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingTab = true, tabError = null)
            val outcome = runCatchingCancellable { fetchFirst(tab, username) }
            _state.value = _state.value.copy(isLoadingTab = false, tabError = outcome.exceptionOrNull())
        }
    }

    private suspend fun fetchFirst(tab: ActivityTab, username: String) {
        when (tab) {
            ActivityTab.Overview, ActivityTab.Comments -> coroutineScope {
                // The overview draws openers as topic cards; their scores and
                // counts live on the topics list, so its first page rides along.
                // Optional: without it those cards just go without the numbers.
                val topics = async {
                    if (tab == ActivityTab.Overview && !_state.value.topicsLoaded) {
                        runCatchingCancellable { activity.topics(username, null) }.getOrNull()
                    } else {
                        null
                    }
                }
                val posts = activity.posts(username, null)
                topics.await()?.let(::applyTopics)
                _state.value = _state.value.copy(posts = posts.posts, postsCursor = posts.nextCursor, postsLoaded = true)
                if (tab == ActivityTab.Comments) fillComments(username)
            }
            ActivityTab.Topics -> applyTopics(activity.topics(username, null))
            ActivityTab.Points -> {
                val history = client.pointsHistory(username)
                _state.value = _state.value.copy(points = history.pointsHistory.orEmpty(), pointsLoaded = true)
            }
            ActivityTab.Bookmarks -> {
                // Its own endpoint, not a user list — see DiscourseClient.bookmarks.
                val response = client.bookmarks(username)
                _state.value = _state.value.copy(
                    bookmarks = response.userBookmarkList.bookmarks.map { it.asActivity() },
                    bookmarksHasMore = response.userBookmarkList.moreBookmarksUrl != null,
                    bookmarksLoaded = true,
                )
            }
            else -> Unit
        }
    }

    private fun applyTopics(page: ApiUserTopicPage, append: Boolean = false) {
        val now = _state.value
        val known = if (append) now.topics.mapTo(HashSet()) { it.id } else emptySet()
        val fresh = page.topics.filter { it.id !in known }
        // A vote made here outranks what a later page says about the same topic.
        val seeded = fresh.associate { it.id to TopicVote(voteOf(it.viewerVote), it.voteScore) }
        _state.value = now.copy(
            topics = if (append) now.topics + fresh else fresh,
            topicsCursor = page.nextCursor,
            topicsLoaded = true,
            votes = seeded + now.votes,
        )
    }

    /**
     * 评论 is the posts list without its openers, so a page can come back with
     * nothing to show; keep reading until it has something or the list ends,
     * or the end-of-list trigger would never fire again and the tab would stall.
     */
    private suspend fun fillComments(username: String) {
        repeat(COMMENT_FILL_PAGES) {
            val now = _state.value
            if (now.posts.any { it.postNumber > 1 } || now.postsCursor == null) return
            val page = activity.posts(username, now.postsCursor)
            appendPosts(page)
        }
    }

    private fun appendPosts(page: ApiUserPostPage) {
        val now = _state.value
        val known = now.posts.mapTo(HashSet()) { it.id }
        _state.value = now.copy(
            posts = now.posts + page.posts.filter { it.id !in known },
            // A page that brought nothing new is the end, whatever the cursor says.
            postsCursor = page.nextCursor?.takeIf { page.posts.any { p -> p.id !in known } },
        )
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
        if (tab.isLocal || tab == ActivityTab.Points || tab.isPrivateFor(current.isOwn)) return
        if (current.isLoadingTab || current.isLoadingMoreTab) return
        if (!current.currentTabHasMore) return
        val username = current.username.ifEmpty { return }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMoreTab = true)
            runCatchingCancellable {
                when (tab) {
                    ActivityTab.Topics -> applyTopics(activity.topics(username, current.topicsCursor), append = true)
                    ActivityTab.Overview -> appendPosts(activity.posts(username, current.postsCursor))
                    ActivityTab.Comments -> {
                        val before = current.posts.count { it.postNumber > 1 }
                        repeat(COMMENT_FILL_PAGES) {
                            val cursor = _state.value.postsCursor ?: return@runCatchingCancellable
                            appendPosts(activity.posts(username, cursor))
                            if (_state.value.posts.count { it.postNumber > 1 } > before) return@runCatchingCancellable
                        }
                    }
                    ActivityTab.Bookmarks -> {
                        // Bookmarks page; the user lists cursor. Same idea, two dialects.
                        val response = client.bookmarks(username, current.bookmarks.size / ProfileActivityApi.PAGE)
                        val rows = response.userBookmarkList.bookmarks.map { it.asActivity() }
                        _state.value = _state.value.copy(
                            bookmarks = _state.value.bookmarks + rows,
                            bookmarksHasMore = response.userBookmarkList.moreBookmarksUrl != null && rows.isNotEmpty(),
                        )
                    }
                    else -> Unit
                }
            }
            // Failing to extend a list is quiet: what is on screen stays true,
            // and the next scroll to the end asks again.
            _state.value = _state.value.copy(isLoadingMoreTab = false)
        }
    }

    /**
     * A vote from a topic row, optimistic. Cast as from a list: the server does
     * not count list votes towards the author's reputation, the same as the feed.
     */
    fun vote(topic: ApiUserTopicRow, tapped: VoteDirection) {
        val postId = topic.firstPostId ?: return
        if (session.username == null) return
        val previous = _state.value.votes[topic.id] ?: TopicVote(voteOf(topic.viewerVote), topic.voteScore)
        val next = previous.direction.after(tapped)
        val optimistic = TopicVote(next, previous.score + previous.direction.stepTo(next))
        _state.value = _state.value.copy(votes = _state.value.votes + (topic.id to optimistic))
        viewModelScope.launch {
            runCatchingCancellable { client.castVote(postId, next, fromFeed = true) }
                .onSuccess { result ->
                    _state.value = _state.value.copy(
                        votes = _state.value.votes + (topic.id to TopicVote(VoteDirection.entries.firstOrNull { it.wire == result.voteDirection } ?: next, result.voteScore ?: optimistic.score)),
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(votes = _state.value.votes + (topic.id to previous))
                    ToastCenter.showError(it)
                }
        }
    }

    /**
     * Save or unsave a post, optimistic. The lists do not say whether the
     * reader has already saved a row, so a row starts unsaved unless it is on
     * the 收藏 tab — saving it again is harmless, the server keeps one.
     */
    fun toggleSave(postId: Int, currentlySaved: Boolean) {
        val target = !currentlySaved
        _state.value = _state.value.copy(saved = _state.value.saved + (postId to target))
        viewModelScope.launch {
            runCatchingCancellable { if (target) client.bookmark(postId) else client.removeBookmark(postId) }
                .onSuccess { ToastCenter.show(if (target) R.string.me3_saved_toast else R.string.me3_unsaved_toast) }
                .onFailure {
                    _state.value = _state.value.copy(saved = _state.value.saved + (postId to currentlySaved))
                    ToastCenter.showError(it)
                }
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

    /**
     * Someone else's energy balance.
     *
     * The API has no public source for it: `/api/v1/users/{name}` leaves it
     * out, and the energy board only reaches those near the top. The website
     * shows it to anyone on the user hover card (`/u/{name}/card`, an HTML
     * fragment), so the number is read from there: the `hc-energy` element,
     * digits only, since the separator is the page's locale's. Null if the
     * card changes shape; the figure then shows a dash as it did before.
     * Once the API carries the balance this should read that instead.
     *
     * Asked for as HTML, as a browser asks: a JSON-only request to a website
     * path is how the server recognises the retired Discourse-era app, and it
     * answers that with 410 "app_outdated" (backend internal/web/oldapp.go).
     */
    private suspend fun cardEnergy(username: String): Long? {
        val html = runCatchingCancellable {
            val api = client.api
            val path = "u/${java.net.URLEncoder.encode(username, "UTF-8").replace("+", "%20")}/card"
            api.perform(api.requestBuilder(api.url(path)).header("Accept", "text/html").get().build(), NodelocApi.Channel.Read)
        }.getOrNull() ?: return null
        val raw = CARD_ENERGY.find(html)?.groupValues?.get(1) ?: return null
        val negative = raw.trimStart().startsWith("-") || raw.trimStart().startsWith("−")
        val digits = raw.filter { it in '0'..'9' }.takeIf { it.isNotEmpty() } ?: return null
        return digits.toLongOrNull()?.let { if (negative) -it else it }
    }

    private companion object {
        /** How far 评论 reads ahead past openers before it gives up on one page. */
        const val COMMENT_FILL_PAGES = 3

        val CARD_ENERGY = Regex("""class="hc-energy"[^>]*>([^<]*)<""")
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
