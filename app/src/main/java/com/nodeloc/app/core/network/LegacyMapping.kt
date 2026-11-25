package com.nodeloc.app.core.network

import com.nodeloc.app.core.model.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Native API answers, reshaped into the models the screens were written for.
 *
 * Pure functions with no I/O, so every mapping can be pinned by a JVM test with a
 * fixture captured from a real server. The rule throughout: fill what the old
 * model meant, leave the rest at its default, and never invent data the server
 * did not send.
 */
object Legacy {

    /**
     * A stand-in user id for places that only carry a username.
     *
     * The old list payloads joined rows to a `users` array by id; the native ones
     * name the author directly. Ids made from the name keep that join working
     * inside one response, which is the only place they are ever compared.
     */
    fun syntheticUserId(username: String?): Int =
        if (username.isNullOrEmpty()) 0 else (username.lowercase(Locale.ROOT).hashCode() and 0x3fffffff) or 0x40000000

    fun user(username: String, avatar: String?, name: String? = null, id: Int? = null): DiscourseUser =
        DiscourseUser(id = id ?: syntheticUserId(username), username = username, name = name, avatarTemplate = avatar)

    fun direction(viewerVote: Int): String = when {
        viewerVote > 0 -> VoteDirection.Up.wire
        viewerVote < 0 -> VoteDirection.Down.wire
        else -> VoteDirection.None.wire
    }

    fun voteValue(direction: VoteDirection): Int = when (direction) {
        VoteDirection.Up -> 1
        VoteDirection.Down -> -1
        VoteDirection.None -> 0
    }

    // ------------------------------------------------------------ topic lists

    fun topicListItem(topic: ApiTopicSummary, nodeId: Int?): TopicListItem {
        val replies = topic.replyCount ?: 0
        val lastRead = topic.lastReadPostNumber
        // The native list reports unread as a count past the reader's mark,
        // the old one as two post numbers; this rebuilds the pair.
        val highest = if (lastRead != null) lastRead + topic.unreadCount else replies + 1
        val images = topic.images.orEmpty().ifEmpty { listOfNotNull(topic.imageUrl) }
        return TopicListItem(
            id = topic.id,
            title = topic.title,
            postsCount = replies + 1,
            replyCount = replies,
            views = topic.views,
            createdAt = topic.createdAt,
            lastPostedAt = topic.lastPostedAt,
            bumpedAt = topic.lastPostedAt ?: topic.createdAt,
            categoryId = nodeId,
            pinned = topic.pinned,
            lastReadPostNumber = lastRead,
            highestPostNumber = highest,
            excerpt = topic.excerpt,
            imageUrl = topic.imageUrl ?: images.firstOrNull(),
            topicImages = images.takeIf { it.size > 1 },
            posters = topic.author?.let { listOf(TopicPoster(userId = topic.authorId ?: syntheticUserId(it))) },
            tags = topic.tags.map { TopicTag(name = it, slug = it) },
            opVoteScore = topic.voteScore,
            opPostId = topic.firstPostId,
            opVoteDirection = topic.viewerVote?.let(::direction),
            opCanVoteDown = topic.firstPostId != null,
        )
    }

    fun topicUsers(topics: List<ApiTopicSummary>): List<DiscourseUser> =
        topics.mapNotNull { t -> t.author?.let { user(it, t.authorAvatar, id = t.authorId) } }.distinctBy { it.id }

    // ------------------------------------------------------------------ posts

    data class Viewer(val id: Int?, val canModerate: Boolean)

    fun topicPost(post: ApiPost, viewer: Viewer, children: List<TopicPost>? = null): TopicPost {
        val yours = viewer.id != null && post.authorId == viewer.id
        val likes = post.upvotes
        return TopicPost(
            id = post.id,
            username = post.author,
            avatarTemplate = post.authorAvatar,
            createdAt = post.createdAt,
            cooked = post.html,
            postNumber = post.postNumber,
            replyToPostNumber = post.replyToPostNumber,
            actionsSummary = listOf(ActionSummary(id = 2, count = likes, acted = post.viewerVote > 0)),
            bookmarked = post.viewerBookmarked,
            // Bookmarks are keyed by post now; the old flow takes one back off by
            // this id, so it is the post's.
            bookmarkId = post.id.takeIf { post.viewerBookmarked },
            version = post.version,
            children = children,
            directReplyCount = post.replies ?: post.replyCount,
            totalDescendantCount = post.replies ?: post.replyCount,
            userTitle = post.authorTitle,
            yours = yours,
            canEdit = post.canEdit ?: ((yours || viewer.canModerate) && !post.deleted),
            canDelete = post.canDelete ?: ((yours || viewer.canModerate) && !post.deleted && post.postNumber > 1),
            canRecover = viewer.canModerate && post.deleted,
            deletedAt = if (post.deleted) post.editedAt ?: post.createdAt else null,
            locked = post.locked,
            voteScore = post.upvotes - post.downvotes,
            voteDirection = direction(post.viewerVote),
            canVoteDown = viewer.id != null,
        )
    }

    /**
     * Rebuilds the reply tree from the thread view's pre-order list.
     *
     * The native thread endpoint sends each post with its depth rather than
     * nested; a depth that goes up is a child of the post before it, a depth
     * that comes back down returns to an earlier ancestor.
     */
    fun tree(posts: List<ApiPost>, viewer: Viewer): List<TopicPost> {
        class Node(val post: ApiPost, val depth: Int) {
            val children = mutableListOf<Node>()
        }
        val roots = mutableListOf<Node>()
        val stack = ArrayDeque<Node>()
        val base = posts.minOfOrNull { it.depth ?: 1 } ?: 1
        for (post in posts) {
            if (post.kind == "action") continue
            val node = Node(post, post.depth ?: base)
            while (stack.isNotEmpty() && stack.last().depth >= node.depth) stack.removeLast()
            if (stack.isEmpty()) roots += node else stack.last().children += node
            stack.addLast(node)
        }
        fun convert(node: Node): TopicPost =
            topicPost(node.post, viewer, node.children.map(::convert).takeIf { it.isNotEmpty() })
        return roots.map(::convert)
    }

    fun topicResponse(page: ApiTopicPage, nodeId: Int?, viewer: Viewer): TopicResponse {
        val topic = page.topic
        val posts = page.posts.filter { it.kind != "action" }.map { topicPost(it, viewer) }
        val replies = topic.replyCount ?: (posts.size - 1).coerceAtLeast(0)
        return TopicResponse(
            id = topic.id,
            title = topic.title,
            archetype = "regular",
            highestPostNumber = topic.highestPostNumber,
            postsCount = replies + 1,
            likeCount = page.posts.firstOrNull()?.upvotes,
            views = topic.views,
            categoryId = nodeId,
            createdAt = topic.createdAt,
            closed = topic.closed,
            postStream = PostStream(posts = posts, stream = posts.map { it.id }),
        )
    }

    // ------------------------------------------------------------------ polls

    fun poll(poll: ApiPoll): PostPoll = PostPoll(
        id = poll.id,
        name = poll.name.ifEmpty { "poll" },
        type = when (poll.type) {
            "multiple", "regular" -> poll.type
            null, "" -> "regular"
            else -> poll.type
        },
        status = if (poll.closed) "closed" else "open",
        results = poll.results,
        min = poll.min,
        max = poll.max,
        options = poll.options.map { PollOptionResult(id = it.id.toString(), html = it.text, votes = it.votes) },
        voters = poll.voters,
        close = poll.closeAt,
        isPublic = poll.public,
    )

    fun pollVotes(polls: List<ApiPoll>): Map<String, List<String>> =
        polls.filter { p -> p.options.any { it.chosen } }
            .associate { p -> p.name.ifEmpty { "poll" } to p.options.filter { it.chosen }.map { it.id.toString() } }

    fun lottery(lottery: ApiLottery): PostLottery = PostLottery(
        id = lottery.id,
        title = lottery.title,
        postId = lottery.postId,
        minParticipants = lottery.minParticipants,
        maxParticipants = lottery.maxParticipants,
        maxTicketsPerUser = lottery.maxTickets,
        minTicketsPerUser = lottery.minTickets,
        minTrustLevel = lottery.minTrustLevel,
        drawAt = lottery.drawAt,
        status = lottery.status,
        levels = lottery.prizes.mapIndexed { index, prize ->
            LotteryPrizeLevel(id = index + 1, name = prize.name, prize = prize.prize, quantity = prize.quantity)
        },
        ticketsCount = lottery.ticketCount,
        participantsCount = lottery.participantCount,
        userTickets = lottery.myTickets,
        isParticipating = (lottery.myTickets ?: 0) > 0,
        participants = lottery.participants.map {
            LotteryParticipant(username = it.username, avatarTemplate = it.avatarUrl, tickets = it.tickets)
        },
        winners = lottery.prizes.flatMap { prize ->
            prize.winners.map {
                LotteryWinner(username = it.username, avatarTemplate = it.avatarUrl, levelName = prize.name, prize = prize.prize)
            }
        },
    )

    fun redEnvelope(envelope: ApiRedEnvelope, topicId: Int): TopicRedEnvelope {
        val total = envelope.totalCount ?: 0
        val claimed = envelope.claimedCount ?: envelope.claims.size
        return TopicRedEnvelope(
            id = envelope.id ?: topicId,
            topicId = envelope.topicId ?: topicId,
            totalPoints = envelope.totalPoints,
            totalCount = envelope.totalCount,
            claimedCount = claimed,
            remainingPoints = envelope.remainingPoints,
            availableCount = (total - claimed).coerceAtLeast(0),
            exhausted = envelope.ended || (total in 1..claimed),
            claimPercentage = if (total > 0) claimed * 100.0 / total else null,
            createdAt = envelope.createdAt,
        )
    }

    // ------------------------------------------------------------------ nodes

    fun category(node: ApiNode, children: List<DiscourseCategory>? = null): DiscourseCategory = DiscourseCategory(
        id = node.id,
        name = node.name,
        color = node.color,
        slug = node.slug,
        topicCount = node.topicCount,
        postCount = node.postCount,
        memberCount = node.memberCount,
        descriptionExcerpt = node.description,
        description = node.description,
        parentCategoryId = node.parentId,
        isJoined = node.joined,
        uploadedLogo = node.logoUrl?.let { DiscourseUploadAsset(url = it) },
        uploadedBackground = node.bannerUrl?.let { DiscourseUploadAsset(url = it) },
        url = "/n/${node.slug}",
        subcategoryList = children,
        notificationLevel = when (node.notificationLevel) {
            "watching" -> 3
            "tracking" -> 2
            "muted" -> 0
            null -> null
            else -> 1
        },
    )

    fun nodeLevel(level: Int): String = when (level) {
        3, 4 -> "watching"
        2 -> "tracking"
        0 -> "muted"
        else -> "normal"
    }

    // ---------------------------------------------------------- notifications

    /**
     * Native kinds onto the Discourse type numbers the inbox and push code
     * branch on. A system notice becomes type 0 carrying its own title, which
     * the formatter shows verbatim rather than as group-inbox wording.
     */
    fun notification(item: ApiNotification): DiscourseNotification {
        val data = item.data as? JsonObject
        fun field(name: String): String? = (data?.get(name) as? JsonPrimitive)?.contentOrNull
        val type = when (item.kind) {
            "reply" -> NotificationTypes.REPLIED
            "mention" -> NotificationTypes.MENTIONED
            "vote" -> if (item.actorCount > 1) NotificationTypes.LIKED_CONSOLIDATED else NotificationTypes.LIKED
            "watching" -> NotificationTypes.POSTED
            "followed_topic" -> NotificationTypes.FOLLOWING_CREATED_TOPIC
            "follow" -> NotificationTypes.FOLLOWING
            "message" -> NotificationTypes.CHAT_MESSAGE
            "node_topic", "node_hot_topic" -> NotificationTypes.WATCHING_CATEGORY_OR_TAG
            "repost" -> NotificationTypes.LINKED
            else -> 0
        }
        val conversation = (data?.get("conversation") as? JsonPrimitive)?.intOrNull
        val title = item.topicTitle
            ?: if (item.kind == "message") field("excerpt") else field("title") ?: field("body")
        return DiscourseNotification(
            id = item.id,
            notificationType = type,
            read = item.read,
            createdAt = item.createdAt,
            data = NotificationData(
                topicTitle = title,
                displayUsername = item.actor,
                username = item.actor,
                chatChannelId = conversation,
            ),
            topicId = item.topicId,
            postNumber = item.postNumber,
        )
    }

    // ------------------------------------------------------------------- chat

    fun chatUser(username: String, avatar: String?): ChatUser =
        ChatUser(id = syntheticUserId(username), username = username, avatarTemplate = avatar)

    fun chatMessage(message: ApiChatMessage, channelId: Int, videos: Map<String, ApiVideo?> = emptyMap()): ChatMessage {
        val (html, images) = chatImages(message.html, videos)
        return chatMessageOf(message, channelId, html, images)
    }

    private fun chatMessageOf(message: ApiChatMessage, channelId: Int, html: String, images: List<DiscourseUpload>) = ChatMessage(
        id = message.id,
        message = if (message.deleted) null else com.nodeloc.app.core.util.DiscourseFormat.plainText(html),
        cooked = html,
        uploads = images.takeIf { it.isNotEmpty() },
        createdAt = message.createdAt,
        deletedAt = if (message.deleted) message.createdAt else null,
        chatChannelId = channelId,
        user = chatUser(message.sender, message.avatar),
        inReplyTo = message.reply?.let {
            ChatInReplyToMessage(
                id = it.id,
                excerpt = it.excerpt,
                message = it.excerpt,
                user = it.sender?.let { name -> chatUser(name, null) },
            )
        },
    )

    private val chatImgTag = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val chatImgSrc = Regex("""\bsrc\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val chatImgClass = Regex("""\bclass\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    /** An uploaded video in message HTML: only its id, the stream is looked up (`GET /api/v1/videos/{sha1}`). */
    val chatVideoMarkup = Regex("""<div class="video-embed" data-provider="upload" data-video-id="([0-9a-fA-F]{40})"></div>""")
    /** A video file written as an image (`![](….mp4)`), which the server sends as a plain `<video src>`. */
    private val chatVideoFile = Regex("""<video\b[^>]*\bsrc="([^"]+)"[^>]*>\s*</video>""", RegexOption.IGNORE_CASE)
    private val chatEmptyLink = Regex("""<a\b[^>]*>\s*</a>""", RegexOption.IGNORE_CASE)
    private val chatEmptyParagraph = Regex("""<p>(?:\s|<br\s*/?>)*</p>""", RegexOption.IGNORE_CASE)

    private fun chatImgAttr(tag: String, name: String): Int? =
        Regex("""\b$name\s*=\s*"(\d+)"""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The server sends a chat picture inside the message HTML, as an `<img>`;
     * the bubble shows pictures from `uploads` and text from `cooked`. Pictures
     * are moved across (emoji stay inline) so they render as media tiles, and so
     * are uploaded videos whose stream is in [videos] and finished transcoding.
     */
    fun chatImages(html: String, videos: Map<String, ApiVideo?> = emptyMap()): Pair<String, List<DiscourseUpload>> {
        val images = mutableListOf<DiscourseUpload>()
        val withoutVideos = chatVideoMarkup.replace(html) { match ->
            val video = videos[match.groupValues[1].lowercase()] ?: videos[match.groupValues[1]]
            val stream = video?.hlsUrl?.takeIf { video.status == null || video.status == "ready" }
            if (stream == null) {
                match.value
            } else {
                images += DiscourseUpload(id = -(images.size + 1), url = stream, width = video.width, height = video.height, fileExtension = "m3u8")
                ""
            }
        }
        val withoutFiles = chatVideoFile.replace(withoutVideos) { match ->
            val url = match.groupValues[1].replace("&amp;", "&")
            val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
            images += DiscourseUpload(id = -(images.size + 1), url = url, fileExtension = ext ?: "mp4")
            ""
        }
        val stripped = chatImgTag.replace(withoutFiles) { match ->
            val tag = match.value
            val classes = chatImgClass.find(tag)?.groupValues?.get(1).orEmpty()
            val src = chatImgSrc.find(tag)?.groupValues?.get(1)
            if (src == null || classes.split(' ').any { it == "emoji" || it.startsWith("emoji-") }) {
                tag
            } else {
                val url = src.replace("&amp;", "&")
                val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
                images += DiscourseUpload(
                    id = -(images.size + 1),
                    url = url,
                    width = chatImgAttr(tag, "width"),
                    height = chatImgAttr(tag, "height"),
                    fileExtension = ext,
                )
                ""
            }
        }
        if (images.isEmpty()) return html to images
        val cleaned = chatEmptyParagraph.replace(chatEmptyLink.replace(stripped, ""), "").trim()
        return cleaned to images
    }

    fun chatPage(page: ApiChatPage, channelId: Int, videos: Map<String, ApiVideo?> = emptyMap()): ChatMessagesResponse = ChatMessagesResponse(
        // The native page is newest-first; the conversation screen was written
        // for Discourse's oldest-first order.
        messages = page.messages.sortedBy { it.id }.map { chatMessage(it, channelId, videos) },
        meta = ChatMessagesMeta(canLoadMorePast = page.nextBefore != null, canLoadMoreFuture = false),
    )

    fun channel(item: ApiChatChannel): ChatChannel = ChatChannel(
        id = item.id,
        title = item.name,
        slug = item.slug,
        description = item.description,
        chatableType = "Category",
        lastMessage = ChatLastMessage(
            excerpt = item.excerpt,
            message = item.excerpt,
            createdAt = item.lastMessageAt,
            user = item.lastSender?.let { chatUser(it, null) },
        ),
        currentUserMembership = ChatMembership(
            unreadCount = item.unreadCount,
            following = item.joined,
            muted = item.muted,
            notificationLevel = if (item.muted) "never" else "always",
        ),
    )

    fun group(item: ApiChatGroup, me: String?): ChatChannel = ChatChannel(
        id = item.id,
        title = item.name?.takeIf { it.isNotBlank() } ?: item.members.filter { it != me }.joinToString(", "),
        chatableType = "DirectMessage",
        chatable = ChatChannelChatable(
            id = item.id,
            group = true,
            users = item.members.filter { it != me }.map { chatUser(it, null) },
        ),
        lastMessage = ChatLastMessage(
            excerpt = item.excerpt,
            message = item.excerpt,
            createdAt = item.lastMessageAt,
            user = item.lastSender?.let { chatUser(it, null) },
        ),
        currentUserMembership = ChatMembership(
            unreadCount = item.unreadCount,
            following = true,
            muted = item.muted,
            notificationLevel = if (item.muted) "never" else "always",
        ),
    )

    fun direct(item: ApiDirect): ChatChannel = ChatChannel(
        id = item.id,
        title = item.with,
        chatableType = "DirectMessage",
        chatable = ChatChannelChatable(id = item.id, group = false, users = listOf(chatUser(item.with, item.avatar))),
        lastMessage = ChatLastMessage(
            excerpt = item.excerpt,
            message = item.excerpt,
            createdAt = item.lastMessageAt,
        ),
        currentUserMembership = ChatMembership(
            unreadCount = item.unreadCount,
            following = true,
            muted = item.muted,
            notificationLevel = if (item.muted) "never" else "always",
        ),
    )

    // --------------------------------------------------------------- profiles

    fun profile(envelope: ApiUserEnvelope): UserResponse {
        val u = envelope.user
        return UserResponse(
            user = UserProfile(
                id = u.id,
                username = u.username,
                name = u.name,
                avatarTemplate = u.avatarPath,
                createdAt = u.createdAt,
                lastSeenAt = u.lastSeenAt,
                title = u.title ?: u.tierName,
                profileBackgroundUploadUrl = u.coverUrl,
                location = u.location,
                website = u.website,
                bioRaw = u.bioRaw,
                bioExcerpt = u.bioHtml?.let { com.nodeloc.app.core.util.DiscourseFormat.plainText(it) } ?: u.bioRaw,
                trustLevel = u.trustLevel,
                admin = u.isAdmin,
                moderator = u.isModerator,
                postCount = envelope.stats?.postCount,
                topicCount = envelope.stats?.topicCount,
                likesReceived = envelope.stats?.votesReceived,
                totalFollowers = u.followerCount,
                totalFollowing = u.followingCount,
                canFollow = true,
                isFollowed = u.viewerFollowing,
                canIgnoreUsers = true,
            ),
        )
    }

    fun currentUser(user: ApiUser, balance: Long?): CurrentUser = CurrentUser(
        id = user.id,
        username = user.username,
        name = user.name,
        avatarTemplate = user.avatarPath,
        admin = user.isAdmin,
        moderator = user.isModerator,
        canCreateCommunity = true,
        canCreatePoll = true,
        gamificationScore = balance?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
    )

    fun upload(upload: ApiUpload): DiscourseUpload = DiscourseUpload(
        id = upload.id,
        url = upload.url,
        originalFilename = upload.filename,
        filesize = upload.size,
        width = upload.width,
        height = upload.height,
        fileExtension = upload.filename?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() },
    )

    /** A video's markdown is `[video id=…]`, so the composer carries the id in the short URL. */
    fun videoUpload(video: ApiVideo, fileName: String): DiscourseUpload = DiscourseUpload(
        // Videos have no upload row; a negative id keeps them apart from images.
        id = -((video.sha1.hashCode() and 0x3fffffff) or 1),
        url = video.posterUrl ?: video.hlsUrl,
        originalFilename = fileName,
        width = video.width,
        height = video.height,
        fileExtension = fileName.substringAfterLast('.', "mp4"),
        shortUrl = VIDEO_URL_PREFIX + video.sha1,
    )

    const val VIDEO_URL_PREFIX = "video:"

    /** The report reasons the server accepts, in the order the dialog lists them. */
    val reportReasons = listOf("spam", "inappropriate", "harassment", "off_topic", "illegal", "copyright", "other")

    fun reportReasonFor(actionTypeId: Int): String = reportReasons.getOrNull(actionTypeId - 1) ?: "other"

    fun reportTypes(chinese: Boolean): List<PostActionType> = reportReasons.mapIndexed { index, reason ->
        val (zh, en) = when (reason) {
            "spam" -> "垃圾广告" to "Spam"
            "inappropriate" -> "不当内容" to "Inappropriate"
            "harassment" -> "骚扰或攻击" to "Harassment"
            "off_topic" -> "离题" to "Off-topic"
            "illegal" -> "违法内容" to "Illegal"
            "copyright" -> "侵犯版权" to "Copyright"
            else -> "其他" to "Something else"
        }
        PostActionType(
            id = index + 1,
            name = if (chinese) zh else en,
            nameKey = reason,
            appliesTo = listOf("Post"),
            position = index,
            requireMessage = reason == "other",
            isFlag = true,
        )
    }
}

/** The Discourse type numbers [Legacy.notification] targets; see `NotificationFormatter.Type`. */
internal object NotificationTypes {
    const val MENTIONED = 1
    const val REPLIED = 2
    const val LIKED = 5
    const val POSTED = 9
    const val LINKED = 11
    const val LIKED_CONSOLIDATED = 19
    const val CHAT_MESSAGE = 30
    const val WATCHING_CATEGORY_OR_TAG = 36
    const val FOLLOWING = 800
    const val FOLLOWING_CREATED_TOPIC = 801
}
