package com.nodeloc.app.feature.settings

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ConfirmDialog
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.LocalReduceMotion
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.colors
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.NumberFormat

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
    /** Characters of body text, whitespace not counted — what the editor's own counter shows. */
    val chars: Int = 0,
    val images: Int = 0,
    /** `pc` · `phone` · null for drafts saved before the server recorded it. */
    val device: String? = null,
    val expiresAt: String? = null,
    val scheduleError: String? = null,
    /** Another device's newer save, held back until the owner picks one. */
    val conflictDevice: String? = null,
    val conflictSavedAt: String? = null,
    val hasConflict: Boolean = false,
)

/** Reply drafts are keyed `topic:<id>`; everything else is a new topic, scheduled or not. */
enum class DraftKind { Topic, Reply }

enum class DraftFilter { All, Topics, Replies, Scheduled }

data class DraftsState(
    val loading: Boolean = true,
    val drafts: List<DraftCard> = emptyList(),
    val lifetimeDays: Int? = null,
    val warnDays: Int? = null,
    val limit: Int? = null,
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
                        warnDays = box.warnDays,
                        limit = box.limit,
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

    /** The selection goes in one request; on failure the whole box is put back as it was. */
    fun deleteMany(keys: Set<String>) {
        if (keys.isEmpty()) return
        val before = _state.value.drafts
        _state.value = _state.value.copy(drafts = before.filterNot { it.key in keys })
        viewModelScope.launch {
            runCatchingCancellable { api.deleteDrafts(keys.toList()) }
                .onSuccess { ToastCenter.show(R.string.drafts_deleted_many, keys.size) }
                .onFailure {
                    val kept = _state.value.drafts.map { it.key }.toSet()
                    _state.value = _state.value.copy(drafts = before.filter { it.key in keys || it.key in kept })
                    ToastCenter.showError(it)
                }
        }
    }

    /**
     * Picks between this draft and the save another device had held back.
     * The banner goes on the tap; the reload afterwards brings the chosen
     * text, which only the server has when the held copy wins.
     */
    fun resolve(card: DraftCard, useHeld: Boolean) {
        setConflict(card.key, false)
        viewModelScope.launch {
            runCatchingCancellable { api.resolveDraftConflict(card.key, useHeld) }
                .onSuccess { load() }
                .onFailure {
                    setConflict(card.key, true)
                    ToastCenter.showError(it)
                }
        }
    }

    private fun setConflict(key: String, value: Boolean) {
        _state.value = _state.value.copy(
            drafts = _state.value.drafts.map { if (it.key == key) it.copy(hasConflict = value) else it },
        )
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
        val text = plainTextFromHtml(body).replace(MARKDOWN_IMAGE, "").trim()
        val conflict = entry.conflict
        return DraftCard(
            key = entry.key,
            kind = if (reply) DraftKind.Reply else DraftKind.Topic,
            nodeSlug = field("node")?.takeIf { it != "_profile" },
            title = if (reply) field("topic_title").orEmpty() else field("title").orEmpty(),
            excerpt = text,
            updatedAt = entry.updatedAt,
            publishAt = entry.publishAt,
            topicId = if (reply) entry.key.removePrefix("topic:").toIntOrNull() else null,
            chars = text.count { !it.isWhitespace() },
            images = MARKDOWN_IMAGE.findAll(body).count() + HTML_IMAGE.findAll(body).count(),
            device = entry.device?.takeIf { it.isNotBlank() },
            expiresAt = entry.expiresAt,
            scheduleError = entry.scheduleError?.takeIf { it.isNotBlank() },
            conflictDevice = conflict?.device?.takeIf { it.isNotBlank() },
            conflictSavedAt = conflict?.savedAt,
            hasConflict = conflict != null && (conflict.data as? JsonObject) != null,
        )
    }
}

private val MARKDOWN_IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
private val HTML_IMAGE = Regex("<img\\b", RegexOption.IGNORE_CASE)

@Composable
fun DraftsScreen(navigator: Navigator) {
    val viewModel: DraftsViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf(DraftFilter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirming by remember { mutableStateOf<DraftCard?>(null) }
    var confirmingMany by remember { mutableStateOf(false) }

    // Every visit, not once: the composer this box opens saves back into the
    // drafts it lists, and a publish removes one.
    LaunchedEffect(Unit) { viewModel.load() }

    val drafts = state.drafts
    // A key that left the box (deleted, published elsewhere) must not linger
    // in the selection and be counted or sent.
    val liveSelection = selected.filterTo(mutableSetOf()) { key -> drafts.any { it.key == key } }

    ModScaffold("", navigator::back) {
        val topics = drafts.count { it.kind == DraftKind.Topic && it.publishAt == null }
        val replies = drafts.count { it.kind == DraftKind.Reply }
        val scheduled = drafts.count { it.publishAt != null }
        val needle = query.trim()
        val shown = drafts.filter {
            when (filter) {
                DraftFilter.All -> true
                DraftFilter.Topics -> it.kind == DraftKind.Topic && it.publishAt == null
                DraftFilter.Replies -> it.kind == DraftKind.Reply
                DraftFilter.Scheduled -> it.publishAt != null
            }
        }.filter {
            needle.isEmpty() || it.title.contains(needle, ignoreCase = true) ||
                it.excerpt.contains(needle, ignoreCase = true) || it.nodeSlug?.contains(needle, ignoreCase = true) == true
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp)) {
            item {
                Text(stringResource(R.string.drafts_title), style = Type.heading(24, FontWeight.Bold), color = Nocturne.text)
                if (drafts.isNotEmpty()) {
                    Text(stringResource(R.string.drafts_header_count, drafts.size), style = Type.body(13), color = Nocturne.textSecondary)
                }
                SearchField(query, { query = it }, Modifier.padding(top = 12.dp))
            }
            item {
                Column(Modifier.padding(top = 12.dp)) {
                    if (liveSelection.isEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterPill(stringResource(R.string.drafts_filter_all), filter == DraftFilter.All, count = drafts.size) { filter = DraftFilter.All }
                                FilterPill(stringResource(R.string.drafts_kind_new), filter == DraftFilter.Topics, count = topics) { filter = DraftFilter.Topics }
                                FilterPill(stringResource(R.string.drafts_kind_reply), filter == DraftFilter.Replies, count = replies) { filter = DraftFilter.Replies }
                                FilterPill(stringResource(R.string.drafts_filter_scheduled), filter == DraftFilter.Scheduled, count = scheduled) { filter = DraftFilter.Scheduled }
                            }
                        }
                    } else {
                        SelectionBar(
                            count = liveSelection.size,
                            onDelete = { confirmingMany = true },
                            onCancel = { selected = emptySet() },
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
                }
            }
            when {
                state.loading && drafts.isEmpty() -> item { RowsSkeleton(rows = 4, avatar = false) }
                state.error != null && drafts.isEmpty() -> item {
                    EmptyStateView(
                        icon = Lucide.FilePen,
                        title = stringResource(R.string.drafts_load_failed),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = viewModel::load,
                    )
                }
                drafts.isEmpty() -> item {
                    EmptyStateView(
                        icon = Lucide.FilePen,
                        title = stringResource(R.string.drafts_empty_title),
                        detail = stringResource(R.string.drafts_empty_detail),
                    )
                }
                shown.isEmpty() -> item {
                    EmptyStateView(
                        icon = if (needle.isNotEmpty()) Lucide.Search else Lucide.FilePen,
                        title = when {
                            needle.isNotEmpty() -> stringResource(R.string.drafts_search_empty, needle)
                            filter == DraftFilter.Scheduled -> stringResource(R.string.drafts_empty_scheduled)
                            else -> stringResource(R.string.drafts_empty)
                        },
                    )
                }
                else -> items(shown, key = { it.key }) { card ->
                    DraftRow(
                        card = card,
                        checked = card.key in liveSelection,
                        onToggle = {
                            selected = if (card.key in liveSelection) liveSelection - card.key else liveSelection + card.key
                        },
                        onEdit = {
                            val topicId = card.topicId
                            // A reply is written in the reader's sheet, under
                            // the post it answers; only a new topic has a
                            // composer of its own to reopen.
                            if (topicId != null) navigator.openTopic(topicId) else navigator.openCompose(draftKey = card.key)
                        },
                        onDelete = { confirming = card },
                        onResolve = { useHeld -> viewModel.resolve(card, useHeld) },
                    )
                }
            }
            if (drafts.isNotEmpty()) {
                val lifetime = state.lifetimeDays?.takeIf { it > 0 }
                val warn = state.warnDays?.takeIf { it > 0 }
                val limit = state.limit?.takeIf { it > 0 }
                item {
                    Text(
                        if (lifetime != null && warn != null && limit != null) {
                            stringResource(R.string.drafts_footer_full, lifetime, warn, limit, drafts.size)
                        } else if (lifetime != null) {
                            stringResource(R.string.drafts_footer, lifetime)
                        } else {
                            ""
                        },
                        style = Type.body(12),
                        color = Nocturne.textSecondary,
                        modifier = Modifier.padding(top = 16.dp),
                    )
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
                selected = selected - card.key
                viewModel.delete(card)
            },
            onDismiss = { confirming = null },
        )
    }
    if (confirmingMany) {
        val keys = liveSelection
        ConfirmDialog(
            title = stringResource(R.string.drafts_delete_many_title, keys.size),
            body = stringResource(R.string.drafts_delete_body),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                confirmingMany = false
                selected = emptySet()
                viewModel.deleteMany(keys)
            },
            onDismiss = { confirmingMany = false },
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(Nocturne.surface)
            .border(1.dp, Nocturne.divider, CircleShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Lucide.Search, null, tint = Nocturne.textSecondary, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.body(15).copy(color = Nocturne.text),
            cursorBrush = SolidColor(Nocturne.accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(stringResource(R.string.drafts_search_hint), style = Type.body(15), color = Nocturne.textSecondary)
                }
                inner()
            },
        )
    }
}

@Composable
private fun SelectionBar(count: Int, onDelete: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Nocturne.accent100)
            .border(1.dp, Nocturne.accent300, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.drafts_selected, count), style = Type.body(14, FontWeight.Bold), color = Nocturne.accent700, modifier = Modifier.weight(1f))
        val press = rememberPressSource()
        Box(
            Modifier
                .pressScale(press)
                .height(38.dp)
                .clip(CircleShape)
                .background(Nocturne.bg)
                .border(1.dp, Nocturne.danger, CircleShape)
                .clickable(interactionSource = press, indication = ripple(), onClick = onDelete)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.drafts_delete_selected), style = Type.body(14, FontWeight.Bold), color = Nocturne.danger)
        }
        Text(
            stringResource(R.string.common_cancel),
            style = Type.body(14, FontWeight.Bold),
            color = Nocturne.text,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun DraftRow(
    card: DraftCard,
    checked: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onResolve: (useHeld: Boolean) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectBox(checked, card.title, onToggle)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindTag(card)
                    Text(
                        when {
                            card.kind == DraftKind.Reply && card.title.isNotEmpty() -> stringResource(R.string.drafts_reply_to, card.title)
                            card.kind == DraftKind.Reply -> ""
                            card.nodeSlug != null -> "n/${card.nodeSlug}"
                            else -> stringResource(R.string.drafts_no_node)
                        },
                        style = Type.body(12, FontWeight.Bold),
                        color = Nocturne.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val title = when {
                    card.kind == DraftKind.Reply -> stringResource(R.string.drafts_reply_untitled)
                    else -> card.title.ifEmpty { stringResource(R.string.drafts_untitled) }
                }
                Text(
                    title,
                    style = Type.body(16, FontWeight.Bold),
                    color = Nocturne.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).clickable(onClick = onEdit),
                )
                if (card.excerpt.isNotEmpty()) {
                    Text(
                        card.excerpt,
                        style = Type.body(14),
                        color = Nocturne.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                MetaLine(card)
                card.scheduleError?.let {
                    Text(
                        stringResource(R.string.drafts_schedule_failed, it),
                        style = Type.body(12, FontWeight.Medium),
                        color = Nocturne.danger,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                val reduceMotion = LocalReduceMotion.current
                AnimatedVisibility(
                    visible = card.hasConflict,
                    enter = if (reduceMotion) fadeIn(initialAlpha = 1f) else expandVertically() + fadeIn(),
                    exit = if (reduceMotion) fadeOut(targetAlpha = 1f) else shrinkVertically() + fadeOut(),
                ) {
                    ConflictBanner(card, onResolve)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PrimaryButton(
                    stringResource(if (card.publishAt != null) R.string.drafts_edit_scheduled else R.string.drafts_continue),
                    onClick = onEdit,
                )
                val delete = stringResource(R.string.drafts_delete_one)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onDelete)
                        .semantics { contentDescription = delete },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Trash2, null, tint = Nocturne.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
    }
}

@Composable
private fun SelectBox(checked: Boolean, title: String, onToggle: () -> Unit) {
    val fill by animatedColor(if (checked) Nocturne.accent else Nocturne.bg, "draftCheckFill")
    val edge by animatedColor(if (checked) Nocturne.accent else Nocturne.neutral600, "draftCheckEdge")
    val label = stringResource(R.string.drafts_select)
    val press = rememberPressSource()
    Box(
        Modifier
            .pressScale(press)
            .padding(top = 0.dp)
            .size(36.dp)
            .clip(CircleShape)
            .clickable(interactionSource = press, indication = ripple(), role = Role.Checkbox, onClick = onToggle)
            .semantics { contentDescription = "$label: $title" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(fill).border(2.dp, edge, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Lucide.Check, null, tint = Nocturne.bg, modifier = Modifier.size(14.dp))
        }
    }
}

/** 新帖 grey, 回复 blue, 定时发布 green — the board tells the three apart by colour first. */
@Composable
private fun KindTag(card: DraftCard) {
    val (tone, label) = when {
        card.publishAt != null -> PillTone.Accent to stringResource(R.string.drafts_filter_scheduled)
        card.kind == DraftKind.Reply -> PillTone.Info to stringResource(R.string.drafts_kind_reply)
        else -> PillTone.Neutral to stringResource(R.string.drafts_kind_new)
    }
    val (ground, ink) = tone.colors()
    Text(
        label,
        style = Type.body(12, FontWeight.Bold),
        color = ink,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(ground).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun MetaLine(card: DraftCard) {
    val device = deviceLabel(card.device)
    Row(
        Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f, fill = false)) {
            Icon(if (card.publishAt != null) Lucide.CalendarClock else Lucide.Clock, null, tint = Nocturne.textSecondary, modifier = Modifier.size(13.dp))
            val stamp = if (card.publishAt != null) {
                stringResource(R.string.drafts_publishes_at, DiscourseFormat.dayAndTime(card.publishAt))
            } else {
                stringResource(R.string.drafts_saved_at, ago(card.updatedAt))
            }
            Text(
                if (device != null) "$stamp · $device" else stamp,
                style = Type.body(12),
                color = Nocturne.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (card.chars > 0) {
            Text(stringResource(R.string.drafts_chars, NumberFormat.getIntegerInstance().format(card.chars)), style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
        }
        if (card.images > 0) {
            Text(stringResource(R.string.drafts_images, card.images), style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
        }
    }
    // The server sets an expiry only once the draft is inside its warning
    // window, so its presence is the signal and needs no threshold here.
    daysUntil(card.expiresAt)?.let { days ->
        Text(
            stringResource(R.string.drafts_expires_in, days),
            style = Type.body(12, FontWeight.Bold),
            color = Nocturne.warning,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ConflictBanner(card: DraftCard, onResolve: (useHeld: Boolean) -> Unit) {
    val device = deviceLabel(card.conflictDevice) ?: stringResource(R.string.drafts_device_phone)
    Column(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Nocturne.warningBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Lucide.TriangleAlert, null, tint = Nocturne.warning, modifier = Modifier.size(16.dp).padding(top = 1.dp))
            Text(
                stringResource(R.string.drafts_conflict, device, ago(card.conflictSavedAt)),
                style = Type.body(13),
                color = Nocturne.text,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(stringResource(R.string.drafts_conflict_use, device)) { onResolve(true) }
            SecondaryButton(stringResource(R.string.drafts_conflict_keep), compact = true) { onResolve(false) }
        }
    }
}

/** The board's dark pill for the choice that replaces this copy. */
@Composable
private fun InkButton(text: String, onClick: () -> Unit) {
    val press = rememberPressSource()
    Box(
        Modifier
            .pressScale(press)
            .height(30.dp)
            .clip(CircleShape)
            .background(Nocturne.text)
            .clickable(interactionSource = press, indication = ripple(), onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body(13, FontWeight.SemiBold), color = Nocturne.bg, maxLines = 1)
    }
}

@Composable
private fun deviceLabel(raw: String?): String? = when (raw) {
    "pc" -> stringResource(R.string.drafts_device_pc)
    "phone" -> stringResource(R.string.drafts_device_phone)
    else -> null
}

private fun daysUntil(raw: String?): Int? {
    val instant = DiscourseFormat.instant(raw) ?: return null
    val millis = instant.toEpochMilli() - System.currentTimeMillis()
    if (millis <= 0) return null
    return ((millis + DateUtils.DAY_IN_MILLIS - 1) / DateUtils.DAY_IN_MILLIS).toInt()
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
