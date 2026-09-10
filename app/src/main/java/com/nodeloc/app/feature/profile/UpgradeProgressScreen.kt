package com.nodeloc.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.isScrolledPastTop
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.model.UpgradeCondition
import com.nodeloc.app.core.model.UpgradeProgress
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.feature.nav.Navigator

/**
 * How close an account is to its next trust level.
 *
 * Every requirement arrives already written and translated — the plugin renders
 * the sentence, the label and the window server-side so the wording tracks the
 * site's own thresholds. Nothing on this page composes text out of numbers; it
 * only groups what it is given and draws the bars.
 */
@Composable
fun UpgradeProgressScreen(username: String, navigator: Navigator) {
    val viewModel: UpgradeProgressViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(username) { viewModel.bind(username) }

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        val progress = state.progress
        when {
            progress == null && state.isLoading -> Column(
                Modifier
                    .padding(top = floatingHeaderInset, start = Space.page, end = Space.page)
                    .skeletonPulsing(),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                SkeletonBox(Modifier.fillMaxWidth().height(96.dp))
                repeat(6) { SkeletonBox(Modifier.fillMaxWidth().height(56.dp)) }
            }

            progress == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val offline = state.error?.isOfflineError() == true
                EmptyStateView(
                    icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                    title = stringResource(
                        if (offline) R.string.error_offline else R.string.upgrade_load_failed,
                    ),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = viewModel::retry,
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = floatingHeaderInset,
                    start = Space.page,
                    end = Space.page,
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
                overscrollEffect = rememberTapSafeOverscroll(),
            ) {
                item { SummaryCard(progress) }

                // Fixed order rather than the order they arrive in: the groups
                // are a hierarchy — who you are, then what you did, then over
                // what window — and shuffling it with the payload would move
                // the page around between two loads of the same account.
                GROUP_ORDER.forEach { group ->
                    val rows = progress.conditions.filter { it.group == group }
                    if (rows.isEmpty()) return@forEach
                    item(key = "head-$group") {
                        Text(
                            groupTitle(group, progress.evaluationPeriod),
                            style = Type.body(12, FontWeight.SemiBold),
                            color = Nocturne.muted(0.45f),
                            modifier = Modifier.padding(top = Space.s3),
                        )
                    }
                    // A requirement that is simply true or false has no number
                    // and no bar, so half a card would be half empty. It takes
                    // the full width and reads as a line rather than a tile.
                    val (flags, counted) = rows.partition { !it.isCounted }
                    items(flags, key = { "$group-flag-${it.key}" }) { FlagCard(it) }

                    // Two to a row. A counted requirement is a label, a number
                    // and a bar; one per row left most of the width empty and
                    // made a list of eight look like a page of forms.
                    items(counted.chunked(2), key = { "$group-${it.first().key}" }) { pair ->
                        Row(
                            // Intrinsic rather than natural height: side by
                            // side, one card wrapping to two lines and its
                            // neighbour to one leaves a ragged pair and the
                            // bars at different heights.
                            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(Space.s3),
                        ) {
                            pair.forEach { ConditionCard(it, Modifier.weight(1f).fillMaxHeight()) }
                            // An odd last one keeps its half rather than
                            // stretching across, so the column stays a column.
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        FloatingHeaderBar(
            scrolled = listState.isScrolledPastTop(threshold = 0),
            leading = {
                HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = navigator::back)
            },
            center = {
                HeaderGroup {
                    Text(
                        stringResource(R.string.upgrade_title),
                        style = Type.heading(17, FontWeight.SemiBold),
                        color = Nocturne.headerText,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            },
        )
    }
}

/** standing · activity · recent · all_time, in the order the page reads. */
private val GROUP_ORDER = listOf("standing", "activity", "recent", "all_time")

@Composable
private fun groupTitle(group: String, period: Int?): String = when (group) {
    "standing" -> stringResource(R.string.upgrade_group_standing)
    "activity" -> stringResource(R.string.upgrade_group_activity)
    // The window is the server's, not a constant: TL3 is measured over a period
    // the site can change, and the payload carries the number in force.
    "recent" -> period?.let { stringResource(R.string.upgrade_group_recent, it) }
        ?: stringResource(R.string.upgrade_group_recent_plain)
    else -> stringResource(R.string.upgrade_group_all_time)
}

@Composable
private fun SummaryCard(progress: UpgradeProgress) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(Nocturne.surface)
            .padding(Space.card),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Text(
            progress.currentLevelName.orEmpty(),
            style = Type.heading(20, FontWeight.SemiBold),
            color = Nocturne.text,
        )

        // Three ways this page ends, and only one of them is a climb: the top
        // level, the leader step (a vote, so there is no checklist to finish),
        // and everyone else. The first two arrive with the server's own
        // sentence, which says more than a bar could.
        progress.message?.let {
            Text(it, style = Type.body(13), color = Nocturne.muted(0.6f))
        }

        progress.nextLevelName?.takeIf { !progress.retention }?.let {
            Text(
                stringResource(R.string.upgrade_next_level, it),
                style = Type.body(13),
                color = Nocturne.accent,
            )
        }

        if (progress.retention) {
            Text(
                stringResource(R.string.upgrade_retention_title),
                style = Type.body(13, FontWeight.SemiBold),
                color = Nocturne.text,
            )
        }

        if (progress.totalConditions > 0) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(Radius.sm)),
                color = if (progress.allMet) Nocturne.accent else Nocturne.accent2,
                trackColor = Nocturne.neutral300,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Text(
                stringResource(R.string.upgrade_met_count, progress.metCount, progress.totalConditions),
                style = Type.body(12),
                color = Nocturne.muted(0.5f),
            )
        }

        if (progress.allMet && !progress.retention) {
            Text(
                stringResource(R.string.upgrade_all_met),
                style = Type.body(13),
                color = Nocturne.accent,
            )
        }

        // Worth saying even though nothing else on the page changes: a locked
        // level neither rises nor falls, so the numbers below are a reference
        // rather than a countdown.
        if (progress.trustLevelLocked) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Lucide.Lock, null, tint = Nocturne.muted(0.45f), modifier = Modifier.size(13.dp))
                Text(
                    stringResource(R.string.upgrade_locked),
                    style = Type.body(12),
                    color = Nocturne.muted(0.5f),
                )
            }
        }
    }
}

/**
 * One counted requirement: what it measures, where the account stands, and how
 * far that is from the line.
 *
 * The label rather than the sentence. The server sends both — `text` reads
 * "访问天数（近 100 天）：63/50", which is the whole card said twice over once
 * the number and the bar are drawn — and the short label is what it sends for
 * exactly this. The window the label leaves out is the group heading above.
 */
@Composable
private fun ConditionCard(condition: UpgradeCondition, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(Nocturne.surface)
            .padding(Space.card),
        // The bar and the verdict sit at the foot of the card whatever the
        // label above them did, so a pair keeps its two bars on one line.
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Top),
    ) {
        Row(
            Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            Text(
                condition.label.orEmpty().ifEmpty { condition.text.orEmpty() },
                style = Type.body(13, FontWeight.Medium),
                color = Nocturne.text,
                modifier = Modifier.weight(1f),
            )
            StatusDot(condition.met)
        }

        // The count leads and the line it is measured against follows it in the
        // small type: which of the two is the account's own is then the size
        // difference, not something to be read off a label.
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                "${condition.value ?: 0}",
                style = Type.heading(22, FontWeight.Bold),
                color = Nocturne.text,
                maxLines = 1,
            )
            condition.target?.let {
                Text(
                    stringResource(
                        // A `max` requirement is met by staying under its
                        // number, so calling it a goal would invite the reader
                        // to climb towards the one thing they must not reach.
                        if (condition.comparison == "max") R.string.upgrade_limit
                        else R.string.upgrade_target,
                        it,
                    ),
                    style = Type.body(11),
                    color = Nocturne.muted(0.45f),
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }

        LinearProgressIndicator(
            progress = { condition.fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(Radius.sm)),
            color = if (condition.met) Nocturne.accent else Nocturne.accent2,
            trackColor = Nocturne.neutral300,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )

        Text(
            conditionStatus(condition),
            style = Type.body(11, FontWeight.Medium),
            color = when {
                condition.met -> Nocturne.accent
                condition.comparison == "max" -> Nocturne.danger
                else -> Nocturne.muted(0.45f)
            },
            maxLines = 1,
        )
    }
}

/**
 * The line under the bar. "Not met" would only repeat the grey bar above it;
 * what is missing is how much further there is to go.
 */
@Composable
private fun conditionStatus(condition: UpgradeCondition): String = when {
    condition.met -> stringResource(R.string.upgrade_condition_met)
    condition.comparison == "max" -> stringResource(R.string.upgrade_condition_over)
    else -> {
        val remaining = ((condition.target ?: 0) - (condition.value ?: 0)).coerceAtLeast(0)
        stringResource(R.string.upgrade_condition_remaining, remaining)
    }
}

/**
 * A requirement with no number: not silenced, no penalties on record.
 *
 * Full width and a line rather than a tile, because there is nothing to put in
 * the other two thirds of a card — and [UpgradeCondition.scope] belongs here
 * rather than on a counted card, where the group heading already says it.
 */
@Composable
private fun FlagCard(condition: UpgradeCondition) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(Nocturne.surface)
            .padding(Space.card),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        StatusDot(condition.met)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                condition.label.orEmpty().ifEmpty { condition.text.orEmpty() },
                style = Type.body(14, FontWeight.Medium),
                color = Nocturne.text,
            )
            // The sentence, not the label, when the requirement is failing: it
            // is the half that says *why* — which penalty, and when it lapses.
            val detail = if (condition.met) condition.scope else condition.text
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Type.body(11), color = Nocturne.muted(0.45f))
            }
        }
    }
}

/** Met or not, as one glyph. Filled where it is met, an empty ring where it is not. */
@Composable
private fun StatusDot(met: Boolean, size: Dp = 18.dp) {
    if (met) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Nocturne.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Check, null, tint = Nocturne.bg, modifier = Modifier.size(size * 0.62f))
        }
    } else {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .border(1.5.dp, Nocturne.muted(0.25f), CircleShape),
        )
    }
}
