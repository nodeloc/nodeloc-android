package com.nodeloc.app.feature.post

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Dices
import com.composables.icons.lucide.Frown
import com.composables.icons.lucide.Gift
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Ticket
import com.nodeloc.app.R
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.model.LotteryParticipant
import com.nodeloc.app.core.model.PostLottery
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.profile.TierDirectory
import com.nodeloc.app.feature.profile.formatCount
import com.nodeloc.app.feature.profile.level
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** How often, and how many times, the reader looks for the result once the countdown has run out. */
internal const val LOTTERY_DRAW_POLLS = 5
internal const val LOTTERY_DRAW_POLL_MS = 6_000L

/**
 * The lottery's own colours: the red-envelope ground, its gold ornaments, the
 * prize-grade pills and the red buy button (design board App-Android-Lottery).
 *
 * Artwork rather than interface colour, which is why they are not [Nocturne]
 * tokens: a lottery is red in either theme, the way a red envelope is, and the
 * white panels on it are drawn from Nocturne so they still follow the theme.
 */
private object LotteryArt {
    private fun rgb(hex: Long, alpha: Float = 1f) = Color(0xFF000000 or hex).copy(alpha = alpha)

    val deep = rgb(0xA80F18)
    val mid = rgb(0xD2231C)
    val light = rgb(0xE5482A)
    val glowTop = rgb(0xFFD678, 0.45f)
    val glowBottom = rgb(0xFFC85A, 0.35f)
    val dot = rgb(0xFFDE96, 0.22f)
    val ring = rgb(0xFFD678, 0.25f)
    val diamond = rgb(0xFFD58A, 0.8f)
    val diamondPale = rgb(0xFFE7B0, 0.7f)
    val shadow = rgb(0xA80F18, 0.25f)

    val tileGround = rgb(0xFFFFFF, 0.18f)
    val tileRim = rgb(0xFFD678, 0.5f)
    val gold = rgb(0xFFD58A)
    val onRed = rgb(0xFFFFFF)
    val onRedSoft = rgb(0xFFE1C2)

    val progressFrom = rgb(0xD2231C)
    val progressTo = rgb(0xF08A24)
    val link = rgb(0xB3121B)
    val button = rgb(0xC81E1E)

    val badgeGround = rgb(0xFDE6E4)
    val badgeInk = rgb(0xB3121B)

    /** 一等奖 gold, 二等奖 silver, 三等奖 bronze; anything past the third takes the theme's neutral. */
    val grades = listOf(
        rgb(0xFFE9CF) to rgb(0x8A4B00),
        rgb(0xE5EBEE) to rgb(0x2A3C42),
        rgb(0xF3E3D3) to rgb(0x7A3E10),
    )
}

private val tabular = TextStyle(fontFeatureSettings = "tnum")

// --------------------------------------------------------------- rules

/** Tickets the reader may still buy under the per-person cap. */
private fun PostLottery.room(): Int = max(0, (maxTicketsPerUser ?: 1) - (userTickets ?: 0))

/** The smallest purchase the server accepts now: the per-person minimum counts what the reader already holds. */
private fun PostLottery.leastPurchase(): Int = max(1, (minTicketsPerUser ?: 1) - (userTickets ?: 0))

private fun PostLottery.drawMillis(): Long? = DiscourseFormat.instant(drawAt)?.toEpochMilli()

/**
 * Open for buying right now, ticking over to false at `draw_at` on its own —
 * the server may take a little while to publish the draw, and in between the
 * buy bar must not offer what the server will refuse.
 */
@Composable
fun rememberLotteryOpen(lottery: PostLottery?): Boolean {
    val drawAt = lottery?.drawMillis()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(drawAt) {
        if (drawAt == null) return@LaunchedEffect
        val wait = drawAt - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        now = System.currentTimeMillis()
    }
    return lottery?.isOpen == true && (drawAt == null || now < drawAt)
}

/**
 * Whether the bar at the foot of the thread should sell tickets to this
 * reader. The checks are the server's own (play/lottery.go `BuyTickets`) that
 * the app can see: not the organiser, room under the cap, room in the draw.
 * The tier requirement is not among them — the session does not carry the
 * reader's tier, and the server's refusal names the tier needed.
 */
fun PostLottery.sellsTo(me: String?, organiser: String?): Boolean {
    if (me == null) return false
    if (organiser != null && organiser.equals(me, ignoreCase = true)) return false
    if (room() <= 0) return false
    val full = hasParticipantCap && (participantsCount ?: 0) >= (maxParticipants ?: 0)
    return !(full && (userTickets ?: 0) == 0)
}

/** Month, day, weekday and time in the reader's language; ICU orders the fields. */
private fun lotteryMoment(raw: String?, skeleton: String): String {
    val instant = DiscourseFormat.instant(raw) ?: return ""
    val locale = Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    val formatter = runCatching { DateTimeFormatter.ofPattern(pattern, locale) }
        .getOrElse { DateTimeFormatter.ofPattern("MMM d HH:mm", locale) }
    return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(formatter)
}

// ---------------------------------------------------------- title badge

/** The red 抽奖 pill the header puts before a lottery post's title. */
@Composable
fun LotteryTitleBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(LotteryArt.badgeGround)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Lucide.Gift, null, tint = LotteryArt.badgeInk, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.lottery_title), style = Type.body(13, FontWeight.Bold), color = LotteryArt.badgeInk)
    }
}

// ----------------------------------------------------------------- card

/**
 * The lottery on the first post (design board App-Android-Lottery*): the red
 * card with its countdown, entrants and prizes while open, and the reader's
 * own result once drawn. Buying lives in [LotteryBuyBar] at the foot of the
 * thread, not here.
 */
@Composable
fun LotteryCard(
    lottery: PostLottery,
    me: String?,
    onDeadline: () -> Unit,
    loadParticipants: suspend (Int) -> List<LotteryParticipant>,
) {
    var holderSheet by remember { mutableStateOf<HolderSheet?>(null) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(16.dp, shape, ambientColor = LotteryArt.shadow, spotColor = LotteryArt.shadow)
            .clip(shape)
            .lotteryGround(),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LotteryHeader(lottery)
            when (lottery.status) {
                "open" -> {
                    CountdownPanel(lottery, onDeadline)
                    if (!lottery.participants.isNullOrEmpty()) {
                        ParticipantStrip(lottery) { holderSheet = HolderSheet.All(lottery.id, lottery.participantsCount ?: 0) }
                    }
                }
                "drawn" -> ResultBanner(lottery, me)
                "failed" -> FailedBanner(lottery)
            }
            PrizesPanel(lottery, me) { name, winners -> holderSheet = HolderSheet.Winners(name, winners) }
        }
    }
    holderSheet?.let { sheet ->
        HolderListSheet(sheet, loadParticipants) { holderSheet = null }
    }
}

/** The ground of the card: the red gradient, two warm glows, a dot grid and the ring and diamonds at the top. */
private fun Modifier.lotteryGround(): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val base = Brush.linearGradient(
        0f to LotteryArt.deep, 0.55f to LotteryArt.mid, 1f to LotteryArt.light,
        start = Offset.Zero, end = Offset(w, h),
    )
    // CSS sizes these as a share of the distance to the farthest corner.
    val topCenter = Offset(w * 0.08f, -h * 0.10f)
    val topGlow = Brush.radialGradient(
        listOf(LotteryArt.glowTop, Color.Transparent),
        center = topCenter,
        radius = 0.38f * hypot(w - topCenter.x, h - topCenter.y),
    )
    val bottomCenter = Offset(w * 1.05f, h * 1.10f)
    val bottomGlow = Brush.radialGradient(
        listOf(LotteryArt.glowBottom, Color.Transparent),
        center = bottomCenter,
        radius = 0.42f * hypot(bottomCenter.x, bottomCenter.y),
    )
    val step = 16.dp.toPx()
    val dotRadius = 1.5.dp.toPx()
    val ringStroke = 10.dp.toPx()
    val ringOuter = 120.dp.toPx()
    val ringCenter = Offset(w + 18.dp.toPx() - ringOuter / 2, -22.dp.toPx() + ringOuter / 2)
    val bigDiamond = 8.dp.toPx()
    val smallDiamond = 6.dp.toPx()
    val bigDiamondAt = Offset(w - 70.dp.toPx() - bigDiamond, 14.dp.toPx())
    val smallDiamondAt = Offset(w * 0.46f, 8.dp.toPx())
    onDrawBehind {
        drawRect(base)
        drawRect(topGlow)
        drawRect(bottomGlow)
        var y = step / 2
        while (y < h) {
            var x = step / 2
            while (x < w) {
                drawCircle(LotteryArt.dot, dotRadius, Offset(x, y))
                x += step
            }
            y += step
        }
        drawCircle(LotteryArt.ring, (ringOuter - ringStroke) / 2, ringCenter, style = Stroke(ringStroke))
        rotate(45f, Offset(bigDiamondAt.x + bigDiamond / 2, bigDiamondAt.y + bigDiamond / 2)) {
            drawRect(LotteryArt.diamond, bigDiamondAt, Size(bigDiamond, bigDiamond))
        }
        rotate(45f, Offset(smallDiamondAt.x + smallDiamond / 2, smallDiamondAt.y + smallDiamond / 2)) {
            drawRect(LotteryArt.diamondPale, smallDiamondAt, Size(smallDiamond, smallDiamond))
        }
    }
}

@Composable
private fun LotteryHeader(lottery: PostLottery) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(LotteryArt.tileGround)
                .border(1.dp, LotteryArt.tileRim, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Gift, null, tint = LotteryArt.gold, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                lottery.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.lottery_title),
                style = Type.heading(16, FontWeight.Bold),
                color = LotteryArt.onRed,
            )
            val moment = when (lottery.status) {
                "open" -> stringResource(R.string.lot2_draws_on, lotteryMoment(lottery.drawAt, "MMMdEEEjmm"))
                "drawn" -> stringResource(R.string.lot2_drawn_on, lotteryMoment(lottery.drawnAt ?: lottery.drawAt, "MMMdjmm"))
                else -> lotteryMoment(lottery.drawAt, "MMMdjmm")
            }
            if (moment.isNotBlank()) {
                Text(moment, style = Type.body(12).merge(tabular), color = LotteryArt.onRedSoft)
            }
        }
        val (label, ground, ink) = when (lottery.status) {
            "open" -> Triple(stringResource(R.string.lot2_status_open), Nocturne.accent100, Nocturne.accent700)
            "drawn" -> Triple(stringResource(R.string.lottery_drawn), Nocturne.neutral400, Nocturne.textSecondary)
            "failed" -> Triple(stringResource(R.string.lot2_status_failed), Nocturne.dangerBg, Nocturne.danger)
            else -> Triple(stringResource(R.string.lot2_status_cancelled), Nocturne.dangerBg, Nocturne.danger)
        }
        Box(
            Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(ground).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = Type.body(12, FontWeight.Bold), color = ink)
        }
    }
}

/** A white panel on the red ground. */
@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Nocturne.bg)
            .padding(horizontal = padding, vertical = 14.dp),
    ) { content() }
}

@Composable
private fun CountdownPanel(lottery: PostLottery, onDeadline: () -> Unit) {
    val drawAt = lottery.drawMillis()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Ticks only while the card is composed and the screen is started: the
    // reader's list disposes the card once it scrolls away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(drawAt, lifecycle) {
        if (drawAt == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                if (now >= drawAt) break
                delay(1_000 - now % 1_000)
            }
        }
    }
    val left = ((drawAt ?: now) - now).coerceAtLeast(0) / 1_000
    val expired = drawAt != null && left == 0L
    LaunchedEffect(expired) { if (expired) onDeadline() }

    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(stringResource(R.string.lot2_countdown), style = Type.body(12), color = Nocturne.textSecondary)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CountdownBox((left / 86_400).toString(), stringResource(R.string.lot2_unit_day))
                    CountdownBox("%02d".format(left / 3_600 % 24), stringResource(R.string.lot2_unit_hour))
                    CountdownBox("%02d".format(left / 60 % 60), stringResource(R.string.lot2_unit_min))
                    CountdownBox("%02d".format(left % 60), stringResource(R.string.lot2_unit_sec))
                }
            }
            Column {
                val joined = lottery.participantsCount ?: 0
                val needed = lottery.minParticipants ?: 0
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.lot2_joined, joined),
                        style = Type.body(12).merge(tabular),
                        color = Nocturne.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (needed > 0) {
                        Text(stringResource(R.string.lot2_min_to_draw, needed), style = Type.body(12).merge(tabular), color = Nocturne.textSecondary)
                    }
                }
                val fraction = if (needed <= 0) 1f else (joined.toFloat() / needed).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Nocturne.neutral400),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(Brush.horizontalGradient(listOf(LotteryArt.progressFrom, LotteryArt.progressTo))),
                    )
                }
                val tierName by produceState<String?>(null, lottery.minTrustLevel) {
                    val level = lottery.minTrustLevel?.takeIf { it > 0 } ?: return@produceState
                    value = TierDirectory.get().level(level)?.name?.takeIf { it.isNotBlank() }
                }
                val facts = listOfNotNull(
                    stringResource(R.string.lot2_sold, lottery.ticketsCount ?: 0),
                    stringResource(R.string.lot2_per_ticket),
                    lottery.maxParticipants?.takeIf { lottery.hasParticipantCap }?.let { stringResource(R.string.lot2_cap, it) },
                    tierName?.let { stringResource(R.string.lot2_requires_tier, it) },
                )
                Text(
                    facts.joinToString(" · "),
                    style = Type.body(12).merge(tabular),
                    color = Nocturne.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CountdownBox(value: String, unit: String) {
    Column(
        Modifier
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Nocturne.bg)
            .border(1.dp, Nocturne.neutral400, RoundedCornerShape(10.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = Type.heading(22, FontWeight.Bold).merge(tabular), color = Nocturne.text)
        Text(unit, style = Type.body(11), color = Nocturne.textSecondary)
    }
}

/** Overlapping avatars, each ringed in the panel colour so the stack reads as separate faces. */
@Composable
private fun AvatarStack(
    people: List<LotteryParticipant>,
    size: Dp,
    extra: Int,
    textSize: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), verticalAlignment = Alignment.CenterVertically) {
        people.forEach { person ->
            val name = person.username.orEmpty()
            RemoteAvatar(
                url = DiscourseConfig.avatarUrl(person.avatarTemplate, 96),
                letter = name.ifEmpty { "?" },
                size = size,
                variant = name.hashCode() and 1,
                modifier = Modifier.border(2.dp, Nocturne.bg, CircleShape),
            )
        }
        if (extra > 0) {
            Box(
                Modifier
                    .height(size)
                    .defaultMinSize(minWidth = size)
                    .clip(RoundedCornerShape(size / 2))
                    .background(Nocturne.text)
                    .border(2.dp, Nocturne.bg, RoundedCornerShape(size / 2))
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+$extra", style = Type.body(textSize, FontWeight.Bold).merge(tabular), color = Nocturne.bg)
            }
        }
    }
}

@Composable
private fun ParticipantStrip(lottery: PostLottery, onAll: () -> Unit) {
    val total = lottery.participantsCount ?: 0
    val a11y = stringResource(R.string.lot2_all_a11y, total)
    val press = rememberPressSource()
    Panel(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.lot2_participants), style = Type.body(13, FontWeight.Bold), color = Nocturne.text)
            Row(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .pressScale(press)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(interactionSource = press, indication = ripple(), onClick = onAll)
                    .semantics { contentDescription = a11y }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BoxWithConstraints(Modifier.weight(1f)) {
                    // As many faces as the row holds, leaving room for the "+N" pill.
                    val fits = ((maxWidth - 8.dp - 44.dp) / 24.dp).toInt().coerceIn(1, 7)
                    val shown = lottery.participants.orEmpty().take(fits)
                    AvatarStack(shown, 32.dp, extra = total - shown.size, textSize = 12)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.lot2_all), style = Type.body(13, FontWeight.Bold), color = LotteryArt.link)
                    Icon(Lucide.ChevronRight, null, tint = LotteryArt.link, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, ground: Color, ink: Color, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ground)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        Column {
            Text(title, style = Type.body(15, FontWeight.Bold), color = ink)
            Text(body, style = Type.body(13).merge(tabular), color = ink.copy(alpha = 0.85f))
        }
    }
}

/** Only a signed-in reader who held tickets has a result to be told. */
@Composable
private fun ResultBanner(lottery: PostLottery, me: String?) {
    val mine = lottery.userTickets ?: 0
    if (me == null || mine <= 0) return
    val won = lottery.levels.orEmpty().firstOrNull { level ->
        level.winners.orEmpty().any { it.username.equals(me, ignoreCase = true) }
    }
    if (won != null) {
        Banner(
            Lucide.Check,
            Nocturne.accent100,
            Nocturne.accent700,
            stringResource(R.string.lot2_win_title, won.name.orEmpty()),
            stringResource(R.string.lot2_win_body, won.prize?.takeIf { it.isNotBlank() } ?: won.name.orEmpty()),
        )
    } else {
        Banner(
            Lucide.Frown,
            Nocturne.surface,
            Nocturne.neutral800,
            stringResource(R.string.lot2_lose_title),
            stringResource(R.string.lot2_lose_body, mine, lottery.participantsCount ?: 0, lottery.ticketsCount ?: 0),
        )
    }
}

@Composable
private fun FailedBanner(lottery: PostLottery) {
    val joined = lottery.participantsCount ?: 0
    val needed = lottery.minParticipants ?: 0
    val mine = lottery.userTickets ?: 0
    Banner(
        Lucide.CircleAlert,
        Nocturne.dangerBg,
        Nocturne.danger,
        stringResource(R.string.lot2_failed_title),
        if (mine > 0) stringResource(R.string.lot2_failed_refund, joined, needed, mine)
        else stringResource(R.string.lot2_failed_body, joined, needed),
    )
}

@Composable
private fun PrizesPanel(lottery: PostLottery, me: String?, onWinners: (String, List<LotteryParticipant>) -> Unit) {
    val levels = lottery.levels.orEmpty()
    if (levels.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Nocturne.bg)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            stringResource(R.string.lot2_prizes),
            style = Type.body(13, FontWeight.Bold),
            color = Nocturne.text,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        levels.forEachIndexed { index, level ->
            HairLine()
            Column(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val (ground, ink) = LotteryArt.grades.getOrNull(index) ?: (Nocturne.neutral400 to Nocturne.textSecondary)
                    Box(
                        Modifier
                            .widthIn(min = 52.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ground)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(level.name.orEmpty(), style = Type.body(12, FontWeight.Bold), color = ink, maxLines = 1)
                    }
                    Text(
                        level.prize.orEmpty(),
                        style = Type.body(14, FontWeight.Medium),
                        color = Nocturne.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.lot2_prize_count, level.quantity ?: 1),
                        style = Type.body(13).merge(tabular),
                        color = Nocturne.textSecondary,
                    )
                }
                val winners = level.winners.orEmpty()
                if (winners.isNotEmpty()) {
                    WinnerChip(winners, me) { onWinners(level.name.orEmpty(), winners) }
                }
            }
        }
    }
}

@Composable
private fun WinnerChip(winners: List<LotteryParticipant>, me: String?, onOpen: () -> Unit) {
    val mine = me != null && winners.any { it.username.equals(me, ignoreCase = true) }
    val label = when {
        mine && winners.size == 1 -> stringResource(R.string.lot2_winners_you)
        mine -> stringResource(R.string.lot2_winners_you_and, winners.size - 1)
        winners.size == 1 -> winners.first().username.orEmpty()
        else -> stringResource(R.string.lot2_winners_many, winners.first().username.orEmpty(), winners.size)
    }
    // The reader's own face leads when they are among them, as the label does.
    val ordered = if (mine) winners.sortedByDescending { it.username.equals(me, ignoreCase = true) } else winners
    val shown = if (ordered.size > 4) ordered.take(3) else ordered
    val press = rememberPressSource()
    Row(
        Modifier
            .height(36.dp)
            .pressScale(press)
            .clip(RoundedCornerShape(18.dp))
            .background(Nocturne.surface)
            .clickable(interactionSource = press, indication = ripple(), onClick = onOpen)
            .padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarStack(shown, 28.dp, extra = ordered.size - shown.size, textSize = 11)
        Text(label, style = Type.body(13, FontWeight.Bold), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------- holder sheets

private sealed interface HolderSheet {
    data class All(val lotteryId: Int, val count: Int) : HolderSheet
    data class Winners(val level: String, val people: List<LotteryParticipant>) : HolderSheet
}

/** Everyone who bought, most tickets first (fetched on open), or one tier's winners (already in hand). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HolderListSheet(
    sheet: HolderSheet,
    loadParticipants: suspend (Int) -> List<LotteryParticipant>,
    onDismiss: () -> Unit,
) {
    val people by produceState<Result<List<LotteryParticipant>>?>(
        initialValue = (sheet as? HolderSheet.Winners)?.let { Result.success(it.people) },
        sheet,
    ) {
        if (sheet is HolderSheet.All) value = runCatchingCancellable { loadParticipants(sheet.lotteryId) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Nocturne.bg,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                when (sheet) {
                    is HolderSheet.All -> stringResource(R.string.lot2_participants_title, sheet.count)
                    is HolderSheet.Winners -> stringResource(R.string.lot2_winners_title, sheet.level)
                },
                style = Type.heading(17, FontWeight.SemiBold),
                color = Nocturne.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val result = people
            when {
                result == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Nocturne.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
                result.isFailure -> SheetNote(stringResource(R.string.lot2_participants_failed))
                result.getOrThrow().isEmpty() -> SheetNote(stringResource(R.string.lot2_participants_empty))
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(result.getOrThrow()) { person ->
                        val name = person.username.orEmpty()
                        Row(
                            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RemoteAvatar(
                                url = DiscourseConfig.avatarUrl(person.avatarTemplate, 96),
                                letter = name.ifEmpty { "?" },
                                size = 36.dp,
                                variant = name.hashCode() and 1,
                            )
                            Text(
                                name,
                                style = Type.body(15, FontWeight.Medium),
                                color = Nocturne.text,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            person.tickets?.let {
                                Text(stringResource(R.string.lot2_holder_tickets, it), style = Type.body(13).merge(tabular), color = Nocturne.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetNote(text: String) {
    Text(
        text,
        style = Type.body(14),
        color = Nocturne.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
    )
}

// ------------------------------------------------------------- buy bar

/**
 * Takes the reply bar's place while the reader can still buy: the price, what
 * they hold or their balance, a way to the reply composer, and 买票/再买,
 * which opens [LotteryBuySheet].
 */
@Composable
fun LotteryBuyBar(
    lottery: PostLottery,
    balance: Long?,
    buying: Boolean,
    onComment: () -> Unit,
    onBuy: (quantity: Int, random: Boolean, onBought: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val held = lottery.userTickets ?: 0
    Column(
        modifier
            .fillMaxWidth()
            .background(Nocturne.bg)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        HairLine()
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lot2_per_ticket), style = Type.body(15, FontWeight.Bold), color = Nocturne.text)
                Text(
                    when {
                        held > 0 -> stringResource(R.string.lot2_bar_holding, held, lottery.room())
                        balance != null -> stringResource(R.string.lot2_bar_new, formatCount(balance))
                        else -> stringResource(R.string.lot2_bar_new_plain)
                    },
                    style = Type.body(12).merge(tabular),
                    color = Nocturne.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val commentPress = rememberPressSource()
            Box(
                Modifier
                    .size(48.dp)
                    .pressScale(commentPress)
                    .clip(CircleShape)
                    .background(Nocturne.neutral300)
                    .clickable(interactionSource = commentPress, indication = ripple(), onClick = onComment),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.MessageSquare, stringResource(R.string.lot2_comment), tint = Nocturne.text, modifier = Modifier.size(20.dp))
            }
            val buyPress = rememberPressSource()
            Row(
                Modifier
                    .height(48.dp)
                    .widthIn(min = 104.dp)
                    .pressScale(buyPress)
                    .clip(RoundedCornerShape(24.dp))
                    .background(LotteryArt.button)
                    .clickable(interactionSource = buyPress, indication = ripple()) { sheetOpen = true }
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Icon(Lucide.Ticket, null, tint = LotteryArt.onRed, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(if (held > 0) R.string.lot2_buy_more else R.string.lot2_buy),
                    style = Type.body(16, FontWeight.Bold),
                    color = LotteryArt.onRed,
                )
            }
        }
    }
    if (sheetOpen) {
        LotteryBuySheet(
            lottery = lottery,
            balance = balance,
            buying = buying,
            onDismiss = { sheetOpen = false },
            onBuy = { quantity, random -> onBuy(quantity, random) { sheetOpen = false } },
        )
    }
}

// ----------------------------------------------------------- buy sheet

private val QUICK_PICKS = listOf(5, 10, 50)
private const val LUCKY_HOLD_MS = 3_000

/**
 * The purchase (board App-Android-LotteryBuy): a stepper with quick picks, the
 * cost against the balance, and two ways to pay — a counted purchase, or 随缘,
 * which asks the server to pick the count (`random: true`) and is armed by a
 * three-second hold so it cannot fire by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LotteryBuySheet(
    lottery: PostLottery,
    balance: Long?,
    buying: Boolean,
    onDismiss: () -> Unit,
    onBuy: (quantity: Int, random: Boolean) -> Unit,
) {
    val least = lottery.leastPurchase()
    val room = lottery.room()
    // The balance caps what the stepper offers; the server would refuse more.
    val most = if (balance != null) min(room.toLong(), balance).toInt() else room
    val affordable = most >= least
    var count by remember { mutableIntStateOf(min(10, most).coerceAtLeast(least)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Nocturne.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.lot2_buy), style = Type.heading(22, FontWeight.Normal), color = Nocturne.text)
            Text(
                stringResource(
                    R.string.lot2_sheet_sub,
                    lottery.title.orEmpty(),
                    lotteryMoment(lottery.drawAt, "EEEjmm"),
                ),
                style = Type.body(13),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepButton(Lucide.Minus, stringResource(R.string.lot2_less), enabled = affordable && count > least) { count-- }
                Row(Modifier.widthIn(min = 96.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                    Text(count.toString(), style = Type.heading(56, FontWeight.Bold).merge(tabular), color = Nocturne.text)
                    Text(
                        stringResource(R.string.lot2_tickets_unit),
                        style = Type.body(16),
                        color = Nocturne.textSecondary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                    )
                }
                StepButton(Lucide.Plus, stringResource(R.string.lot2_more), enabled = affordable && count < most) { count++ }
            }
            Spacer(Modifier.height(18.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK_PICKS.forEach { pick ->
                    QuickPick(
                        stringResource(R.string.lot2_pick, pick),
                        selected = count == pick,
                        enabled = affordable && pick in least..most,
                        modifier = Modifier.weight(1f),
                    ) { count = pick }
                }
                QuickPick(
                    stringResource(R.string.lot2_pick_max, max(most, 0)),
                    selected = count == most && most !in QUICK_PICKS,
                    enabled = affordable,
                    modifier = Modifier.weight(1f),
                ) { count = most }
            }
            Spacer(Modifier.height(18.dp))

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nocturne.bg)) {
                SummaryRow(stringResource(R.string.lot2_cost), stringResource(R.string.lot2_energy, formatCount(count.toLong())), strong = true)
                if (balance != null) {
                    HairLine()
                    SummaryRow(stringResource(R.string.lot2_after), stringResource(R.string.lot2_energy, formatCount(balance - count)))
                }
                HairLine()
                SummaryRow(
                    stringResource(R.string.lot2_limit),
                    stringResource(
                        R.string.lot2_limit_value,
                        lottery.minTicketsPerUser ?: 1,
                        lottery.maxTicketsPerUser ?: 1,
                        max(0, room - count),
                    ),
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LuckyButton(enabled = !buying && room >= least) { onBuy(0, true) }
                val confirmPress = rememberPressSource()
                val canBuy = affordable && !buying
                Box(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .pressScale(confirmPress)
                        .alpha(if (canBuy) 1f else 0.6f)
                        .clip(RoundedCornerShape(26.dp))
                        .background(LotteryArt.button)
                        .clickable(
                            interactionSource = confirmPress,
                            indication = ripple(),
                            enabled = canBuy,
                        ) { onBuy(count, false) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (buying) {
                        CircularProgressIndicator(color = LotteryArt.onRed, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Text(
                            if (affordable) stringResource(R.string.lot2_confirm, count, formatCount(count.toLong()))
                            else stringResource(R.string.lot2_short),
                            style = Type.body(17, FontWeight.Bold).merge(tabular),
                            color = LotteryArt.onRed,
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.lot2_lucky_hint, least, room),
                style = Type.body(13).copy(lineHeight = Type.lineHeight(13, 6)),
                color = Nocturne.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val press = rememberPressSource()
    Box(
        Modifier
            .size(52.dp)
            .pressScale(press)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(Nocturne.neutral400)
            .clickable(interactionSource = press, indication = ripple(), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Nocturne.text, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun QuickPick(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val press = rememberPressSource()
    val ground by com.nodeloc.app.core.design.animatedColor(if (selected) Nocturne.text else Nocturne.neutral400)
    val ink by com.nodeloc.app.core.design.animatedColor(if (selected) Nocturne.bg else Nocturne.text)
    Box(
        modifier
            .height(40.dp)
            .pressScale(press)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(20.dp))
            .background(ground)
            .clickable(interactionSource = press, indication = ripple(), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.body(14, FontWeight.Bold).merge(tabular), color = ink, maxLines = 1)
    }
}

@Composable
private fun SummaryRow(label: String, value: String, strong: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.body(16), color = Nocturne.text, modifier = Modifier.weight(1f))
        Text(
            value,
            style = Type.body(if (strong) 16 else 15, if (strong) FontWeight.Bold else FontWeight.Normal).merge(tabular),
            color = if (strong) Nocturne.text else Nocturne.textSecondary,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 随缘: fires only after a three-second hold, filling as it goes; letting go
 * early cancels. A tap does nothing, which is the point — the count is the
 * server's to choose and the energy is spent the moment it does.
 */
@Composable
private fun LuckyButton(enabled: Boolean, onFire: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.lot2_lucky)
    val ink = Nocturne.warning
    Box(
        Modifier
            .height(52.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(26.dp))
            .background(Nocturne.warningBg)
            .drawBehind {
                if (progress.value > 0f) {
                    drawRect(ink.copy(alpha = 0.18f), size = Size(size.width * progress.value, size.height))
                }
            }
            .semantics {
                // Screen readers cannot hold for a count; a long-press action stands in.
                if (enabled) onLongClick(label) { onFire(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    val hold = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(LUCKY_HOLD_MS, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onFire()
                    }
                    waitForUpOrCancellation()
                    hold.cancel()
                    scope.launch { progress.animateTo(0f, tween(150)) }
                }
            }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Lucide.Dices, null, tint = ink, modifier = Modifier.size(18.dp))
            Text(label, style = Type.body(16, FontWeight.Bold), color = ink)
        }
    }
}
