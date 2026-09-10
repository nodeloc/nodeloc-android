package com.nodeloc.app.feature.media

import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.PostActionState
import com.nodeloc.app.core.html.PostHtmlParser
import com.nodeloc.app.core.model.VideoSuggestionTopic
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable

/** A clip in the full-screen feed, and what the viewer's bars say about it. */
data class VideoFeedItem(
    val topicId: Int?,
    val url: String,
    val source: ImageViewerSource?,
)

/**
 * What plays after the clip on screen.
 *
 * discourse-anyvideo answers `videos/suggestions` with *topics* — the plugin's
 * own overlay links to them and lets the topic page find the player. A swipe
 * cannot wait for a page, so each suggestion is opened here and its opening
 * post read for the clip; a topic that yields none is skipped rather than
 * queued, because the endpoint joins on the upload and a post edited since
 * still qualifies.
 *
 * One item ahead is resolved while the current one plays, which is what makes
 * this a queue instead of a list: the two requests behind every clip have to
 * happen before the finger moves, not after.
 */
class VideoFeedQueue(private val excludeTopicId: Int?) {
    private val services = ServiceLocator.get
    private val pending = ArrayDeque<VideoSuggestionTopic>()

    /** The endpoint samples at random, so it repeats itself across refills. */
    private val seen = mutableSetOf<Int>().apply { excludeTopicId?.let { add(it) } }

    /**
     * The next clip, or null once the site has nothing else to offer.
     *
     * A refill that yields only topics already watched — or only topics with no
     * playable video left in them — would otherwise loop against the endpoint
     * forever, so the pool is asked a bounded number of times.
     */
    suspend fun next(): VideoFeedItem? {
        var refills = 0
        while (true) {
            if (pending.isEmpty()) {
                if (refills++ >= MAX_REFILLS) return null
                val fresh = runCatchingCancellable {
                    services.client.videoSuggestions(excludeTopicId).topics
                }.getOrNull().orEmpty().filterNot { it.id in seen }
                if (fresh.isEmpty()) return null
                seen += fresh.map { it.id }
                pending += fresh
            }
            resolve(pending.removeFirst())?.let { return it }
        }
    }

    private suspend fun resolve(suggestion: VideoSuggestionTopic): VideoFeedItem? {
        val topic = runCatchingCancellable { services.client.topic(suggestion.id) }.getOrNull() ?: return null
        val first = topic.postStream.posts.firstOrNull() ?: return null
        val video = PostHtmlParser.parse(first.cooked).videos.firstOrNull() ?: return null
        val url = DiscourseConfig.absoluteUrl(video.src) ?: return null

        // The site's own category list, already cached after the first topic
        // this session opened; it is where a node's slug comes from, and the
        // suggestion payload carries only the display name.
        services.siteRepository.siteResponse()
        val node = services.siteRepository.category(topic.categoryId)?.slug.orEmpty()
        val author = first.name?.takeIf { it.isNotBlank() } ?: first.username

        val replies = ((topic.postsCount ?: 1) - 1).coerceAtLeast(0)
        return VideoFeedItem(
            topicId = topic.id,
            url = url,
            source = ImageViewerSource(
                node = if (node.isEmpty()) "" else "n/$node",
                title = DiscourseFormat.displayTitle(
                    topic.fancyTitle,
                    topic.fancyTitleLocalized,
                    topic.title,
                ),
                authorName = author,
                avatarUrl = DiscourseConfig.avatarUrl(first.avatarTemplate),
                avatarLetter = author.take(1).uppercase(),
                likeCount = first.likeCount,
                commentCount = replies,
                // The buttons under a suggestion are that topic's, not the
                // host's — which is what [PostActionState.topicId] is for.
                actions = PostActionState(
                    postId = first.id,
                    topicId = topic.id,
                    voteScore = first.voteScore,
                    voteDirection = VoteDirection.from(first.voteDirection),
                    canVoteDown = first.canVoteDown,
                    liked = first.likedByMe,
                    likeCount = first.likeCount,
                    commentCount = replies,
                    rewarded = first.rewardTotal > 0,
                ),
            ),
        )
    }

    private companion object {
        const val MAX_REFILLS = 3
    }
}
