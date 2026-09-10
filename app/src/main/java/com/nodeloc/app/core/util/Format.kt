package com.nodeloc.app.core.util

import com.nodeloc.app.core.html.PostHtmlParser
import com.nodeloc.app.core.html.breakingLongTokens
import com.nodeloc.app.core.html.plainTextFromHtml
import com.nodeloc.app.core.model.DiscourseCategory
import com.nodeloc.app.core.model.DiscourseUser
import com.nodeloc.app.core.model.ImageVariant
import com.nodeloc.app.core.model.Post
import com.nodeloc.app.core.model.PostMedia
import com.nodeloc.app.core.model.TopicListItem
import com.nodeloc.app.core.model.TopicThumbnail
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.network.DiscourseConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Date parsing and the compact relative time used all over the UI. */
object DiscourseFormat {

    fun instant(raw: String?): Instant? {
        if (raw.isNullOrEmpty()) return null
        return try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        }
    }

    /**
     * Month, day and time, spelled the way the current language spells it.
     *
     * ICU decides the field order, the separators and whether the clock has 24
     * hours on it, which is the only way ten languages get this right without
     * ten hand-written patterns behind ten `values-xx` folders. `j` in the
     * skeleton is "the hour cycle this locale actually uses"; Chinese keeps the
     * 14:34 it always had, English gets 2:34 PM.
     *
     * The fallback is not decoration: a skeleton can come back containing a
     * field letter java.time has never heard of, and that throws.
     */
    private fun monthDayTime(): java.time.format.DateTimeFormatter {
        val locale = java.util.Locale.getDefault()
        val skeleton = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdjmm")
        return runCatching { java.time.format.DateTimeFormatter.ofPattern(skeleton, locale) }
            .getOrElse { java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm", locale) }
    }

    /**
     * The title to show, translated when the site translated it.
     *
     * Only when it is a translation. `fancy_title` is also the escaped,
     * emoji-shortcoded form of every other title, so preferring it outright
     * would trade a real 📢 for a literal `:loudspeaker:` across the whole feed
     * to gain nothing. The server says which case this is, so take it at its
     * word and leave the rest alone.
     */
    fun displayTitle(fancyTitle: String?, localized: Boolean?, title: String): String {
        if (localized != true) return title
        val translated = fancyTitle?.let { PostHtmlParser.decodeEntities(it) }?.takeIf { it.isNotBlank() }
        return translated ?: title
    }

    /** An absolute moment from the server, for a card that states a deadline. */
    fun dayAndTime(raw: String?): String {
        val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull() ?: return ""
        return java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
            .format(monthDayTime())
    }

    /**
     * When a lottery started now would draw, spelled out for the composer.
     *
     * The sheet asks for a number of days because that is the control a thumb
     * can use; this is what the server will actually be told, so the two are
     * never left to be guessed at from each other.
     */
    fun drawMoment(days: Int): String {
        val at = java.time.LocalDateTime.now().plusDays(days.toLong())
        return at.format(monthDayTime())
    }

    fun relative(raw: String?): String {
        val instant = instant(raw) ?: return ""
        val seconds = ((System.currentTimeMillis() - instant.toEpochMilli()) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "now"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3600}h"
            seconds < 604_800 -> "${seconds / 86_400}d"
            seconds < 2_629_800 -> "${seconds / 604_800}w"
            else -> "${seconds / 2_629_800}mo"
        }
    }

    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    private val minuteFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    fun day(raw: String?): String = instant(raw)?.let { dayFormatter.format(it) }.orEmpty()

    fun clock(raw: String?): String = instant(raw)?.let { minuteFormatter.format(it) }.orEmpty()

    /** Plain-text excerpt for list rows; post bodies go through the parser. */
    fun plainText(html: String?): String = plainTextFromHtml(html)

    /**
     * Feed media for a topic. Prefers the responsive `thumbnails` set — it
     * carries the representative image at several widths — and falls back to
     * the single `image_url`.
     */
    /**
     * Every picture in the topic, not just the first.
     *
     * `thumbnails` looks like a gallery and is not — it is one picture at
     * several widths, which is why a row with ten images only ever showed one
     * and the card's pager was unreachable. The gallery is `topic_images`, with
     * `topic_thumbnails` holding a display copy of each, index for index.
     */
    fun mediaItems(topic: TopicListItem): List<PostMedia> {
        val first = responsiveMedia(topic.thumbnails)
        val gallery = topic.topicImages.orEmpty()
        if (gallery.size > 1) {
            val display = topic.topicThumbnails.orEmpty()
            return gallery.mapIndexedNotNull { index, original ->
                val full = DiscourseConfig.absoluteUrl(original) ?: return@mapIndexedNotNull null
                // The first one keeps the variants `thumbnails` described, so
                // the card still fetches a size that matches how big it draws.
                if (index == 0 && first != null) return@mapIndexedNotNull first.copy(fullUrl = full)
                val shown = display.getOrNull(index)?.let { DiscourseConfig.absoluteUrl(it) } ?: full
                val (width, height) = dimensions(shown)
                PostMedia(url = shown, width = width, height = height, fullUrl = full)
            }
        }
        first?.let { return listOf(it) }
        val raw = topic.imageUrl ?: return emptyList()
        val url = DiscourseConfig.absoluteUrl(raw) ?: return emptyList()
        val (width, height) = dimensions(raw)
        return listOf(PostMedia(url = url, width = width, height = height))
    }

    private fun responsiveMedia(thumbnails: List<TopicThumbnail>?): PostMedia? {
        if (thumbnails.isNullOrEmpty()) return null
        val variants = thumbnails.mapNotNull { thumb ->
            val width = thumb.width ?: return@mapNotNull null
            val url = DiscourseConfig.absoluteUrl(thumb.url) ?: return@mapNotNull null
            if (width <= 0) null else ImageVariant(width, url)
        }.sortedBy { it.width }
        val largest = variants.lastOrNull() ?: return null
        val original = thumbnails.maxByOrNull { it.width ?: 0 }
        return PostMedia(
            url = largest.url,
            width = original?.width,
            height = original?.height,
            variants = variants,
        )
    }

    private val dimensionPattern = Regex("(\\d+)x(\\d+)")

    /**
     * Pixel size read out of an upload's file name, which is where Discourse
     * puts it — `…_2_576x1024.jpeg`. The only size a caller has for a picture it
     * has not fetched yet, and enough to reserve the right box for it.
     */
    fun dimensionsOf(raw: String?): Pair<Int?, Int?> =
        if (raw.isNullOrBlank()) null to null else dimensions(raw)

    private fun dimensions(raw: String): Pair<Int?, Int?> {
        val match = dimensionPattern.find(raw) ?: return null to null
        return match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
    }

    fun count(value: Int?): String {
        val number = value ?: 0
        return when {
            number >= 10_000 -> "${number / 1000}k"
            number >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", number / 1000f)
            else -> number.toString()
        }
    }
}

/** Shared topic → [Post] mapping, used by the home feed and node topic lists. */
object FeedMapper {
    /**
     * `posts_count` comes back as 0 on some rows of `top.json` — including
     * topics with thousands of posts — so `highest_post_number` stands in.
     * It is the weaker of the two where both are real, counting post numbers
     * that deleted posts have vacated, which is why it is only the fallback.
     */
    private fun topicPostCount(topic: TopicListItem): Int =
        topic.postsCount?.takeIf { it > 0 } ?: topic.highestPostNumber?.takeIf { it > 0 } ?: 1

    fun post(
        topic: TopicListItem,
        usersById: Map<Int, DiscourseUser>,
        category: DiscourseCategory? = null,
    ): Post {
        val author = topic.posters?.firstNotNullOfOrNull { poster -> poster.userId?.let { usersById[it] } }
        val media = DiscourseFormat.mediaItems(topic)
        val letter = (author?.username ?: category?.name ?: "N").take(1).uppercase()
        // Brand new, or read progress trailing the latest post.
        val hasUnreadPosts = topic.lastReadPostNumber != null &&
            topic.lastReadPostNumber < (topic.highestPostNumber ?: 0)
        return Post(
            id = topic.id,
            node = category?.let { "n/${it.slug}" } ?: "n/nodeloc",
            avatarLetter = letter,
            variant = topic.id % 2,
            time = DiscourseFormat.relative(topic.bumpedAt ?: topic.lastPostedAt ?: topic.createdAt),
            // A bare URL in a title has no wrap opportunity and would otherwise
            // widen the whole row.
            title = DiscourseFormat.displayTitle(
                topic.fancyTitle,
                topic.fancyTitleLocalized,
                topic.title,
            ),
            excerpt = DiscourseFormat.plainText(topic.excerpt),
            // Both counts are the ones the topic itself shows once opened.
            //
            // `like_count` is every like in the thread, so a row promised 67
            // and the post it opened onto showed 26. `reply_count` is the
            // other way about — it counts only posts answering another post,
            // which on that same topic was 5 against 32 actual replies.
            baseVotes = topic.opLikeCount ?: topic.likeCount ?: 0,
            comments = (topicPostCount(topic) - 1).coerceAtLeast(0),
            hasImage = media.isNotEmpty(),
            pinned = topic.pinned == true,
            isUnread = topic.unseen == true || hasUnreadPosts,
            imageUrl = media.firstOrNull()?.url,
            avatarUrl = DiscourseConfig.avatarUrl(author?.avatarTemplate, 80),
            authorUsername = author?.username,
            authorName = author?.name,
            media = media,
            tags = topic.tags.orEmpty().mapNotNull { it.name },
            videoUrl = DiscourseConfig.absoluteUrl(topic.topicVideoUrl),
            categoryId = topic.categoryId,
            // Only where discourse-vote serialized a score: that is the
            // plugin's own signal that voting applies to this row, and the app
            // cannot read the site setting behind it. A row also needs
            // `opPostId` to be votable at all — the ballot goes on that post.
            voteScore = topic.opVoteScore,
            voteDirection = VoteDirection.from(topic.opVoteDirection),
            opPostId = topic.opPostId,
            canVoteDown = topic.opCanVoteDown,
        )
    }
}
