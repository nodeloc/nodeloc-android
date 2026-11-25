package com.nodeloc.app.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.Lucide
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.ConfirmDialog
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.html.plainTextFromHtml
import com.nodeloc.app.core.network.ApiDraftEntry
import com.nodeloc.app.core.network.PersonalApi
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.moderation.ModScaffold
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A draft as the box shows it, read loosely out of whatever the editor saved. */
data class DraftCard(
    val key: String,
    val kind: DraftKind,
    val nodeSlug: String?,
    val title: String,
    val excerpt: String,
    val updatedAt: String?,
    val publishAt: String?,
    val topicId: Int?,
)

/** Reply drafts are keyed `topic:<id>`; everything else is a new topic, scheduled or not. */
enum class DraftKind { Topic, Reply }

enum class DraftFilter { All, Topics, Replies, Scheduled }

data class DraftsState(
    val loading: Boolean = true,
    val drafts: List<DraftCard> = emptyList(),
    val lifetimeDays: Int? = null,
    val error: Throwable? = null,
)

class DraftsViewModel : ViewModel() {
    private val api = PersonalApi(ServiceLocator.get.client.api)

    private val _state = MutableStateFlow(DraftsState())
    val state: StateFlow<DraftsState> = _state.asStateFlow()

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatchingCancellable { api.drafts() }
                .onSuccess { box ->
                    _state.value = DraftsState(
                        loading = false,
                        drafts = box.drafts.map(::card),
                        lifetimeDays = box.lifetimeDays,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it) }
        }
    }

    /** Off the list on the tap; back in its place if the server says no. */
    fun delete(card: DraftCard) {
        val before = _state.value.drafts
        val at = before.indexOf(card)
        if (at < 0) return
        _state.value = _state.value.copy(drafts = before - card)
        viewModelScope.launch {
            runCatchingCancellable { api.deleteDraft(card.key) }
                .onSuccess { ToastCenter.show(R.string.drafts_deleted) }
                .onFailure {
                    val now = _state.value.drafts.toMutableList()
                    now.add(at.coerceAtMost(now.size), card)
                    _state.value = _state.value.copy(drafts = now)
                    ToastCenter.showError(it)
                }
        }
    }

    /**
     * The fields the web editor writes: `title`, `raw` (or a link and a note,
     * or a poll's options), `node`, and for a reply the `topic_title` it was
     * answering. Anything missing just leaves its line out of the card.
     */
    private fun card(entry: ApiDraftEntry): DraftCard {
        val data = entry.fields
        fun field(name: String) = (data?.get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val reply = entry.key.startsWith("topic:")
        val body = when (field("kind")) {
            "link" -> listOfNotNull(field("link_url"), field("raw") ?: field("link_note")).joinToString(" ")
            "poll" -> (listOfNotNull(field("raw") ?: field("poll_note")) +
                (data?.get("poll_options") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                .joinToString(" · ")
            else -> field("raw") ?: field("body") ?: field("reply").orEmpty()
        }
        return DraftCard(
            key = entry.key,
            kind = if (reply) DraftKind.Reply else DraftKind.Topic,
            nodeSlug = field("node")?.takeIf { it != "_profile" },
            title = if (reply) field("topic_title").orEmpty() else field("title").orEmpty(),
            excerpt = plainTextFromHtml(body).replace(MARKDOWN_IMAGE, "").trim(),
            updatedAt = entry.updatedAt,
            publishAt = entry.publishAt,
            topicId = if (reply) entry.key.removePrefix("topic:").toIntOrNull() else null,
        )
    }
}

private val MARKDOWN_IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")

@Composable
fun DraftsScreen(navigator: Navigator) {
    val viewModel: DraftsViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf(DraftFilter.All) }
    var confirming by remember { mutableStateOf<DraftCard?>(null) }

    // Every visit, not once: the composer this box opens saves back into the
    // drafts it lists, and a publish removes one.
    LaunchedEffect(Unit) { viewModel.load() }

    ModScaffold(stringResource(R.string.drafts_title), navigator::back) {
        val drafts = state.drafts
        val topics = drafts.count { it.kind == DraftKind.Topic }
        val replies = drafts.count { it.kind == DraftKind.Reply }
        val scheduled = drafts.count { it.publishAt != null }
        FilterPillRow {
            FilterPill(stringResource(R.string.drafts_filter_all), filter == DraftFilter.All, count = drafts.size) { filter = DraftFilter.All }
            FilterPill(stringResource(R.string.drafts_filter_topics), filter == DraftFilter.Topics, count = topics) { filter = DraftFilter.Topics }
            FilterPill(stringResource(R.string.drafts_filter_replies), filter == DraftFilter.Replies, count = replies) { filter = DraftFilter.Replies }
            FilterPill(stringResource(R.string.drafts_filter_scheduled), filter == DraftFilter.Scheduled, count = scheduled) { filter = DraftFilter.Scheduled }
        }
        val shown = drafts.filter {
            when (filter) {
                DraftFilter.All -> true
                DraftFilter.Topics -> it.kind == DraftKind.Topic
                DraftFilter.Replies -> it.kind == DraftKind.Reply
                DraftFilter.Scheduled -> it.publishAt != null
            }
        }
        when {
            state.loading && drafts.isEmpty() -> RowsSkeleton(rows = 4, avatar = false)
            state.error != null && drafts.isEmpty() -> EmptyStateView(
                icon = Lucide.FilePen,
                title = stringResource(R.string.drafts_load_failed),
                retryLabel = stringResource(R.string.common_retry),
                onRetry = viewModel::load,
            )
            shown.isEmpty() -> EmptyStateView(
                icon = Lucide.FilePen,
                title = stringResource(if (filter == DraftFilter.Scheduled) R.string.drafts_empty_scheduled else R.string.drafts_empty),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.key }) { card ->
                    DraftRow(
                        card = card,
                        onEdit = {
                            val topicId = card.topicId
                            // A reply is written in the reader's sheet, under
                            // the post it answers; only a new topic has a
                            // composer of its own to reopen.
                            if (topicId != null) navigator.openTopic(topicId) else navigator.openCompose(draftKey = card.key)
                        },
                        onDelete = { confirming = card },
                    )
                }
                state.lifetimeDays?.takeIf { it > 0 }?.let { days ->
                    item {
                        Text(
                            stringResource(R.string.drafts_footer, days),
                            style = Type.body(12),
                            color = Nocturne.textTertiary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }

    confirming?.let { card ->
        ConfirmDialog(
            title = stringResource(R.string.drafts_delete_title),
            body = stringResource(if (card.publishAt != null) R.string.drafts_delete_scheduled_body else R.string.drafts_delete_body),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                confirming = null
                viewModel.delete(card)
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun DraftRow(card: DraftCard, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            card.nodeSlug?.let { slug ->
                Avatar(slug, variant = slug.length % 2, size = 22.dp)
                Text("n/$slug", style = Type.body(13, FontWeight.SemiBold), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusPill(stringResource(if (card.kind == DraftKind.Reply) R.string.drafts_kind_reply else R.string.drafts_kind_topic))
            Spacer(Modifier.weight(1f))
            Text(ago(card.updatedAt), style = Type.body(13), color = Nocturne.textSecondary, maxLines = 1)
        }
        val title = when {
            card.kind == DraftKind.Reply && card.title.isNotEmpty() -> stringResource(R.string.drafts_reply_to, card.title)
            card.kind == DraftKind.Reply -> stringResource(R.string.drafts_reply_untitled)
            else -> card.title.ifEmpty { stringResource(R.string.drafts_untitled) }
        }
        Text(title, style = Type.body(16, FontWeight.SemiBold), color = Nocturne.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (card.excerpt.isNotEmpty()) {
            Text(card.excerpt, style = Type.body(14), color = Nocturne.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        card.publishAt?.let { at ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Lucide.CalendarClock, null, tint = Nocturne.warning, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.drafts_scheduled_at, DiscourseFormat.dayAndTime(at)), style = Type.body(12), color = Nocturne.warning)
            }
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(Nocturne.accent100)
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.drafts_continue), style = Type.body(14, FontWeight.Medium), color = Nocturne.accent800)
            }
            Box(
                Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.common_delete), style = Type.body(14, FontWeight.Medium), color = Nocturne.accent)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral300))
}

/** "10 minutes ago" in the reader's language, which the compact feed stamp is not. */
private fun ago(raw: String?): String {
    val instant = DiscourseFormat.instant(raw) ?: return ""
    return DateUtils.getRelativeTimeSpanString(
        instant.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
