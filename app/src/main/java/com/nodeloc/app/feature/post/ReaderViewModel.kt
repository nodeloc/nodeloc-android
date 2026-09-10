package com.nodeloc.app.feature.post

import com.nodeloc.app.core.util.sha1Hex
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.core.util.rethrowIfCancellation
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.R
import com.nodeloc.app.feature.compose.ComposerBlock
import com.nodeloc.app.feature.compose.toMarkdown
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.html.PostContent
import com.nodeloc.app.core.html.PostHtmlParser
import com.nodeloc.app.core.model.PostComment
import com.nodeloc.app.core.model.PostLottery
import com.nodeloc.app.core.model.PostPoll
import com.nodeloc.app.core.model.PostReaction
import com.nodeloc.app.core.model.PostReward
import com.nodeloc.app.core.model.ReplySort
import com.nodeloc.app.core.model.TopicPost
import com.nodeloc.app.core.model.TopicResponse
import com.nodeloc.app.core.model.TopicRedEnvelope
import com.nodeloc.app.core.model.UserProfileTarget
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.DiscourseFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderState(
    val topicId: Int = 0,
    val title: String = "",
    val categoryId: Int? = null,
    val nodeName: String = "",
    val nodeSlug: String = "",
    val nodeLogoUrl: String? = null,
    /** A private message has no node; the header names the conversation instead. */
    val isPrivateMessage: Boolean = false,
    val pmHandle: String = "",
    val pmName: String = "",
    val pmAvatarUrl: String? = null,
    val createdAt: String = "",
    /** Nobody may reply to a closed topic; staff can reopen it. */
    val closed: Boolean = false,
    val content: PostContent = PostContent.Empty,
    val comments: List<PostComment> = emptyList(),
    /**
     * Whose replies to leave out.
     *
     * The server does filter these — `TopicView` drops an ignored user's posts
     * with `posts.post_number != 1` — but only on the next fetch, and this
     * screen is looked at while the block is being made. Filtered here rather
     * than at the list, because every jump and every collapse counts indices
     * into [visibleComments] and would land on the wrong reply otherwise.
     */
    val blocked: Set<String> = emptySet(),
    val firstPostId: Int? = null,
    /**
     * The opening post is not a ReplyRow, so its actions live in the topic
     * menu instead. Same guardian answer, different place to put it.
     */
    val firstPostCanEdit: Boolean = false,
    val firstAuthor: UserProfileTarget? = null,
    val firstAuthorTitle: String? = null,
    /** The tail on the opening post, when its author chose to show one. */
    val firstPostSource: String? = null,
    val firstAuthorFlairUrl: String? = null,
    val firstPostLiked: Boolean = false,
    /** Whether the opening post is already bookmarked; drives the menu label. */
    val firstPostBookmarked: Boolean = false,
    val firstPostLikeCount: Int = 0,
    /**
     * discourse-vote on the opening post. Null where voting is off, which is
     * what the action bar reads to choose between a heart and two arrows.
     */
    val firstPostVoteScore: Int? = null,
    val firstPostVoteDirection: VoteDirection = VoteDirection.None,
    val firstPostCanVoteDown: Boolean = false,
    /**
     * The faces the opening post carries, ordered by count, and how many people
     * put one there. The arrows net these into a score; this is what was
     * actually said.
     */
    val firstPostReactions: List<PostReaction> = emptyList(),
    val firstPostReactionCount: Int = 0,
    /**
     * Where discourse-vote folds a post, straight from the site. Null means
     * nothing folds — see [com.nodeloc.app.core.model.isVoteCollapsed].
     */
    val voteCollapseThreshold: Int? = null,
    /**
     * Posts the reader has chosen to see past the fold, this reading only.
     * Folding states a score, not a preference, so it is not persisted.
     */
    val expandedLowScore: Set<Int> = emptySet(),
    val firstPostRewards: List<PostReward> = emptyList(),
    val polls: List<PostPoll> = emptyList(),
    val myPollVotes: Map<String, List<String>> = emptyMap(),
    val lottery: PostLottery? = null,
    val redEnvelope: TopicRedEnvelope? = null,
    val totalReplyCount: Int = 0,
    val replySort: ReplySort = ReplySort.Old,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isSubmitting: Boolean = false,
    /** Attachments staged for the reply being written. */
    val replyMedia: List<ComposerBlock.Media> = emptyList(),
    val isUploadingReplyMedia: Boolean = false,
    /** How far the current video upload has got, or null when none is. */
    val replyUploadProgress: Float? = null,
    val hasMoreComments: Boolean = false,
    val loadingChildren: Set<Int> = emptySet(),
    val collapsed: Set<Int> = emptySet(),
    val error: Throwable? = null,
    /** The thread loaded but its replies did not — distinct from having none. */
    val repliesFailed: Boolean = false,
) {
    /** Nothing arrived and the load is over — the screen must say so. */
    val failedToLoad: Boolean
        get() = error != null && !isLoading && content.isEmpty && comments.isEmpty() && title.isEmpty()

    /**
     * Rows actually drawn: a collapsed reply hides its whole subtree, and the
     * flattened list makes that a filter rather than a tree rewrite.
     */
    val visibleComments: List<PostComment>
        get() {
            val shown =
                if (blocked.isEmpty()) comments else comments.filterNot { it.author in blocked }
            if (collapsed.isEmpty()) return shown
            val result = ArrayList<PostComment>(shown.size)
            var hideBelowDepth: Int? = null
            for (comment in shown) {
                val hidden = hideBelowDepth
                if (hidden != null) {
                    if (comment.nestingDepth > hidden) continue
                    hideBelowDepth = null
                }
                result += comment
                if (comment.postNumber in collapsed) hideBelowDepth = comment.nestingDepth
            }
            return result
        }
}

/**
 * The reader: topic body, the server's nested reply tree, plugin payloads, and
 * every write action available from the page.
 *
 * The tree is flattened once into rows carrying their own rail geometry, so the
 * screen renders as a flat LazyColumn. Nesting composables per level would put
 * the whole thread in one measure pass — the shape that froze the iOS build.
 */
class ReaderViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val site = services.siteRepository

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    init {
        // Blocking from a reply's own menu should take the reply with it, and
        // the only thing that changes at that moment is this set.
        viewModelScope.launch {
            services.blockedUsers.usernames.collect { names ->
                if (names != _state.value.blocked) _state.value = _state.value.copy(blocked = names)
            }
        }
    }

    private var loadedId: Int? = null
    private var nestedRoots: List<TopicPost> = emptyList()
    private var nestedPage = 0

    /**
     * Only the first page carries these, and only when there are any — so they
     * are kept rather than re-read, or loading page two would un-pin everything
     * the reader is looking at.
     */
    private var pinnedPostIds: Set<Int> = emptySet()

    /** Next children page per parent post number. */
    private val childPages = mutableMapOf<Int, Int>()

    private val tracker = ReadTracker(client)

    /** Serialises whole-thread loads so a sort change cannot race a refetch. */
    private var loadJob: Job? = null

    /**
     * True while a whole-thread load is running, initial or refetch.
     *
     * Distinct from `isLoading`, which drives the skeleton and is deliberately
     * left alone during a refetch so the thread stays on screen. Pagination
     * still has to stand off, or a load-more races the reset and one of the two
     * pages is lost.
     */
    private var loadInFlight = false

    fun bind(topicId: Int) {
        if (loadedId == topicId) return
        load(topicId)
    }

    fun load(topicId: Int) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // A different topic: nothing of the old one should survive.
            _state.value = ReaderState(topicId = topicId, isLoading = true, replySort = _state.value.replySort)
            fetch(topicId)
        }
    }

    /**
     * Refetches the thread in place.
     *
     * Suspends, and leaves what is on screen alone while it runs. Posting a
     * reply used to call [load], which rebuilt the state as empty-and-loading:
     * the reader flashed back to a skeleton, and the caller's "scroll to the
     * reply I just wrote" looked for it in a list that had just been cleared,
     * so it never once worked.
     */
    private suspend fun reload(): Boolean {
        val topicId = _state.value.topicId.takeIf { it != 0 } ?: return false
        // Through the same job as every other whole-thread load. Running
        // outside it meant a sort change could not cancel a refetch, and the
        // refetch would then land on top of the new sort and revert it.
        loadJob?.cancelAndJoin()
        _state.value = _state.value.copy(error = null)
        val job = viewModelScope.launch { fetch(topicId) }
        loadJob = job
        job.join()
        return _state.value.error == null
    }

    private suspend fun fetch(topicId: Int) {
        loadInFlight = true
        try {
            try {
                val topic = client.topic(topicId)
                val posts = topic.postStream.posts
                streamPosts = posts
                contentRestricted = !topic.canReadTopic
                val first = posts.firstOrNull()
                parseContents(posts)
                site.siteResponse()
                val category = site.category(topic.categoryId)
                val isPm = topic.archetype == "private_message"
                // Who the conversation is *with*: the one other person, or the
                // group it was addressed to. The subject line is the name only
                // when neither of those exists.
                val others = topic.details?.allowedUsers.orEmpty()
                    .filterNot { it.username.equals(services.session.username, ignoreCase = true) }
                val group = topic.details?.allowedGroups.orEmpty().firstOrNull()?.name
                val counterpart = others.singleOrNull()

                _state.value = _state.value.copy(
                    title = DiscourseFormat.displayTitle(
                        topic.fancyTitle,
                        topic.fancyTitleLocalized,
                        topic.title,
                    ),
                    categoryId = topic.categoryId,
                    nodeName = category?.name.orEmpty(),
                    nodeSlug = category?.slug.orEmpty(),
                    nodeLogoUrl = DiscourseConfig.absoluteUrl(category?.uploadedLogo?.url),
                    isPrivateMessage = isPm,
                    pmHandle = when {
                        counterpart != null -> "u/${counterpart.username}"
                        group != null -> "@$group"
                        else -> ""
                    },
                    pmName = counterpart?.name?.takeIf { it.isNotBlank() }
                        ?: counterpart?.username
                        ?: topic.title,
                    pmAvatarUrl = DiscourseConfig.avatarUrl(counterpart?.avatarTemplate, 80),
                    createdAt = DiscourseFormat.relative(first?.createdAt ?: topic.createdAt),
                    closed = topic.closed,
                    content = first?.let { contentFor(it) } ?: PostContent.Empty,
                    firstPostId = first?.id,
                    firstPostCanEdit = first?.canEdit == true,
                    firstAuthor = first?.let {
                        UserProfileTarget(it.username, it.name, DiscourseConfig.avatarUrl(it.avatarTemplate))
                    },
                    firstAuthorTitle = first?.userTitle,
                    firstPostSource = first?.mobileSource,
                    firstAuthorFlairUrl = flairImageUrl(first?.flairUrl),
                    firstPostLiked = first?.likedByMe == true,
                    firstPostBookmarked = first?.bookmarked == true,
                    firstPostLikeCount = first?.likeCount ?: 0,
                    firstPostVoteScore = first?.voteScore,
                    firstPostVoteDirection = VoteDirection.from(first?.voteDirection),
                    firstPostCanVoteDown = first?.canVoteDown == true,
                    firstPostReactions = first?.reactions.orEmpty(),
                    firstPostReactionCount = first?.reactionUsersCount ?: 0,
                    voteCollapseThreshold = site.voteCollapseThreshold,
                    firstPostRewards = first?.rewards.orEmpty(),
                    polls = first?.polls.orEmpty(),
                    myPollVotes = first?.pollsVotes.orEmpty(),
                    lottery = first?.lottery,
                    redEnvelope = topic.redEnvelope,
                    totalReplyCount = ((topic.postsCount ?: 1) - 1).coerceAtLeast(0),
                )
                loadedId = topicId
                tracker.begin(topicId)
                // A conversation is read by opening it, the way a messenger
                // works — the reader is not going to scroll a two-line reply
                // into view to earn it. The inbox row flips locally too, since
                // arriving from a notification never told it anything.
                if (isPm) markConversationRead(topic)
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _state.value = _state.value.copy(error = error, isLoading = false)
                // Nothing to hang replies off, and a heartbeat for a topic that
                // did not load is a request per tick to a 404.
                return
            }
            loadNested(reset = true)
            _state.value = _state.value.copy(isLoading = false)
            startHeartbeat()
        } finally {
            loadInFlight = false
        }
    }

    // ------------------------------------------------------------ replies

    /**
     * The topic's own post stream, kept as the fallback for replies.
     *
     * The threaded view lives behind nodeloc's `n/{slug}/…` route, which is
     * scoped to a node — a private message has none, so that call comes back
     * empty and the conversation looks like it has no replies at all. The
     * stream is flat rather than threaded, which for a two-person message is
     * what it is anyway.
     */
    private var streamPosts: List<TopicPost> = emptyList()

    /**
     * discourse-read-permission has locked this topic above the reader's trust
     * level. The topic call still answers — with a notice in place of the
     * posts — but `n/{slug}/{id}`, `t/{id}/posts` and the rest all answer 403.
     */
    private var contentRestricted = false

    private suspend fun loadNested(reset: Boolean) {
        val topicId = _state.value.topicId.takeIf { it != 0 } ?: return
        // Nothing to ask for: the threaded tree is one of the endpoints the
        // plugin refuses, and its refusal is a 403 the whole app used to read
        // as a dead session. What the server *does* serve is the notice, and
        // that arrived with the topic.
        if (contentRestricted) {
            if (reset) fallBackToStream()
            return
        }
        if (reset) {
            childPages.clear()
        } else {
            _state.value = _state.value.copy(isLoadingMore = true)
        }
        // Held until the response lands: advancing first meant one failed
        // load-more skipped that page of replies permanently.
        val page = if (reset) 0 else nestedPage + 1
        try {
            val response = client.nestedTopic(topicId, sort = _state.value.replySort.key, page = page)
            val roots = response.roots.orEmpty()
            if (reset && roots.isEmpty() && streamPosts.size > 1) {
                fallBackToStream()
                return
            }
            nestedPage = page
            reportedLoadMoreFailure = false
            _state.value = _state.value.copy(repliesFailed = false)
            // A fresh fetch carries the server's own view of every like, so the
            // local overrides have done their job and would now fight it.
            if (reset) {
                likeOverrides.clear()
                voteOverrides.clear()
                pollVoteOverrides.clear()
                pollOverrides.clear()
                exhaustedChildren.clear()
            }
            if (reset) pinnedPostIds = response.pinnedPostIds.toSet()
            nestedRoots = if (reset) roots else nestedRoots + roots
            parseContents(flatten(nestedRoots))
            _state.value = _state.value.copy(
                comments = buildComments(nestedRoots),
                hasMoreComments = response.hasMoreRoots?.value == true,
                isLoadingMore = false,
            )
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            if (reset) {
                nestedRoots = emptyList()
                if (streamPosts.size > 1) {
                    fallBackToStream()
                    return
                }
                // The thread loaded and its replies did not: without this the
                // action bar says "N comments" over an empty space, and there
                // is nothing to retry from.
                _state.value = _state.value.copy(
                    comments = emptyList(),
                    isLoadingMore = false,
                    repliesFailed = true,
                )
            } else {
                // hasMoreComments stays true so scrolling back down retries —
                // but the sentinel re-fires on every bounce off the bottom, and
                // offline that would be a toast per bounce. Said once, until
                // something succeeds.
                _state.value = _state.value.copy(isLoadingMore = false)
                if (!reportedLoadMoreFailure) {
                    reportedLoadMoreFailure = true
                    ToastCenter.showError(error)
                }
            }
        }
    }

    private suspend fun markConversationRead(topic: TopicResponse) {
        services.messageCenter.markConversationRead(topic.id)
        val highest = topic.highestPostNumber ?: topic.postStream.posts.lastOrNull()?.postNumber ?: return
        runCatchingCancellable {
            client.sendTopicTimings(topic.id, READ_MS, (1..highest).associateWith { READ_MS })
        }
    }

    /** Flat replies from the post stream, for a topic the threaded route cannot serve. */
    private suspend fun fallBackToStream() {
        val replies = streamPosts.drop(1)
        // This is a reset like any other: it replaces the whole reply list, so
        // the per-load bookkeeping goes with it. Leaving `repliesFailed` set
        // put the failure banner above a populated list, and every row below it
        // was then off by one for read timing and every scroll-to-post.
        likeOverrides.clear()
        voteOverrides.clear()
        pollVoteOverrides.clear()
        pollOverrides.clear()
        exhaustedChildren.clear()
        _state.value = _state.value.copy(repliesFailed = false)
        nestedRoots = replies
        parseContents(replies)
        _state.value = _state.value.copy(
            comments = buildComments(replies),
            hasMoreComments = false,
            isLoadingMore = false,
        )
    }

    /** The replies alone, for when the thread loaded and they did not. */
    fun retryReplies() {
        if (_state.value.topicId == 0) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                repliesFailed = false,
                hasMoreComments = false,
                isLoading = true,
                error = null,
            )
            loadNested(reset = true)
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    fun loadMoreComments() {
        val current = _state.value
        // isLoading too: a sort change empties the list, which puts the
        // end-of-list sentinel on screen and fired a load-more straight into
        // the reset that was still running — whichever answered second won,
        // and a page of replies vanished.
        if (!current.hasMoreComments || current.isLoadingMore || loadInFlight) return
        viewModelScope.launch { loadNested(reset = false) }
    }

    /** Fetches the next page of direct replies under one post, splicing them in. */
    fun loadMoreChildren(parentPostNumber: Int) {
        val topicId = _state.value.topicId
        if (parentPostNumber in _state.value.loadingChildren) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingChildren = _state.value.loadingChildren + parentPostNumber)
            val page = childPages[parentPostNumber] ?: 0
            runCatchingCancellable {
                client.nestedChildren(topicId, parentPostNumber, sort = _state.value.replySort.key, page = page)
            }.onSuccess { response ->
                childPages[parentPostNumber] = page + 1
                val fetched = response.children.orEmpty()
                if (fetched.isNotEmpty()) {
                    nestedRoots = appendChildren(nestedRoots, parentPostNumber, fetched)
                    parseContents(flatten(fetched))
                    _state.value = _state.value.copy(comments = buildComments(nestedRoots))
                } else {
                    // The count said there were more and the page came back
                    // empty. Left alone, the row stays and every further tap
                    // fetches another empty page.
                    exhaustedChildren += parentPostNumber
                    _state.value = _state.value.copy(comments = buildComments(nestedRoots))
                }
            }.onFailure {
                // A tap that does nothing at all is the one thing this must
                // not be.
                ToastCenter.showError(it)
            }
            _state.value = _state.value.copy(loadingChildren = _state.value.loadingChildren - parentPostNumber)
        }
    }

    fun applySort(sort: ReplySort) {
        if (sort == _state.value.replySort) return
        _state.value = _state.value.copy(
            replySort = sort,
            comments = emptyList(),
            // Cleared with the list it describes, or the sentinel that appears
            // in the empty list asks for the next page of the old sort.
            hasMoreComments = false,
            isLoading = true,
        )
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadNested(reset = true)
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    /** Collapsing hides the reply's entire subtree; page-local state. */
    fun toggleCollapsed(postNumber: Int) {
        val collapsed = _state.value.collapsed
        _state.value = _state.value.copy(
            collapsed = if (postNumber in collapsed) collapsed - postNumber else collapsed + postNumber,
        )
    }

    /** Dedupes by id so a page overlapping the inlined preview doesn't double up. */
    private fun appendChildren(
        posts: List<TopicPost>,
        parentNumber: Int,
        newKids: List<TopicPost>,
    ): List<TopicPost> = posts.map { post ->
        when {
            post.postNumber == parentNumber -> {
                val existing = post.children.orEmpty().mapTo(mutableSetOf()) { it.id }
                post.copy(children = post.children.orEmpty() + newKids.filterNot { it.id in existing })
            }
            !post.children.isNullOrEmpty() ->
                post.copy(children = appendChildren(post.children, parentNumber, newKids))
            else -> post
        }
    }

    /**
     * DFS-flattens the server tree into rows using each post's own `children`
     * (already server-sorted) rather than re-inferring nesting from
     * `reply_to_post_number`.
     */
    /**
     * Likes the reader has cast, kept apart from the server's copy.
     *
     * `comments` is derived from `nestedRoots`, so anything written straight
     * into it — an optimistic like — disappeared the next time the list was
     * rebuilt, which loading more replies does. Consulted during the rebuild
     * instead, so the flip survives and a later rollback still lands on the
     * right row.
     */
    private val likeOverrides = mutableMapOf<Int, Boolean>()

    // Same shape as likeOverrides, plus the id the DELETE needs: creating a
    // bookmark is what hands that back, so a post bookmarked in this session
    // can be un-bookmarked without refetching the topic.
    private val bookmarkOverrides = mutableMapOf<Int, Boolean>()
    private val bookmarkIds = mutableMapOf<Int, Int>()

    /** So a repeatedly-failing tail says so once rather than on every bounce. */
    private var reportedLoadMoreFailure = false

    /**
     * Votes cast on a reply's poll, kept beside the server's copy for the same
     * reason [likeOverrides] is: `comments` is rebuilt from `nestedRoots`, so
     * anything written straight into it is lost on the next rebuild.
     */
    private val pollVoteOverrides = mutableMapOf<Pair<Int, String>, List<String>>()

    /**
     * The server's recount after voting on a reply's poll.
     *
     * `replacePoll` only rewrites the first post's polls, so without this the
     * checkmark moved but the bars and the voter count stayed where they were
     * until a full reload.
     */
    private val pollOverrides = mutableMapOf<Pair<Int, String>, PostPoll>()

    private fun recordReplyPoll(postId: Int, pollName: String, poll: PostPoll) {
        pollOverrides[postId to pollName] = poll
        _state.value = _state.value.copy(comments = buildComments(nestedRoots))
    }

    /** One place both the optimistic write and its rollback go through. */
    private fun applyVote(postId: Int, pollName: String, options: List<String>?, isReply: Boolean) {
        if (isReply) {
            if (options == null) pollVoteOverrides.remove(postId to pollName)
            else pollVoteOverrides[postId to pollName] = options
            _state.value = _state.value.copy(comments = buildComments(nestedRoots))
        } else {
            _state.value = _state.value.copy(
                myPollVotes = _state.value.myPollVotes + (pollName to options.orEmpty()),
            )
        }
    }

    /** Parents whose "more replies" the server has already run out of. */
    private val exhaustedChildren = mutableSetOf<Int>()

    private fun likeDelta(post: TopicPost): Int {
        val override = likeOverrides[post.id] ?: return 0
        if (override == post.likedByMe) return 0
        return if (override) 1 else -1
    }

    /**
     * The ballot this reader has cast on a post since the thread was loaded.
     *
     * The score arrives whole from the server now — a vote is a reaction, and
     * which faces count against a post is a setting no client is sent — so
     * there is nothing here to derive it from. What is kept instead is the
     * *answer*: score and direction together, either the optimistic pair or the
     * server's own once it replies.
     */
    private val voteOverrides = mutableMapOf<Int, Ballot>()

    private data class Ballot(val score: Int, val direction: VoteDirection)

    private fun buildComments(roots: List<TopicPost>): List<PostComment> {
        val result = mutableListOf<PostComment>()

        fun append(post: TopicPost, parent: TopicPost?, depth: Int, groupId: Int) {
            val kids = post.children.orEmpty()
            result += PostComment(
                id = post.id,
                author = post.username,
                authorName = post.name,
                time = DiscourseFormat.relative(post.createdAt),
                source = post.mobileSource,
                content = contentFor(post),
                // Only on the post that claimed it; see RedEnvelopeClaim.
                redEnvelopeClaim = post.redEnvelopeClaim?.takeIf { it.postId == post.id },
                votes = post.likeCount + likeDelta(post),
                postNumber = post.postNumber ?: 0,
                pinned = post.id in pinnedPostIds,
                canEdit = post.canEdit,
                canDelete = post.canDelete,
                canRecover = post.canRecover,
                deleted = post.deletedAt != null,
                locked = post.locked,
                replyToPostNumber = post.replyToPostNumber,
                parentAuthor = parent?.takeIf { it.postNumber != 1 }?.username,
                parentText = parent?.takeIf { it.postNumber != 1 }
                    ?.let { contentFor(it).excerpt(120) }?.takeIf { it.isNotEmpty() },
                avatarUrl = DiscourseConfig.avatarUrl(post.avatarTemplate, 80),
                // 8 levels of indent is where the rails stop biting into the
                // text column on a phone.
                nestingDepth = minOf(depth, 8),

                hasChildren = kids.isNotEmpty(),
                groupId = groupId,
                authorTitle = post.userTitle,
                flairUrl = flairImageUrl(post.flairUrl),
                flairName = post.flairName,
                flairBgColor = post.flairBgColor,
                flairColor = post.flairColor,
                polls = post.polls.orEmpty().map { poll ->
                    pollOverrides[post.id to poll.pollName] ?: poll
                },
                myPollVotes = post.pollsVotes.orEmpty() +
                    pollVoteOverrides.filterKeys { it.first == post.id }.mapKeys { it.key.second },
                isLiked = likeOverrides[post.id] ?: post.likedByMe,
                isBookmarked = bookmarkOverrides[post.id] ?: (post.bookmarked == true),
                rewards = post.rewards.orEmpty(),
                voteScore = post.voteScore?.let { voteOverrides[post.id]?.score ?: it },
                voteDirection = voteOverrides[post.id]?.direction
                    ?: VoteDirection.from(post.voteDirection),
                canVoteDown = post.canVoteDown,
                reactions = post.reactions.orEmpty(),
                reactionCount = post.reactionUsersCount ?: 0,
            )

            kids.forEach { kid -> append(kid, post, depth + 1, groupId) }

            // Actionable only while direct replies remain unloaded; the count
            // shown is the whole subtree, matching the web's "N 条回复".
            val directRemaining = (post.directReplyCount ?: 0) - kids.size
            val subtreeRemaining = (post.totalDescendantCount ?: post.directReplyCount ?: 0) - subtreeCount(kids)
            val parentNumber = post.postNumber
            if (directRemaining > 0 && subtreeRemaining > 0 &&
                parentNumber != null && parentNumber !in exhaustedChildren
            ) {
                result += PostComment(
                    id = -post.id,
                    author = "",
                    time = "",
                    content = PostContent.Empty,
                    votes = 0,
                    nestingDepth = minOf(depth + 1, 8),
                    groupId = groupId,
                    isLoadMore = true,
                    loadMoreParent = parentNumber,
                    loadMoreRemaining = subtreeRemaining,
                )
            }
        }

        roots.forEach { root -> append(root, null, 0, root.postNumber ?: root.id) }
        return result
    }

    private fun subtreeCount(posts: List<TopicPost>): Int =
        posts.sumOf { 1 + subtreeCount(it.children.orEmpty()) }

    private fun flatten(posts: List<TopicPost>): List<TopicPost> =
        posts.flatMap { listOf(it) + flatten(it.children.orEmpty()) }

    // ---------------------------------------------------------- parsing

    private fun cacheKey(post: TopicPost) = "${post.id}-${post.version ?: 0}"

    private fun contentFor(post: TopicPost): PostContent =
        ParsedContentCache.get(cacheKey(post)) ?: PostHtmlParser.parseSync(post.cooked)
            .also { ParsedContentCache.put(cacheKey(post), it) }

    /**
     * Parses every body in one concurrent pass so the row builder stays
     * synchronous. Default dispatcher: this is CPU work, not I/O.
     */
    private suspend fun parseContents(posts: List<TopicPost>) {
        val pending = posts.filter { ParsedContentCache.get(cacheKey(it)) == null }
        if (pending.isEmpty()) return
        withContext(Dispatchers.Default) {
            pending.map { post ->
                async { cacheKey(post) to PostHtmlParser.parseSync(post.cooked) }
            }.awaitAll()
        }.forEach { (key, content) -> ParsedContentCache.put(key, content) }
    }

    /**
     * Discourse's `flair_url` is either an image path or a bare Font Awesome
     * icon name (e.g. "gem"). Feeding an icon name to the image loader fires a
     * junk request per render, so only a real path counts as an image.
     */
    private fun flairImageUrl(raw: String?): String? =
        raw?.takeIf { it.startsWith("/") || it.startsWith("http") }?.let(DiscourseConfig::absoluteUrl)

    // ---------------------------------------------------------- actions

    /**
     * discourse-vote on the opening post.
     *
     * The tap says which arrow was pressed; what is sent is the direction to
     * end up in, so pressing the arrow you are already on takes the vote back
     * and a replayed request cannot drift from the server.
     *
     * The score is moved by a step and then corrected from the answer, rather
     * than recomputed: a vote is a reaction now, and which faces count against
     * a post is a setting the app is never sent.
     */
    fun voteOnFirstPost(tapped: VoteDirection, face: String? = null) {
        val id = _state.value.firstPostId ?: return
        if (!client.auth.isAuthenticated) return
        val before = _state.value
        val score = before.firstPostVoteScore ?: return
        // Picking a face always casts that direction; only the bare arrow
        // toggles a vote back off.
        val next = if (face != null) tapped else before.firstPostVoteDirection.after(tapped)
        if (next == VoteDirection.Down && !before.firstPostCanVoteDown) {
            ToastCenter.show(R.string.vote_down_not_allowed)
            return
        }

        _state.value = before.copy(
            firstPostVoteDirection = next,
            firstPostVoteScore = score + before.firstPostVoteDirection.stepTo(next),
        )
        viewModelScope.launch {
            runCatchingCancellable { client.castVote(id, next, face) }
                .onSuccess { result ->
                    _state.value = _state.value.copy(
                        firstPostVoteDirection = VoteDirection.from(result.voteDirection),
                        firstPostVoteScore = result.voteScore ?: _state.value.firstPostVoteScore,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        firstPostVoteDirection = before.firstPostVoteDirection,
                        firstPostVoteScore = score,
                    )
                    ToastCenter.showError(it)
                }
        }
    }

    /**
     * Shows a post the site folded for its score.
     *
     * One way only: the fold is a statement about the score, so putting it back
     * would be undoing a reading, not a setting. It returns on its own the next
     * time the thread is opened.
     */
    fun expandLowScore(postId: Int) {
        _state.value = _state.value.copy(
            expandedLowScore = _state.value.expandedLowScore + postId,
        )
    }

    /** Same ballot, on a reply; see [voteOnFirstPost]. */
    fun voteOnReply(id: Int, tapped: VoteDirection, face: String? = null) {
        if (!client.auth.isAuthenticated) return
        val current = _state.value.comments.firstOrNull { it.id == id } ?: return
        val score = current.voteScore ?: return
        val next = if (face != null) tapped else current.voteDirection.after(tapped)
        if (next == VoteDirection.Down && !current.canVoteDown) {
            ToastCenter.show(R.string.vote_down_not_allowed)
            return
        }
        val previous = voteOverrides[id]

        voteOverrides[id] = Ballot(score + current.voteDirection.stepTo(next), next)
        _state.value = _state.value.copy(comments = buildComments(nestedRoots))

        viewModelScope.launch {
            runCatchingCancellable { client.castVote(id, next, face) }
                .onSuccess { result ->
                    // The server's own answer. Worth taking over the guess: a
                    // rule may have moved the ballot somewhere the tap did not
                    // aim it, and a face the reader already held on this post
                    // moves the tally further than one step.
                    voteOverrides[id] = Ballot(
                        result.voteScore ?: (score + current.voteDirection.stepTo(next)),
                        VoteDirection.from(result.voteDirection),
                    )
                    _state.value = _state.value.copy(comments = buildComments(nestedRoots))
                }
                .onFailure {
                    if (previous == null) voteOverrides.remove(id) else voteOverrides[id] = previous
                    _state.value = _state.value.copy(comments = buildComments(nestedRoots))
                    ToastCenter.showError(it)
                }
        }
    }

    fun toggleFirstPostLike() {
        val id = _state.value.firstPostId ?: return
        if (!client.auth.isAuthenticated) return
        val wasLiked = _state.value.firstPostLiked
        _state.value = _state.value.copy(
            firstPostLiked = !wasLiked,
            firstPostLikeCount = _state.value.firstPostLikeCount + if (wasLiked) -1 else 1,
        )
        viewModelScope.launch {
            runCatchingCancellable { if (wasLiked) client.unlikePost(id) else client.likePost(id) }
                .onFailure {
                    _state.value = _state.value.copy(
                        firstPostLiked = wasLiked,
                        firstPostLikeCount = _state.value.firstPostLikeCount + if (wasLiked) 1 else -1,
                    )
                    ToastCenter.showError(it)
                }
        }
    }

    fun toggleReplyLike(id: Int) {
        if (!client.auth.isAuthenticated) return
        val current = _state.value.comments.firstOrNull { it.id == id } ?: return
        val wasLiked = current.isLiked

        likeOverrides[id] = !wasLiked
        _state.value = _state.value.copy(comments = buildComments(nestedRoots))

        viewModelScope.launch {
            runCatchingCancellable { if (wasLiked) client.unlikePost(id) else client.likePost(id) }
                .onFailure {
                    likeOverrides.remove(id)
                    _state.value = _state.value.copy(comments = buildComments(nestedRoots))
                    ToastCenter.showError(it)
                }
        }
    }

    /** Returns the new reply's post number so the reader can scroll to it. */
    /**
     * Uploads a reply attachment, showing it from the moment it is picked.
     *
     * Same shape as the composer: a pending block with a local preview goes in
     * straight away, and only its source changes when the server answers.
     */
    /**
     * Attaches one upload to the reply being written.
     *
     * A reply carries a single attachment — one picture, one clip or one GIF.
     * The sheet has room for exactly that, and a thread reads worse when every
     * answer is a gallery.
     */
    /**
     * A clip or a GIF from the trimmer, streamed off disk.
     *
     * Kept apart from [uploadReplyMedia], which takes bytes because a picture
     * genuinely is a handful of them. A minute of video is not, and reading it
     * into an array to hand to a request body — then again to hash it — is
     * three copies of something that never needed to be in memory once.
     */
    /** Abandon the clip going up; see the composer's note on the same. */
    fun cancelReplyUpload() {
        videoUpload?.cancel()
        videoUpload = null
    }

    private var videoUpload: kotlinx.coroutines.Job? = null

    fun uploadReplyVideo(
        media: com.nodeloc.app.feature.media.TrimmedMedia,
        source: android.net.Uri? = null,
        /** Re-editing the one already attached rather than attaching another. */
        replacing: Boolean = false,
    ) {
        if (!replacing && _state.value.replyMedia.isNotEmpty()) return
        val kind =
            if (media.mimeType == "image/gif") ComposerBlock.MediaKind.Gif
            else ComposerBlock.MediaKind.Video
        // A GIF is its own preview and already a file; a clip has a still.
        val preview = if (kind == ComposerBlock.MediaKind.Gif) {
            media.file.absolutePath
        } else {
            media.posterBytes?.let { cachePreview(it) }
        } ?: return
        val slot = ComposerBlock.Media(
            "", "", media.fileName, kind, preview,
            posterPath = preview,
            // Kept so the editor can be opened on the clip again, from the
            // whole of it and with what was decided last time still in place.
            sourceUri = source?.toString(),
            videoEdit = media.edit,
        )
        _state.value = _state.value.copy(
            replyMedia = listOf(slot),
            isUploadingReplyMedia = true,
            replyUploadProgress = 0f,
        )
        videoUpload = viewModelScope.launch {
            runCatchingCancellable {
                client.uploadMediaFile(media.file, media.fileName, media.mimeType) { sent ->
                    _state.value = _state.value.copy(replyUploadProgress = sent)
                }
            }
                .onSuccess { upload ->
                    val markdownUrl = upload.composerUrl
                    val posterUrl = media.posterBytes?.let { poster ->
                        val digest = withContext(Dispatchers.IO) { sha1Hex(media.file) }
                        runCatchingCancellable { client.uploadVideoPoster(poster, digest) }.getOrNull()?.url
                    }
                    _state.value = _state.value.copy(
                        replyMedia = _state.value.replyMedia.map {
                            if (it !== slot) it
                            else it.copy(
                                url = markdownUrl.orEmpty(),
                                displayUrl = when {
                                    kind == ComposerBlock.MediaKind.Video -> posterUrl.orEmpty()
                                    else -> upload.url ?: markdownUrl.orEmpty()
                                },
                                name = upload.displayFilename,
                                pendingPreview = null,
                            )
                        },
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(replyMedia = _state.value.replyMedia - slot)
                    ToastCenter.showError(it)
                }
            _state.value = _state.value.copy(isUploadingReplyMedia = false, replyUploadProgress = null)
            // The trimmer left it in the cache for us. A GIF's file is still
            // the preview on screen, so that one stays.
            if (kind != ComposerBlock.MediaKind.Gif) {
                withContext(Dispatchers.IO) { runCatching { media.file.delete() } }
            }
        }
    }

    fun uploadReplyMedia(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        kind: ComposerBlock.MediaKind = ComposerBlock.MediaKind.Image,
        posterBytes: ByteArray? = null,
        /** Re-editing the one already attached rather than attaching another. */
        replacing: Boolean = false,
    ) {
        if (!replacing && _state.value.replyMedia.isNotEmpty()) return
        // A clip is shown by its poster frame, here and in the post: the video
        // itself is not an image, so without one there is nothing to show.
        val preview = cachePreview(posterBytes ?: bytes) ?: return
        val slot = ComposerBlock.Media("", "", fileName, kind, preview, posterPath = preview)
        _state.value = _state.value.copy(
            replyMedia = listOf(slot),
            isUploadingReplyMedia = true,
        )
        viewModelScope.launch {
            runCatchingCancellable { client.uploadComposerMedia(bytes, fileName, mimeType) }
                .onSuccess { upload ->
                    val markdownUrl = upload.composerUrl
                    // Discourse links a poster to its video by filename alone
                    // (`original_filename LIKE '<video_sha1>.%'`); the URL it
                    // returns is also the only fetchable thumbnail we get.
                    val posterUrl = posterBytes?.let { poster ->
                        runCatchingCancellable { client.uploadVideoPoster(poster, sha1(bytes)) }.getOrNull()?.url
                    }
                    _state.value = _state.value.copy(
                        replyMedia = _state.value.replyMedia.map {
                            if (it !== slot) it
                            else it.copy(
                                url = markdownUrl.orEmpty(),
                                displayUrl = when {
                                    kind == ComposerBlock.MediaKind.Video -> posterUrl.orEmpty()
                                    else -> upload.url ?: markdownUrl.orEmpty()
                                },
                                name = upload.displayFilename,
                                pendingPreview = null,
                            )
                        },
                    )
                }
                .onFailure {
                    // A slot with nothing behind it promises an attachment the
                    // reply will not carry.
                    _state.value = _state.value.copy(replyMedia = _state.value.replyMedia - slot)
                    ToastCenter.showError(it)
                }
            _state.value = _state.value.copy(isUploadingReplyMedia = false)
        }
    }

    /**
     * The uploaded picture, back as a bitmap, so it can be edited again.
     *
     * The same trip the composer makes for the same reason: what was uploaded
     * is all that is left of it, the local copy having been the encoder's
     * output and nobody's to keep.
     */
    suspend fun loadBitmap(url: String): android.graphics.Bitmap? = runCatchingCancellable {
        val bytes = client.fetchBytes(url)
        withContext(Dispatchers.Default) {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?.let { com.nodeloc.app.feature.media.ImageEditRenderer.downscale(it) }
        }
    }.getOrNull()

    /**
     * A GIF is already hosted by Klipy; there is nothing to upload, so it goes
     * straight into the reply as its own URL.
     */
    fun attachReplyGif(url: String) {
        if (_state.value.replyMedia.isNotEmpty()) return
        _state.value = _state.value.copy(
            replyMedia = listOf(
                ComposerBlock.Media(
                    url = url,
                    displayUrl = url,
                    name = "gif",
                    kind = ComposerBlock.MediaKind.Gif,
                ),
            ),
        )
    }

    /** GIF search has no draft behind it, so the sheet keeps its own results. */
    suspend fun searchGifs(query: String): List<com.nodeloc.app.core.model.KlipyGif> =
        runCatchingCancellable { client.klipySearch(query) }.getOrNull()?.results.orEmpty()

    private fun sha1(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun removeReplyMedia(index: Int) {
        val media = _state.value.replyMedia.toMutableList()
        if (index !in media.indices) return
        media.removeAt(index)
        _state.value = _state.value.copy(replyMedia = media)
    }

    private fun cachePreview(bytes: ByteArray): String? = runCatchingCancellable {
        val dir = java.io.File(services.appContext.cacheDir, "reply").apply { mkdirs() }
        java.io.File(dir, "preview-${System.nanoTime()}.jpg").also { it.writeBytes(bytes) }.absolutePath
    }.getOrNull()

    /**
     * [onPosted] runs the instant the reply exists, before the thread is
     * refetched, and is where the composer closes.
     *
     * The two used to be one: the sheet stayed up for the refetch as well, and
     * because the button stops being a loader as soon as the reply lands, what
     * was left on screen for those two round trips was a live send button over
     * a sent reply — a second tap away from posting it twice.
     */
    suspend fun submitReply(
        raw: String,
        replyToPostNumber: Int? = null,
        onPosted: () -> Unit = {},
    ): Int? {
        // Attachments ride along as markdown paragraphs after the prose.
        val attachments = _state.value.replyMedia.filterNot { it.isPending }
        val trimmed = listOf(raw.trim())
            .plus(attachments.map { it.toMarkdown() })
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        if (trimmed.isEmpty() || !client.auth.isAuthenticated) return null
        val topicId = _state.value.topicId

        val created = try {
            _state.value = _state.value.copy(isSubmitting = true)
            client.reply(topicId, trimmed, replyToPostNumber)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            ToastCenter.showError(error)
            return null
        } finally {
            // Released as soon as the reply exists. Holding it across the
            // refetch left the sheet sitting on "Sending…" for two more round
            // trips after the reply had already been posted.
            _state.value = _state.value.copy(isSubmitting = false)
        }

        _state.value = _state.value.copy(replyMedia = emptyList())
        // Between the `finally` above and here nothing suspends, so the button
        // never draws a frame live while the sheet is still up.
        onPosted()
        // Awaited, so the caller can find the new reply in the list: the old
        // fire-and-forget reload returned before it existed.
        if (!reload()) {
            // The reply is posted and the thread could not be refetched, so the
            // list will not show it. Saying nothing would look like it vanished.
            _state.value.error?.let { ToastCenter.showError(it) }
        }
        return created.postNumber
    }

    /**
     * Refetches after the topic was rewritten somewhere else.
     *
     * Editing the opening post leaves this screen for the composer, so the
     * thread comes back holding the version that was replaced. See [PostEdits].
     */
    fun reloadAfterEdit() {
        viewModelScope.launch { reload() }
    }

    fun bookmarkFirstPost() {
        bookmarkPost(_state.value.firstPostId ?: return)
    }

    /**
     * Adds the bookmark, or takes it off again.
     *
     * It only ever added before, so a second tap came back as "you cannot
     * bookmark the same post twice" — and because nothing on screen showed the
     * state, that error was the only way to find out it was already saved.
     *
     * Discourse bookmarks a post, not a topic; a reply is as bookmarkable as
     * the first. Removal is keyed on the bookmark rather than the post, so the
     * id is either the one the topic arrived with or the one creating it just
     * handed back.
     */
    fun bookmarkPost(postId: Int) {
        val bookmarked = isBookmarked(postId)
        setBookmarked(postId, !bookmarked)
        viewModelScope.launch {
            if (bookmarked) {
                val id = bookmarkIds[postId]
                if (id == null) {
                    // Bookmarked, but the server never said which bookmark —
                    // an older topic payload. Nothing can be deleted without
                    // it, so put the flag back rather than claim it worked.
                    setBookmarked(postId, true)
                    return@launch
                }
                runCatchingCancellable { client.removeBookmark(id) }
                    .onSuccess {
                        bookmarkIds.remove(postId)
                        ToastCenter.show(R.string.reader_bookmark_removed)
                    }
                    .onFailure {
                        setBookmarked(postId, true)
                        ToastCenter.showError(it)
                    }
            } else {
                runCatchingCancellable { client.bookmark(postId) }
                    .onSuccess { created ->
                        created.id?.let { bookmarkIds[postId] = it }
                        ToastCenter.show(R.string.reader_bookmarked)
                    }
                    .onFailure {
                        setBookmarked(postId, false)
                        ToastCenter.showError(it)
                    }
            }
        }
    }

    private fun isBookmarked(postId: Int): Boolean =
        bookmarkOverrides[postId]
            ?: if (postId == _state.value.firstPostId) {
                _state.value.firstPostBookmarked
            } else {
                _state.value.comments.firstOrNull { it.id == postId }?.isBookmarked == true
            }

    /** The opening post is not a row, so its flag sits in state beside them. */
    private fun setBookmarked(postId: Int, value: Boolean) {
        bookmarkOverrides[postId] = value
        _state.value = _state.value.copy(
            firstPostBookmarked =
                if (postId == _state.value.firstPostId) value else _state.value.firstPostBookmarked,
            comments = _state.value.comments.map {
                if (it.id == postId) it.copy(isBookmarked = value) else it
            },
        )
    }

    /**
     * Pins a reply to the top of the thread, or takes the pin off.
     *
     * Not optimistic, unlike the likes and bookmarks around it: pinning moves
     * the reply to a different place in the list, and a row that jumps to the
     * top and then jumps back is worse than one that waits a beat. The server
     * answers with the new list, so the reload after it is showing what is,
     * not what was asked for.
     */
    fun togglePinned(postId: Int) {
        val topicId = _state.value.topicId.takeIf { it != 0 } ?: return
        viewModelScope.launch {
            runCatchingCancellable { client.toggleNestedPin(topicId, postId) }
                .onSuccess { response ->
                    pinnedPostIds = response.pinnedPostIds.toSet()
                    ToastCenter.show(
                        if (postId in pinnedPostIds) R.string.reader_pinned else R.string.reader_unpinned,
                    )
                    loadNested(reset = true)
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    /**
     * The source text of a post, for the editor.
     *
     * `cooked` is already on the screen and is HTML; an editor needs what was
     * typed, which only `GET /posts/:id` carries.
     */
    suspend fun postSource(postId: Int): String? =
        runCatchingCancellable { client.postDetail(postId).raw }
            .onFailure { ToastCenter.showError(it) }
            .getOrNull()

    /**
     * Saves an edit.
     *
     * No optimistic write: the server may refuse — past the edit window it
     * answers with `too_late_to_edit` rather than a bare rejection — and
     * showing the new text first would make a refusal look like it saved and
     * then reverted.
     */
    fun editPost(postId: Int, raw: String, onDone: () -> Unit) {
        // Anything attached while the sheet was open rides along as its own
        // paragraph, exactly as it would on a reply — it is the same slot, and
        // whichever of the two is sent first is the one that carries it.
        val attachments = _state.value.replyMedia.filterNot { it.isPending }
        val trimmed = listOf(raw.trim())
            .plus(attachments.map { it.toMarkdown() })
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSubmitting = true)
            runCatchingCancellable { client.updatePost(postId, trimmed) }
                .onSuccess {
                    _state.value = _state.value.copy(isSubmitting = false, replyMedia = emptyList())
                    ToastCenter.show(R.string.reader_edit_saved)
                    onDone()
                    reload()
                }
                // The server's own words. It knows which of a dozen rules
                // refused this and the client does not. The sheet stays open on
                // what was written, with its attachment still staged.
                .onFailure {
                    _state.value = _state.value.copy(isSubmitting = false)
                    ToastCenter.showError(it)
                }
        }
    }

    fun deletePost(postId: Int) {
        viewModelScope.launch {
            runCatchingCancellable { client.deletePost(postId) }
                .onSuccess {
                    ToastCenter.show(R.string.reader_delete_done)
                    reload()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun recoverPost(postId: Int) {
        viewModelScope.launch {
            runCatchingCancellable { client.recoverPost(postId) }
                .onSuccess {
                    ToastCenter.show(R.string.reader_recover_done)
                    reload()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    /** Staff. A locked post is one its author can no longer edit. */
    fun setPostLocked(postId: Int, locked: Boolean) {
        viewModelScope.launch {
            runCatchingCancellable { client.setPostLocked(postId, locked) }
                .onSuccess {
                    ToastCenter.show(if (locked) R.string.reader_locked else R.string.reader_unlocked)
                    reload()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    /** Staff. Deleting the whole thread, which is what deleting its first post means. */
    fun deleteTopic(onDeleted: () -> Unit) {
        val topicId = _state.value.topicId.takeIf { it != 0 } ?: return
        viewModelScope.launch {
            runCatchingCancellable { client.deleteTopic(topicId) }
                .onSuccess {
                    ToastCenter.show(R.string.reader_topic_deleted)
                    onDeleted()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun setTopicClosed(closed: Boolean) {
        val topicId = _state.value.topicId.takeIf { it != 0 } ?: return
        viewModelScope.launch {
            runCatchingCancellable { client.setTopicStatus(topicId, "closed", closed) }
                .onSuccess {
                    ToastCenter.show(if (closed) R.string.reader_topic_closed else R.string.reader_topic_reopened)
                    reload()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun giveReward(postId: Int, amount: Int, note: String?) {
        viewModelScope.launch {
            runCatchingCancellable { client.giveReward(postId, amount, note) }
                .onSuccess {
                    ToastCenter.show(R.string.reader_reward_done, amount)
                    reload()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun repost(categoryId: Int, title: String) {
        viewModelScope.launch {
            runCatchingCancellable { client.repost(_state.value.topicId, categoryId, title) }
                .onSuccess { ToastCenter.show(R.string.reader_reposted) }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    // ------------------------------------------------------ plugin actions

    /**
     * Keyed by post as well as name. Discourse names an unnamed poll "poll", so
     * the topic's and every reply's shared one key — and a vote on the second
     * was dropped without a write, a request or a word to the reader.
     */
    private val pollsInFlight = mutableSetOf<Pair<Int, String>>()

    fun vote(pollName: String, options: List<String>, postId: Int? = null) {
        val target = postId ?: _state.value.firstPostId ?: return
        if (!pollsInFlight.add(target to pollName)) return
        val isReply = target != _state.value.firstPostId
        val previousVotes = if (isReply) {
            pollVoteOverrides[target to pollName]
        } else {
            _state.value.myPollVotes[pollName]
        }
        applyVote(target, pollName, options, isReply)
        viewModelScope.launch {
            runCatchingCancellable { client.votePoll(target, pollName, options) }
                .onSuccess { response ->
                    response.poll?.let { poll ->
                        if (isReply) recordReplyPoll(target, pollName, poll) else replacePoll(poll, pollName)
                    }
                    response.vote?.let { applyVote(target, pollName, it, isReply) }
                }
                .onFailure {
                    applyVote(target, pollName, previousVotes, isReply)
                    ToastCenter.showError(it)
                }
            pollsInFlight.remove(target to pollName)
        }
    }

    fun removeVote(pollName: String, postId: Int? = null) {
        val target = postId ?: _state.value.firstPostId ?: return
        if (!pollsInFlight.add(target to pollName)) return
        val isReply = target != _state.value.firstPostId
        val previousVotes = if (isReply) {
            pollVoteOverrides[target to pollName]
        } else {
            _state.value.myPollVotes[pollName]
        }
        applyVote(target, pollName, emptyList(), isReply)
        viewModelScope.launch {
            runCatchingCancellable { client.removePollVote(target, pollName) }
                .onSuccess {
                    it.poll?.let { poll ->
                        if (isReply) recordReplyPoll(target, pollName, poll) else replacePoll(poll, pollName)
                    }
                }
                .onFailure {
                    applyVote(target, pollName, previousVotes, isReply)
                    ToastCenter.showError(it)
                }
            pollsInFlight.remove(target to pollName)
        }
    }

    private fun replacePoll(poll: PostPoll, name: String) {
        _state.value = _state.value.copy(
            polls = _state.value.polls.map { if (it.pollName == name) poll else it },
        )
    }

    private var lotteryBusy = false

    fun participateInLottery(quantity: Int, isRandom: Boolean) {
        val lottery = _state.value.lottery ?: return
        if (lotteryBusy) return
        lotteryBusy = true
        viewModelScope.launch {
            runCatchingCancellable { client.participateInLottery(lottery.id, quantity, isRandom) }
                .onSuccess { response ->
                    if (response.success?.value == false) {
                        response.error?.let(ToastCenter::show) ?: ToastCenter.show(R.string.lottery_join_failed)
                    } else if (response.lottery != null) {
                        _state.value = _state.value.copy(lottery = response.lottery)
                    } else {
                        // The endpoint doesn't always echo the lottery back;
                        // refetch so ticket counts stay truthful.
                        runCatchingCancellable { client.topic(_state.value.topicId) }.getOrNull()?.let { topic ->
                            _state.value = _state.value.copy(
                                lottery = topic.postStream.posts.firstOrNull()?.lottery,
                            )
                        }
                    }
                }
                .onFailure { ToastCenter.showError(it) }
            lotteryBusy = false
        }
    }

    // ------------------------------------------------------ read progress

    fun setVisiblePosts(postNumbers: Set<Int>) = tracker.setVisible(postNumbers)

    private var heartbeatStarted = false

    private fun startHeartbeat() {
        if (heartbeatStarted) return
        heartbeatStarted = true
        viewModelScope.launch {
            var sinceFlush = 0
            while (isActive) {
                delay(1000)
                tracker.tick()
                sinceFlush += 1
                if (sinceFlush >= 10) {
                    sinceFlush = 0
                    tracker.flush()
                }
            }
        }
    }

    override fun onCleared() {
        // One last flush so the final seconds of reading aren't lost.
        val pending = tracker
        ServiceLocator.get.let {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { pending.flush() }
        }

    }
}

/**
 * Which posts are on screen and for how long, reported to `topics/timings`.
 * That is what marks posts read, accrues read time, and clears the topic's
 * unread state — the same bookkeeping the web client's screen tracker does.
 */
private class ReadTracker(private val client: com.nodeloc.app.core.network.DiscourseClient) {
    private var topicId: Int? = null
    private val visible = mutableSetOf<Int>()
    private val pendingByPost = mutableMapOf<Int, Int>()
    private var pendingTopicTime = 0

    fun begin(topicId: Int) {
        // A refetch of the same thread is not a new reading session. Restarting
        // here discarded up to ten seconds of unflushed timings and reset the
        // visible set to the first post, which the screen only corrects on its
        // next scroll.
        if (this.topicId == topicId) return
        this.topicId = topicId
        visible.clear()
        // The first post is on screen the moment a topic opens.
        visible += 1
        pendingByPost.clear()
        pendingTopicTime = 0
    }

    /**
     * Replaces the visible set rather than adding to it.
     *
     * The screen only ever reported arrivals, so a post scrolled past stayed
     * "visible" for the rest of the session: every tick credited it another
     * second, and every flush re-sent the whole accumulated list. A long thread
     * meant hundreds of timing fields per request and read times that bore no
     * relation to what anyone had read.
     */
    fun setVisible(postNumbers: Set<Int>) {
        visible.clear()
        visible += postNumbers
    }

    fun tick(elapsedMs: Int = 1000) {
        if (topicId == null || visible.isEmpty()) return
        visible.forEach { pendingByPost[it] = (pendingByPost[it] ?: 0) + elapsedMs }
        pendingTopicTime += elapsedMs
    }

    suspend fun flush() {
        val id = topicId ?: return
        if (pendingByPost.isEmpty()) return
        // Read progress belongs to an account. Signed out, every flush was a
        // 403 BAD CSRF — one every few seconds for as long as a guest kept
        // reading, and nothing to show for any of them.
        if (!client.auth.isAuthenticated) {
            pendingByPost.clear()
            pendingTopicTime = 0
            return
        }
        val timings = pendingByPost.toMap()
        val time = pendingTopicTime
        pendingByPost.clear()
        pendingTopicTime = 0
        runCatchingCancellable { client.sendTopicTimings(id, time, timings) }
    }
}

/**
 * Parsed bodies keyed by post id + edit version, shared across reader
 * instances: backing out of a topic and reopening it must not re-parse HTML
 * that hasn't changed. Bounded, because a long session visits many topics.
 */
private object ParsedContentCache {
    private val cache = LruCache<String, PostContent>(600)

    fun get(key: String): PostContent? = cache.get(key)

    fun put(key: String, content: PostContent) {
        cache.put(key, content)
    }
}

/** Enough time on screen for Discourse to count a post as read. */
private const val READ_MS = 1_000
