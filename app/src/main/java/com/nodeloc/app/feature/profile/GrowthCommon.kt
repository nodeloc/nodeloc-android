package com.nodeloc.app.feature.profile

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.Award
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Pen
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Zap
import com.nodeloc.app.R
import com.nodeloc.app.core.design.LocalReduceMotion
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.NumberFormat
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The tier directory, fetched once per process.
 *
 * Public, localised, and configured server-side — nothing a screen should ask
 * for every time it opens. A failure is not cached, so the next screen retries.
 */
object TierDirectory {
    private val lock = Mutex()
    private var cached: List<ApiTierInfo>? = null

    suspend fun get(): List<ApiTierInfo> = lock.withLock {
        cached ?: runCatchingCancellable { GrowthApi.shared.tiers() }.getOrNull()
            ?.also { cached = it }
            .orEmpty()
    }
}

fun List<ApiTierInfo>.level(level: Int?): ApiTierInfo? = level?.let { l -> firstOrNull { it.level == l } }

/** Grouped digits in the reader's locale: balances run to six figures. */
fun formatCount(value: Long): String = NumberFormat.getIntegerInstance().format(value)

/** A true minus sign, not a hyphen, as the design draws debits. */
fun formatSigned(value: Long): String =
    if (value >= 0) "+${formatCount(value)}" else "−${formatCount(-value)}"

/** A metal: the light and deep ends of its 145° gradient, and the ink drawn on it. */
data class Metal(val light: Color, val deep: Color, val ink: Color)

/**
 * The tiers' and achievement grades' metals, and the night each tier's card
 * sits in — copied from the web (static/app.css `.tierc-N`, `.ra-tN`,
 * `.ra-gN`, the reputation source colours).
 *
 * These are artwork, not interface colour, which is why they are not
 * [Nocturne] tokens: the web keeps them the same in dark mode ("段位和档次的
 * 颜色是「东西」"), a bronze medal is bronze in either theme, and the server
 * serves no gradient stops. Keep them in step with app.css.
 */
object GrowthArt {
    private fun rgb(hex: Long) = Color(0xFF000000 or hex)

    private val tierMetals = listOf(
        Metal(rgb(0xF6E3D2), rgb(0xD9A679), rgb(0x7A3A10)),
        Metal(rgb(0xF4F7F8), rgb(0xC5D0D6), rgb(0x2A3C42)),
        Metal(rgb(0xFFF4CF), rgb(0xEBC65A), rgb(0x6B4700)),
        Metal(rgb(0xE6FAF6), rgb(0x9ED9CF), rgb(0x0F5D57)),
        Metal(rgb(0xEEF5FF), rgb(0xA9C8FF), rgb(0x1E4E9A)),
        Metal(rgb(0xF8ECFF), rgb(0xD3A8F5), rgb(0x6B21A8)),
        Metal(rgb(0xFFEAF2), rgb(0xF4A3C4), rgb(0x9D174D)),
        Metal(rgb(0xFFEDE3), rgb(0xFFB38A), rgb(0xA33A00)),
        Metal(rgb(0x2B4160), rgb(0x10202E), rgb(0xFBEFC9)),
    )
    private val tierNights = listOf(
        rgb(0x2B1A12), rgb(0x1E272C), rgb(0x2A2108), rgb(0x0A2926), rgb(0x0D1E3B),
        rgb(0x25123B), rgb(0x36102A), rgb(0x3A170A), rgb(0x131318),
    )
    private val gradeMetals = listOf(
        tierMetals[0],
        tierMetals[1],
        tierMetals[2],
        Metal(rgb(0x1B3A2F), rgb(0x0B1F18), rgb(0x5CC79A)),
    )

    /** The near-black the hero's scene is shaded with, so the white type stays legible over any tier. */
    val shade = rgb(0x06080E)

    fun tierMetal(level: Int): Metal = tierMetals[level.coerceIn(0, tierMetals.lastIndex)]
    fun tierNight(level: Int): Color = tierNights[level.coerceIn(0, tierNights.lastIndex)]

    /** 1 铜 · 2 银 · 3 金 · 4 稀有. */
    fun gradeMetal(grade: Int): Metal = gradeMetals[(grade - 1).coerceIn(0, gradeMetals.lastIndex)]

    /** Reputation sources; accept, featured and achievement step up a shade in dark mode, as the web's do. */
    @Composable
    fun source(source: String): Color = when (source) {
        "like" -> Nocturne.accent
        "accept" -> if (Nocturne.isDark) rgb(0x5EC4D6) else rgb(0x0E7490)
        "featured" -> if (Nocturne.isDark) rgb(0xA78BFA) else rgb(0x6D28D9)
        "achievement" -> if (Nocturne.isDark) rgb(0xE0B04A) else rgb(0xB7791F)
        "revoke" -> Nocturne.danger
        else -> Nocturne.textTertiary
    }
}

/** The tier glyphs (backend internal/platform/tier): ring, steps, star, links, gem, banner, wings, laurel, gate. */
@DrawableRes
fun tierIconRes(level: Int): Int = when (level.coerceIn(0, 8)) {
    0 -> R.drawable.ic_tier_bronze
    1 -> R.drawable.ic_tier_silver
    2 -> R.drawable.ic_tier_gold
    3 -> R.drawable.ic_tier_platinum
    4 -> R.drawable.ic_tier_diamond
    5 -> R.drawable.ic_tier_master
    6 -> R.drawable.ic_tier_unrivaled
    7 -> R.drawable.ic_tier_glory
    else -> R.drawable.ic_tier_legend
}

/**
 * A glyph whose path the server sent (32 grid, evenodd) — the permissions
 * page's column icons, which include role glyphs the app has no copy of.
 */
@Composable
fun ServedGlyph(pathData: String, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    val path = remember(pathData) {
        runCatching { PathParser().parsePathString(pathData).toPath().apply { fillType = PathFillType.EvenOdd } }
            .getOrNull()
    } ?: return
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 32f
        scale(k, k, pivot = Offset.Zero) { drawPath(path, tint) }
    }
}

/** The membership tier as the site paints it, falling back to the neutral pill. */
@Composable
fun TierPill(name: String, info: ApiTierInfo?, modifier: Modifier = Modifier) {
    val (ground, ink) = info?.colors(Nocturne.isDark) ?: (Nocturne.infoBg to Nocturne.info)
    Box(
        modifier
            .height(20.dp)
            .clip(CircleShape)
            .background(ground)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, style = Type.body(11, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

/**
 * An achievement's tier (铜 / 银 / 金 / 稀有) as a ground and an ink, for the
 * small flat uses (profile showcase, pills). The pages that show medals use
 * [AchMedal] and the real metals.
 * Locked ones are all the same grey, so colour is something you earn.
 */
@Composable
fun achTierColors(tier: Int, unlocked: Boolean = true): Pair<Color, Color> = when {
    !unlocked -> Nocturne.neutral300 to Nocturne.textTertiary
    tier == 1 -> Nocturne.dangerBg to Nocturne.danger
    tier == 2 -> Nocturne.neutral300 to Nocturne.textSecondary
    tier == 3 -> Nocturne.warningBg to Nocturne.warning
    else -> Nocturne.infoBg to Nocturne.info
}

/**
 * The medal disc: the grade's metal with a bright inner rim and a hairline in
 * the deep tone, as the design draws it. Locked is a flat grey coin.
 */
@Composable
fun AchMedal(grade: Int, unlocked: Boolean, icon: ImageVector, size: Dp, iconSize: Dp, modifier: Modifier = Modifier) {
    val metal = GrowthArt.gradeMetal(grade)
    val ground = if (unlocked) Brush.linearGradient(listOf(metal.light, metal.deep)) else Brush.linearGradient(
        listOf(Nocturne.neutral400, Nocturne.neutral400),
    )
    val rim = if (unlocked) metal.deep else Nocturne.divider
    val inner = if (unlocked) Nocturne.neutral100.copy(alpha = 0.7f) else Nocturne.bg.copy(alpha = 0.7f)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(ground)
            .border(1.dp, rim, CircleShape)
            .padding(1.dp)
            .border(2.dp, inner, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (unlocked) metal.ink else Nocturne.textTertiary, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun achTierLabel(tier: Int): String = stringResource(
    when (tier) {
        1 -> R.string.ach_tier_bronze
        2 -> R.string.ach_tier_silver
        3 -> R.string.ach_tier_gold
        else -> R.string.ach_tier_rare
    },
)

/** The catalogue's icon names (achievement/catalog.go) as glyphs. */
fun achievementIcon(name: String?): ImageVector = when (name) {
    "pen" -> Lucide.Pen
    "bolt" -> Lucide.Zap
    "book" -> Lucide.BookOpen
    "chat" -> Lucide.MessageCircle
    "flag" -> Lucide.Flag
    "flame" -> Lucide.Flame
    "heart" -> Lucide.Heart
    "node" -> Lucide.LayoutGrid
    "star" -> Lucide.Star
    "up" -> Lucide.ArrowUp
    "user" -> Lucide.User
    else -> Lucide.Award
}

/** "发帖  3 / 4": a group heading on the grey pages, label left, figure right. */
@Composable
fun GrowthSectionHead(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = Type.body(13, FontWeight.SemiBold),
            color = Nocturne.textSecondary,
            modifier = Modifier.weight(1f),
        )
        trailing?.let { Text(it, style = Type.body(13, FontWeight.SemiBold), color = Nocturne.accent) }
    }
}

/** The white card the grey growth pages are built from. */
@Composable
fun GrowthCard(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(radius))
            .background(Nocturne.bg)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A flat, rounded bar; the Material indicator's gap and stop dot read as a slider here. */
@Composable
fun GrowthBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    track: Color = Nocturne.neutral400,
    fill: Color = Nocturne.accent,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track),
    ) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height / 2))
                    .background(fill),
            )
        }
    }
}

// ------------------------------------------------------------------ Hero

private const val TOP_TIER = 8

/** The hero's white ink at the strengths the design uses; the card is a night scene in both themes. */
@Composable
private fun heroInk(alpha: Float = 1f): Color = Nocturne.neutral100.copy(alpha = alpha)

fun tierNameOf(level: Int, rep: ApiRepSummary, tiers: List<ApiTierInfo>): String =
    tiers.level(level)?.name ?: rep.tiers.firstOrNull { it.level == level }?.name.orEmpty()

/**
 * The sentence under the tier name, as the web's hero writes it
 * (web/achievements.go `currentLine`): the review and lock states win over
 * the plain "N to go", because in them the number is not what is holding
 * the account back.
 */
@Composable
private fun currentLine(rep: ApiRepSummary, tiers: List<ApiTierInfo>): String {
    val next = rep.next ?: return stringResource(R.string.me2_top_tier)
    val locked = rep.upgradeLockedUntil?.let { DiscourseFormat.instant(it) }?.takeIf { it.isAfter(Instant.now()) }
    return when {
        locked != null -> stringResource(R.string.upgrade2_locked_until, DiscourseFormat.day(rep.upgradeLockedUntil))
        rep.legendReview == "pending" && rep.needed <= 0 -> stringResource(R.string.upgrade2_awaiting_review)
        rep.tierReview == "pending" && rep.needed <= 0 -> stringResource(R.string.upgrade2_line_tier_review)
        // Bronze is the one tier left by finishing onboarding, not by reputation alone.
        rep.tier == 0 && rep.onboardingDoneAt != null -> stringResource(R.string.upgrade2_line_onboarded)
        rep.tier == 0 -> stringResource(R.string.upgrade2_line_bronze, formatCount(rep.needed.toLong()))
        else -> stringResource(R.string.upgrade2_line_next, tierNameOf(next.level, rep, tiers), formatCount(rep.needed.toLong()))
    }
}

/**
 * The 声望与成就 card both growth pages open with: the tier as a night scene
 * in its own colour, where you stand, and the nine tiers as a track you can
 * tap through to read each one's requirement.
 *
 * The board also floats a per-tier illustration top right and paints a full
 * scene behind each tier; that artwork is not in the app, so the card keeps
 * the tier's night and one drifting silk band in the tier's metal.
 */
@Composable
fun GrowthHero(rep: ApiRepSummary, tiers: List<ApiTierInfo>, modifier: Modifier = Modifier) {
    val current = rep.tier.coerceIn(0, TOP_TIER)
    var preview by rememberSaveable(current) { mutableStateOf<Int?>(null) }
    val shown = preview ?: current
    val steps = rep.tiers.sortedBy { it.level }
    val step = steps.firstOrNull { it.level == shown }
    val night by animatedColor(GrowthArt.tierNight(shown), "heroNight")
    val metal = GrowthArt.tierMetal(shown)

    val stateLabel = when {
        shown == current -> stringResource(R.string.upgrade2_current)
        shown < current -> stringResource(R.string.upgrade2_state_past)
        else -> stringResource(R.string.upgrade2_state_ahead)
    }
    // Mirrors web/achievements.go `tierTrack`: a past rung is complete, a
    // rung ahead shows how much of its whole threshold is already in hand.
    val line: String
    val pctLabel: String
    val fraction: Double
    when {
        shown == current -> {
            line = currentLine(rep, tiers)
            pctLabel = stringResource(R.string.upgrade2_pct_now)
            fraction = if (rep.next == null) 1.0 else rep.progress
        }
        shown < current -> {
            line = if ((step?.threshold ?: 0) <= 0) {
                stringResource(R.string.upgrade2_from_start)
            } else {
                stringResource(R.string.upgrade2_line_threshold, formatCount(step!!.threshold.toLong()))
            }
            pctLabel = stringResource(R.string.upgrade2_pct_done)
            fraction = 1.0
        }
        else -> {
            val threshold = step?.threshold ?: 0
            line = stringResource(
                R.string.upgrade2_line_ahead,
                formatCount(threshold.toLong()),
                formatCount((threshold - rep.reputation).coerceAtLeast(0).toLong()),
            )
            pctLabel = stringResource(R.string.upgrade2_pct_done)
            fraction = if (threshold > 0) rep.reputation.toDouble() / threshold else 0.0
        }
    }
    val kicker = Type.body(12, FontWeight.Bold).copy(letterSpacing = 0.08.em)

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(night)
            .drawBehind { drawSilk(metal) },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp)) {
            Text(
                stringResource(R.string.upgrade2_kicker),
                style = kicker,
                color = heroInk(0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tierNameOf(shown, rep, tiers),
                    style = Type.heading(30, FontWeight.ExtraBold),
                    color = heroInk(),
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .height(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(heroInk(0.92f))
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stateLabel, style = Type.body(12, FontWeight.Bold), color = Nocturne.neutral900, maxLines = 1)
                }
            }
            Text(line, style = Type.body(13), color = heroInk(0.78f), modifier = Modifier.padding(top = 2.dp))
            Row(
                Modifier.heightIn(min = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    buildAnnotatedString {
                        append("$pctLabel ")
                        withStyle(SpanStyle(color = heroInk(), fontWeight = FontWeight.Bold)) {
                            append("${(fraction.coerceIn(0.0, 1.0) * 100).roundToInt()}%")
                        }
                    },
                    style = Type.body(13),
                    color = heroInk(0.78f),
                )
                AnimatedVisibility(
                    visible = preview != null,
                    enter = if (LocalReduceMotion.current) fadeIn(tween(0)) else fadeIn(),
                    exit = if (LocalReduceMotion.current) fadeOut(tween(0)) else fadeOut(),
                ) {
                    val press = rememberPressSource()
                    Box(
                        Modifier
                            .pressScale(press)
                            .height(28.dp)
                            .clip(CircleShape)
                            .background(heroInk(0.14f))
                            .clickable(interactionSource = press, indication = ripple(), role = Role.Button) { preview = null }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.upgrade2_back), style = Type.body(12, FontWeight.Bold), color = heroInk())
                    }
                }
            }

            Text(
                stringResource(R.string.upgrade2_my_rep),
                style = kicker,
                color = heroInk(0.6f),
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                formatCount(rep.reputation.toLong()),
                style = Type.heading(36, FontWeight.ExtraBold).copy(letterSpacing = (-0.02).em),
                color = heroInk(),
                maxLines = 1,
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HeroPill(
                    stringResource(R.string.upgrade2_week, formatSigned(rep.week.toLong())),
                    icon = if (rep.week > 0) Lucide.ArrowUpRight else null,
                    ground = heroInk(0.16f),
                )
                // Likes settle the next day; until then they are counted nowhere,
                // which looks like lost reputation unless the card says so.
                if (rep.pending > 0) {
                    HeroPill(
                        stringResource(R.string.upgrade2_pending_pill, formatCount(rep.pending.toLong())),
                        icon = null,
                        ground = heroInk(0.12f),
                    )
                }
            }

            if (steps.isNotEmpty()) {
                TierTrack(
                    rep = rep,
                    steps = steps,
                    tiers = tiers,
                    selected = shown,
                    modifier = Modifier.padding(top = 20.dp),
                ) { level -> preview = if (level == current) null else level }
            }
        }
    }
}

// The board's silk band (its 钻石 scene, `di-env`), on the scene's own
// 688×292 grid, fitted like `xMidYMid slice`.
private val SILK_FILLS = listOf(
    "M-75 128.91 C50 40.89 137 216.57 246 144.83 334 89.44 418 151.68 511 77.96 596 70.36 655 -3.43 758 -29.51 L758 -39.11 C655 -3.14 596 57.17 511 69.62 418 118.31 334 115.07 246 147.24 137 210.68 50 38.32 -75 135.52Z",
    "M-75 130.89 C50 30.99 137 218.99 246 152.09 334 105.28 418 129.68 511 65.64 596 63.32 655 -12.45 758 -41.17 L758 -50.77 C655 -12.16 596 50.13 511 57.3 418 96.31 334 130.91 246 154.5 137 213.1 50 28.42 -75 137.5Z",
)
private val SILK_RIMS = listOf(
    "M-75 128.91 C50 40.89 137 216.57 246 144.83 334 89.44 418 151.68 511 77.96 596 70.36 655 -3.43 758 -29.51",
    "M-75 130.89 C50 30.99 137 218.99 246 152.09 334 105.28 418 129.68 511 65.64 596 63.32 655 -12.45 758 -41.17",
)
private val silkFills: List<Path> by lazy { SILK_FILLS.map { PathParser().parsePathString(it).toPath() } }
private val silkRims: List<Path> by lazy { SILK_RIMS.map { PathParser().parsePathString(it).toPath() } }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSilk(metal: Metal) {
    val k = max(size.width / 688f, size.height / 292f)
    val dx = (size.width - 688f * k) / 2f
    val dy = (size.height - 292f * k) / 2f
    // The scene's metal for a dark tier (传奇) is dark itself; its ink is the light end there.
    val glow = if (metal.light.luminance() < 0.3f) metal.ink else metal.light
    val silk = Brush.linearGradient(
        0f to metal.deep.copy(alpha = 0.12f),
        0.3f to glow.copy(alpha = 0.65f),
        0.6f to glow,
        1f to metal.deep.copy(alpha = 0.18f),
        start = Offset(15f, 179f),
        end = Offset(700f, -15f),
    )
    translate(dx, dy) {
        scale(k, k, pivot = Offset.Zero) {
            silkFills.forEachIndexed { i, path -> drawPath(path, silk, alpha = if (i == 0) 0.17f else 0.135f) }
            silkRims.forEach { drawPath(it, silk, alpha = 0.4f, style = Stroke(width = 1.15f)) }
        }
    }
    // The board's two shades: darker on the left where the type sits, and up from the bottom under the track.
    drawRect(
        Brush.horizontalGradient(
            0f to GrowthArt.shade.copy(alpha = 0.62f),
            0.7f to GrowthArt.shade.copy(alpha = 0.12f),
            1f to GrowthArt.shade.copy(alpha = 0.5f),
        ),
    )
    drawRect(
        Brush.verticalGradient(
            0.58f to GrowthArt.shade.copy(alpha = 0f),
            1f to GrowthArt.shade.copy(alpha = 0.7f),
        ),
    )
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
private fun HeroPill(text: String, icon: ImageVector?, ground: Color) {
    Row(
        Modifier
            .height(22.dp)
            .clip(CircleShape)
            .background(ground)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        icon?.let { Icon(it, null, tint = heroInk(), modifier = Modifier.size(12.dp)) }
        Text(text, style = Type.body(12, FontWeight.Bold), color = heroInk(), maxLines = 1)
    }
}

/**
 * The nine rungs on one rail. The rail runs centre to centre, so its fill is
 * (tier + progress) / 8 of it — the web's `TrackFill`.
 */
@Composable
private fun TierTrack(
    rep: ApiRepSummary,
    steps: List<ApiRepStep>,
    tiers: List<ApiTierInfo>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val top = steps.maxOf { it.level }.coerceAtLeast(1)
    val fill = if (rep.next == null) 1f else ((rep.tier + rep.progress.coerceIn(0.0, 1.0)) / top).toFloat().coerceIn(0f, 1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val slot = maxWidth / steps.size
        Box(
            Modifier
                .offset(x = slot / 2, y = 21.dp)
                .width(maxWidth - slot)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(heroInk(0.2f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fill)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(heroInk(0.7f), heroInk(), heroInk(0.7f)))),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            steps.forEach { step ->
                TierNode(
                    level = step.level,
                    name = tierNameOf(step.level, rep, tiers),
                    current = rep.tier,
                    isSelected = step.level == selected,
                    modifier = Modifier.weight(1f),
                ) { onSelect(step.level) }
            }
        }
    }
}

@Composable
private fun TierNode(
    level: Int,
    name: String,
    current: Int,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val isCurrent = level == current
    val reached = level < current
    val big = isCurrent || isSelected
    val size by animateDpAsState(if (big) 36.dp else 28.dp, tween(if (reduce) 0 else 250), label = "tierDot")
    val metal = GrowthArt.tierMetal(level)
    val press = rememberPressSource()
    val description = stringResource(
        when {
            isCurrent -> R.string.ach_tier_cd_now
            reached -> R.string.ach_tier_cd_past
            else -> R.string.ach_tier_cd_ahead
        },
        name,
    )
    Column(
        modifier
            .pressScale(press)
            .clip(RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = description
                selected = isSelected
            }
            .clickable(interactionSource = press, indication = ripple(), role = Role.Tab, onClick = onClick)
            .padding(top = 4.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                // The design's soft halo, then its slow pulse; the pulse is motion, the halo is not.
                Box(Modifier.size(46.dp).clip(CircleShape).background(heroInk(0.16f)))
                if (!reduce) PulseRing()
            }
            val ground = when {
                isCurrent -> Brush.linearGradient(listOf(metal.light, metal.deep))
                reached -> Brush.linearGradient(listOf(heroInk(0.2f), heroInk(0.2f)))
                else -> Brush.linearGradient(listOf(GrowthArt.shade.copy(alpha = 0.45f), GrowthArt.shade.copy(alpha = 0.45f)))
            }
            val ring = when {
                big -> heroInk()
                reached -> heroInk(0.42f)
                else -> heroInk(0.16f)
            }
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(ground)
                    .border(if (big) 2.dp else 1.dp, ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(tierIconRes(level)),
                    null,
                    tint = when {
                        isCurrent -> metal.ink
                        reached || isSelected -> heroInk()
                        else -> heroInk(0.5f)
                    },
                    modifier = Modifier.size(if (big) 18.dp else 14.dp),
                )
            }
        }
        val ink by animatedColor(if (isSelected) heroInk() else heroInk(0.62f), "tierLabel")
        Text(
            name,
            style = Type.body(12, if (isSelected) FontWeight.Bold else FontWeight.Medium),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** The design's slow halo around the current rung; skipped entirely under reduce-motion. */
@Composable
private fun PulseRing() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseT",
    )
    Box(
        Modifier
            .size(50.dp)
            .graphicsLayer {
                val s = 0.82f + 0.68f * t
                scaleX = s
                scaleY = s
                alpha = 0.75f * (1f - t)
            }
            .border(2.dp, heroInk(0.55f), CircleShape),
    )
}
