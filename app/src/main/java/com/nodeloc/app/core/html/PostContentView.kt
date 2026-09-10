package com.nodeloc.app.core.html

import android.content.ClipData
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.CirclePlay
import com.composables.icons.lucide.ChevronUp
import androidx.core.net.toUri
import com.nodeloc.app.R
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.FeedMedia
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.media.InlineVideoPlayer

/**
 * Renders a parsed post body with native composables.
 *
 * Sizes come from the design system, not from HTML: 16sp body with 6sp extra
 * leading, replies at 14/5, and each level of quoting drops one step (min 12).
 */
@Composable
fun PostContentView(
    content: PostContent,
    modifier: Modifier = Modifier,
    baseSize: Int = 16,
    lineExtra: Int = 6,
    onOpenLink: (String) -> Unit = {},
    onOpenImage: (PostImage) -> Unit = {},
    onOpenVideo: (PostVideo) -> Unit = {},
    onOpenMention: (String) -> Unit = {},
    pollSlot: @Composable (String) -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s4)) {
        content.blocks.forEach { block ->
            BlockView(block, baseSize, lineExtra, onOpenLink, onOpenImage, onOpenVideo, onOpenMention, pollSlot)
        }
    }
}

@Composable
private fun BlockView(
    block: PostBlock,
    baseSize: Int,
    lineExtra: Int,
    onOpenLink: (String) -> Unit,
    onOpenImage: (PostImage) -> Unit,
    onOpenVideo: (PostVideo) -> Unit,
    onOpenMention: (String) -> Unit,
    pollSlot: @Composable (String) -> Unit,
) {
    when (block) {
        is PostBlock.Paragraph -> InlineText(block.inlines, baseSize, lineExtra, onOpenLink, onOpenMention)

        // One step per level, four steps at most. The old two-step ramp put an
        // in-body `#` at 25sp — larger than the topic's own title, so a post
        // that opened with a heading looked like it had two titles, the wrong
        // one louder.
        is PostBlock.Heading -> InlineText(
            inlines = block.inlines,
            size = (baseSize + (6 - block.level.coerceIn(1, 6))).coerceIn(baseSize, baseSize + 4),
            lineExtra = lineExtra,
            onOpenLink = onOpenLink,
            onOpenMention = onOpenMention,
            weight = FontWeight.SemiBold,
        )

        is PostBlock.Image -> PostImageView(block.image, onOpenImage)

        is PostBlock.Video -> PostVideoView(block.video, onOpenVideo)

        is PostBlock.CodeBlock -> CodeBlockView(block)

        is PostBlock.Quote -> QuoteView(block.quote, baseSize, lineExtra, onOpenLink, onOpenImage, onOpenVideo, onOpenMention, pollSlot)

        is PostBlock.BlockQuote -> NestedBlocks(
            blocks = block.blocks,
            baseSize = baseSize,
            lineExtra = lineExtra,
            modifier = Modifier
                .fillMaxWidth()
                .quoteRail(Nocturne.divider)
                .padding(start = Space.s4, top = Space.s2, bottom = Space.s2),
            onOpenLink = onOpenLink,
            onOpenImage = onOpenImage,
            onOpenVideo = onOpenVideo,
            onOpenMention = onOpenMention,
            pollSlot = pollSlot,
        )

        is PostBlock.ListBlock -> ListView(block, baseSize, lineExtra, onOpenLink, onOpenImage, onOpenVideo, onOpenMention, pollSlot)

        is PostBlock.Table -> TableView(block, baseSize, onOpenLink, onOpenMention)

        is PostBlock.Details -> DetailsView(block, baseSize, lineExtra, onOpenLink, onOpenImage, onOpenVideo, onOpenMention, pollSlot)

        is PostBlock.Spoiler -> SpoilerView(block, baseSize, lineExtra, onOpenLink, onOpenImage, onOpenVideo, onOpenMention, pollSlot)

        is PostBlock.Onebox -> OneboxView(block.onebox, onOpenLink)

        PostBlock.Divider -> Box(
            Modifier.fillMaxWidth().padding(vertical = Space.s2).height(1.dp).background(Nocturne.divider),
        )

        is PostBlock.PollPlaceholder -> pollSlot(block.name)
    }
}

@Composable
private fun NestedBlocks(
    blocks: List<PostBlock>,
    baseSize: Int,
    lineExtra: Int,
    modifier: Modifier = Modifier,
    onOpenLink: (String) -> Unit,
    onOpenImage: (PostImage) -> Unit,
    onOpenVideo: (PostVideo) -> Unit,
    onOpenMention: (String) -> Unit,
    pollSlot: @Composable (String) -> Unit,
) {
    // Quoting steps the text down one notch per level, never below 12.
    val nestedSize = (baseSize - 2).coerceAtLeast(12)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        blocks.forEach {
            BlockView(it, nestedSize, lineExtra, onOpenLink, onOpenImage, onOpenVideo, onOpenMention, pollSlot)
        }
    }
}

/** Reddit-style left rail marking a quoted passage. */
private fun Modifier.quoteRail(color: Color, width: Dp = 2.dp): Modifier =
    drawBehind { drawRect(color = color, size = androidx.compose.ui.geometry.Size(width.toPx(), size.height)) }

@Composable
private fun QuoteView(
    quote: PostQuote,
    baseSize: Int,
    lineExtra: Int,
    onOpenLink: (String) -> Unit,
    onOpenImage: (PostImage) -> Unit,
    onOpenVideo: (PostVideo) -> Unit,
    onOpenMention: (String) -> Unit,
    pollSlot: @Composable (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(Nocturne.surface)
            .padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        if (quote.username != null || quote.topicTitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RemoteAvatar(
                    DiscourseConfig.absoluteUrl(quote.avatarUrl),
                    quote.username?.take(1)?.uppercase() ?: "?",
                    size = 18.dp,
                )
                quote.username?.let {
                    Text(it, style = Type.body(12, FontWeight.SemiBold), color = Nocturne.muted(0.72f))
                }
                quote.topicTitle?.let {
                    Text(it, style = Type.body(12), color = Nocturne.muted(0.45f), maxLines = 1)
                }
            }
        }
        NestedBlocks(
            quote.blocks, baseSize, lineExtra,
            onOpenLink = onOpenLink,
            onOpenImage = onOpenImage,
            onOpenVideo = onOpenVideo,
            onOpenMention = onOpenMention,
            pollSlot = pollSlot,
        )
    }
}

@Composable
private fun ListView(
    block: PostBlock.ListBlock,
    baseSize: Int,
    lineExtra: Int,
    onOpenLink: (String) -> Unit,
    onOpenImage: (PostImage) -> Unit,
    onOpenVideo: (PostVideo) -> Unit,
    onOpenMention: (String) -> Unit,
    pollSlot: @Composable (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        block.items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Text(
                    if (block.ordered) "${index + 1}." else "•",
                    style = Type.body(baseSize),
                    color = Nocturne.muted(0.5f),
                    modifier = Modifier.width(if (block.ordered) 24.dp else 14.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    item.forEach {
                        BlockView(it, baseSize, lineExtra, onOpenLink, onOpenImage, onOpenVideo, onOpenMention, pollSlot)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: PostBlock.CodeBlock) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    DisableSelection {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(Nocturne.neutral300)
            // A command is written to be run somewhere else, and one press
            // takes the whole of it — which is what you want for a command and
            // what dragging a selection across a wrapped line is not.
            //
            // Kept even though a post selects now, and the block is wrapped in
            // DisableSelection below so the two gestures are not both trying to
            // answer the same long press.
            // Long-press only. combinedClickable would also claim the tap,
            // which is how a reply collapses — and would announce every code
            // block to a screen reader as a button that does nothing.
            .pointerInput(block.code) {
                detectTapGestures(
                    onLongPress = {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(block.language, block.code))) }
                        ToastCenter.show(R.string.reader_code_copied)
                    },
                )
            }
            .padding(Space.s3),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                block.language?.takeIf { it.isNotEmpty() }.orEmpty(),
                style = Type.body(10),
                color = Nocturne.muted(0.4f),
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                stringResource(R.string.reader_code_copy_hint),
                style = Type.body(10),
                color = Nocturne.muted(0.3f),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        // Code never wraps: it scrolls in its own lane, so a long line can't
        // widen the page.
        Text(
            block.code,
            style = Type.body(13).copy(fontFamily = FontFamily.Monospace),
            color = Nocturne.text,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
    }
}

@Composable
private fun TableView(
    block: PostBlock.Table,
    baseSize: Int,
    onOpenLink: (String) -> Unit,
    onOpenMention: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, Nocturne.divider, RoundedCornerShape(Radius.sm)),
    ) {
        if (block.headers.isNotEmpty()) {
            Row(Modifier.background(Nocturne.surface)) {
                block.headers.forEach { cell ->
                    Box(Modifier.width(140.dp).padding(8.dp)) {
                        InlineText(cell, baseSize - 2, 4, onOpenLink, onOpenMention, weight = FontWeight.SemiBold)
                    }
                }
            }
        }
        block.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Box(Modifier.width(140.dp).padding(8.dp)) {
                        InlineText(cell, baseSize - 2, 4, onOpenLink, onOpenMention)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsView(
    block: PostBlock.Details,
    baseSize: Int,
    lineExtra: Int,
    onOpenLink: (String) -> Unit,
    onOpenImage: (PostImage) -> Unit,
    onOpenVideo: (PostVideo) -> Unit,
    onOpenMention: (String) -> Unit,
    pollSlot: @Composable (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(Nocturne.surface)
            .padding(Space.s3),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                InlineText(block.summary, baseSize - 1, lineExtra, onOpenLink, onOpenMention, weight = FontWeight.Medium)
            }
            Icon(
                if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
                tint = Nocturne.muted(0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(Space.s3))
            NestedBlocks(
                block.blocks, baseSize, lineExtra,
                onOpenLink = onOpenLink,
                onOpenImage = onOpenImage,
                onOpenVideo = onOpenVideo,
                onOpenMention = onOpenMention,
                pollSlot = pollSlot,
            )
        }
    }
}

@Composable
private fun SpoilerView(
    block: PostBlock.Spoiler,
    baseSize: Int,
    lineExtra: Int,
    onOpenLink: (String) -> Unit,
    onOpenImage: (PostImage) -> Unit,
    onOpenVideo: (PostVideo) -> Unit,
    onOpenMention: (String) -> Unit,
    pollSlot: @Composable (String) -> Unit,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        NestedBlocks(
            block.blocks, baseSize, lineExtra,
            modifier = Modifier.fillMaxWidth(),
            onOpenLink = onOpenLink,
            onOpenImage = onOpenImage,
            onOpenVideo = onOpenVideo,
            onOpenMention = onOpenMention,
            pollSlot = pollSlot,
        )
        if (!revealed) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(Nocturne.neutral800)
                    // The cover takes the tap itself. A plain background does
                    // not participate in hit testing, so events fell through to
                    // the content it was hiding — a link inside a spoiler could
                    // be opened without ever revealing it.
                    .clickable { revealed = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.reader_spoiler), style = Type.body(12), color = Nocturne.neutral200)
            }
        }
    }
}

@Composable
private fun PostImageView(image: PostImage, onOpenImage: (PostImage) -> Unit) {
    val ratio = image.aspectRatio
    val intrinsicWidth = image.width

    RemoteImage(
        DiscourseConfig.absoluteUrl(image.bestSrc),
        modifier = Modifier
            // Never wider than the picture actually is. Everything used to be
            // stretched to the column, so a 120px sticker or a small badge was
            // blown up to full width and resampled to mush.
            .then(if (intrinsicWidth != null) Modifier.widthIn(max = intrinsicWidth.dp) else Modifier)
            .fillMaxWidth()
            // Reserve the box from the HTML's own dimensions so the page does
            // not reflow when the bitmap lands. Without them, let the image
            // size itself rather than forcing a landscape box onto what is
            // often a portrait screenshot.
            .then(
                // Without dimensions there is nothing to reserve, and the node
                // measures to zero until the bitmap lands — then jumps to full
                // height and shoves the rest of the thread down. Discourse omits
                // them for hot-linked images, so this is common.
                if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.heightIn(min = 160.dp),
            )
            .clip(RoundedCornerShape(Radius.md))
            .clickable { onOpenImage(image) },
        // Fit, not Crop: the reserved box comes from the image's own ratio, so
        // cropping could only ever cut something off — which it did, turning a
        // tall screenshot into a letterboxed strip whenever the dimensions were
        // missing and the fallback ratio was used.
        contentScale = ContentScale.Fit,
        contentDescription = image.alt,
    )
}

@Composable
private fun PostVideoView(video: PostVideo, onOpenVideo: (PostVideo) -> Unit) {
    val url = DiscourseConfig.absoluteUrl(video.src)
    if (url == null) {
        // Nothing to play: keep the poster and the affordance rather than a gap.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(Radius.md))
                .background(Nocturne.neutral300)
                .clickable { onOpenVideo(video) },
            contentAlignment = Alignment.Center,
        ) {
            video.posterSrc?.let {
                RemoteImage(DiscourseConfig.absoluteUrl(it), modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
            Icon(Lucide.CirclePlay, stringResource(R.string.common_play), tint = Color.White, modifier = Modifier.size(48.dp))
        }
        return
    }
    // The same player the feed cards use, sized the same way and with the same
    // blurred backdrop: a video in a post played only after a trip through the
    // full-screen viewer, while the identical video in the feed row that opened
    // the post was already running — and at a different height.
    //
    // The clip carries no dimensions of its own, so the box comes from its
    // poster, whose size Discourse writes into the file name.
    val poster = DiscourseConfig.absoluteUrl(video.posterSrc)
    val (posterWidth, posterHeight) = DiscourseFormat.dimensionsOf(video.posterSrc)
    InlineVideoPlayer(
        url = url,
        aspectRatio = 1f / FeedMedia.heightRatio(posterWidth, posterHeight),
        backdropUrl = poster,
        onClick = { onOpenVideo(video) },
    )
}

@Composable
private fun OneboxView(onebox: PostOnebox, onOpenLink: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .border(1.dp, Nocturne.divider, RoundedCornerShape(Radius.md))
            .clickable(enabled = onebox.url != null) { onebox.url?.let(onOpenLink) }
            .padding(Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        onebox.imageUrl?.let {
            RemoteImage(
                DiscourseConfig.absoluteUrl(it),
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(Radius.sm)),
                contentScale = ContentScale.Crop,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            onebox.title?.let {
                Text(it, style = Type.body(14, FontWeight.Medium), color = Nocturne.text, maxLines = 2)
            }
            onebox.descriptionText?.let {
                Text(it, style = Type.body(12), color = Nocturne.muted(0.5f), maxLines = 3)
            }
            onebox.url?.let {
                Text(hostOf(it), style = Type.body(11), color = Nocturne.accent, maxLines = 1)
            }
        }
    }
}

private fun hostOf(url: String): String =
    runCatching { url.toUri().host.orEmpty() }.getOrDefault(url)

/**
 * Inline runs as one AnnotatedString, so a paragraph is a single Text and
 * measures once no matter how many styled spans it holds. Links and mentions
 * carry their own click targets, which keeps the tap area exactly the run —
 * the surrounding text stays free to trigger the row's own gesture (collapse).
 */
@Composable
fun InlineText(
    inlines: List<PostInline>,
    size: Int,
    lineExtra: Int,
    onOpenLink: (String) -> Unit,
    onOpenMention: (String) -> Unit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Nocturne.text,
    maxLines: Int = Int.MAX_VALUE,
) {
    if (inlines.isEmpty()) return
    val accent = Nocturne.accent
    val codeBg = Nocturne.neutral300
    val spoilerCover = Nocturne.neutral300

    // Per InlineText, so revealing a spoiler in one post does not reveal the
    // same words in another.
    // Saveable, like the block-level cover: scrolling a reply out of the list
    // and back should not re-hide what the reader chose to see. An explicit
    // saver because the default one only handles Bundle-native types.
    val revealedList = rememberSaveable(
        inlines,
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf() }
    val revealed = revealedList.toSet()

    val text = remember(inlines, accent, codeBg, spoilerCover, revealedList.size, onOpenLink, onOpenMention) {
        buildAnnotatedString {
            inlines.forEach { inline ->
                appendInline(
                    inline, accent, codeBg, spoilerCover, revealed,
                    onOpenLink, onOpenMention,
                    onRevealSpoiler = { if (it !in revealedList) revealedList += it },
                )
            }
        }
    }

    // Custom emoji are images, and Discourse posts are full of them: rendering
    // the `:shortcode:` instead would be legible but wrong.
    val emojiUrls = remember(inlines) { collectEmojiUrls(inlines) }
    val emojiSize = (size * 1.2f).sp
    val inlineContent = remember(emojiUrls, emojiSize) {
        emojiUrls.associateWith { url ->
            InlineTextContent(
                Placeholder(emojiSize, emojiSize, PlaceholderVerticalAlign.TextCenter),
            ) {
                AsyncImage(
                    model = DiscourseConfig.absoluteUrl(url),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    Text(
        text = text,
        modifier = modifier,
        style = Type.body(size, weight).copy(color = color, lineHeight = (size + lineExtra).sp),
        maxLines = maxLines,
        inlineContent = inlineContent,
    )
}

/** Whether anything in this subtree is a spoiler still covered. */
private fun List<PostInline>.anyHiddenSpoiler(revealed: Set<String>): Boolean = any { inline ->
    when (inline) {
        is PostInline.Styled -> PostTextStyle.Spoiler in inline.style && inline.value !in revealed
        is PostInline.Link -> inline.children.anyHiddenSpoiler(revealed)
        else -> false
    }
}

private fun collectEmojiUrls(inlines: List<PostInline>): Set<String> = buildSet {
    fun walk(items: List<PostInline>) {
        items.forEach { inline ->
            when (inline) {
                is PostInline.Emoji -> if (inline.url.isNotEmpty()) add(inline.url)
                is PostInline.Link -> walk(inline.children)
                else -> Unit
            }
        }
    }
    walk(inlines)
}

private fun AnnotatedString.Builder.appendInline(
    inline: PostInline,
    accent: Color,
    codeBg: Color,
    spoilerCover: Color,
    revealed: Set<String>,
    onOpenLink: (String) -> Unit,
    onOpenMention: (String) -> Unit,
    onRevealSpoiler: (String) -> Unit,
) {
    when (inline) {
        is PostInline.Text -> append(inline.value.breakingLongTokens())

        is PostInline.Styled -> {
            val hidden = PostTextStyle.Spoiler in inline.style && inline.value !in revealed
            val style = inline.style.toSpanStyle(codeBg, spoilerCover, hidden)
            if (hidden) {
                // The blocks replace the characters rather than merely painting
                // over them. Transparent ink is a paint-time property: the real
                // words stayed in the string backing this Text, so a screen
                // reader read the spoiler out loud. Same length, so revealing
                // does not reflow the paragraph.
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "spoiler",
                        linkInteractionListener = { onRevealSpoiler(inline.value) },
                    ),
                ) {
                    withStyle(style) { append("\u2588".repeat(inline.value.length)) }
                }
            } else {
                withStyle(style) { append(inline.value.breakingLongTokens()) }
            }
        }

        is PostInline.Link -> if (inline.children.anyHiddenSpoiler(revealed)) {
            // No link while a spoiler inside it is hidden: the link's own style
            // is applied after this string is built and would paint over the
            // cover, and its tap target would win over the reveal — so the
            // words showed in the clear and could not be revealed.
            inline.children.forEach {
                appendInline(it, accent, codeBg, spoilerCover, revealed, onOpenLink, onOpenMention, onRevealSpoiler)
            }
        } else withLink(
            LinkAnnotation.Clickable(
                tag = inline.href,
                styles = TextLinkStyles(style = SpanStyle(color = accent)),
                linkInteractionListener = { onOpenLink(inline.href) },
            ),
        ) {
            inline.children.forEach {
                appendInline(it, accent, codeBg, spoilerCover, revealed, onOpenLink, onOpenMention, onRevealSpoiler)
            }
        }

        is PostInline.Mention -> withLink(
            LinkAnnotation.Clickable(
                tag = inline.username,
                styles = TextLinkStyles(style = SpanStyle(color = accent, fontWeight = FontWeight.Medium)),
                linkInteractionListener = { onOpenMention(inline.username) },
            ),
        ) {
            append("@${inline.username}")
        }

        // The id is the URL, which is what the inlineContent map is keyed on;
        // the shortcode is the alternate text a screen reader or a copy gets.
        is PostInline.Emoji -> if (inline.url.isNotEmpty()) {
            appendInlineContent(inline.url, inline.shortcode)
        } else {
            append(inline.shortcode)
        }

        PostInline.LineBreak -> append("\n")
    }
}

private fun PostTextStyle.toSpanStyle(codeBg: Color, spoilerCover: Color, hidden: Boolean): SpanStyle {
    val isSpoiler = PostTextStyle.Spoiler in this
    return SpanStyle(
        fontWeight = if (PostTextStyle.Bold in this) FontWeight.Bold else null,
        fontStyle = if (PostTextStyle.Italic in this) FontStyle.Italic else null,
        textDecoration = if (PostTextStyle.Strikethrough in this) TextDecoration.LineThrough else null,
        fontFamily = if (PostTextStyle.Code in this) FontFamily.Monospace else null,
        // Ink the same colour as the block it sits on, so the words occupy
        // their space — the line does not reflow on reveal — without being
        // readable, and without being recoverable by selecting the text.
        color = if (isSpoiler && hidden) Color.Transparent else Color.Unspecified,
        background = when {
            isSpoiler && hidden -> spoilerCover
            PostTextStyle.Code in this -> codeBg
            else -> Color.Unspecified
        },
    )
}
