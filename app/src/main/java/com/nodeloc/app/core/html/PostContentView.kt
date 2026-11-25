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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.toColorInt
import com.composables.icons.lucide.Expand
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.MousePointerClick
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
    links: PostLinks = PostLinks.Empty,
) {
    val runs = remember(content) { topLevelRuns(content.blocks) }
    CompositionLocalProvider(LocalPostLinks provides links) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            runs.forEach { run ->
                when (run) {
                    is BlockRun.Gallery -> GalleryView(run.images, onOpenImage)
                    is BlockRun.Figure -> FigureView(run.image, onOpenImage)
                    is BlockRun.Single ->
                        BlockView(run.block, baseSize, lineExtra, onOpenLink, onOpenImage, onOpenVideo, onOpenMention, pollSlot)
                }
            }
        }
    }
}

/**
 * The preview card behind a pasted URL, as `GET /link-previews` describes it.
 * Absent until the server has fetched it; the bare URL stands in until then.
 */
data class LinkCard(
    val title: String? = null,
    val description: String? = null,
    val siteName: String? = null,
    val iconUrl: String? = null,
    val imageUrl: String? = null,
    val repo: RepoMeta? = null,
)

/** A code repository's line under its card: language, stars, licence. */
data class RepoMeta(val language: String? = null, val stars: Long? = null, val license: String? = null)

/** What a body's links carry beyond their HTML: cards by URL, and this post's click counts by URL. */
data class PostLinks(
    val cards: Map<String, LinkCard> = emptyMap(),
    val clicks: Map<String, Long> = emptyMap(),
) {
    fun clicksOf(url: String): Long? = (clicks[url] ?: clicks[url.trimEnd('/')] ?: clicks["$url/"])?.takeIf { it > 0 }

    companion object {
        val Empty = PostLinks()
    }
}

/**
 * Provided rather than passed: every block renderer here recurses through the
 * same eight parameters, and a ninth used by two leaves is not worth that.
 */
private val LocalPostLinks = staticCompositionLocalOf { PostLinks.Empty }

private sealed interface BlockRun {
    data class Single(val block: PostBlock) : BlockRun
    data class Figure(val image: PostImage) : BlockRun
    data class Gallery(val images: List<PostImage>) : BlockRun
}

/**
 * The web's figures, rebuilt from what the API serves: the stored HTML is the
 * pre-figure markup, and the server only groups images when it renders the
 * page. Same rule as there — only top-level runs of picture-only paragraphs,
 * which the parser hands over as consecutive image blocks. One is a figure
 * with a caption; more are a gallery.
 */
private fun topLevelRuns(blocks: List<PostBlock>): List<BlockRun> {
    val out = mutableListOf<BlockRun>()
    var pending = mutableListOf<PostImage>()
    fun flush() {
        when (pending.size) {
            0 -> Unit
            1 -> out += BlockRun.Figure(pending.single())
            else -> out += BlockRun.Gallery(pending)
        }
        pending = mutableListOf()
    }
    blocks.forEach { block ->
        if (block is PostBlock.Image) {
            pending += block.image
        } else {
            flush()
            out += BlockRun.Single(block)
        }
    }
    flush()
    return out
}

/**
 * The backend's `markup.Caption`: what the author wrote under a picture, but
 * not the file name the editor fills in by itself (`image`, `IMG_1234`,
 * `屏幕截图 2026-…`), which is not a caption.
 */
internal fun captionOf(alt: String?): String? {
    val text = alt?.trim().orEmpty()
    return text.takeIf { it.isNotEmpty() && !DEFAULT_IMAGE_NAME.matches(text) }
}

private val DEFAULT_IMAGE_NAME = Regex(
    """^([\x21-\x7e]+|(屏幕截图|截屏|截图|微信图片|QQ ?图片).*|(Snipaste|Screenshot|Screen Shot|mmexport|IMG|DSC|PXL)\b.*)$""",
    RegexOption.IGNORE_CASE,
)

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

        // A plain quote is a tinted panel now, not a rail: the design draws it
        // that way, and the rail had read as one more reply nesting level in
        // a thread that already uses rails for exactly that.
        is PostBlock.BlockQuote -> NestedBlocks(
            blocks = block.blocks,
            baseSize = baseSize,
            lineExtra = lineExtra,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(Nocturne.surface)
                .padding(horizontal = Space.card, vertical = 10.dp),
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

/** A picture standing alone: tap-to-enlarge chip on it, the author's caption under it. */
@Composable
private fun FigureView(image: PostImage, onOpenImage: (PostImage) -> Unit) {
    val caption = remember(image.alt) { captionOf(image.alt) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            PostImageView(image, onOpenImage)
            // Not on a sticker or a badge: on a picture that small the chip
            // would cover most of it.
            if ((image.width ?: Int.MAX_VALUE) >= MIN_HINTED_WIDTH) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Nocturne.accent900.copy(alpha = 0.72f))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Lucide.Expand, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.reader3_view_full), style = Type.body(12, FontWeight.Bold), color = Color.White)
                }
            }
        }
        caption?.let { Text(it, style = Type.body(13), color = Nocturne.textSecondary) }
    }
}

/**
 * A run of pictures as the phone layout draws it: two side by side, or one
 * across the top with two under it, the last carrying "+N" for the rest. Every
 * tile opens the viewer at that picture, which pages through all of them.
 */
@Composable
private fun GalleryView(images: List<PostImage>, onOpenImage: (PostImage) -> Unit) {
    val tileGap = 4.dp
    val shape = RoundedCornerShape(12.dp)
    @Composable
    fun Tile(image: PostImage, modifier: Modifier, more: Int = 0) {
        Box(modifier.clickable { onOpenImage(image) }) {
            RemoteImage(
                DiscourseConfig.absoluteUrl(image.bestSrc),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                contentDescription = captionOf(image.alt),
            )
            if (more > 0) {
                Box(
                    Modifier.fillMaxSize().background(Nocturne.accent900.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+$more", style = Type.heading(22, FontWeight.ExtraBold), color = Color.White)
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth().clip(shape), verticalArrangement = Arrangement.spacedBy(tileGap)) {
            if (images.size == 2) {
                Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(tileGap)) {
                    Tile(images[0], Modifier.weight(1f).fillMaxSize())
                    Tile(images[1], Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Tile(images[0], Modifier.fillMaxWidth().height(220.dp + tileGap))
                Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(tileGap)) {
                    Tile(images[1], Modifier.weight(1f).fillMaxSize())
                    Tile(images[2], Modifier.weight(1f).fillMaxSize(), more = images.size - GALLERY_TILES)
                }
            }
        }
        Text(
            stringResource(R.string.reader3_gallery_note, images.size),
            style = Type.body(13),
            color = Nocturne.textSecondary,
        )
    }
}

private const val GALLERY_TILES = 3
private const val MIN_HINTED_WIDTH = 200

/**
 * A pasted URL as a card: where it goes and how often it was opened, then what
 * the server's preview says about it. Without a preview the URL itself is the
 * title, which is all the HTML has.
 */
@Composable
private fun OneboxView(onebox: PostOnebox, onOpenLink: (String) -> Unit) {
    val links = LocalPostLinks.current
    val card = onebox.url?.let { links.cards[it] }
    val clicks = onebox.url?.let { links.clicksOf(it) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Nocturne.divider, shape)
            .clickable(enabled = onebox.url != null) { onebox.url?.let(onOpenLink) }
            .padding(horizontal = Space.card, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val icon = card?.iconUrl ?: onebox.faviconUrl
                if (icon != null) {
                    RemoteImage(
                        DiscourseConfig.absoluteUrl(icon),
                        modifier = Modifier.size(16.dp).clip(RoundedCornerShape(Radius.sm)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(Radius.sm)).background(Nocturne.neutral500))
                }
                Text(
                    onebox.url?.let(::hostOf) ?: card?.siteName.orEmpty(),
                    style = Type.body(12),
                    color = Nocturne.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                if (clicks != null) {
                    Row(
                        Modifier
                            .height(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Nocturne.neutral400)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Lucide.MousePointerClick, null, tint = Nocturne.textSecondary, modifier = Modifier.size(12.dp))
                        Text(
                            stringResource(R.string.reader3_link_clicks, DiscourseFormat.count(clicks.toInt())),
                            style = Type.body(12, FontWeight.Medium),
                            color = Nocturne.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
            (card?.title ?: onebox.title)?.let {
                Text(
                    it,
                    style = Type.body(15, FontWeight.Bold),
                    color = Nocturne.accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            (card?.description ?: onebox.descriptionText)?.let {
                Text(it, style = Type.body(13), color = Nocturne.neutral800, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            card?.repo?.let { repo -> RepoLine(repo) }
        }
        (card?.imageUrl ?: onebox.imageUrl)?.takeIf { card?.repo == null }?.let {
            RemoteImage(
                DiscourseConfig.absoluteUrl(it),
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(Radius.md)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun RepoLine(repo: RepoMeta) {
    Row(
        Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repo.language?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Nocturne.info))
                Text(it, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
            }
        }
        repo.stars?.let {
            Text(
                stringResource(R.string.reader3_repo_stars, DiscourseFormat.count(it.toInt())),
                style = Type.body(12),
                color = Nocturne.textSecondary,
                maxLines = 1,
            )
        }
        repo.license?.let {
            Text(stringResource(R.string.reader3_repo_license, it), style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
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

    val links = LocalPostLinks.current
    val text = remember(inlines, accent, codeBg, spoilerCover, revealedList.size, onOpenLink, onOpenMention, links) {
        buildAnnotatedString {
            inlines.forEach { inline ->
                appendInline(
                    inline, accent, codeBg, spoilerCover, revealed,
                    onOpenLink, onOpenMention,
                    onRevealSpoiler = { if (it !in revealedList) revealedList += it },
                    links = links,
                )
            }
        }
    }

    // Custom emoji are images, and Discourse posts are full of them: rendering
    // the `:shortcode:` instead would be legible but wrong.
    val emojiUrls = remember(inlines) { collectEmojiUrls(inlines) }
    val emojiSize = (size * 1.2f).sp
    val chips = remember(inlines, links) { collectChips(inlines, links) }
    val chipContent = chipInlineContent(chips, size, onOpenLink)
    val inlineContent = remember(emojiUrls, emojiSize, chipContent) {
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
        } + chipContent
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

private const val EXTERNAL_ICON_ID = "chip:ext"

private fun refChipId(link: PostInline.Link) = "chip:ref:${link.kind}:${link.href}:${link.children.plainText}"

private fun countChipId(href: String) = "chip:count:$href"

private val siteHost: String = hostOf(DiscourseConfig.BASE_URL).removePrefix("www.")

private fun isExternal(href: String): Boolean {
    if (!href.startsWith("http://") && !href.startsWith("https://")) return false
    val host = hostOf(href).lowercase().removePrefix("www.")
    return host.isNotEmpty() && host != siteHost && !host.endsWith(".$siteHost")
}

/** Inline pieces drawn as composables rather than as text. */
private sealed interface InlineChip {
    val id: String

    data class Ref(override val id: String, val link: PostInline.Link, val label: String) : InlineChip
    data class Count(override val id: String, val label: String) : InlineChip
    data object External : InlineChip {
        override val id = EXTERNAL_ICON_ID
    }
}

private fun collectChips(inlines: List<PostInline>, links: PostLinks): List<InlineChip> = buildList {
    fun walk(items: List<PostInline>) {
        items.forEach { inline ->
            if (inline !is PostInline.Link) return@forEach
            if (inline.kind != LinkKind.Plain) {
                add(InlineChip.Ref(refChipId(inline), inline, inline.children.plainText))
            } else {
                walk(inline.children)
                if (isExternal(inline.href)) add(InlineChip.External)
                links.clicksOf(inline.href)?.let { add(InlineChip.Count(countChipId(inline.href), it.toString())) }
            }
        }
    }
    walk(inlines)
}.distinctBy { it.id }

/**
 * Inline content has to declare its size before it is drawn, so each chip's
 * label is measured with the style the chip will draw it in, and the box is
 * built around that: a guess here either clips the label or leaves a gap in
 * the sentence.
 */
@Composable
private fun chipInlineContent(
    chips: List<InlineChip>,
    size: Int,
    onOpenLink: (String) -> Unit,
): Map<String, InlineTextContent> {
    if (chips.isEmpty()) return emptyMap()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val refStyle = Type.body(size * 0.93f, FontWeight.Medium)
    val countStyle = Type.body(12, FontWeight.Medium)
    return remember(chips, size, density, onOpenLink) {
        with(density) {
            val refHeight = (size * 1.45f).sp.toDp()
            chips.associate { chip ->
                val content = when (chip) {
                    is InlineChip.Ref -> {
                        val textWidth = measurer.measure(chip.label, refStyle, maxLines = 1).size.width.toDp()
                        val isNode = chip.link.kind == LinkKind.Node
                        val avatar = refHeight - 6.dp
                        val width = if (isNode) 3.dp + avatar + 5.dp + textWidth + 9.dp + 2.dp else 7.dp + textWidth + 7.dp + 2.dp
                        InlineTextContent(
                            Placeholder(width.toSp(), refHeight.toSp(), PlaceholderVerticalAlign.TextCenter),
                        ) {
                            if (isNode) {
                                NodeChip(chip.link, chip.label, avatar, refStyle, onOpenLink)
                            } else {
                                TagChip(chip.link, chip.label, refStyle, onOpenLink)
                            }
                        }
                    }
                    is InlineChip.Count -> {
                        val textWidth = measurer.measure(chip.label, countStyle, maxLines = 1).size.width.toDp()
                        InlineTextContent(
                            Placeholder((4.dp + 6.dp + textWidth + 6.dp + 1.dp).toSp(), 18.dp.toSp(), PlaceholderVerticalAlign.TextCenter),
                        ) { CountChip(chip.label, countStyle) }
                    }
                    InlineChip.External -> {
                        val side = (size * 0.85f).sp
                        InlineTextContent(Placeholder(side, side, PlaceholderVerticalAlign.TextCenter)) {
                            Icon(
                                Lucide.ExternalLink,
                                null,
                                tint = Nocturne.accent,
                                modifier = Modifier.fillMaxSize().padding(start = 2.dp),
                            )
                        }
                    }
                }
                chip.id to content
            }
        }
    }
}

@Composable
private fun NodeChip(link: PostInline.Link, label: String, avatar: Dp, style: TextStyle, onOpenLink: (String) -> Unit) {
    val badge = link.badge
    val ground = badge?.color?.let { runCatching { Color(it.toColorInt()) }.getOrNull() } ?: Nocturne.accent
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .border(1.dp, Nocturne.divider, shape)
            .background(Nocturne.bg)
            .clickable { onOpenLink(link.href) }
            .padding(start = 3.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val letter = badge?.letter ?: label.removePrefix("n/").take(1).uppercase()
        if (badge?.logoUrl != null) {
            RemoteAvatar(DiscourseConfig.absoluteUrl(badge.logoUrl), letter, size = avatar)
        } else {
            Box(Modifier.size(avatar).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
                Text(letter, style = Type.body(11, FontWeight.Bold), color = Color.White, maxLines = 1)
            }
        }
        Text(label, style = style, color = Nocturne.text, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun TagChip(link: PostInline.Link, label: String, style: TextStyle, onOpenLink: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Nocturne.neutral400)
            .clickable { onOpenLink(link.href) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = style.copy(fontWeight = FontWeight.Normal), color = Nocturne.text, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun CountChip(label: String, style: TextStyle) {
    val description = stringResource(R.string.reader3_link_clicked_times, label)
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Nocturne.neutral400)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = style, color = Nocturne.textSecondary, maxLines = 1, softWrap = false)
    }
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
    links: PostLinks = PostLinks.Empty,
) {
    fun child(it: PostInline) =
        appendInline(it, accent, codeBg, spoilerCover, revealed, onOpenLink, onOpenMention, onRevealSpoiler, links)
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
            inline.children.forEach(::child)
        } else if (inline.kind != LinkKind.Plain) {
            // A node or tag is a chip drawn as inline content; the label is
            // what a copy or a screen reader gets instead of the picture.
            appendInlineContent(refChipId(inline), inline.children.plainText)
        } else {
            val external = isExternal(inline.href)
            withLink(
                LinkAnnotation.Clickable(
                    tag = inline.href,
                    // Underlined only when it leaves the site: the line is what
                    // says "this goes elsewhere" before the icon after it does.
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = accent,
                            textDecoration = if (external) TextDecoration.Underline else null,
                        ),
                    ),
                    linkInteractionListener = { onOpenLink(inline.href) },
                ),
            ) {
                inline.children.forEach(::child)
                if (external) appendInlineContent(EXTERNAL_ICON_ID, "")
            }
            links.clicksOf(inline.href)?.let { appendInlineContent(countChipId(inline.href), " $it") }
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
