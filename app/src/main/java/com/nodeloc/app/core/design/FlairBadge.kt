package com.nodeloc.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Award
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.BadgeCheck
import com.composables.icons.lucide.Crown
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.Gem
import com.composables.icons.lucide.GraduationCap
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Rocket
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Sprout
import com.composables.icons.lucide.Zap
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Users
import com.nodeloc.app.core.network.DiscourseConfig

/**
 * The group flair Discourse shows beside a name — the badge a member picks from
 * whichever of their groups grants one.
 *
 * `flair_url` is two things wearing one name: an uploaded image's path, or a
 * Font Awesome icon name like `chess-queen`. The app draws no Font Awesome, so
 * a name is matched against the handful of shapes these actually use and falls
 * back to the group's initial — which still lands on the group's own colours,
 * and those are the half of the flair people recognise at this size.
 */
@Composable
fun FlairBadge(
    flairUrl: String?,
    flairName: String?,
    backgroundColor: String?,
    foregroundColor: String?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    if (flairUrl.isNullOrBlank() && flairName.isNullOrBlank()) return
    val background = parseFlairColor(backgroundColor) ?: Nocturne.accent
    val foreground = parseFlairColor(foregroundColor) ?: Nocturne.bg
    val image = flairUrl?.takeIf { it.contains('/') || it.startsWith("http") }

    Box(
        modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        when {
            image != null -> AsyncImage(
                model = DiscourseConfig.absoluteUrl(image),
                contentDescription = flairName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size * 0.72f),
            )

            // An unrecognised *icon* still gets an icon. Falling back to a
            // letter there produced a circle with "T" in it for every trust
            // level, the initial of `trust_level_3` — a name Discourse
            // generates and never shows anyone. Only a flair with no icon at
            // all, where the name is a real group's, is worth a letter.
            flairUrl != null -> Icon(
                iconFor(flairUrl) ?: Lucide.Award,
                flairName,
                tint = foreground,
                modifier = Modifier.size(size * 0.66f),
            )

            else -> Text(
                flairName?.take(1)?.uppercase().orEmpty(),
                style = Type.body((size.value * 0.55f).toInt().coerceAtLeast(8)),
                color = foreground,
            )
        }
    }
}

/**
 * Font Awesome names mapped to the nearest Lucide shape.
 *
 * The ones this site uses are `chess-queen` for staff, `gem` for the trust
 * levels, `certificate`, `medal` and `fab-alipay`; the rest are the common
 * neighbours of those, since a group added tomorrow will most likely reach for
 * one of them. The style prefixes Font Awesome allows are stripped first —
 * `fab-alipay` is the `alipay` glyph, and Lucide has no brand icons, so that
 * one still falls through to the initial.
 */
private fun iconFor(name: String?) = when (name?.removePrefix("fab-")?.removePrefix("fas-")?.removePrefix("far-")) {
    "chess-queen", "chess-king", "crown" -> Lucide.Crown
    "gem", "diamond" -> Lucide.Gem
    "certificate", "badge-check" -> Lucide.BadgeCheck
    "shield", "shield-halved" -> Lucide.Shield
    "user-shield", "shield-check" -> Lucide.ShieldCheck
    // The trust ladder's lower rungs: Discourse ships `user` for the newest
    // members, and a site that renames the tiers tends to reach along here.
    "user", "user-circle", "circle-user" -> Lucide.User
    "users", "user-group", "handshake" -> Lucide.Users
    "seedling", "leaf", "sprout" -> Lucide.Sprout
    "graduation-cap" -> Lucide.GraduationCap
    "star" -> Lucide.Star
    "sparkles", "wand-magic-sparkles" -> Lucide.Sparkles
    "award", "medal", "trophy" -> Lucide.Award
    "bolt", "zap" -> Lucide.Zap
    "fire", "flame" -> Lucide.Flame
    "key" -> Lucide.Key
    "rocket" -> Lucide.Rocket
    "heart" -> Lucide.Heart
    "bookmark" -> Lucide.Bookmark
    "circle", "dot-circle" -> Lucide.Circle
    else -> null
}

/** Discourse stores these without the leading `#`. */
private fun parseFlairColor(raw: String?): Color? {
    val hex = raw?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() } ?: return null
    if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    val expanded = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6 -> hex
        else -> return null
    }
    return expanded.toLongOrNull(16)?.let { Color(it or 0xFF000000L) }
}
