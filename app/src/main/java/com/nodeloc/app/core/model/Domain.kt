package com.nodeloc.app.core.model

import androidx.annotation.StringRes
import com.nodeloc.app.R

import com.nodeloc.app.core.html.PostContent

/** One resolution of a responsive image set. */
data class ImageVariant(val width: Int, val url: String)

data class PostMedia(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    /**
     * The same image at several widths, ascending. Lets each display site fetch
     * a size matching how big it draws instead of upscaling one middling image
     * everywhere.
     */
    val variants: List<ImageVariant> = emptyList(),
    /**
     * The original behind the display copy, where the two differ.
     *
     * A gallery row only gets one optimised URL per picture, so there are no
     * variants to walk up to the full size — the full-screen viewer needs to be
     * told it separately.
     */
    val fullUrl: String? = null,
) {
    /**
     * The smallest variant at least as wide as the target, so it is crisp
     * without over-fetching; falls back to the largest, then to [url].
     */
    fun bestUrl(targetPx: Int): String {
        if (variants.isEmpty() || targetPx <= 0) return url
        return variants.firstOrNull { it.width >= targetPx }?.url ?: variants.last().url
    }

    /** What the full-screen viewer loads. */
    val fullSizeUrl: String get() = fullUrl ?: bestUrl(2048)
}

/** A feed row: the shared shape behind all three reading modes. */
data class Post(
    val id: Int,
    val node: String,
    val avatarLetter: String,
    val variant: Int,
    val time: String,
    val title: String,
    val excerpt: String,
    val baseVotes: Int,
    val comments: Int,
    val hasImage: Boolean,
    val pinned: Boolean = false,
    /** New, or read progress trailing the latest post — drives the unread dot. */
    val isUnread: Boolean = false,
    val imageUrl: String? = null,
    val avatarUrl: String? = null,
    val authorUsername: String? = null,
    val authorName: String? = null,
    val media: List<PostMedia> = emptyList(),
    val tags: List<String> = emptyList(),
    /** First post's video; card mode autoplays it. */
    val videoUrl: String? = null,
    val categoryId: Int? = null,
    /**
     * discourse-vote. Null where voting is off — and where it is on, the vote
     * goes to [opPostId], because a row stands for the topic but a ballot is
     * always cast on a post.
     */
    val voteScore: Int? = null,
    val voteDirection: VoteDirection = VoteDirection.None,
    val opPostId: Int? = null,
    val canVoteDown: Boolean = false,
) {
    /** `u/name`, for lists where every row is already in the same node. */
    val authorHandle: String? get() = authorUsername?.takeIf { it.isNotBlank() }?.let { "u/$it" }
}

/** Where tapping an author goes. */
data class UserProfileTarget(
    val username: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
) {
    val initial: String
        get() = (displayName?.takeIf { it.isNotBlank() } ?: username).take(1).uppercase()
}

/**
 * One row of the reader's flattened reply tree.
 *
 * The server hands back a nested tree; the reader depth-first flattens it once
 * and carries the rail geometry per row, so the list can stay a plain
 * LazyColumn instead of nesting composables per level.
 */
data class PostComment(
    val id: Int,
    val author: String,
    /** "iQOO Z11i" — beside the time, when its author opted in. */
    val source: String? = null,
    val authorName: String? = null,
    val time: String,
    val content: PostContent,
    val redEnvelopeClaim: RedEnvelopeClaim? = null,
    /**
     * A reply can hold a poll like any other post. These used to be dropped on
     * the way in, so the placeholder in the body rendered as a blank gap.
     */
    val polls: List<PostPoll> = emptyList(),
    val myPollVotes: Map<String, List<String>> = emptyMap(),
    val votes: Int,
    val postNumber: Int = 0,
    val replyToPostNumber: Int? = null,
    val parentAuthor: String? = null,
    val parentText: String? = null,
    val avatarUrl: String? = null,
    val nestingDepth: Int = 0,
    /** Staff pinned this reply to the top of the thread. */
    val pinned: Boolean = false,
    /** Straight from the server's Guardian; see [com.nodeloc.app.core.model.TopicPost]. */
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val canRecover: Boolean = false,
    val deleted: Boolean = false,
    val locked: Boolean = false,
    val hasChildren: Boolean = false,
    /** The top-level reply this thread hangs off; rows are grouped by it. */
    val groupId: Int = 0,
    /** A "load N more replies" affordance rather than a reply. */
    val isLoadMore: Boolean = false,
    val loadMoreParent: Int = 0,
    val loadMoreRemaining: Int = 0,
    val authorTitle: String? = null,
    val flairUrl: String? = null,
    val flairName: String? = null,
    val flairBgColor: String? = null,
    val flairColor: String? = null,
    val isLiked: Boolean = false,
    /**
     * Whether this reply is already bookmarked.
     *
     * Read from the server rather than assumed false. Without it the menu
     * offered "bookmark" on something already bookmarked, and the only way to
     * find out was to tap it and be told you cannot do it twice.
     */
    val isBookmarked: Boolean = false,
    val rewards: List<PostReward> = emptyList(),
    /**
     * discourse-vote. Null where voting is off, which is also how the reply row
     * decides between the heart and the arrows — see
     * [com.nodeloc.app.core.model.TopicPost.voteScore].
     */
    val voteScore: Int? = null,
    val voteDirection: VoteDirection = VoteDirection.None,
    val canVoteDown: Boolean = false,
    /** The faces this reply carries, ordered by count, and how many chose one. */
    val reactions: List<PostReaction> = emptyList(),
    val reactionCount: Int = 0,
) {
    val rewardTotal: Int get() = rewards.filter { it.amount > 0 }.sumOf { it.amount }
}

/** One face an arrow offers: what to send, and what to draw. */
data class VoteFace(val name: String, val url: String)

/**
 * One tab of the emoji picker.
 *
 * The server names groups in snake_case (`smileys_&_emotion`) and the custom
 * sets by whatever the admin called the upload, so the label is the raw name
 * tidied rather than translated — there is nothing to translate it from.
 */
data class EmojiGroup(val name: String, val emojis: List<DiscourseEmoji>) {
    val label: String get() = name.replace('_', ' ').trim()
}

/** A node in list form, shared by the sidebar, browse page and pickers. */
data class NodeSummary(
    val id: Int,
    val name: String,
    val slug: String,
    val description: String? = null,
    val logoUrl: String? = null,
    val bannerUrl: String? = null,
    val colorHex: String? = null,
    val memberCount: Int? = null,
    val topicCount: Int? = null,
    val parentCategoryId: Int? = null,
    val isJoined: Boolean = false,
    val notificationLevel: Int? = null,
) {
    val letter: String get() = name.take(1).uppercase()

    /** `/c/{parent}/{child}/{id}` needs the slug pair; callers resolve parent. */
    val handle: String get() = "n/$slug"
}

data class NodeGroupSummary(
    val id: Int,
    val name: String,
    val slug: String,
    val nodes: List<NodeSummary>,
    val totalCount: Int = nodes.size,
    val hasMore: Boolean = false,
)

enum class NodeSort(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val detailRes: Int,
    val requiresAuth: Boolean = false,
    /**
     * Only the root serves it.
     *
     * `/best` is the community plugin's own route — `get "/best", to:
     * "feed#index"` — and there is no `/c/{slug}/{id}/best.json` behind it, so
     * offering it on a node page would be offering a page that does not exist.
     */
    val feedOnly: Boolean = false,
) {
    /**
     * What the site itself recommends, which is the home page it wants read.
     *
     * The envelope is the ordinary one: `filter: community_feed`, thirty to a
     * page and `more_topics_url: /best.json?page=1`, so it pages like the rest.
     */
    Best("best", R.string.sort_best, R.string.sort_best_detail, feedOnly = true),
    Latest("latest", R.string.sort_latest, R.string.sort_latest_detail),
    New("new", R.string.sort_new, R.string.sort_new_detail, requiresAuth = true),
    Hot("hot", R.string.sort_hot, R.string.sort_hot_detail),
    Featured("featured", R.string.sort_featured, R.string.sort_featured_detail),
    Top("top", R.string.sort_top, R.string.sort_top_detail),
}

/** How a topic list renders. Shared globally across feed and node pages. */
enum class NodeReadingMode(val key: String, @StringRes val labelRes: Int) {
    Compact("compact", R.string.reading_mode_compact),
    Expand("expand", R.string.reading_mode_expand),
    Card("card", R.string.reading_mode_card),
    ;

    companion object {
        fun from(raw: String?): NodeReadingMode? = entries.firstOrNull { it.key == raw }

        /**
         * What to show when neither this device nor an account has chosen.
         *
         * Cards for a guest. There is no account preference to honour and no
         * reading history to move through quickly — someone seeing the site
         * for the first time is better served by the view that shows what the
         * topics are about than by the one that fits the most rows.
         */
        fun default(signedIn: Boolean): NodeReadingMode = if (signedIn) Compact else Card
    }
}

/**
 * How much a node notifies you. The raw values are Discourse's own
 * `NotificationLevels.all` — the same integers the web dropdown sends — so they
 * must never be renumbered.
 */
enum class NodeNotificationLevel(
    val level: Int,
    @StringRes val labelRes: Int,
    @StringRes val detailRes: Int,
) {
    Muted(0, R.string.level_muted, R.string.level_muted_detail),
    Regular(1, R.string.level_regular, R.string.level_regular_detail),
    Tracking(2, R.string.level_tracking, R.string.level_tracking_detail),
    Watching(3, R.string.level_watching, R.string.level_watching_detail),
    WatchingFirstPost(4, R.string.level_watching_first, R.string.level_watching_first_detail),
    ;

    companion object {
        /** Ordered as the web dropdown presents them: most notifying first. */
        val menuOrder = listOf(Watching, Tracking, WatchingFirstPost, Regular, Muted)

        fun from(level: Int?): NodeNotificationLevel = entries.firstOrNull { it.level == level } ?: Regular
    }
}

/** Reply ordering in the reader; server-side sort on the nested endpoint. */
enum class ReplySort(val key: String, @StringRes val labelRes: Int) {
    Top("top", R.string.reply_sort_top),
    New("new", R.string.reply_sort_new),
    Old("old", R.string.reply_sort_old),
}

data class ChatChannelSummary(
    val id: Int,
    val title: String,
    val letter: String,
    val avatarUrl: String? = null,
    val lastMessage: String = "",
    val time: String = "",
    val unreadCount: Int = 0,
    val isDirectMessage: Boolean = false,
    val lastMessageId: Int? = null,
    /** Where this reader got to; the conversation marks its unread from here. */
    val lastReadMessageId: Int? = null,
    /** Raw ISO timestamp of the last message; [time] is the same thing, shown. */
    val lastActivityAt: String? = null,
    /**
     * Who else is in it, by username.
     *
     * The title is a display name, and a block is by username — the two are
     * different strings and only one of them can be matched against.
     */
    val memberUsernames: List<String> = emptyList(),
)

data class InboxNotification(
    val id: Int,
    val type: Int,
    val read: Boolean,
    val title: String,
    val detail: String,
    val time: String,
    val topicId: Int? = null,
    val postNumber: Int? = null,
    val slug: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val chatChannelId: Int? = null,
    val chatMessageId: Int? = null,
    val badgeId: Int? = null,
    val badgeSlug: String? = null,
    val groupName: String? = null,
)

data class PrivateMessageSummary(
    val id: Int,
    val title: String,
    val excerpt: String,
    val time: String,
    val unread: Boolean,
    val counterpartName: String?,
    val avatarUrl: String?,
    val letter: String,
)

data class AppSummary(
    val id: Int,
    val slug: String,
    val name: String,
    val description: String? = null,
    val logoUrl: String? = null,
    val installs: Int? = null,
    val isWebview: Boolean = false,
)
