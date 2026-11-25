package com.nodeloc.app.feature.moderation

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ScrollText
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.VolumeX
import com.composables.icons.lucide.X
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.ApiAdminUserDetail
import com.nodeloc.app.core.network.ApiNodeRestriction
import com.nodeloc.app.core.network.ApiPenalty
import com.nodeloc.app.core.network.ApiUserNote
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import java.time.Instant

data class ModUserState(
    val detail: ApiAdminUserDetail? = null,
    val notes: List<ApiUserNote> = emptyList(),
    /** This user's restrictions in the node the page was opened for. */
    val restrictions: List<ApiNodeRestriction> = emptyList(),
    val loading: Boolean = true,
    val failed: Throwable? = null,
    val busy: Boolean = false,
)

/**
 * Two pages in one. With `admin.users` it is the site's view of the account;
 * a node moderator without it sees only what they control — this user's
 * restrictions in their node — because every other number here comes from an
 * admin endpoint they would get a 403 from.
 */
class ModUserViewModel(private val userId: Long, private val nodeSlug: String?) : ViewModel() {
    private val api = ServiceLocator.get.moderationApi
    private val privileges get() = ServiceLocator.get.moderation.privileges.value

    val siteMode: Boolean get() = privileges.managesUsers
    val nodeMode: Boolean get() = nodeSlug != null && privileges.moderates(nodeSlug)

    private val _state = MutableStateFlow(ModUserState())
    val state: StateFlow<ModUserState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.detail == null && it.restrictions.isEmpty(), failed = null) }
            val detail = if (siteMode) async { runCatchingCancellable { api.userDetail(userId) } } else null
            val notes = if (siteMode) async { runCatchingCancellable { api.notes(userId) } } else null
            val restrictions = if (nodeMode) async { runCatchingCancellable { api.nodeRestrictions(nodeSlug!!) } } else null
            val detailResult = detail?.await()
            val restrictionResult = restrictions?.await()
            val failure = detailResult?.exceptionOrNull() ?: restrictionResult?.exceptionOrNull()
            _state.update { state ->
                state.copy(
                    detail = detailResult?.getOrNull() ?: state.detail,
                    notes = notes?.await()?.getOrNull() ?: state.notes,
                    restrictions = restrictionResult?.getOrNull()?.filter { it.userId == userId } ?: state.restrictions,
                    loading = false,
                    failed = failure,
                )
            }
        }
    }

    fun revoke(kind: String) = write(R.string.mod_user_revoked) { api.revokePenalty(userId, kind) }

    fun lift() = write(R.string.mod_user_lifted) { api.liftNodeRestriction(nodeSlug!!, userId) }

    fun addNote(body: String, onDone: () -> Unit) = write(R.string.mod_user_note_added) {
        api.addNote(userId, body)
        onDone()
    }

    fun deleteNote(id: Long) {
        val before = _state.value.notes
        _state.update { state -> state.copy(notes = state.notes.filterNot { it.id == id }) }
        viewModelScope.launch {
            runCatchingCancellable { api.deleteNote(id) }
                .onFailure {
                    _state.update { it.copy(notes = before) }
                    ToastCenter.showError(it)
                }
        }
    }

    private fun write(@StringRes success: Int, block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatchingCancellable { block() }
                .onSuccess {
                    ToastCenter.show(success)
                    load()
                }
                .onFailure { ToastCenter.showError(it) }
            _state.update { it.copy(busy = false) }
        }
    }
}

private fun ApiPenalty.isActive(now: Instant = Instant.now()): Boolean =
    revokedAt == null && (endsAt == null || (DiscourseFormat.instant(endsAt)?.isAfter(now) ?: false))

/** "还剩 2 天 4 小时", or 永久 for no end (or the server's year-9999 stand-in). */
@Composable
private fun remaining(until: String?): String {
    if (until == null || isPermanent(until)) return stringResource(R.string.mod_duration_forever)
    val end = DiscourseFormat.instant(until) ?: return ""
    val left = Duration.between(Instant.now(), end).coerceAtLeast(Duration.ZERO)
    val days = left.toDays().toInt()
    val hours = (left.toHours() % 24).toInt()
    return if (days > 0) stringResource(R.string.mod_user_left_days, days, hours)
    else stringResource(R.string.mod_user_left_hours, hours.coerceAtLeast(1))
}

/** One line of the 违规记录, merged from three sources and ordered by time. */
private data class RecordEntry(val at: Instant?, val title: String, val detail: String, val itemId: Long? = null)

private sealed interface UserSheet {
    data object None : UserSheet
    data class Penalty(val severe: Boolean) : UserSheet
}

@Composable
fun ModUserScreen(userId: Long, username: String, nodeSlug: String?, navigator: Navigator) {
    val viewModel: ModUserViewModel = viewModel(key = "mod-user-$userId-$nodeSlug") { ModUserViewModel(userId, nodeSlug) }
    val state by viewModel.state.collectAsState()
    val privileges by ServiceLocator.get.moderation.privileges.collectAsState()
    var sheet by remember { mutableStateOf<UserSheet>(UserSheet.None) }
    val detail = state.detail
    val user = detail?.user
    val now = Instant.now()
    val activePenalties = detail?.penalties.orEmpty().filter { it.isActive(now) }

    ModUndoHost {
        ModScaffold(title = stringResource(R.string.mod_user_title), onBack = navigator::back) {
            when {
                !viewModel.siteMode && !viewModel.nodeMode -> EmptyStateView(Lucide.ShieldAlert, stringResource(R.string.mod_user_no_access))
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NodelocLoader() }
                viewModel.siteMode && detail == null -> EmptyStateView(
                    Lucide.ShieldAlert,
                    stringResource(R.string.mod_user_load_failed),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = viewModel::load,
                )
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    // Header
                    Row(
                        Modifier.fillMaxWidth().clickable { navigator.openProfile(username) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RemoteAvatar(user?.avatar, username, size = 56.dp)
                        Spacer(Modifier.padding(start = 14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(user?.name?.takeIf { it.isNotBlank() } ?: username, style = Type.heading(20, FontWeight.SemiBold), color = Nocturne.text)
                                when {
                                    user?.suspended == true -> StatusPill(stringResource(R.string.mod_user_pill_suspended), PillTone.Danger)
                                    user?.silenced == true -> StatusPill(stringResource(R.string.mod_user_pill_silenced), PillTone.Warning)
                                    state.restrictions.isNotEmpty() -> StatusPill(stringResource(R.string.mod_user_pill_restricted), PillTone.Warning)
                                    user?.deleted == true -> StatusPill(stringResource(R.string.mod_user_pill_deleted), PillTone.Neutral)
                                }
                            }
                            Text(
                                listOfNotNull(
                                    "@$username",
                                    user?.tierName?.takeIf { it.isNotBlank() },
                                    user?.createdAt?.let { stringResource(R.string.mod_user_joined, joinedFor(it)) },
                                ).joinToString(" · "),
                                style = Type.body(13),
                                color = Nocturne.textSecondary,
                            )
                        }
                    }

                    if (detail != null) {
                        val deleted = detail.reportsAgainst.count { it.resolution == "delete_post" }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Stat(detail.stats.topics.toString(), stringResource(R.string.mod_user_stat_topics), Modifier.weight(1f))
                            Stat(detail.stats.posts.toString(), stringResource(R.string.mod_user_stat_posts), Modifier.weight(1f))
                            Stat(deleted.toString(), stringResource(R.string.mod_review_stat_deleted), Modifier.weight(1f), warn = deleted > 0)
                            Stat(
                                detail.stats.timesReported.toString(),
                                stringResource(R.string.mod_review_stat_reported),
                                Modifier.weight(1f),
                                warn = detail.stats.timesReported > 0,
                            )
                        }
                    }

                    // Active penalties and restrictions, each with its own way out.
                    activePenalties.distinctBy { it.kind }.forEach { penalty ->
                        PenaltyCard(
                            title = stringResource(
                                if (penalty.kind == "suspend") R.string.mod_user_active_suspend else R.string.mod_user_active_silence,
                                remaining(penalty.endsAt ?: if (penalty.kind == "suspend") user?.suspendedUntil else user?.silencedUntil),
                            ),
                            detail = listOfNotNull(
                                penalty.reason.takeIf { it.isNotBlank() },
                                penalty.createdBy?.let { stringResource(R.string.mod_user_by, it) },
                            ).joinToString(" · "),
                            busy = state.busy,
                        ) { viewModel.revoke(penalty.kind) }
                    }
                    state.restrictions.forEach { restriction ->
                        PenaltyCard(
                            title = stringResource(
                                if (restriction.kind == "ban") R.string.mod_user_active_node_ban else R.string.mod_user_active_node_mute,
                                nodeSlug.orEmpty(),
                                remaining(restriction.expiresAt),
                            ),
                            detail = listOfNotNull(
                                restriction.reason.takeIf { it.isNotBlank() },
                                restriction.createdBy.takeIf { it.isNotBlank() }?.let { stringResource(R.string.mod_user_by, it) },
                            ).joinToString(" · "),
                            busy = state.busy,
                        ) { viewModel.lift() }
                    }

                    if (detail != null) {
                        val records = buildRecords(detail)
                        ModSectionLabel(stringResource(R.string.mod_user_records))
                        if (records.isEmpty()) {
                            Text(
                                stringResource(R.string.mod_user_records_empty),
                                style = Type.body(14),
                                color = Nocturne.textSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        records.forEach { entry ->
                            SanctionRow(
                                entry.title,
                                Lucide.History,
                                supporting = entry.detail.ifBlank { null },
                                onClick = entry.itemId?.takeIf { privileges.reviewsSite }?.let { id -> { navigator.openReviewItem(id, null) } },
                            )
                        }
                    }

                    ModSectionLabel(stringResource(R.string.mod_user_actions))
                    if (viewModel.siteMode && user != null && user.deleted.not()) {
                        val hasActive = activePenalties.isNotEmpty()
                        SanctionRow(
                            stringResource(if (hasActive) R.string.mod_user_extend else R.string.mod_user_silence),
                            if (hasActive) Lucide.Ban else Lucide.VolumeX,
                            danger = hasActive,
                            onClick = { sheet = UserSheet.Penalty(severe = hasActive) },
                        )
                        if (!hasActive) {
                            SanctionRow(stringResource(R.string.mod_user_suspend), Lucide.Ban, danger = true, onClick = { sheet = UserSheet.Penalty(severe = true) })
                        }
                    } else if (viewModel.nodeMode) {
                        SanctionRow(
                            stringResource(R.string.mod_user_restrict_in_node, nodeSlug.orEmpty()),
                            Lucide.VolumeX,
                            supporting = stringResource(R.string.mod_user_restrict_hint),
                            onClick = { sheet = UserSheet.Penalty(severe = false) },
                        )
                    }
                    SanctionRow(stringResource(R.string.mod_user_profile), Lucide.Flag, onClick = { navigator.openProfile(username) })

                    if (viewModel.siteMode) {
                        NotesSection(state.notes, state.busy, viewModel)
                    }
                }
            }
        }
    }

    when (val current = sheet) {
        is UserSheet.Penalty -> PenaltySheet(
            username = username,
            userId = userId,
            nodeSlug = nodeSlug,
            avatarUrl = user?.avatar,
            subtitle = listOfNotNull("@$username", user?.createdAt?.let { stringResource(R.string.mod_user_joined, joinedFor(it)) })
                .joinToString(" · "),
            severe = current.severe,
            activeSiteKinds = activePenalties.map { it.kind }.toSet(),
            onDismiss = { sheet = UserSheet.None },
            onApplied = viewModel::load,
        )
        UserSheet.None -> Unit
    }
}

@Composable
private fun buildRecords(detail: ApiAdminUserDetail): List<RecordEntry> {
    val entries = mutableListOf<RecordEntry>()
    detail.penalties.forEach { penalty ->
        val start = DiscourseFormat.instant(penalty.createdAt)
        val end = DiscourseFormat.instant(penalty.endsAt)
        val length = if (start == null || end == null) {
            stringResource(R.string.mod_duration_forever)
        } else {
            stringResource(R.string.mod_duration_days, Duration.between(start, end).toDays().coerceAtLeast(1).toInt())
        }
        val kind = stringResource(if (penalty.kind == "suspend") R.string.mod_sheet_kind_suspend else R.string.mod_sheet_kind_silence)
        entries += RecordEntry(
            start,
            "$kind $length" + if (penalty.revokedAt != null) " · ${stringResource(R.string.mod_user_revoked_tag)}" else "",
            listOfNotNull(DiscourseFormat.dayAndTime(penalty.createdAt), penalty.createdBy, penalty.reason.takeIf { it.isNotBlank() })
                .filter { it.isNotBlank() }.joinToString(" · "),
        )
    }
    detail.reportsAgainst.forEach { report ->
        val title = when {
            report.resolution == "delete_post" -> stringResource(R.string.mod_user_record_deleted)
            report.resolution == "hide_post" -> stringResource(R.string.mod_user_record_hidden)
            report.kind == "pending_post" -> stringResource(R.string.mod_user_record_held)
            else -> stringResource(R.string.mod_user_record_reported, report.reportCount)
        }
        val status = stringResource(
            when (report.status) {
                "pending" -> R.string.mod_review_status_pending
                "approved" -> R.string.mod_review_status_approved
                "rejected" -> R.string.mod_review_status_rejected
                else -> R.string.mod_review_status_ignored
            },
        )
        entries += RecordEntry(
            DiscourseFormat.instant(report.createdAt),
            title,
            listOf(DiscourseFormat.dayAndTime(report.createdAt), status).filter { it.isNotBlank() }.joinToString(" · "),
            itemId = report.itemId,
        )
    }
    // Penalties already have their own rows; the log adds what undid or changed them.
    detail.logs.filterNot { it.action == "user.silence" || it.action == "user.suspend" }.forEach { log ->
        val label = when (log.action) {
            "user.unsilence" -> stringResource(R.string.mod_user_log_unsilence)
            "user.unsuspend" -> stringResource(R.string.mod_user_log_unsuspend)
            "user.update_penalty" -> stringResource(R.string.mod_user_log_update_penalty)
            "user.set_role" -> stringResource(R.string.mod_user_log_set_role)
            else -> log.action
        }
        val reason = (log.details?.get("reason") as? JsonPrimitive)?.contentOrNull
        entries += RecordEntry(
            DiscourseFormat.instant(log.createdAt),
            label,
            listOfNotNull(DiscourseFormat.dayAndTime(log.createdAt), log.actor, reason).filter { it.isNotBlank() }.joinToString(" · "),
        )
    }
    return entries.sortedByDescending { it.at ?: Instant.EPOCH }
}

@Composable
private fun PenaltyCard(title: String, detail: String, busy: Boolean, onLift: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .shadow(1.dp, shape)
            .clip(shape)
            .background(Nocturne.warningBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body(14, FontWeight.SemiBold), color = Nocturne.warning)
            if (detail.isNotBlank()) Text(detail, style = Type.body(13), color = Nocturne.warning)
        }
        Text(
            stringResource(R.string.mod_user_lift),
            style = Type.body(14, FontWeight.Bold),
            color = Nocturne.accent.copy(alpha = if (busy) 0.4f else 1f),
            modifier = Modifier.clickable(enabled = !busy, onClick = onLift).padding(8.dp),
        )
    }
}

@Composable
private fun NotesSection(notes: List<ApiUserNote>, busy: Boolean, viewModel: ModUserViewModel) {
    var draft by remember { mutableStateOf("") }
    ModSectionLabel(stringResource(R.string.mod_user_notes, notes.size))
    notes.forEach { note ->
        SanctionRow(
            note.body,
            Lucide.ScrollText,
            supporting = listOfNotNull(note.author, DiscourseFormat.dayAndTime(note.createdAt)).filter { it.isNotBlank() }.joinToString(" · "),
            trailing = {
                Icon(
                    Lucide.X,
                    stringResource(R.string.common_delete),
                    tint = Nocturne.textSecondary,
                    modifier = Modifier.clickable { viewModel.deleteNote(note.id) }.padding(4.dp),
                )
            },
        )
    }
    SanctionField(draft, { draft = it.take(2000) }, stringResource(R.string.mod_user_note_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
        ModButton(stringResource(R.string.mod_user_note_add), ModButtonStyle.Filled, enabled = draft.isNotBlank() && !busy) {
            viewModel.addNote(draft) { draft = "" }
        }
    }
    Spacer(Modifier.height(8.dp))
}
