package com.nodeloc.app.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The native `/api/v1` shapes, decoded with [NodelocApi.json] (snake_case).
 *
 * Optional by default for the same reason the old models were: one field typed
 * wrong takes the whole response down, and these are read by a client that may
 * be a release behind the server.
 */

@Serializable
data class ApiUser(
    val id: Int = 0,
    val username: String = "",
    val name: String? = null,
    val role: String? = null,
    val trustLevel: Int? = null,
    val tierName: String? = null,
    val title: String? = null,
    val createdAt: String? = null,
    val email: String? = null,
    val emailVerified: Boolean? = null,
    val bioRaw: String? = null,
    val bioHtml: String? = null,
    val website: String? = null,
    val location: String? = null,
    val avatarUrl: String? = null,
    val avatar: String? = null,
    val coverUrl: String? = null,
    val lastSeenAt: String? = null,
    val followerCount: Int? = null,
    val followingCount: Int? = null,
    val viewerFollowing: Boolean? = null,
    val viewerBlocked: Boolean? = null,
    val canMessage: Boolean? = null,
    /** Why [canMessage] is false, worded and localised by the server; absent when it is true. */
    val messageNote: String? = null,
    /** How many achievements they hold; public even when the list itself is not. */
    val achievementCount: Int? = null,
    /** The nodes they own (主理人), slug and name. */
    val ownedNodes: List<ApiNodeRef> = emptyList(),
) {
    val isAdmin: Boolean get() = role == "admin"
    val isModerator: Boolean get() = role == "moderator" || role == "admin"
    val avatarPath: String? get() = avatarUrl ?: avatar
}

@Serializable
data class ApiUserEnvelope(val user: ApiUser = ApiUser(), val token: String? = null, val stats: ApiUserStats? = null)

@Serializable
data class ApiUserStats(
    val daysVisited: Int? = null,
    val postCount: Int? = null,
    val topicCount: Int? = null,
    val votesReceived: Int? = null,
)

@Serializable
data class ApiTopicSummary(
    val id: Int,
    val title: String = "",
    val excerpt: String? = null,
    val nodeSlug: String? = null,
    val nodeName: String? = null,
    val author: String? = null,
    val authorId: Int? = null,
    val authorAvatar: String? = null,
    val tags: List<String> = emptyList(),
    val closed: Boolean = false,
    val pinned: Boolean = false,
    val featured: Boolean = false,
    val views: Int? = null,
    val replyCount: Int? = null,
    val voteScore: Int? = null,
    val lastPostedAt: String? = null,
    val createdAt: String? = null,
    val lastReadPostNumber: Int? = null,
    val unreadCount: Int = 0,
    val imageUrl: String? = null,
    val images: List<String>? = null,
    /** Lets a list row vote without opening the topic (absent on the personal feed). */
    val firstPostId: Int? = null,
    val viewerVote: Int? = null,
    val adult: Boolean = false,
)

@Serializable
data class ApiTopicList(
    val topics: List<ApiTopicSummary> = emptyList(),
    val nextCursor: String? = null,
    val nextOffset: Int? = null,
)

@Serializable
data class ApiPost(
    val id: Int,
    val postNumber: Int = 0,
    val author: String = "",
    val authorId: Int? = null,
    val authorAvatar: String? = null,
    val authorTitle: String? = null,
    val replyToPostNumber: Int? = null,
    val kind: String = "post",
    val actionCode: String? = null,
    val html: String = "",
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val replyCount: Int = 0,
    val version: Int = 1,
    val editedAt: String? = null,
    val deleted: Boolean = false,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val collapsed: Boolean = false,
    val viewerVote: Int = 0,
    val viewerBookmarked: Boolean = false,
    val canEdit: Boolean? = null,
    val canDelete: Boolean? = null,
    val createdAt: String? = null,
    // Thread view only.
    val depth: Int? = null,
    val replies: Int? = null,
)

@Serializable
data class ApiTopicDetail(
    val id: Int = 0,
    val title: String = "",
    val nodeSlug: String? = null,
    val nodeName: String? = null,
    val author: String? = null,
    val tags: List<String> = emptyList(),
    val closed: Boolean = false,
    val pinned: Boolean = false,
    val featured: Boolean = false,
    val views: Int? = null,
    val replyCount: Int? = null,
    val highestPostNumber: Int? = null,
    val voteScore: Int? = null,
    val createdAt: String? = null,
    val viewerFollowing: Boolean? = null,
    val canAccept: Boolean? = null,
)

@Serializable
data class ApiTopicPermissions(val reply: Boolean = false, val moderate: Boolean = false)

@Serializable
data class ApiTopicPage(
    val topic: ApiTopicDetail = ApiTopicDetail(),
    val posts: List<ApiPost> = emptyList(),
    val nextAfter: Int? = null,
    val permissions: ApiTopicPermissions = ApiTopicPermissions(),
)

@Serializable
data class ApiThreadPage(
    val posts: List<ApiPost> = emptyList(),
    val maxDepth: Int? = null,
    val nextCursor: String? = null,
    val nextOffset: Int? = null,
    val hasMore: Boolean? = null,
)

@Serializable
data class ApiNode(
    val id: Int,
    val parentId: Int? = null,
    val slug: String = "",
    val name: String = "",
    val description: String? = null,
    val color: String? = null,
    val logoUrl: String? = null,
    val bannerUrl: String? = null,
    val visibility: String? = null,
    val topicCount: Int? = null,
    val postCount: Int? = null,
    val memberCount: Int? = null,
    val ownerId: Int? = null,
    val joined: Boolean = false,
    val notificationLevel: String? = null,
)

@Serializable
data class ApiNodeList(val nodes: List<ApiNode> = emptyList())

@Serializable
data class ApiNodeDetail(
    val node: ApiNode? = null,
    val bannerUrl: String? = null,
    val permissions: JsonObject? = null,
    val posting: ApiNodePosting? = null,
)

/**
 * A node's rules for a new topic's tags: at least [minTags], and when
 * [tagsRestricted] only the [tagOptions].
 */
@Serializable
data class ApiNodePosting(
    val minTags: Int = 0,
    val tagsRestricted: Boolean = false,
    val tagOptions: List<String> = emptyList(),
)

@Serializable
data class ApiNotification(
    val id: Int,
    val kind: String = "",
    val actor: String? = null,
    val actorAvatar: String? = null,
    val postId: Int? = null,
    val actorCount: Int = 1,
    val topicId: Int? = null,
    val topicTitle: String? = null,
    val postNumber: Int? = null,
    val data: JsonElement? = null,
    val read: Boolean = false,
    val createdAt: String? = null,
)

@Serializable
data class ApiNotificationList(
    val notifications: List<ApiNotification> = emptyList(),
    val unread: Int = 0,
    val nextBefore: Long? = null,
)

@Serializable
data class ApiBookmark(
    val id: Int = 0,
    val postId: Int = 0,
    val postNumber: Int? = null,
    val topicId: Int? = null,
    val topicTitle: String? = null,
    val author: String? = null,
    val html: String? = null,
    val createdAt: String? = null,
)

/** One row of `GET /api/v1/saved`. */
@Serializable
data class ApiSaved(
    val id: Int = 0,
    val postId: Int = 0,
    val postNumber: Int? = null,
    val topicId: Int? = null,
    val topicTitle: String? = null,
    val excerpt: String? = null,
    val author: String? = null,
    val nodeSlug: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiSavedList(val bookmarks: List<ApiSaved> = emptyList(), val total: Int? = null, val nextOffset: Int? = null)

/** `GET /api/v1/me/nodes`: the reader's relation to nodes in one call. */
@Serializable
data class ApiMyNode(
    val slug: String = "",
    val name: String = "",
    val color: String = "",
    /** `member` or `moderator`; owners are stored as `moderator` and the field is absent for an owner with no membership row. */
    val role: String? = null,
    val notificationLevel: String? = null,
)

@Serializable
data class ApiMyNodes(
    val joined: List<ApiMyNode> = emptyList(),
    val favorites: List<ApiMyNode> = emptyList(),
    val muted: List<String> = emptyList(),
)

@Serializable
data class ApiBookmarkList(val bookmarks: List<ApiBookmark> = emptyList(), val nextBefore: Long? = null)

@Serializable
data class ApiDraft(
    val key: String = "",
    /** Whatever the web editor saved; read loosely (see the draft mapper). */
    val data: JsonElement? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ApiDraftList(val drafts: List<ApiDraft> = emptyList())

@Serializable
data class ApiUpload(
    val id: Int = 0,
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val image: Boolean = true,
    val filename: String? = null,
    val size: Long? = null,
)

@Serializable
data class ApiUploadEnvelope(val upload: ApiUpload = ApiUpload())

@Serializable
data class ApiVideo(
    val sha1: String = "",
    val status: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Int? = null,
    val hlsUrl: String? = null,
    val posterUrl: String? = null,
)

@Serializable
data class ApiVideoEnvelope(val video: ApiVideo = ApiVideo())

@Serializable
data class ApiPollOption(val id: Int = 0, val text: String = "", val votes: Int? = null, val chosen: Boolean = false)

@Serializable
data class ApiPoll(
    val id: Int = 0,
    val postId: Int? = null,
    val name: String = "poll",
    val type: String? = null,
    val min: Int? = null,
    val max: Int? = null,
    val results: String? = null,
    val public: Boolean = false,
    val closeAt: String? = null,
    val closed: Boolean = false,
    val voters: Int? = null,
    val voted: Boolean = false,
    val resultsVisible: Boolean = true,
    val options: List<ApiPollOption> = emptyList(),
)

@Serializable
data class ApiPollList(val polls: List<ApiPoll> = emptyList())

@Serializable
data class ApiPollEnvelope(val poll: ApiPoll? = null)

@Serializable
data class ApiLotteryHolder(
    val username: String? = null,
    val avatarUrl: String? = null,
    val tickets: Int? = null,
    val joinedAt: String? = null,
)

@Serializable
data class ApiLotteryPrize(
    val name: String? = null,
    val prize: String? = null,
    val quantity: Int? = null,
    val winners: List<ApiLotteryHolder> = emptyList(),
)

@Serializable
data class ApiLottery(
    val id: Int = 0,
    val postId: Int? = null,
    val title: String? = null,
    val status: String? = null,
    val drawAt: String? = null,
    val drawnAt: String? = null,
    val minTickets: Int? = null,
    val maxTickets: Int? = null,
    val minParticipants: Int? = null,
    val maxParticipants: Int? = null,
    val minTrustLevel: Int? = null,
    val participantCount: Int? = null,
    val ticketCount: Int? = null,
    val myTickets: Int? = null,
    val prizes: List<ApiLotteryPrize> = emptyList(),
    val participants: List<ApiLotteryHolder> = emptyList(),
)

@Serializable
data class ApiLotteryEnvelope(val lottery: ApiLottery? = null)

/** `GET /lotteries/{id}/participants`: everyone, most tickets first. */
@Serializable
data class ApiLotteryParticipants(
    val participants: List<ApiLotteryHolder> = emptyList(),
    val participantCount: Int? = null,
    val ticketCount: Int? = null,
)

/** `POST /lotteries/{id}/tickets` answers with the buyer's total after the purchase. */
@Serializable
data class ApiLotteryPurchase(val tickets: Int? = null)

@Serializable
data class ApiRedEnvelopeClaim(val username: String? = null, val postId: Int? = null, val points: Int? = null)

@Serializable
data class ApiRedEnvelope(
    val id: Int? = null,
    val topicId: Int? = null,
    val totalPoints: Int? = null,
    val totalCount: Int? = null,
    val claimedCount: Int? = null,
    val remainingPoints: Int? = null,
    val ended: Boolean = false,
    val claims: List<ApiRedEnvelopeClaim> = emptyList(),
    val createdAt: String? = null,
)

@Serializable
data class ApiRedEnvelopeEnvelope(val redEnvelope: ApiRedEnvelope? = null)

@Serializable
data class ApiCheckinStatus(
    val checkedInToday: Boolean = false,
    val todayPoints: Int? = null,
    val total: Int = 0,
    val streak: Int = 0,
)

@Serializable
data class ApiCheckinResult(val points: Int? = null, val balance: Long? = null, val day: String? = null)

@Serializable
data class ApiPointsSummary(val balance: Long = 0)

@Serializable
data class ApiPointsEntry(
    val id: Int = 0,
    val kind: String? = null,
    val amount: Long = 0,
    val memo: String? = null,
    val counterpart: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiPointsHistory(val entries: List<ApiPointsEntry> = emptyList(), val total: Int? = null, val nextBefore: Long? = null)

@Serializable
data class ApiChatChannel(
    val id: Int,
    val slug: String? = null,
    val name: String? = null,
    val description: String? = null,
    val memberCount: Int? = null,
    val joined: Boolean = false,
    val unreadCount: Int = 0,
    val muted: Boolean = false,
    val mentionCount: Int = 0,
    val excerpt: String? = null,
    val lastKind: String? = null,
    val lastSender: String? = null,
    val lastMessageAt: String? = null,
)

@Serializable
data class ApiChatGroup(
    val id: Int,
    val name: String? = null,
    val memberCount: Int? = null,
    val members: List<String> = emptyList(),
    val owner: String? = null,
    val ownerMe: Boolean = false,
    val unreadCount: Int = 0,
    val muted: Boolean = false,
    val excerpt: String? = null,
    val lastKind: String? = null,
    val lastSender: String? = null,
    val lastMessageAt: String? = null,
)

@Serializable
data class ApiDirect(
    val id: Int,
    val with: String = "",
    val avatar: String? = null,
    val excerpt: String? = null,
    val lastKind: String? = null,
    val lastFromMe: Boolean = false,
    val unreadCount: Int = 0,
    val muted: Boolean = false,
    val lastMessageAt: String? = null,
)

@Serializable
data class ApiChatOverview(
    val channels: List<ApiChatChannel> = emptyList(),
    val groups: List<ApiChatGroup> = emptyList(),
    val directs: List<ApiDirect> = emptyList(),
    val unreadCount: Int = 0,
)

@Serializable
data class ApiConversationList(val conversations: List<ApiDirect> = emptyList(), val unreadCount: Int = 0)

@Serializable
data class ApiChatQuote(val id: Int = 0, val sender: String? = null, val excerpt: String? = null, val kind: String? = null)

@Serializable
data class ApiChatEvent(val type: String? = null, val name: String? = null)

@Serializable
data class ApiChatMessage(
    val id: Int,
    val fromMe: Boolean = false,
    val sender: String = "",
    val avatar: String? = null,
    val html: String = "",
    val createdAt: String? = null,
    val deleted: Boolean = false,
    val event: ApiChatEvent? = null,
    val reply: ApiChatQuote? = null,
)

@Serializable
data class ApiChatPage(
    val messages: List<ApiChatMessage> = emptyList(),
    val nextBefore: Int? = null,
    val conversationId: Int? = null,
    val lastReadId: Int? = null,
    val unreadCount: Int? = null,
)

@Serializable
data class ApiChatSent(val message: ApiChatMessage? = null)

@Serializable
data class ApiGroupMember(val username: String = "", val name: String? = null, val avatar: String? = null)

@Serializable
data class ApiGroupMembers(val members: List<ApiGroupMember> = emptyList())

@Serializable
data class ApiMentionUser(val username: String = "", val name: String? = null, val avatar: String? = null)

@Serializable
data class ApiMentionCandidates(val users: List<ApiMentionUser> = emptyList())

@Serializable
data class ApiTag(
    val id: Int? = null,
    val name: String = "",
    val topicCount: Int? = null,
    /** The reader's-language display name; [name] is what is saved. */
    val label: String? = null,
    /** Only staff (site or the node's) may add it. */
    val staffOnly: Boolean = false,
)

@Serializable
data class ApiTagList(val tags: List<ApiTag> = emptyList())

@Serializable
data class ApiUpdateRelease(
    val version: String = "",
    val build: Int = 0,
    val minSupportedBuild: Int = 0,
    val notes: String? = null,
    val downloadUrl: String? = null,
    val sha256: String? = null,
)

@Serializable
data class ApiUpdateCheck(
    val latest: ApiUpdateRelease? = null,
    val updateAvailable: Boolean = false,
    val updateRequired: Boolean = false,
)

@Serializable
data class ApiAuthProvider(val name: String = "", val label: String? = null, val icon: String? = null, val url: String? = null)

@Serializable
data class ApiAuthProviders(val providers: List<ApiAuthProvider> = emptyList())

@Serializable
data class ApiSearchPost(
    val id: Int = 0,
    val topicId: Int? = null,
    val postNumber: Int? = null,
    val excerpt: String? = null,
    val blurb: String? = null,
    val author: String? = null,
)

@Serializable
data class ApiSearchResults(
    val topics: List<ApiTopicSummary> = emptyList(),
    val posts: List<ApiSearchPost> = emptyList(),
    val users: List<ApiMentionUser> = emptyList(),
    val nodes: List<ApiSearchNode> = emptyList(),
    val hasMore: Boolean? = null,
)

/** Search names nodes by slug only; the id comes from the node directory. */
@Serializable
data class ApiSearchNode(
    val slug: String = "",
    val name: String = "",
    val color: String? = null,
    val description: String? = null,
    val memberCount: Int? = null,
    val joined: Boolean = false,
)

@Serializable
data class ApiSession(
    val id: Int = 0,
    val userAgent: String? = null,
    val ip: String? = null,
    val lastSeenAt: String? = null,
    val current: Boolean = false,
)

@Serializable
data class ApiSessions(val sessions: List<ApiSession> = emptyList())

@Serializable
data class ApiIdentity(val provider: String = "", val name: String? = null, val username: String? = null)

@Serializable
data class ApiIdentities(val hasPassword: Boolean = false, val identities: List<ApiIdentity> = emptyList())

@Serializable
data class ApiTwoFactorStatus(val enabled: Boolean = false, val backupCodesLeft: Int = 0)

@Serializable
data class ApiTwoFactorSetup(val secret: String? = null, val uri: String? = null)

@Serializable
data class ApiBackupCodes(val backupCodes: List<String> = emptyList())

@Serializable
data class ApiVoteResult(val upvotes: Int = 0, val downvotes: Int = 0, val viewerVote: Int = 0)

@Serializable
data class ApiCreatedTopic(val topic: ApiIdRef? = null, val post: ApiIdRef? = null, val held: Boolean = false)

@Serializable
data class ApiIdRef(val id: Int = 0, val postNumber: Int? = null, val title: String? = null)

@Serializable
data class ApiCreatedReply(val post: ApiIdRef? = null, val held: Boolean = false)

@Serializable
data class ApiRaw(val raw: String = "")

@Serializable
data class ApiEmoji(val code: String = "", val name: String = "", val url: String = "")

@Serializable
data class ApiEmojiPack(val id: String = "", val name: String = "", val enabled: Boolean = true, val emojis: List<ApiEmoji> = emptyList())

@Serializable
data class ApiEmojiCatalog(val emojis: Map<String, String> = emptyMap(), val packs: List<ApiEmojiPack> = emptyList())

@Serializable
data class ApiReputation(
    val reputation: Int = 0,
    val tier: Int = 0,
    val tierName: String? = null,
    val next: ApiTierStep? = null,
    val needed: Int? = null,
    val tiers: List<ApiTierStep> = emptyList(),
)

@Serializable
data class ApiTierStep(val level: Int = 0, val name: String? = null, val threshold: Int = 0)

@Serializable
data class ApiAchievement(val key: String = "", val name: String = "", val description: String? = null, val icon: String? = null)

@Serializable
data class ApiAchievements(val showcase: List<ApiAchievement> = emptyList())

@Serializable
data class ApiFlags(val flags: Map<String, Boolean> = emptyMap())

/** A node named in passing: a user's `owned_nodes`. */
@Serializable
data class ApiNodeRef(val slug: String = "", val name: String? = null)
