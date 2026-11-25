package com.nodeloc.app.feature.moderation

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.nodeloc.app.R
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.util.DiscourseFormat
import java.time.Instant

/**
 * The Material top app bar the moderation pages share (design: Android
 * "Top App Bar, title left, back is an arrow"): 64 high, 22sp title.
 */
@Composable
fun ModTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Nocturne.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = onBack)
        Text(
            title,
            style = Type.heading(22, FontWeight.Normal),
            color = Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        actions()
    }
}

/** Top bar over a filling body, with the navigation-bar inset below. */
@Composable
fun ModScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Nocturne.bg)) {
        ModTopBar(title, onBack, actions = actions)
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        if (bottomBar != null) {
            Box(Modifier.fillMaxWidth().background(Nocturne.bg).windowInsetsPadding(WindowInsets.navigationBars)) {
                bottomBar()
            }
        } else {
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

/** A section label in the design's list pages: 13sp grey, page margins. */
@Composable
fun ModSectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.body(13, FontWeight.Medium), color = Nocturne.textSecondary, modifier = Modifier.weight(1f))
        trailing()
    }
}

/**
 * Why content is removed. The server takes free text: the label itself is
 * sent as the `note` (or the post's delete `reason`), which is what the author
 * then reads, so these are worded for the author.
 */
enum class RemovalReason(@StringRes val labelRes: Int) {
    Promotion(R.string.mod_reason_promotion),
    OffTopic(R.string.mod_reason_off_topic),
    Harassment(R.string.mod_reason_harassment),
    Duplicate(R.string.mod_reason_duplicate),
    Illegal(R.string.mod_reason_illegal),
    Other(R.string.mod_reason_other),
}

/** A reporter's reason code (`POST /posts/{id}/reports`) as a label. */
@StringRes
fun reportReasonLabel(code: String): Int = when (code) {
    "spam" -> R.string.mod_report_spam
    "harassment" -> R.string.mod_report_harassment
    "off_topic" -> R.string.mod_report_off_topic
    "copyright" -> R.string.mod_report_copyright
    "inappropriate" -> R.string.mod_report_inappropriate
    "illegal" -> R.string.mod_report_illegal
    else -> R.string.mod_reason_other
}

/** Penalty lengths offered everywhere; 0 is permanent, as the server reads it. */
val PENALTY_DAYS = listOf(1, 3, 7, 30, 0)

@Composable
fun penaltyDaysLabel(days: Int): String =
    if (days == 0) stringResource(R.string.mod_duration_forever) else stringResource(R.string.mod_duration_days, days)

/**
 * Site penalties store "permanent" as the year 9999; anything that far out is
 * shown as permanent rather than as a date.
 */
fun isPermanent(until: String?): Boolean {
    val instant = DiscourseFormat.instant(until) ?: return false
    return instant.isAfter(Instant.parse("9000-01-01T00:00:00Z"))
}

/** A Material list row (56 high): title, optional supporting line, trailing slot. */
@Composable
fun ModListRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (supporting == null) 16.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body(16), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Text(supporting, style = Type.body(14), color = Nocturne.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** What the reader hands the topic moderation sheet. */
data class ModTopicRef(
    val topicId: Long,
    val firstPostId: Long,
    val title: String,
    val nodeSlug: String?,
    val nodeName: String?,
    val pinned: Boolean,
    val closed: Boolean,
    val featured: Boolean,
    val authorUsername: String,
    val authorId: Long?,
)

/** What the reader hands the reply moderation sheet. */
data class ModPostRef(
    val postId: Long,
    val topicId: Long,
    val postNumber: Int,
    val nodeSlug: String?,
    val excerpt: String,
    val authorUsername: String,
    val authorId: Long?,
    val deleted: Boolean,
)
