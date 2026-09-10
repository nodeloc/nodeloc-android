package com.nodeloc.app.core.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.User
import com.nodeloc.app.R

// ---------------------------------------------------------------- Brand mark

/**
 * The site's wordmark, at [height] and its own aspect ratio.
 *
 * It carries its colours with it, so it needs no theming — and it is an image
 * of a word, which is why the app name goes in the content description rather
 * than beside it.
 */
@Composable
fun NodelocLogo(modifier: Modifier = Modifier, height: Dp = 22.dp) {
    Image(
        painter = painterResource(R.drawable.ic_wordmark),
        contentDescription = stringResource(R.string.app_name),
        modifier = modifier.height(height).aspectRatio(WORDMARK_ASPECT),
    )
}

/** 960x280, the viewport of `ic_wordmark`. */
private const val WORDMARK_ASPECT = 960f / 280f

// ------------------------------------------------------------------- Avatars

/** Letter avatar; the two-variant palette keeps rows visually distinct. */
@Composable
fun Avatar(
    letter: String,
    modifier: Modifier = Modifier,
    variant: Int = 0,
    size: Dp = 34.dp,
    cornerRadius: Dp? = null,
    bg: Color? = null,
    fg: Color? = null,
) {
    val palette = Nocturne.avatarColors(variant)
    val shape: Shape = if (cornerRadius == null) CircleShape else RoundedCornerShape(cornerRadius)
    Box(
        modifier.size(size).clip(shape).background(bg ?: palette.first),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter.take(1).uppercase(),
            style = Type.heading((size.value * 0.36f).toInt().coerceAtLeast(8), FontWeight.SemiBold),
            color = fg ?: palette.second,
        )
    }
}

/** Remote avatar with letter fallback; a null url skips the network entirely. */
@Composable
fun RemoteAvatar(
    url: String?,
    letter: String,
    modifier: Modifier = Modifier,
    variant: Int = 0,
    size: Dp = 34.dp,
    cornerRadius: Dp? = null,
) {
    val shape: Shape = if (cornerRadius == null) CircleShape else RoundedCornerShape(cornerRadius)
    Box(modifier.size(size).clip(shape).background(Nocturne.neutral300), contentAlignment = Alignment.Center) {
        if (url.isNullOrEmpty()) {
            Avatar(letter, variant = variant, size = size, cornerRadius = cornerRadius)
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                loading = { Avatar(letter, variant = variant, size = size, cornerRadius = cornerRadius) },
                error = { Avatar(letter, variant = variant, size = size, cornerRadius = cornerRadius) },
            )
        }
    }
}

/** Guest state: a grey person glyph wherever a signed-in avatar would sit. */
@Composable
fun GuestAvatar(modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(Nocturne.neutral300),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Lucide.User,
            contentDescription = stringResource(R.string.common_guest),
            tint = Nocturne.muted(0.45f),
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

// --------------------------------------------------------------------- Chips

enum class TagStyle { Accent, Accent2, Neutral, Outline }

@Composable
fun TagChip(
    text: String,
    modifier: Modifier = Modifier,
    style: TagStyle = TagStyle.Neutral,
    padding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
) {
    val shape = RoundedCornerShape(Radius.md * 0.75f)
    // A badge, not a label on a slab. The ground is a wash of the same hue as
    // the text, so a run of them — a title, a group, when someone was last
    // here — reads as one set of facts about a person even where each keeps
    // its own colour. Solid dark pills did the opposite: brown beside black,
    // shouting louder than the name above them.
    //
    // Which end of the ramp is text and which is ground swaps with the theme,
    // hence the branch: `accent100` is the palest green in both, and pale text
    // on pale ground is unreadable in the light one.
    val dark = Nocturne.isDark
    val (fg, bg) = when (style) {
        TagStyle.Accent ->
            if (dark) Nocturne.accent300 to Nocturne.accent800
            else Nocturne.accent700 to Nocturne.accent100

        TagStyle.Accent2 ->
            Nocturne.accent2700 to if (dark) Nocturne.accent2800 else Nocturne.accent2100

        // The neutral ramp already inverts itself between themes, so this one
        // needs no branch.
        TagStyle.Neutral -> Nocturne.neutral700 to Nocturne.neutral300

        TagStyle.Outline -> Nocturne.accent to Color.Transparent
    }
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .then(if (style == TagStyle.Outline) Modifier.border(1.dp, Nocturne.accent, shape) else Modifier)
            .padding(padding),
    ) {
        Text(text, style = Type.body(11), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// --------------------------------------------------------------------- Cards

@Composable
fun Card(
    modifier: Modifier = Modifier,
    background: Color = Nocturne.surface,
    bordered: Boolean = true,
    padding: Dp = Space.s3,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.md)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(if (bordered) Modifier.border(1.dp, Nocturne.neutral400, shape) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
        content = content,
    )
}

// --------------------------------------------------------- Floating controls

/**
 * Shared sizing for the floating headers that ride above scrolling content.
 * iOS uses Liquid Glass here; Android approximates with a tonal surface, a
 * translucent tint and the same shadow — no live blur (not worth the frames).
 */
object FloatingHeader {
    val controlHeight: Dp = 34.dp
    /** Control outer height: the 34dp cap plus 7dp of padding each side. */
    val touchHeight: Dp = 48.dp
    /** Material 3's small top app bar. The status bar sits above this. */
    val barHeight: Dp = 64.dp
    val horizontalInset: Dp = 16.dp
}

/** A bare icon button for the top bar, identical everywhere it appears. */
@Composable
fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // No disc behind it: the bar itself is the surface once the page scrolls
    // under it, and a floating pill on top of a filled app bar is two surfaces
    // saying the same thing. The circular ripple is all the affordance a top
    // bar icon gets on Android.
    Box(
        modifier
            .size(FloatingHeader.touchHeight)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val color = LocalHeaderContentColor.current.takeIf { it.isSpecified } ?: Nocturne.headerText
        Icon(
            icon,
            contentDescription,
            tint = color.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * A grouping row for header controls.
 *
 * Flat: the bar behind it fills in when the page scrolls under it, and a glass
 * pill riding on a filled app bar is a second surface saying what the first
 * already said. It keeps the circular clip so a tappable group still ripples
 * as one.
 */
@Composable
fun HeaderGroup(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val base = Modifier
        .height(FloatingHeader.touchHeight)
        .clip(CircleShape)
    Row(
        modifier
            .then(base)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** One glyph inside a glass capsule, at the shared size and weight. */
@Composable
fun CapsuleIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(FloatingHeader.controlHeight + 6.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val fallback = LocalHeaderContentColor.current.takeIf { it.isSpecified } ?: Nocturne.headerText
        Icon(
            icon,
            contentDescription,
            tint = (tint ?: fallback).copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The single "登录" pill shared by all five guest entry points (feed, node
 * browse, node detail, inbox, profile) so they can never drift apart.
 */
@Composable
fun GuestLoginButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    // A tonal chip rather than a glass pill: it is the one call to action in
    // the bar, so it should read as a button and not as another floating disc.
    Box(
        modifier
            .height(FloatingHeader.controlHeight)
            .clip(CircleShape)
            .background(Nocturne.selected)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.common_login), style = Type.body(14, FontWeight.SemiBold), color = Nocturne.accent)
    }
}

// ------------------------------------------------------------------ Buttons

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    block: Boolean = false,
    enabled: Boolean = true,
    /** Sized to sit inline with a name, rather than to be the page's action. */
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.md)
    Box(
        modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .border(1.dp, Nocturne.accent.copy(alpha = if (enabled) 1f else 0.4f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                vertical = if (compact) Space.s1 else Space.s2,
                horizontal = if (compact) Space.s3 else Space.s3 * 1.2f,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Type.heading(if (compact) 12 else 14),
            color = Nocturne.accent.copy(alpha = if (enabled) 1f else 0.4f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    block: Boolean = false,
    leadingAligned: Boolean = false,
    enabled: Boolean = true,
    /** Sized to sit inline with a name, rather than to be the page's action. */
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.md)
    Box(
        modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .border(1.dp, Nocturne.divider, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                vertical = if (compact) Space.s1 else Space.s2,
                horizontal = when {
                    leadingAligned -> 16.dp
                    compact -> Space.s3
                    else -> Space.s3 * 1.2f
                },
            ),
        contentAlignment = if (leadingAligned) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(text, style = Type.heading(if (compact) 12 else 14), color = Nocturne.text)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.s1, vertical = 2.dp),
    ) {
        Text(text, style = Type.heading(12), color = Nocturne.accent)
    }
}

/** Filled accent button used for the auth flow's "继续". */
@Composable
fun FilledAccentButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.md)
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .background(Nocturne.accent.copy(alpha = if (enabled && !loading) 1f else 0.4f))
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            NodelocLoader(height = 22.dp, tint = Nocturne.bg)
        } else {
            Text(text, style = Type.heading(16, FontWeight.SemiBold), color = if (Nocturne.isDark) Nocturne.neutral900 else Color.White)
        }
    }
}


@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
}

@Composable
fun SectionKicker(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = Type.body(11).copy(letterSpacing = 0.9.sp),
        color = Nocturne.muted(0.5f),
    )
}

// ---------------------------------------------------------------- Skeletons

/**
 * Container-level pulse. Attached once around a whole skeleton so every bar
 * breathes in phase — attaching per row visibly beats against itself.
 */
@Composable
fun Modifier.skeletonPulsing(): Modifier {
    if (LocalReduceMotion.current) return this.alpha(0.8f)
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    return this.graphicsLayer { this.alpha = alpha }
}

@Composable
fun SkeletonLine(modifier: Modifier = Modifier, widthFraction: Float = 1f, height: Dp = 13.dp) {
    Box(
        modifier
            // Measured short, not drawn scaled: scaling is about the centre, so
            // a 70% line ended up centred in a left-aligned row — and its
            // corners came out squashed along with it.
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(Nocturne.neutral300),
    )
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, corner: Dp = Radius.md) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(Nocturne.neutral300))
}

// -------------------------------------------------------------- Empty states

@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Icon(icon, null, tint = Nocturne.muted(0.28f), modifier = Modifier.size(44.dp))
        Text(title, style = Type.body(15, FontWeight.Medium), color = Nocturne.muted(0.72f), textAlign = TextAlign.Center)
        if (detail != null) {
            Text(detail, style = Type.body(13), color = Nocturne.muted(0.45f), textAlign = TextAlign.Center)
        }
        if (retryLabel != null && onRetry != null) {
            Spacer(Modifier.height(Space.s2))
            SecondaryButton(retryLabel, onClick = onRetry)
        }
    }
}

// ------------------------------------------------------------- Text fields

@Composable
fun FieldSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.md)
    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clip(shape)
            .background(Nocturne.surface)
            .border(BorderStroke(1.dp, Nocturne.divider), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
        content = { content() },
    )
}

// ------------------------------------------------------------ Remote images

/**
 * The picture itself, blown up to fill and blurred, sitting behind a copy of it
 * shown whole.
 *
 * A feed card is one shape and photographs are every shape, so something has to
 * give: cropping loses the edges of a panorama and the top of a portrait, and
 * letterboxing leaves bands of flat grey. This fills the bands with the picture
 * out of focus, which is what Reddit does and what makes a tall screenshot read
 * as deliberate rather than as a layout accident.
 *
 * `Modifier.blur` needs API 31. Below that it does nothing, and what is left —
 * the same picture enlarged, cropped and dimmed — is still a backdrop rather
 * than a hole, so there is no separate path for it.
 */
@Composable
fun BlurredMediaBackdrop(url: String?, modifier: Modifier = Modifier) {
    if (url.isNullOrEmpty()) return
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .blur(24.dp, BlurredEdgeTreatment.Rectangle)
            // Scaled past the edges because a blur samples beyond them and
            // would otherwise smear the border into the frame.
            .scale(1.12f),
    )
    // Enough to sink the backdrop behind the picture without turning the bands
    // into black bars.
    Box(modifier.background(Color.Black.copy(alpha = 0.38f)))
}

/**
 * One action of a post's action bar, on a ground of its own.
 *
 * Reddit's shape, and the reason for it: a bare glyph on the page is a picture,
 * and a picture does not look like something to press. The rounded ground says
 * button, and it hands the tap an area with edges rather than the few dp the
 * glyph itself covers.
 */
@Composable
fun ActionPill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    horizontalPadding: Dp = 10.dp,
    /**
     * A pill holding nothing but a glyph is a circle, not a stadium: with no
     * number beside it there is nothing for the extra width to be for, and a
     * squat rounded box beside true circles reads as a mistake.
     */
    circular: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            // Fixed, not wrapped. The glyphs in these are not all one size and
            // some carry a number beside them, so a row of pills that sized to
            // their contents came out visibly uneven.
            .then(
                if (circular) Modifier.size(ActionPillHeight)
                else Modifier.height(ActionPillHeight),
            )
            // Fully round, the way the web's 999px is: at this height the shape
            // is a stadium, and a glyph on its own in it is a disc.
            .clip(CircleShape)
            .background(LocalActionPillStyle.current?.ground ?: Nocturne.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (circular) Modifier else Modifier.padding(horizontal = horizontalPadding)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (circular) Arrangement.Center else Arrangement.spacedBy(5.dp),
        content = content,
    )
}

/** Room for the largest glyph any of these carries, and the same for the rest. */
val ActionPillHeight: Dp = 32.dp

/**
 * How an action pill paints itself against what is behind it.
 *
 * A local rather than parameters, following [LocalHeaderContentColor]: the row
 * a full-screen viewer draws is the reader's own composable, and the ground and
 * the neutral tint would otherwise have to be threaded through `PostActionRow`,
 * [VoteControl] and every pill inside them — for the sake of the one caller
 * that draws over a photograph.
 */
data class ActionPillStyle(
    /** The ground behind the pill. */
    val ground: Color,
    /** A glyph saying nothing in particular: unvoted, untipped, unliked. */
    val icon: Color,
    /** The number beside it. */
    val label: Color,
) {
    companion object {
        /**
         * Over a picture or a clip. The ground is barely there on purpose —
         * what it has to do is keep a white glyph legible over a white frame,
         * not look like a button on a page — and the reader's greys are the
         * body text colour, which over a dark clip is nothing at all.
         */
        val OverMedia = ActionPillStyle(
            ground = Color.White.copy(alpha = 0.14f),
            icon = Color.White.copy(alpha = 0.92f),
            label = Color.White.copy(alpha = 0.92f),
        )
    }
}

/** Null everywhere but inside a full-screen viewer; the pills use their tokens. */
val LocalActionPillStyle = compositionLocalOf<ActionPillStyle?> { null }

/**
 * Coil image that reserves its box first, so nothing reflows on load.
 *
 * [placeholder] is what fills that reserved box until the image covers it. Turn
 * it off for anything drawn on transparency — an emoji does not cover its box,
 * so the grey stays behind it as a permanent tile rather than showing for the
 * length of a fetch.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    placeholder: Boolean = true,
) {
    val ground = if (placeholder) Modifier.background(Nocturne.neutral300) else Modifier
    if (url.isNullOrEmpty()) {
        Box(modifier.then(ground))
    } else {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier.then(ground),
        )
    }
}
