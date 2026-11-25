package com.nodeloc.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.feature.moderation.ModScaffold
import com.nodeloc.app.feature.nav.Navigator

/**
 * How close the account is to its next tier, and what each tier may do.
 *
 * Only ever your own: the endpoint is `/me`, and how near a stranger is to
 * their next tier is their business. [username] keys the load, nothing more.
 * How to earn reputation lives on the 成就 page, which carries the sources.
 */
@Composable
fun UpgradeProgressScreen(username: String, navigator: Navigator) {
    val viewModel: UpgradeProgressViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(username) { viewModel.bind(username) }

    ModScaffold(title = stringResource(R.string.upgrade_title), onBack = navigator::back) {
        Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
            val rep = state.reputation
            when {
                rep == null && state.isLoading -> Column(
                    Modifier.padding(16.dp).skeletonPulsing(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SkeletonBox(Modifier.fillMaxWidth().height(300.dp), corner = 24.dp)
                    repeat(5) { SkeletonBox(Modifier.fillMaxWidth().height(52.dp)) }
                }

                rep == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val offline = state.error?.isOfflineError() == true
                    EmptyStateView(
                        icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                        title = stringResource(if (offline) R.string.error_offline else R.string.upgrade_load_failed),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = viewModel::retry,
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "hero") { GrowthHero(rep, state.tiers, Modifier.padding(horizontal = 16.dp)) }
                    item(key = "perms") {
                        PermissionsSection(state, Modifier.padding(top = 2.dp), viewModel::retryPermissions)
                    }
                    item(key = "links") {
                        Column(
                            Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            LinkCard(
                                title = stringResource(R.string.upgrade2_kicker),
                                detail = achievementsLine(rep, state.tiers),
                            ) { navigator.openAchievements() }
                            LinkCard(
                                title = stringResource(R.string.upgrade2_guidelines),
                                detail = stringResource(R.string.upgrade2_guidelines_desc),
                            ) { navigator.openBrowser("${DiscourseConfig.BASE_URL}/guidelines") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun achievementsLine(rep: ApiRepSummary, tiers: List<ApiTierInfo>): String {
    val now = tierNameOf(rep.tier, rep, tiers)
    val next = rep.next ?: return stringResource(R.string.upgrade2_link_ach_top, now)
    return stringResource(
        R.string.upgrade2_link_ach_line,
        now,
        formatCount(rep.needed.coerceAtLeast(0).toLong()),
        tierNameOf(next.level, rep, tiers),
    )
}

/**
 * 用户组与权限: the site's own comparison, one column at a time, as its
 * narrow layout shows it. Every word in the table is the server's, already
 * in the reader's language.
 */
@Composable
private fun PermissionsSection(state: UpgradeProgressState, modifier: Modifier, onRetry: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val matrix = state.permissions
    val table = if (tab == 1) matrix?.roles ?: matrix?.tiers else matrix?.tiers
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.upgrade2_perm_title), style = Type.heading(24, FontWeight.ExtraBold), color = Nocturne.text)
            Text(
                stringResource(R.string.upgrade2_perm_sub),
                style = Type.body(14),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        when {
            table != null -> {
                if (matrix?.roles != null) {
                    Segmented(
                        listOf(stringResource(R.string.upgrade3_tab_tier), stringResource(R.string.upgrade3_tab_role)),
                        tab,
                        Modifier.padding(horizontal = 16.dp),
                    ) { tab = it }
                }
                // Keyed on the tab so each table opens on the reader's own column.
                androidx.compose.runtime.key(tab) { PermTableView(table) }
            }
            state.permissionsFailed -> PermFailed(onRetry)
            else -> PermSkeleton()
        }
    }
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Nocturne.neutral400)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val chosen = index == selected
            val ground by animatedColor(if (chosen) Nocturne.bg else Nocturne.neutral400, "segGround")
            val ink by animatedColor(if (chosen) Nocturne.text else Nocturne.textSecondary, "segInk")
            val press = rememberPressSource()
            Box(
                Modifier
                    .pressScale(press)
                    .then(if (chosen) Modifier.shadow(2.dp, RoundedCornerShape(19.dp)) else Modifier)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(ground)
                    .semantics { this.selected = chosen }
                    .clickable(interactionSource = press, indication = ripple(), role = Role.Tab) { onSelect(index) }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.body(14, FontWeight.Bold), color = ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PermTableView(table: PermTable) {
    var picked by rememberSaveable { mutableStateOf(table.mine) }
    val column = table.columns.firstOrNull { it.index == picked } ?: table.columns.first()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Opens scrolled to the reader's own column, which for the higher tiers is off the right edge.
        val start = table.columns.indexOfFirst { it.index == table.mine }.coerceAtLeast(0)
        LazyRow(
            state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0)),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(table.columns, key = { it.index }) { col -> ColumnChip(col, col.index == column.index) { picked = col.index } }
        }
        ColumnCard(column, Modifier.padding(horizontal = 16.dp))
        if (table.intro.isNotEmpty()) {
            Text(
                table.intro,
                style = Type.body(13),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        PermList(table, column.index)
    }
}

@Composable
private fun ColumnChip(column: PermColumn, chosen: Boolean, onClick: () -> Unit) {
    val ground by animatedColor(if (chosen) Nocturne.text else Nocturne.neutral400, "chipGround")
    val ink by animatedColor(if (chosen) Nocturne.bg else Nocturne.text, "chipInk")
    val disc by animatedColor(if (chosen) Nocturne.bg.copy(alpha = 0.16f) else Nocturne.bg, "chipDisc")
    val press = rememberPressSource()
    Row(
        Modifier
            .pressScale(press)
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(ground)
            .semantics { selected = chosen }
            .clickable(interactionSource = press, indication = ripple(), role = Role.Tab, onClick = onClick)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
            column.iconPath?.let { ServedGlyph(it, ink, 15.dp) }
        }
        Text(column.name, style = Type.body(14, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

/** The chosen column as the board's ink card: glyph, name, "你的段位", and what it adds over the one before. */
@Composable
private fun ColumnCard(column: PermColumn, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Nocturne.text)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Nocturne.bg.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            column.iconPath?.let { ServedGlyph(it, Nocturne.bg, 26.dp) }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(column.name, style = Type.body(18, FontWeight.ExtraBold), color = Nocturne.bg, maxLines = 1)
                column.mineLabel?.let {
                    Box(
                        Modifier
                            .height(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Nocturne.bg)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(it, style = Type.body(11, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
                    }
                }
            }
            if (column.sub.isNotEmpty()) {
                Text(column.sub, style = Type.body(12), color = Nocturne.bg.copy(alpha = 0.72f))
            }
        }
    }
}

@Composable
private fun PermList(table: PermTable, column: Int) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Nocturne.divider, RoundedCornerShape(16.dp)),
    ) {
        table.groups.forEach { group ->
            if (group.title.isNotEmpty()) {
                Text(
                    group.title,
                    style = Type.body(13, FontWeight.Bold),
                    color = Nocturne.neutral800,
                    modifier = Modifier.fillMaxWidth().background(Nocturne.surface).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            group.rows.forEach { row -> PermRowView(row, column) }
        }
    }
}

@Composable
private fun PermRowView(row: PermRow, column: Int) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral300))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.label, style = Type.body(14), color = Nocturne.text, modifier = Modifier.weight(1f, fill = false))
                row.fresh[column]?.let { mark ->
                    Box(
                        Modifier
                            .height(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Nocturne.accent100)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(mark, style = Type.body(11, FontWeight.Bold), color = Nocturne.accent700, maxLines = 1)
                    }
                }
            }
            row.note?.let {
                Text(it, style = Type.body(12), color = Nocturne.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        // The board caps the value at a little under half the row, so a long label wraps before it does.
        Box(Modifier.widthIn(max = 160.dp), contentAlignment = Alignment.CenterEnd) {
            when (val value = row.values[column]) {
                PermValue.Yes -> Icon(
                    Lucide.Check,
                    stringResource(R.string.upgrade3_yes),
                    tint = Nocturne.accent,
                    modifier = Modifier.size(18.dp),
                )
                PermValue.No, null -> Icon(
                    Lucide.Minus,
                    stringResource(R.string.upgrade3_no),
                    tint = Nocturne.neutral500,
                    modifier = Modifier.size(16.dp),
                )
                is PermValue.Text -> Text(
                    value.text,
                    style = Type.body(13, FontWeight.Bold),
                    color = Nocturne.text,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun PermSkeleton() {
    Column(
        Modifier.padding(horizontal = 16.dp).skeletonPulsing(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SkeletonBox(Modifier.widthIn(max = 200.dp).fillMaxWidth().height(44.dp), corner = 22.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(4) { SkeletonBox(Modifier.weight(1f).height(40.dp), corner = 20.dp) }
        }
        SkeletonBox(Modifier.fillMaxWidth().height(76.dp), corner = 16.dp)
        repeat(4) { SkeletonBox(Modifier.fillMaxWidth().height(52.dp)) }
    }
}

/** The table could not be read: say so where it would be, and offer the retry — never a made-up table. */
@Composable
private fun PermFailed(onRetry: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Nocturne.divider, RoundedCornerShape(16.dp))
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.upgrade3_perm_failed),
            style = Type.body(14),
            color = Nocturne.textSecondary,
            textAlign = TextAlign.Center,
        )
        SecondaryButton(stringResource(R.string.common_retry), onClick = onRetry)
    }
}

@Composable
private fun LinkCard(title: String, detail: String, onClick: () -> Unit) {
    val press = rememberPressSource()
    Row(
        Modifier
            .pressScale(press)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Nocturne.divider, RoundedCornerShape(14.dp))
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body(14, FontWeight.Bold), color = Nocturne.text)
            Text(detail, style = Type.body(12), color = Nocturne.textSecondary)
        }
        Icon(Lucide.ChevronRight, null, tint = Nocturne.text, modifier = Modifier.size(18.dp))
    }
}
