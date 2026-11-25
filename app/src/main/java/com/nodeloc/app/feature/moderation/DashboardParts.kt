package com.nodeloc.app.feature.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.util.DiscourseFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * Pieces the dashboard, the log and the node pages share: the design's green
 * section label, its icon list row, the white stat card, the Snackbar, and the
 * localized "n minutes ago" that DiscourseFormat.relative (a compact "5m") is
 * not.
 */

/** The design's section heading on these pages: 14sp medium, accent green. */
@Composable
internal fun DashSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Type.body(14, FontWeight.Medium),
        color = Nocturne.accent,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

/**
 * A Material list row with a bare leading icon: 56 high with one line, 72
 * with a supporting line. [trailingText] is the grey value the node page shows
 * before the chevron ("3 人").
 */
@Composable
internal fun DashListRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailingText: String? = null,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = if (supporting == null) 56.dp else 72.dp)
            .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = Nocturne.textSecondary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body(16), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Text(
                    supporting,
                    style = Type.body(14),
                    color = Nocturne.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingText != null) {
            Text(trailingText, style = Type.body(14), color = Nocturne.textSecondary, maxLines = 1)
        }
        trailing?.invoke(this)
        if (chevron && onClick != null) {
            Icon(Lucide.ChevronRight, null, tint = Nocturne.textSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

/** The white raised card the dashboard's tiles and "today" strip sit on. */
internal fun Modifier.dashCard(ground: Color, corner: Int = 16): Modifier =
    this
        .shadow(1.dp, RoundedCornerShape(corner.dp), clip = false)
        .clip(RoundedCornerShape(corner.dp))
        .background(ground)

/** A round tinted icon well, as on the dashboard tiles. */
@Composable
internal fun DashIconWell(icon: ImageVector, ground: Color, ink: Color, size: Int = 32, corner: Int? = null) {
    Box(
        Modifier
            .size(size.dp)
            .clip(if (corner == null) CircleShape else RoundedCornerShape(corner.dp))
            .background(ground),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size((size * 0.56f).dp))
    }
}

/** The design's dark Snackbar, above the navigation bar. */
@Composable
internal fun DashSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 8.dp)) { data ->
        Snackbar(
            data,
            containerColor = Nocturne.text,
            contentColor = Nocturne.bg,
            actionColor = Nocturne.accent300,
            shape = RoundedCornerShape(4.dp),
        )
    }
}

// ------------------------------------------------------------------ time

/** "刚刚" / "12 分钟前" / "3 小时前" / "2 天前", then the date. */
@Composable
internal fun modAgo(raw: String?): String {
    val instant = DiscourseFormat.instant(raw) ?: return ""
    val seconds = ((System.currentTimeMillis() - instant.toEpochMilli()) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> stringResource(R.string.mod_log_just_now)
        seconds < 3600 -> stringResource(R.string.mod_log_minutes_ago, (seconds / 60).toInt())
        seconds < 86_400 -> stringResource(R.string.mod_log_hours_ago, (seconds / 3600).toInt())
        seconds < 30 * 86_400L -> stringResource(R.string.mod_log_days_ago, (seconds / 86_400).toInt())
        else -> localDate(instant).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

internal fun localDate(instant: Instant): LocalDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()

internal fun startOfToday(): Instant = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()

// ------------------------------------------------------------------ json

internal fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject?.long(key: String): Long? {
    val primitive = this?.get(key) as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.contentOrNull?.toDoubleOrNull()?.toLong()
}

internal fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
