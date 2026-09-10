@file:OptIn(ExperimentalSerializationApi::class)

package com.nodeloc.app.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * DTOs for the Discourse JSON API and the plugins nodeloc runs.
 *
 * Everything optional by default: a field type guessed wrong takes the *whole*
 * response down, and on iOS exactly that turned every post's node info silently
 * blank for weeks. Where the backend is known to be inconsistent (see
 * [FlexibleBool]) the decoder absorbs both shapes rather than trusting docs.
 */

// ------------------------------------------------------------------ Helpers

/**
 * A "true" that arrives in whatever shape the endpoint felt like.
 *
 * The nested endpoints answer `has_more` / `has_more_roots` with a JSON bool on
 * one path and 0/1 on another; `node/join` answers `"success":"OK"` — a string
 * where the name promises a bool. Decode all three, default false.
 */
@Serializable(with = FlexibleBoolSerializer::class)
data class FlexibleBool(val value: Boolean)

object FlexibleBoolSerializer : KSerializer<FlexibleBool> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBool", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): FlexibleBool {
        val input = decoder as? JsonDecoder ?: return FlexibleBool(decoder.decodeBoolean())
        val element = input.decodeJsonElement() as? JsonPrimitive ?: return FlexibleBool(false)
        element.booleanOrNull?.let { return FlexibleBool(it) }
        element.intOrNull?.let { return FlexibleBool(it != 0) }
        // Rails' `render json: { success: "OK" }`, and its relatives.
        return FlexibleBool(element.content.equals("OK", ignoreCase = true) ||
            element.content.equals("true", ignoreCase = true))
    }

    override fun serialize(encoder: Encoder, value: FlexibleBool) = encoder.encodeBoolean(value.value)
}

// -------------------------------------------------------------------- Users

@Serializable
data class DiscourseUser(
    val id: Int,
    val username: String,
    val name: String? = null,
    val avatarTemplate: String? = null,
)

// --------------------------------------------------------------- Topic lists

@Serializable
data class TopicPoster(
    val userId: Int? = null,
    val description: String? = null,
)

@Serializable
data class TopicTag(val id: Int? = null, val name: String? = null, val slug: String? = null)

/**
 * One resolution in a topic's responsive image set. The field is `thumbnails`
 * (not `topic_thumbnails`), and the largest entry is the original upload.
 */
@Serializable
data class TopicThumbnail(
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val url: String? = null,
)

@Serializable
data class TopicListItem(
    val id: Int,
    val title: String = "",
    /**
     * The translated title, when the site has one for us.
     *
     * Content localisation rewrites `fancy_title` and leaves `title` in the
     * language it was written in, so a list built from `title` is the original
     * every time however the account is set up. `fancy_title` is HTML-escaped
     * and spells emoji as `:shortcodes:`, which is why it is not simply
     * preferred outright — see `DiscourseFormat.displayTitle`.
     */
    val fancyTitle: String? = null,
    /** Whether [fancyTitle] is a translation rather than the original. */
    val fancyTitleLocalized: Boolean? = null,
    val slug: String? = null,
    val postsCount: Int? = null,
    val replyCount: Int? = null,
    /** Likes across every post in the topic. */
    val likeCount: Int? = null,
    /** Likes on the first post alone — what the reader's action bar shows. */
    val opLikeCount: Int? = null,
    val views: Int? = null,
    val createdAt: String? = null,
    val lastPostedAt: String? = null,
    val bumpedAt: String? = null,
    val categoryId: Int? = null,
    val pinned: Boolean? = null,
    /** Per-user read state; only present when signed in. */
    val unseen: Boolean? = null,
    val lastReadPostNumber: Int? = null,
    val highestPostNumber: Int? = null,
    val excerpt: String? = null,
    val imageUrl: String? = null,
    /** Width variants of the *first* picture only, largest first. */
    val thumbnails: List<TopicThumbnail>? = null,
    /** Every picture in the topic, full size, in the order they appear. */
    val topicImages: List<String>? = null,
    /** The same pictures, optimised for display; index-for-index with the above. */
    val topicThumbnails: List<String>? = null,
    val posters: List<TopicPoster>? = null,
    val tags: List<TopicTag>? = null,
    /** discourse-community adds this expressly for card-mode autoplay. */
    val topicVideoUrl: String? = null,

    /**
     * discourse-vote, carried on the row so a feed needs no extra request.
     *
     * A row's score is its *first post's*: what a reader votes on from a list
     * is the thing that was posted, not the conversation under it — so the
     * vote goes to [opPostId], not to the topic.
     */
    val opPostId: Int? = null,
    val opVoteScore: Int? = null,
    /** `up`, `down` or `none`; absent where voting does not apply. */
    val opVoteDirection: String? = null,
    val opCanVoteDown: Boolean = false,
)

@Serializable
data class TopicList(
    val topics: List<TopicListItem> = emptyList(),
    /** Present while more pages exist; null on the last page. */
    val moreTopicsUrl: String? = null,
)

@Serializable
data class LatestResponse(
    val users: List<DiscourseUser>? = null,
    val topicList: TopicList = TopicList(),
)

@Serializable
data class DiscourseSiteSettings(
    val pollEnabled: Boolean? = null,
    val pollMaximumOptions: Int? = null,
    val redEnvelopeEnabled: Boolean? = null,
    val redEnvelopeMinPoints: Int? = null,
    val redEnvelopeMinAvgPoints: Int? = null,
    val redEnvelopeMinCount: Int? = null,
    val redEnvelopeMaxCount: Int? = null,
    val lotteryEnabled: Boolean? = null,
    val lotteryMinTrustLevel: Int? = null,
    val lotteryMinTicketsPerUser: Int? = null,
    val lotteryMaxTicketsPerUser: Int? = null,
    val lotteryMaxDrawDays: Int? = null,
)

@Serializable
data class SiteResponse(
    val categories: List<DiscourseCategory>? = null,
    /**
     * Flag reasons, as this site defines them — nine here, two of which it
     * added itself. Names and descriptions arrive already translated, so the
     * app shows the server's wording rather than a list of its own that would
     * be wrong the day a moderator edits one.
     */
    val postActionTypes: List<PostActionType> = emptyList(),
    /**
     * The score at or below which discourse-vote folds a post.
     *
     * A client setting, which the web reads from the page's preload store and
     * an API client has no access to — so discourse-vote serializes it here on
     * purpose. Null means voting is off, and nothing folds: a threshold this
     * app guessed at would disagree with the site the moment it was changed.
     */
    val voteCollapseScoreThreshold: Int? = null,
    /**
     * The faces each arrow offers, in the site's own order — the first of each
     * is what a bare tap casts.
     *
     * Sent because `discourse_reactions_excluded_from_like` is not: which faces
     * count as a downvote is a server rule, and a client that guessed at it
     * would offer people a face that votes the other way.
     */
    val voteUpvoteReactions: List<String> = emptyList(),
    val voteDownvoteReactions: List<String> = emptyList(),
    /**
     * Which social sign-ins the site offers. The credentials for each live on
     * the server, so this is the whole of what a client needs to know: a name
     * to put in `/auth/{name}` and something to label the button with.
     */
    val authProviders: List<AuthProvider>? = null,
    val popularApps: List<SidebarDiscourseApp>? = null,
    val appsBrowseUrl: String? = null,
    val siteSettings: DiscourseSiteSettings? = null,
    /** A **name → id map**, not an array. Modelled loosely on purpose. */
    val trustLevels: Map<String, Int>? = null,
    val userFields: List<UserFieldDefinition>? = null,
)

/**
 * A custom field the site collects. The required ones are checked at signup —
 * `users_controller.rb:739` fails the whole registration with
 * "missing_user_field" when one is blank — so a client that does not send them
 * cannot register anybody.
 *
 * For a dropdown the server matches the submitted string against [options]
 * exactly, which is why the option is what travels and the label is only ever
 * a local translation of it.
 */
@Serializable
data class UserFieldDefinition(
    val id: Int,
    val name: String? = null,
    val description: String? = null,
    val fieldType: String? = null,
    val required: Boolean = false,
    val showOnSignup: Boolean = false,
    val options: List<String>? = null,
)

@Serializable
data class AuthProvider(
    val name: String,
    val providerUrl: String? = null,
    /** A Font Awesome name, as the website's own buttons use. */
    val iconOverride: String? = null,
    val prettyNameOverride: String? = null,
    val titleOverride: String? = null,
    val canConnect: Boolean? = null,
) {
    /**
     * `google_oauth2` is the strategy's name, not a label. Discourse titles
     * these from its own locale files, which are not exposed here, so the
     * strategy name is tidied into something a person would recognise.
     */
    val label: String
        get() = titleOverride?.takeIf { it.isNotBlank() }
            ?: prettyNameOverride?.takeIf { it.isNotBlank() }
            ?: when (name) {
                "google_oauth2" -> "Google"
                "github" -> "GitHub"
                "twitter" -> "X"
                "telegram" -> "Telegram"
                "discord" -> "Discord"
                "facebook" -> "Facebook"
                "linkedin_oidc" -> "LinkedIn"
                else -> name.replace('_', ' ').replaceFirstChar(Char::uppercase)
            }
}

// --------------------------------------------------------------- Categories

@Serializable
data class DiscourseUploadAsset(val id: Int? = null, val url: String? = null)

@Serializable
data class CategoryModerator(
    val id: Int,
    val username: String,
    val name: String? = null,
    val avatarTemplate: String? = null,
)

@Serializable
data class DiscourseCategory(
    val id: Int,
    val name: String = "",
    val color: String? = null,
    val slug: String = "",
    val topicCount: Int? = null,
    val postCount: Int? = null,
    val memberCount: Int? = null,
    val descriptionExcerpt: String? = null,
    val description: String? = null,
    val parentCategoryId: Int? = null,
    val isJoined: Boolean? = null,
    val isCreator: Boolean? = null,
    val uploadedLogo: DiscourseUploadAsset? = null,
    val uploadedLogoDark: DiscourseUploadAsset? = null,
    val uploadedBackground: DiscourseUploadAsset? = null,
    val uploadedBackgroundDark: DiscourseUploadAsset? = null,
    val url: String? = null,
    /** Only populated with `include_subcategories=true`. Nodes *are* subcategories. */
    val subcategoryList: List<DiscourseCategory>? = null,
    /** Only meaningful for a signed-in request. */
    val notificationLevel: Int? = null,
    val moderators: List<CategoryModerator>? = null,
)

@Serializable
data class CategoryTopicsResponse(
    val topicList: TopicList? = null,
    val users: List<DiscourseUser>? = null,
)

@Serializable
data class CategoryList(val categories: List<DiscourseCategory> = emptyList())

@Serializable
data class CategoriesResponse(val categoryList: CategoryList = CategoryList())

@Serializable
data class NodeMembershipResponse(
    /** `"OK"`, not `true` — see [FlexibleBool]. Decoding it as a bool fails the whole response. */
    val success: FlexibleBool? = null,
    val joined: Boolean? = null,
)

@Serializable
data class SidebarCommunitiesMeta(
    val total: Int? = null,
    val page: Int? = null,
    val perPage: Int? = null,
    val hasMore: Boolean? = null,
)

@Serializable
data class SidebarGroupedNodeBucket(
    val category: DiscourseCategory,
    val totalCount: Int? = null,
    val hasMore: Boolean? = null,
)

@Serializable
data class SidebarCommunitiesResponse(
    val communities: List<DiscourseCategory>? = null,
    val recommended: List<DiscourseCategory>? = null,
    val grouped: Map<String, SidebarGroupedNodeBucket>? = null,
    val meta: SidebarCommunitiesMeta? = null,
    val recommendedMeta: SidebarCommunitiesMeta? = null,
)

@Serializable
data class NodeSlugAvailabilityResponse(val available: Boolean = false, val message: String? = null)

@Serializable
data class CreateCommunityResponse(val category: DiscourseCategory? = null)

@Serializable
data class SidebarDiscourseApp(
    val id: Int,
    val slug: String = "",
    val name: String = "",
    val logoUrl: String? = null,
    val url: String? = null,
)

@Serializable
data class SidebarCustomFeed(
    val id: Int,
    val name: String = "",
    val slug: String = "",
    val description: String? = null,
    val color: String? = null,
    val url: String? = null,
    val username: String? = null,
    val nodeCount: Int? = null,
)

@Serializable
data class SidebarCustomFeedsResponse(val customFeeds: List<SidebarCustomFeed> = emptyList())

// ------------------------------------------------------------------ Uploads

@Serializable
data class DiscourseUpload(
    val id: Int,
    val url: String? = null,
    val originalFilename: String? = null,
    val filesize: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailWidth: Int? = null,
    val thumbnailHeight: Int? = null,
    @SerialName("extension") val fileExtension: String? = null,
    val shortUrl: String? = null,
    val shortPath: String? = null,
) {
    /** What goes into composer markdown. */
    val composerUrl: String? get() = shortUrl ?: url
    val displayFilename: String get() = originalFilename ?: "media"
}

// -------------------------------------------------------------- Post detail

@Serializable
data class ActionSummary(val id: Int, val count: Int? = null, val acted: Boolean? = null)

/** discourse-reward: one reward given to a post. */
@Serializable
data class PostReward(
    val id: Int,
    val userId: Int? = null,
    val username: String? = null,
    val avatarTemplate: String? = null,
    val amount: Int = 0,
    val note: String? = null,
    val createdAt: String? = null,
    val isSystemReward: Boolean? = null,
)

/**
 * One person's take from a red envelope.
 *
 * The amount is `points_received`, not `points` — under the old name it
 * decoded to null on every post and the badge never appeared.
 *
 * [postId] matters as much as the amount: the claim is serialized onto *every*
 * post its owner wrote in the topic, so a person who replied twice carries it
 * on both. Only the post it names actually claimed anything.
 */
@Serializable
data class RedEnvelopeClaim(
    val id: Int? = null,
    val redEnvelopeId: Int? = null,
    val userId: Int? = null,
    val postId: Int? = null,
    val pointsReceived: Int? = null,
    val createdAt: String? = null,
)

@Serializable
data class TopicPost(
    val id: Int,
    /** The tail its author chose to show, already reduced by the server. */
    val mobileSource: String? = null,
    val username: String = "",
    val name: String? = null,
    val avatarTemplate: String? = null,
    val createdAt: String? = null,
    val cooked: String? = null,
    val postNumber: Int? = null,
    val replyToPostNumber: Int? = null,
    val actionsSummary: List<ActionSummary>? = null,
    val bookmarked: Boolean? = null,
    /** Needed to take a bookmark off again: the DELETE is keyed on it, not the post. */
    val bookmarkId: Int? = null,
    /** Edit revision — part of the parse cache key. */
    val version: Int? = null,
    val polls: List<PostPoll>? = null,
    /** `{poll_name: [option_digest]}` — this user's votes. */
    val pollsVotes: Map<String, List<String>>? = null,
    val lottery: PostLottery? = null,
    /** Only serialized for post_number > 1: replying is what claims an envelope. */
    val redEnvelopeClaim: RedEnvelopeClaim? = null,
    /** Nested view only: direct replies inlined under this post. */
    val children: List<TopicPost>? = null,
    val directReplyCount: Int? = null,
    val totalDescendantCount: Int? = null,
    val userTitle: String? = null,
    val flairName: String? = null,
    val flairUrl: String? = null,
    val flairBgColor: String? = null,
    val flairColor: String? = null,
    val rewards: List<PostReward>? = null,
    /**
     * What this reader may do to this post, as the server's own Guardian
     * decided it.
     *
     * Not re-derived here. `can_edit_post?` alone weighs the edit window, the
     * trust level, whether the post is locked, hidden, wiki, a category
     * description, the topic archived — a client copy of that would be wrong
     * the first time the site changed a setting, and wrong silently.
     */
    val yours: Boolean = false,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val canRecover: Boolean = false,
    val deletedAt: String? = null,
    val locked: Boolean = false,

    /**
     * discourse-vote, which now holds a vote as a *reaction*: an upvote is any
     * reaction counting as a like, a downvote any reaction excluded from one.
     *
     * The score is the server's arithmetic, not the app's. It used to be
     * derived here from the like count and a downvote tally, and both of those
     * inputs are gone: which faces count against a post is
     * `discourse_reactions_excluded_from_like`, a setting no client is sent.
     *
     * Serialized only where voting is switched on, and the app cannot read the
     * setting that decides that either — so, exactly as the plugin's own client
     * does, the presence of [voteScore] is what says a post is votable.
     * [voteDirection] and [canVoteDown] additionally need a signed-in reader.
     */
    val voteScore: Int? = null,
    val voteDirection: String? = null,
    val canVoteDown: Boolean = false,

    /**
     * discourse-reactions' own tally: which faces this post carries and how
     * many of each, already ordered by count. The arrows net these into one
     * number; this is the faces themselves.
     */
    val reactions: List<PostReaction>? = null,
    val reactionUsersCount: Int? = null,
) {
    val likeCount: Int get() = actionsSummary?.firstOrNull { it.id == 2 }?.count ?: 0
    val likedByMe: Boolean get() = actionsSummary?.firstOrNull { it.id == 2 }?.acted ?: false

    /** Excludes system deducts, which are negative and are not "被打赏". */
    val rewardTotal: Int get() = rewards.orEmpty().filter { it.amount > 0 }.sumOf { it.amount }
}

@Serializable
data class NestedTopicResponse(
    val opPost: TopicPost? = null,
    val roots: List<TopicPost>? = null,
    val hasMoreRoots: FlexibleBool? = null,
    val page: Int? = null,
    val sort: String? = null,
    val effectiveSort: String? = null,
    /**
     * Replies staff has pinned to the top of the thread. Sent on the first page
     * only, and omitted entirely when there are none — the server has already
     * moved them to the front of `roots`, so this is only what says *why* they
     * are there.
     */
    val pinnedPostIds: List<Int> = emptyList(),
)

@Serializable
data class NestedPinResponse(val pinnedPostIds: List<Int> = emptyList())

@Serializable
data class NestedChildrenResponse(
    val children: List<TopicPost>? = null,
    val hasMore: FlexibleBool? = null,
    val page: Int? = null,
)

@Serializable
data class PostStream(val posts: List<TopicPost> = emptyList(), val stream: List<Int>? = null)

@Serializable
data class TopicResponse(
    val id: Int,
    val title: String = "",
    /** The translated title; see [TopicListItem.fancyTitle]. */
    val fancyTitle: String? = null,
    val fancyTitleLocalized: Boolean? = null,
    /** "regular" or "private_message" — a PM has no node and no node route. */
    val archetype: String? = null,
    val highestPostNumber: Int? = null,
    val details: TopicDetails? = null,
    val postsCount: Int? = null,
    val likeCount: Int? = null,
    val views: Int? = null,
    val categoryId: Int? = null,
    val createdAt: String? = null,
    /** Nobody may reply to a closed topic; staff can reopen it. */
    val closed: Boolean = false,
    val archived: Boolean = false,
    val postStream: PostStream = PostStream(),
    /** Serialized onto topic_view, not onto any individual post. */
    val redEnvelope: TopicRedEnvelope? = null,
    /**
     * discourse-read-permission: whether this reader has the trust level the
     * topic asks for.
     *
     * False means the stream above is already a stand-in — one post carrying
     * the plugin's notice — and that every *other* way into the topic answers
     * 403. Absent where the plugin is off, hence the default.
     */
    val canReadTopic: Boolean = true,
)

/** Only the parts of `details` a private message needs to name itself. */
@Serializable
data class TopicDetails(
    val allowedUsers: List<AllowedUser>? = null,
    val allowedGroups: List<AllowedGroup>? = null,
)

@Serializable
data class AllowedUser(
    val id: Int? = null,
    val username: String = "",
    val name: String? = null,
    val avatarTemplate: String? = null,
)

@Serializable
data class AllowedGroup(val id: Int? = null, val name: String? = null)

@Serializable
data class TopicPostsResponse(val postStream: PostStream = PostStream())

/**
 * discourse-anyvideo's `videos/suggestions`: topics whose *opening* post holds a
 * transcoded clip, sampled at random from the recent ones.
 *
 * The payload names topics, not videos — the plugin's own overlay links to them
 * and lets the topic page find the player. Which is why the clip still has to
 * be dug out of the topic; see `VideoFeedQueue`.
 */
/**
 * One entry of `post_action_types` — a flag reason, or the like that shares the
 * same table.
 *
 * [isFlag] is what separates the two, and [appliesTo] is why the list cannot be
 * used as it stands: several of these are for topics or for chat messages, and
 * one offered against a post it does not apply to is refused by the server.
 */
@Serializable
data class PostActionType(
    val id: Int = 0,
    val name: String = "",
    val nameKey: String = "",
    /** Carries markup: two of them link the site's guidelines. */
    val description: String? = null,
    val shortDescription: String? = null,
    val appliesTo: List<String> = emptyList(),
    val position: Int = 0,
    /** The server refuses these without a note; the dialog asks for one. */
    val requireMessage: Boolean = false,
    val enabled: Boolean = true,
    val isFlag: Boolean = false,
)

@Serializable
data class VideoSuggestionsResponse(val topics: List<VideoSuggestionTopic> = emptyList())

@Serializable
data class VideoSuggestionTopic(val id: Int, val title: String = "")

/**
 * One post fetched on its own, for the source text.
 *
 * The topic and nested endpoints send `cooked` — the rendered HTML — and an
 * editor needs what was typed. `GET /posts/:id.json` is the only place `raw`
 * comes from.
 */
@Serializable
data class PostDetail(
    val id: Int = 0,
    val raw: String = "",
    val topicId: Int? = null,
    val postNumber: Int? = null,
    val canEdit: Boolean = false,
)

@Serializable
data class CreatePostResponse(
    val id: Int? = null,
    val topicId: Int? = null,
    val postNumber: Int? = null,
)

// --------------------------------------------------------------------- Poll

@Serializable
data class PollOptionResult(val id: String, val html: String? = null, val votes: Int? = null)

@Serializable
data class PostPoll(
    val id: Int? = null,
    val name: String? = null,
    val type: String? = null,
    val status: String? = null,
    val results: String? = null,
    val min: Int? = null,
    val max: Int? = null,
    val step: Int? = null,
    val options: List<PollOptionResult>? = null,
    val voters: Int? = null,
    val close: String? = null,
    val chartType: String? = null,
    val title: String? = null,
    @SerialName("public") val isPublic: Boolean? = null,
) {
    /** What polls_votes and the vote endpoint key on; unnamed polls are "poll". */
    val pollName: String get() = name ?: "poll"
    val isClosed: Boolean get() = status == "closed"
    val isMultiple: Boolean get() = type == "multiple"

    /** Ranked-choice and number polls render read-only rather than fake a vote. */
    val isVotable: Boolean get() = type == "regular" || type == "multiple"
    val totalVotes: Int get() = options.orEmpty().sumOf { it.votes ?: 0 }
}

@Serializable
data class PollVoteResponse(val poll: PostPoll? = null, val vote: List<String>? = null)

// ------------------------------------------------------------------ Lottery

@Serializable
data class LotteryPrizeLevel(
    val id: Int? = null,
    val name: String? = null,
    val prize: String? = null,
    val quantity: Int? = null,
)

@Serializable
data class LotteryParticipant(
    val username: String? = null,
    val avatarTemplate: String? = null,
    val tickets: Int? = null,
    val isRandom: Boolean? = null,
)

@Serializable
data class LotteryWinner(
    val username: String? = null,
    val avatarTemplate: String? = null,
    val levelName: String? = null,
    val prize: String? = null,
)

@Serializable
data class PostLottery(
    val id: Int,
    val title: String? = null,
    val userId: Int? = null,
    val postId: Int? = null,
    val minParticipants: Int? = null,
    val maxParticipants: Int? = null,
    val maxTicketsPerUser: Int? = null,
    val minTicketsPerUser: Int? = null,
    val minTrustLevel: Int? = null,
    val drawAt: String? = null,
    val status: String? = null,
    val levels: List<LotteryPrizeLevel>? = null,
    val ticketsCount: Int? = null,
    val participantsCount: Int? = null,
    val userTickets: Int? = null,
    val isParticipating: Boolean? = null,
    val canDraw: Boolean? = null,
    val canManage: Boolean? = null,
    val canClose: Boolean? = null,
    val participants: List<LotteryParticipant>? = null,
    val winners: List<LotteryWinner>? = null,
) {
    val isOpen: Boolean get() = status == "open"

    /** The server stores "unlimited" as a 1,000,000 sentinel rather than null. */
    val hasParticipantCap: Boolean
        get() = maxParticipants != null && maxParticipants > 0 && maxParticipants < 1_000_000
    val totalPrizes: Int get() = levels.orEmpty().sumOf { maxOf(1, it.quantity ?: 1) }
}

@Serializable
data class LotteryActionResponse(
    /** Rails' `success_json` — the string "OK". See [FlexibleBool]. */
    val success: FlexibleBool? = null,
    val error: String? = null,
    val lottery: PostLottery? = null,
)

@Serializable
data class LotteryCreateResponse(
    val success: FlexibleBool? = null,
    val error: String? = null,
    val id: Int? = null,
)

// ------------------------------------------------------------- Red envelope

@Serializable
data class TopicRedEnvelope(
    val id: Int,
    val topicId: Int? = null,
    val userId: Int? = null,
    val totalPoints: Int? = null,
    val totalCount: Int? = null,
    val claimedCount: Int? = null,
    val remainingPoints: Int? = null,
    val availableCount: Int? = null,
    val exhausted: Boolean? = null,
    val claimPercentage: Double? = null,
    val createdAt: String? = null,
)

@Serializable
data class RedEnvelopeResponse(
    val success: FlexibleBool? = null,
    val error: String? = null,
    val id: Int? = null,
)

// ------------------------------------------------------------------- Search

@Serializable
data class SearchPost(
    val id: Int,
    val topicId: Int? = null,
    val blurb: String? = null,
    val username: String? = null,
)

@Serializable
data class SearchResponse(
    val topics: List<TopicListItem>? = null,
    val posts: List<SearchPost>? = null,
    val categories: List<DiscourseCategory>? = null,
    val users: List<DiscourseUser>? = null,
)

/** `POST /categories/search` — the only search that reports a total. */
@Serializable
data class CategorySearchResponse(
    val categoriesCount: Int? = null,
    val categories: List<DiscourseCategory>? = null,
)

/** `GET /u/search/users.json` — leaner users than the search facet returns. */
@Serializable
data class UserSearchResponse(
    val users: List<DiscourseUser>? = null,
)

/** One row of `/tags/filter/search`: what `#` offers besides the nodes. */
@Serializable
data class DiscourseTag(
    val id: Int? = null,
    val name: String = "",
    val slug: String? = null,
    val count: Int? = null,
)

@Serializable
data class TagSearchResponse(val results: List<DiscourseTag> = emptyList())

/** One face on a post, and how many people put it there. */
@Serializable
data class PostReaction(val id: String = "", val count: Int = 0)

/**
 * `GET /discourse-reactions/posts/:id/reactions-users.json` — who reacted, in
 * groups, one per face.
 *
 * Grouped rather than flat because that is the question the sheet answers: not
 * "who liked this" but "who chose which".
 */
@Serializable
data class ReactionUsersResponse(val reactionUsers: List<ReactionUserGroup> = emptyList())

@Serializable
data class ReactionUserGroup(
    val id: String = "",
    val count: Int = 0,
    val users: List<ReactionUser> = emptyList(),
)

/**
 * Not [DiscourseUser]: this endpoint sends no `id`, and a required one there
 * took the whole response down — the sheet opened, decoded nothing and said
 * there were no reactions on a post plainly showing twenty-three.
 */
@Serializable
data class ReactionUser(
    val username: String = "",
    val name: String? = null,
    val avatarTemplate: String? = null,
)

/**
 * What `PUT /vote/posts/:id` answers with: the post, re-serialized.
 *
 * Worth reconciling against rather than trusting the optimistic guess — the
 * ballot may have landed somewhere the tap did not aim it, and a reaction the
 * reader already held elsewhere on the post moves the tally by two, not one.
 */
@Serializable
data class PostVoteResult(
    val id: Int? = null,
    val voteScore: Int? = null,
    val voteDirection: String? = null,
    val canVoteDown: Boolean = false,
)

/**
 * Which way a ballot points.
 *
 * The wire value is the direction to *end up* in, never a toggle: replaying a
 * request then cannot drift from the server, which is what a double tap on a
 * phone would otherwise do.
 */
enum class VoteDirection(val wire: String) {
    Up("up"),
    Down("down"),
    None("none"),
    ;

    /** Tapping the way you already voted takes the vote back, as Reddit does. */
    fun after(tapped: VoteDirection): VoteDirection = if (this == tapped) None else tapped

    /**
     * How far a score moves going from here to [to].
     *
     * An upvote is worth +1 and a downvote −1, so crossing from one to the
     * other is a swing of two rather than of one.
     */
    fun stepTo(to: VoteDirection): Int = to.weight - weight

    private val weight: Int
        get() = when (this) {
            Up -> 1
            Down -> -1
            None -> 0
        }

    companion object {
        fun from(raw: String?): VoteDirection =
            entries.firstOrNull { it.wire == raw } ?: None
    }
}

/**
 * Whether discourse-vote would fold this post.
 *
 * Both arguments are null in the ordinary case — no voting on this post, or no
 * threshold from the site — and either one being null means nothing folds. The
 * comparison is `<=`, matching the plugin: a threshold of −5 folds a post *at*
 * −5, not only below it.
 */
fun isVoteCollapsed(score: Int?, threshold: Int?): Boolean =
    score != null && threshold != null && score <= threshold

/**
 * One entry of `GET /emojis.json`.
 *
 * The response is a map of group name to list, and the groups after the
 * Unicode ones are this site's own uploads — which is the whole point of
 * having a picker rather than leaving people the system keyboard.
 */
@Serializable
data class DiscourseEmoji(
    val name: String = "",
    val url: String = "",
    val group: String? = null,
    /** Skin-tone variants exist; the app offers the base form only. */
    val tonable: Boolean = false,
)

// ------------------------------------------------------------------ Profile

@Serializable
data class UserGroupFlair(
    val id: Int,
    val name: String = "",
    val fullName: String? = null,
    val flairUrl: String? = null,
    /** A group may grant its members a wearable title. */
    val title: String? = null,
) {
    val displayName: String get() = fullName?.takeIf { it.isNotEmpty() } ?: name
}

@Serializable
data class UserProfile(
    val id: Int,
    val username: String = "",
    val name: String? = null,
    val avatarTemplate: String? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val title: String? = null,
    val profileBackgroundUploadUrl: String? = null,
    /** Gated on `profile_background_allowed_groups`; staff always pass. */
    val canUploadProfileHeader: Boolean? = null,
    val cardBackgroundUploadUrl: String? = null,
    val location: String? = null,
    val websiteName: String? = null,
    val website: String? = null,
    val bioRaw: String? = null,
    val bioExcerpt: String? = null,
    val flairGroupId: Int? = null,
    val groups: List<UserGroupFlair>? = null,
    val trustLevel: Int? = null,
    val admin: Boolean? = null,
    val moderator: Boolean? = null,
    val badgeCount: Int? = null,
    val postCount: Int? = null,
    val topicCount: Int? = null,
    val likesGiven: Int? = null,
    val likesReceived: Int? = null,
    val profileViewCount: Int? = null,
    /** Either a Font Awesome icon name or an uploaded image path — never assume. */
    val flairUrl: String? = null,
    val flairName: String? = null,
    val flairBgColor: String? = null,
    val flairColor: String? = null,
    val totalFollowers: Int? = null,
    val totalFollowing: Int? = null,
    val canFollow: Boolean? = null,
    val isFollowed: Boolean? = null,
    /**
     * Who this user has ignored, and whether they are allowed to.
     *
     * Only sent for yourself, and only ever read for yourself: the serializer
     * fills them from your own rows, so on anybody else's profile they arrive
     * absent rather than wrong.
     */
    val ignoredUsernames: List<String>? = null,
    val canIgnoreUsers: Boolean? = null,
)

@Serializable
data class UserBadge(
    val id: Int,
    val name: String = "",
    val badgeTypeId: Int? = null,
    /** Only a badge with this set may be worn as a title. */
    val allowTitle: Boolean? = null,
)

@Serializable
data class UserResponse(val user: UserProfile, val badges: List<UserBadge>? = null)

@Serializable
data class SummaryCategory(
    val id: Int,
    val name: String? = null,
    val color: String? = null,
    val slug: String? = null,
    val topicCount: Int? = null,
    val postCount: Int? = null,
)

@Serializable
data class SummaryBadge(
    val id: Int,
    val name: String? = null,
    val description: String? = null,
    val grantCount: Int? = null,
    val icon: String? = null,
)

@Serializable
data class UserSummary(
    val likesGiven: Int? = null,
    val likesReceived: Int? = null,
    val topicsEntered: Int? = null,
    val postsReadCount: Int? = null,
    val daysVisited: Int? = null,
    val topicCount: Int? = null,
    val postCount: Int? = null,
    val timeRead: Int? = null,
    val solvedCount: Int? = null,
    val topCategories: List<SummaryCategory>? = null,
)

@Serializable
data class UserSummaryResponse(
    val userSummary: UserSummary = UserSummary(),
    val badges: List<SummaryBadge>? = null,
)

@Serializable
data class BadgeDefinition(
    val id: Int,
    val name: String = "",
    val allowTitle: Boolean? = null,
    val imageUrl: String? = null,
    val description: String? = null,
)

@Serializable
data class UserBadgesResponse(val badges: List<BadgeDefinition>? = null)

// ------------------------------------------------------------ Account admin

@Serializable
data class AssociatedAccount(val name: String, val description: String? = null)

@Serializable
data class UserAuthToken(
    val id: Int,
    val clientId: String? = null,
    val deviceName: String? = null,
    val osName: String? = null,
    val clientName: String? = null,
    val seenAt: String? = null,
    val isActive: Boolean? = null,
)

@Serializable
data class AccountDetailPayload(
    val associatedAccounts: List<AssociatedAccount>? = null,
    val userAuthTokens: List<UserAuthToken>? = null,
    val secondFactorEnabled: Boolean? = null,
)

@Serializable
data class AccountDetail(val user: AccountDetailPayload = AccountDetailPayload())

@Serializable
data class TOTPCreateResponse(val key: String? = null, val qr: String? = null, val error: String? = null)

@Serializable
data class BackupCodesResponse(val backupCodes: List<String>? = null, val error: String? = null)

@Serializable
data class TOTPDevice(val id: Int, val name: String? = null, val lastUsed: String? = null)

@Serializable
data class SecurityKeyDevice(val id: Int, val name: String? = null)

@Serializable
data class SecondFactorsResponse(
    val totps: List<TOTPDevice>? = null,
    val securityKeys: List<SecurityKeyDevice>? = null,
)

@Serializable
data class SessionTrustResponse(
    val success: String? = null,
    val failed: String? = null,
    val error: String? = null,
) {
    val isTrusted: Boolean get() = success == "OK"
}


@Serializable
data class UsernameCheckResponse(val available: Boolean? = null, val suggestion: String? = null)

/**
 * `session/hp.json`. The two values a signup has to echo back to prove it came
 * from a form rather than a script.
 */
@Serializable
data class HoneypotResponse(val value: String? = null, val challenge: String? = null)

/**
 * `u/check_email.json`. Answers `success` or `failed` + `errors`, and answers
 * `success` to everything when the site hides whether an address is taken —
 * which is what nodeloc does, so [errors] is the only part worth reading.
 */
@Serializable
data class EmailCheckResponse(
    val success: String? = null,
    val failed: String? = null,
    val errors: List<String> = emptyList(),
)

// ------------------------------------------------------------------- Points

@Serializable
data class PointsHistoryEntry(
    val date: String? = null,
    val points: Int? = null,
    val description: String? = null,
    val createdAt: String? = null,
    val isPositive: Boolean? = null,
)

@Serializable
data class PointsHistoryResponse(
    val pointsHistory: List<PointsHistoryEntry> = emptyList(),
    val page: Int? = null,
    val hasMore: Boolean? = null,
)

@Serializable
data class PointsScoresResponse(val totalScores: Int? = null)

// ----------------------------------------------------------------- Activity

@Serializable
data class UserActionItem(
    val actionType: Int? = null,
    val title: String? = null,
    val excerpt: String? = null,
    val createdAt: String? = null,
    val avatarTemplate: String? = null,
    val username: String? = null,
    val name: String? = null,
    val categoryId: Int? = null,
    val topicId: Int? = null,
    val postNumber: Int? = null,
    val postId: Int? = null,
)

@Serializable
data class UserActionsResponse(val userActions: List<UserActionItem> = emptyList())

/**
 * One saved bookmark.
 *
 * Bookmarks are not user actions and have not been for years: `UserAction` no
 * longer defines a BOOKMARK type at all, so the filter the profile used to ask
 * with matched nothing and the tab was permanently empty. They live in their
 * own table behind `u/{username}/bookmarks.json`, in this shape.
 */
@Serializable
data class UserBookmark(
    val id: Int? = null,
    val title: String? = null,
    val excerpt: String? = null,
    val createdAt: String? = null,
    val topicId: Int? = null,
    val categoryId: Int? = null,
    val postId: Int? = null,
    val linkedPostNumber: Int? = null,
    /** Whoever wrote the bookmarked post, not whoever bookmarked it. */
    val user: DiscourseUser? = null,
)

@Serializable
data class UserBookmarkList(
    val bookmarks: List<UserBookmark> = emptyList(),
    /** Absent on the last page, which is how paging knows to stop. */
    val moreBookmarksUrl: String? = null,
)

@Serializable
data class UserBookmarksResponse(val userBookmarkList: UserBookmarkList = UserBookmarkList())

/**
 * One unposted draft the site is holding.
 *
 * The app keeps its own draft on the phone, which is why a draft started in a
 * browser was invisible here: two stores, never introduced. `data` is a JSON
 * string rather than an object — Discourse stores the composer's state as text
 * and hands it back the same way, so it is parsed separately.
 */
@Serializable
data class DraftItem(
    val draftKey: String = "",
    val data: String? = null,
    val title: String? = null,
    val categoryId: Int? = null,
    val createdAt: String? = null,
    val topicId: Int? = null,
)

@Serializable
data class DraftsResponse(val drafts: List<DraftItem> = emptyList())

/** The composer state inside [DraftItem.data]. */
@Serializable
data class DraftPayload(
    val reply: String? = null,
    val title: String? = null,
    val categoryId: Int? = null,
)

/** What creating a bookmark answers: the id needed to remove it again. */
@Serializable
data class BookmarkCreated(val id: Int? = null, val success: String? = null)

// -------------------------------------------------------------- Current user

@Serializable
data class CurrentUserGroup(val id: Int? = null, val name: String = "", val hasMessages: Boolean? = null)

@Serializable
data class CurrentUser(
    val id: Int,
    val username: String = "",
    val name: String? = null,
    val avatarTemplate: String? = null,
    val admin: Boolean? = null,
    val moderator: Boolean? = null,
    val recentApps: List<SidebarDiscourseApp>? = null,
    val recentPostCategoryIds: List<Int>? = null,
    val canCreateCommunity: Boolean? = null,
    val canCreatePoll: Boolean? = null,
    /** Spendable balance for red envelopes (discourse-gamification). */
    val gamificationScore: Int? = null,
    val userOption: UserPreferences? = null,
    val unreadNotifications: Int? = null,
    val newPersonalMessagesNotificationsCount: Int? = null,
    val groups: List<CurrentUserGroup>? = null,
)

@Serializable
data class CurrentUserResponse(val currentUser: CurrentUser)

// -------------------------------------------------------------- Preferences

/**
 * `user_option`, the account preference bag.
 *
 * Rails serializes several of these enums as **strings** (their enum names),
 * not integers — modelling `default_calendar` as Int once made the entire
 * current-user decode fail and the login flow report a mysterious
 * "登录未完成". Anything uncertain stays a String.
 */
@Serializable
data class UserPreferences(
    val userId: Int? = null,
    val emailMessages: Boolean? = null,
    val emailLevel: Int? = null,
    val emailMessagesLevel: Int? = null,
    val emailDigests: Boolean? = null,
    val digestAfterMinutes: Int? = null,
    val mailingListMode: Boolean? = null,
    val mailingListModeFrequency: Int? = null,
    val emailPreviousReplies: Int? = null,
    val emailInReplyTo: Boolean? = null,
    val likeNotificationFrequency: Int? = null,
    val includeTl0InDigests: Boolean? = null,
    val automaticallyUnpinTopics: Boolean? = null,
    val enableQuoting: Boolean? = null,
    val enableDefer: Boolean? = null,
    val externalLinksInNewTab: Boolean? = null,
    val dynamicFavicon: Boolean? = null,
    val newTopicDurationMinutes: Int? = null,
    val autoTrackTopicsAfterMsecs: Int? = null,
    val notificationLevelWhenReplying: Int? = null,
    val hideProfile: Boolean? = null,
    val hidePresence: Boolean? = null,
    val textSizeSeq: Int? = null,
    val titleCountModeKey: String? = null,
    val timezone: String? = null,
    val skipNewUserTips: Boolean? = null,
    val colorSchemeId: Int? = null,
    val darkSchemeId: Int? = null,
    val themeIds: List<Int>? = null,
    val watchedCategoryIds: List<Int>? = null,
    val trackedCategoryIds: List<Int>? = null,
    val mutedCategoryIds: List<Int>? = null,
    /** Rails enum names, serialized as strings. */
    val defaultCalendar: String? = null,
    val bookmarkAutoDeletePreference: Int? = null,
    val textSize: String? = null,
    val homepageId: Int? = null,
    val seenPopups: List<Int>? = null,
    val sidebarLinkToFilteredList: Boolean? = null,
    val sidebarShowCountOfNewItems: Boolean? = null,
    val watchedPrecedenceOverMuted: Boolean? = null,
    /** discourse-community: the shared feed row mode. */
    val communityViewMode: String? = null,
)

// ------------------------------------------------------------- Notifications

@Serializable
data class NotificationData(
    val topicTitle: String? = null,
    val displayUsername: String? = null,
    val username: String? = null,
    val badgeName: String? = null,
    val badgeId: Int? = null,
    val badgeSlug: String? = null,
    val groupName: String? = null,
    val inboxCount: Int? = null,
    val chatChannelId: Int? = null,
    val chatMessageId: Int? = null,
)

@Serializable
data class DiscourseNotification(
    val id: Int,
    val notificationType: Int = 0,
    val read: Boolean = false,
    val createdAt: String? = null,
    val data: NotificationData? = null,
    val topicId: Int? = null,
    val postNumber: Int? = null,
    val slug: String? = null,
)

@Serializable
data class NotificationsResponse(val notifications: List<DiscourseNotification> = emptyList())

// ---------------------------------------------------------- Private messages

@Serializable
data class PrivateMessageTopic(
    val id: Int,
    val title: String? = null,
    val fancyTitle: String? = null,
    val slug: String? = null,
    val lastPostedAt: String? = null,
    val bumpedAt: String? = null,
    val excerpt: String? = null,
    val highestPostNumber: Int? = null,
    val lastReadPostNumber: Int? = null,
    val participants: List<TopicPoster>? = null,
    val posters: List<TopicPoster>? = null,
)

@Serializable
data class PrivateMessageList(val topics: List<PrivateMessageTopic> = emptyList())

@Serializable
data class PrivateMessagesResponse(
    val users: List<DiscourseUser>? = null,
    val topicList: PrivateMessageList = PrivateMessageList(),
)

// --------------------------------------------------------------------- Apps

@Serializable
data class DirectoryApp(
    val id: Int,
    val slug: String = "",
    val name: String = "",
    val description: String? = null,
    val installsCount: Int? = null,
    val approvedScopes: List<String>? = null,
    /** "webview" apps can run natively; "blocks" apps cannot. */
    val surface: String? = null,
    val versionNumber: Int? = null,
    val readmeCooked: String? = null,
    val logoUrl: String? = null,
    val homeUrl: String? = null,
    val categoryUrl: String? = null,
    val author: DiscourseUser? = null,
) {
    val isWebview: Boolean get() = surface == "webview"

    /** Topic id from `home_url` — the first all-digit segment after "t". */
    val hostTopicId: Int?
        get() {
            val parts = homeUrl?.split("/") ?: return null
            val index = parts.indexOf("t").takeIf { it >= 0 } ?: return null
            return parts.drop(index + 1).firstNotNullOfOrNull { it.toIntOrNull() }
        }
}

@Serializable
data class DirectoryAppResponse(val directoryApp: DirectoryApp)

// ------------------------------------------------------- Custom badge styles

@Serializable
data class CustomBadgeStyle(
    val textColor: String? = null,
    val textEffect: String? = null,
    val glitchLeftColor: String? = null,
    val glitchRightColor: String? = null,
)

@Serializable
data class CustomGroupStyleItem(
    val id: Int,
    val name: String? = null,
    val fullName: String? = null,
    val title: String? = null,
    val customGroupStyle: CustomBadgeStyle? = null,
)

@Serializable
data class CustomBadgeStyleItem(
    val id: Int,
    val name: String? = null,
    val customStyle: CustomBadgeStyle? = null,
)

// --------------------------------------------------------------------- Chat

@Serializable
data class ChatUser(
    val id: Int,
    val username: String = "",
    val name: String? = null,
    val avatarTemplate: String? = null,
)

@Serializable
data class ChatLastMessage(
    val id: Int? = null,
    val message: String? = null,
    val cooked: String? = null,
    val excerpt: String? = null,
    val createdAt: String? = null,
    val user: ChatUser? = null,
)

@Serializable
data class ChatInReplyToMessage(
    val id: Int? = null,
    val message: String? = null,
    val cooked: String? = null,
    val excerpt: String? = null,
    val user: ChatUser? = null,
)

@Serializable
data class ChatThreadOriginalMessage(
    val id: Int,
    val message: String? = null,
    val cooked: String? = null,
    val excerpt: String? = null,
    val createdAt: String? = null,
    val chatChannelId: Int? = null,
    val deletedAt: String? = null,
    val user: ChatUser? = null,
)

@Serializable
data class ChatThreadPreview(
    val lastReplyCreatedAt: String? = null,
    val lastReplyExcerpt: String? = null,
    val lastReplyId: Int? = null,
    val participantCount: Int? = null,
    val replyCount: Int? = null,
    val lastReplyUser: ChatUser? = null,
    val participantUsers: List<ChatUser>? = null,
)

@Serializable
data class ChatThreadMembership(
    val unreadCount: Int? = null,
    val following: Boolean? = null,
    val lastReadMessageId: Int? = null,
)

@Serializable
data class ChatThreadSummary(
    val id: Int,
    val title: String? = null,
    val status: String? = null,
    val channelId: Int? = null,
    val replyCount: Int? = null,
    val currentUserMembership: ChatThreadMembership? = null,
    val preview: ChatThreadPreview? = null,
    val lastMessageId: Int? = null,
    val force: Boolean? = null,
    val channel: ChatChannel? = null,
    val originalMessage: ChatThreadOriginalMessage? = null,
)

@Serializable
data class ChatMessage(
    val id: Int,
    val message: String? = null,
    val cooked: String? = null,
    val excerpt: String? = null,
    val createdAt: String? = null,
    val deletedAt: String? = null,
    val threadId: Int? = null,
    val chatChannelId: Int? = null,
    val streaming: Boolean? = null,
    val user: ChatUser? = null,
    val inReplyTo: ChatInReplyToMessage? = null,
    val uploads: List<DiscourseUpload>? = null,
    val thread: ChatThreadSummary? = null,
    val threadTitle: String? = null,
    val channel: ChatChannel? = null,
    /** Absent rather than empty where nobody has reacted. */
    val reactions: List<ChatReaction>? = null,
    val edited: Boolean? = null,
)

@Serializable
data class ChatMessagesMeta(
    val targetMessageId: Int? = null,
    val canLoadMoreFuture: Boolean? = null,
    val canLoadMorePast: Boolean? = null,
)

@Serializable
data class ChatMessagesResponse(
    val messages: List<ChatMessage> = emptyList(),
    val meta: ChatMessagesMeta? = null,
)

@Serializable
data class ChatSearchMeta(val hasMore: Boolean? = null, val limit: Int? = null, val offset: Int? = null)

@Serializable
data class ChatSearchResponse(
    val messages: List<ChatMessage> = emptyList(),
    val meta: ChatSearchMeta? = null,
)

@Serializable
data class ChatThreadsResponse(val threads: List<ChatThreadSummary> = emptyList())

@Serializable
data class ChatCreateMessageResponse(val success: String? = null, val messageId: Int? = null)

@Serializable
data class ChatChannelChatable(
    val id: Int? = null,
    val name: String? = null,
    val slug: String? = null,
    val group: Boolean? = null,
    val users: List<ChatUser>? = null,
)

@Serializable
data class ChatTrackingState(
    val unreadCount: Int? = null,
    val mentionCount: Int? = null,
    val watchedThreadsUnreadCount: Int? = null,
    val lastReplyCreatedAt: String? = null,
) {
    val totalUnreadCount: Int
        get() = (unreadCount ?: 0) + (mentionCount ?: 0) + (watchedThreadsUnreadCount ?: 0)
}

@Serializable
data class ChatTrackingReport(val channelTracking: Map<String, ChatTrackingState>? = null) {
    fun state(channelId: Int): ChatTrackingState? = channelTracking?.get(channelId.toString())
}

@Serializable
data class ChatMembership(
    val unreadCount: Int? = null,
    val lastViewedAt: String? = null,
    val following: Boolean? = null,
    val muted: Boolean? = null,
    /** Where this reader got to; the divider goes under it. */
    val lastReadMessageId: Int? = null,
    /**
     * `never`, `mention` or `always` — a Rails enum, so the wire carries the
     * name rather than the number.
     */
    val notificationLevel: String? = null,
)

/** `GET /chat/api/channels/:id/memberships` — who else is in here. */
@Serializable
data class ChatMemberRow(val user: ChatUser? = null)

@Serializable
data class ChatMembershipsResponse(val memberships: List<ChatMemberRow> = emptyList())

/**
 * One emoji's tally on a message, as the server groups them.
 *
 * [reacted] is this reader's own — the server works it out rather than the app
 * hunting for itself in [users], which only ever holds the first five.
 */
@Serializable
data class ChatReaction(
    val emoji: String = "",
    val count: Int = 0,
    val reacted: Boolean = false,
    val users: List<ChatUser>? = null,
)

/** `GET /presence/get` — who is in a channel right now. */
@Serializable
data class PresenceChannelState(
    val count: Int = 0,
    val users: List<ChatUser>? = null,
)

@Serializable
data class PresenceGetResponse(val channels: Map<String, PresenceChannelState> = emptyMap())

@Serializable
data class ChatChannel(
    val id: Int,
    val title: String? = null,
    val unicodeTitle: String? = null,
    val slug: String? = null,
    val description: String? = null,
    val chatable: ChatChannelChatable? = null,
    val chatableType: String? = null,
    val lastMessage: ChatLastMessage? = null,
    val currentUserMembership: ChatMembership? = null,
) {
    val isDirectMessage: Boolean
        get() = chatableType == "DirectMessage" || chatable?.users?.isNotEmpty() == true
}

/** One channel, as `direct-message-channels` answers when opening a DM. */
@Serializable
data class ChatChannelResponse(val channel: ChatChannel? = null)

@Serializable
data class ChatChannelsResponse(
    val channels: List<ChatChannel>? = null,
    val publicChannels: List<ChatChannel>? = null,
    val directMessageChannels: List<ChatChannel>? = null,
    val tracking: ChatTrackingReport? = null,
) {
    val allChannels: List<ChatChannel>
        get() {
            val structured = directMessageChannels.orEmpty() + publicChannels.orEmpty()
            return structured.ifEmpty { channels.orEmpty() }
        }
}

// ---------------------------------------------------------------- Klipy GIF

@Serializable
data class KlipyFormat(val url: String? = null, val dims: List<Int>? = null)

@Serializable
data class KlipyGif(val title: String? = null, val mediaFormats: Map<String, KlipyFormat>? = null)

@Serializable
data class KlipySearchResponse(val results: List<KlipyGif>? = null)

// ----------------------------------------------------------------- Check-in

/**
 * The answer to `POST /checkin`.
 *
 * Note the status code says nothing: a second check-in on the same day is a
 * 200 carrying `success: false`, and only the gates in front of the action
 * (rate limit, replayed nonce) answer 4xx. Read [success], never the code.
 */
@Serializable
data class CheckinResponse(
    val success: FlexibleBool? = null,
    /** Energy awarded, present only on the first check-in of the day. */
    val points: Int? = null,
    /** The server's idea of today, in [timezone] — the day this counted for. */
    val userDate: String? = null,
    val timezone: String? = null,
    /** Prose, and the only explanation of a refusal the endpoint gives. */
    val message: String? = null,
) {
    val checkedIn: Boolean get() = success?.value == true
}

// ---------------------------------------------------------- Upgrade progress

/**
 * One requirement for the next trust level.
 *
 * [label] and [text] arrive already written and translated — the plugin renders
 * every requirement server-side so its wording stays in step with the site's
 * settings. Nothing here needs a string resource.
 */
@Serializable
data class UpgradeCondition(
    val key: String? = null,
    val label: String? = null,
    val text: String? = null,
    /** "当前" / "近 6 个月内" — the window the requirement is measured over. */
    val scope: String? = null,
    /** standing · activity · recent · all_time; the sections of the page. */
    val group: String? = null,
    /** min · max · boolean. A `max` requirement is met by staying *under* it. */
    val comparison: String? = null,
    val value: Int? = null,
    val target: Int? = null,
    val met: Boolean = false,
) {
    /** Only a counted requirement has a bar to draw; a boolean one is a state. */
    val isCounted: Boolean get() = comparison != "boolean" && value != null && target != null

    val fraction: Float
        get() {
            val current = value ?: return if (met) 1f else 0f
            val goal = target ?: return if (met) 1f else 0f
            if (comparison == "max") return if (met) 1f else 0f
            if (goal <= 0) return 1f
            return (current.toFloat() / goal).coerceIn(0f, 1f)
        }
}

/**
 * `GET /u/:username/upgrade-progress` — how close an account is to the next
 * trust level.
 *
 * Three states are not a checklist and carry [message] instead: the top level
 * reached, the leader step (granted by a vote, so there is nothing to tick
 * off), and a locked trust level. In the first two [retention] is set and the
 * conditions describe *keeping* the level rather than earning the next one.
 */
@Serializable
data class UpgradeProgress(
    val currentLevel: Int = 0,
    val currentLevelName: String? = null,
    val nextLevel: Int? = null,
    val nextLevelName: String? = null,
    /** Days the windowed requirements are measured over; null before TL3. */
    val evaluationPeriod: Int? = null,
    val conditions: List<UpgradeCondition> = emptyList(),
    val totalConditions: Int = 0,
    val metCount: Int = 0,
    val maxLevelReached: Boolean = false,
    val leaderUpgradeNeeded: Boolean = false,
    val retention: Boolean = false,
    val trustLevelLocked: Boolean = false,
    val message: String? = null,
) {
    val fraction: Float
        get() = if (totalConditions <= 0) 0f else (metCount.toFloat() / totalConditions).coerceIn(0f, 1f)

    val allMet: Boolean get() = totalConditions > 0 && metCount == totalConditions
}

// ------------------------------------------------------------ nodeloc plugin

/**
 * What the site's own app plugin says it can do.
 *
 * Asked before anything else and cached for the session: the app draws a
 * check-in button because the site answers that it has one, not because this
 * build happened to ship the code for it.
 */
@Serializable
data class MobileMeta(
    val apiVersion: Int = 0,
    val features: Map<String, Boolean> = emptyMap(),
) {
    fun has(feature: String): Boolean = features[feature] == true
}

/** The read side `discourse-checkin` never had; see [CheckinResponse]. */
@Serializable
data class CheckinStatus(
    val checkedInToday: Boolean = false,
    /** The server's day, in [timezone]. The app compares against this, not its own clock. */
    val today: String? = null,
    val timezone: String? = null,
    val pointsToday: Int? = null,
    /** What a claim would be worth right now; null once today is spent. */
    val nextPoints: Int? = null,
    val streak: Int = 0,
    val totalDays: Int = 0,
)

/** How much of this handset the account has agreed to show on a post. */
@Serializable
data class PostSourcePreference(
    val level: Int = 0,
    val levels: List<Int> = emptyList(),
)

/**
 * A release, as the endpoint answers for this platform.
 *
 * `release` is null for "you are up to date" — a 200 rather than a 404,
 * because being current is an answer and not a fault.
 */
@Serializable
data class ReleaseResponse(val release: ReleaseInfo? = null)

@Serializable
data class ReleaseInfo(
    val platform: String? = null,
    val versionCode: Int = 0,
    val versionName: String = "",
    val url: String = "",
    val sha256: String? = null,
    val size: Long? = null,
    val notes: String? = null,
    val mandatory: Boolean = false,
)
