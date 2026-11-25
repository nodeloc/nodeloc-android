package com.nodeloc.app.feature.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowUpDown
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.VolumeX
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ConfirmDialog
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.ApiNode
import com.nodeloc.app.core.network.ModerationApi
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs the sheets' writes. A ViewModel rather than the sheet's own scope
 * because the sheet is dismissed the moment the moderator confirms, and a
 * write cancelled half-way would leave the reader guessing what happened.
 */
class SanctionViewModel : ViewModel() {
    private val api = ServiceLocator.get.moderationApi

    private val _nodes = MutableStateFlow<List<ApiNode>?>(null)
    val nodes: StateFlow<List<ApiNode>?> = _nodes.asStateFlow()

    fun run(block: suspend ModerationApi.() -> Unit, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            runCatchingCancellable { api.block() }
                .onSuccess { onDone(true) }
                .onFailure {
                    ToastCenter.showError(it)
                    onDone(false)
                }
        }
    }

    fun loadNodes() {
        if (_nodes.value != null) return
        viewModelScope.launch {
            runCatchingCancellable { ServiceLocator.get.client.nodeDirectory() }
                .onSuccess { _nodes.value = it }
                .onFailure {
                    ToastCenter.showError(it)
                    _nodes.value = emptyList()
                }
        }
    }
}

/** A removal reason plus the moderator's own words, as the author will read it. */
internal fun removalText(label: String, note: String): String =
    if (note.isBlank()) label else "$label · ${note.trim()}"

// ------------------------------------------------------------------ delete sheet

/**
 * "删除回复 / 删除内容": a reason the author will see, optional extra words.
 *
 * There is deliberately no "notify the author" or "allow appeal" switch: the
 * server always notifies on a review deletion and never on a direct one, and
 * has no appeal flag, so a switch would be a lie either way.
 *
 * @param onConfirm receives the composed reason text ("广告推广 · 补充说明").
 */
@Composable
fun DeleteContentSheet(
    title: String,
    subtitle: String?,
    confirmLabel: String,
    onDismiss: () -> Unit,
    penaltyMax: Int? = null,
    onConfirm: (reason: String, reputationPenalty: Int) -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf(RemovalReason.Promotion) }
    var note by rememberSaveable { mutableStateOf("") }
    // 0 is 不扣; CUSTOM_PENALTY opens the number field.
    var penaltyChoice by rememberSaveable { mutableIntStateOf(0) }
    var customPenalty by rememberSaveable { mutableStateOf("") }
    val label = stringResource(reason.labelRes)
    val penalty = when (penaltyChoice) {
        CUSTOM_PENALTY -> customPenalty.toIntOrNull() ?: 0
        else -> penaltyChoice
    }
    val penaltyTooBig = penaltyMax != null && penalty > penaltyMax
    SanctionSheet(title, onDismiss, subtitle) {
        SanctionLabel(stringResource(R.string.mod_sheet_delete_reason_label))
        RemovalReason.entries.forEach { option ->
            SanctionRadio(stringResource(option.labelRes), option == reason) { reason = option }
        }
        Spacer(Modifier.height(8.dp))
        SanctionField(
            note,
            { note = it.take(180) },
            stringResource(R.string.mod_sheet_delete_note),
            Modifier.padding(horizontal = 24.dp),
        )
        if (penaltyMax != null && penaltyMax > 0) {
            // The server's presets (5 / 10 / 20 / 50 / 100) as the website shows
            // them, cut at what this moderator may take — a node moderator's 20.
            SanctionLabel(stringResource(R.string.mod_rep_penalty_label), Modifier.padding(top = 12.dp))
            FlowRow(
                Modifier.padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill(stringResource(R.string.mod_rep_penalty_none), penaltyChoice == 0) { penaltyChoice = 0 }
                REPUTATION_PRESETS.filter { it <= penaltyMax }.forEach { value ->
                    FilterPill("−$value", penaltyChoice == value) { penaltyChoice = value }
                }
                FilterPill(stringResource(R.string.mod_rep_penalty_custom), penaltyChoice == CUSTOM_PENALTY) {
                    penaltyChoice = CUSTOM_PENALTY
                }
            }
            if (penaltyChoice == CUSTOM_PENALTY) {
                SanctionField(
                    customPenalty,
                    { customPenalty = it.filter(Char::isDigit).take(4) },
                    stringResource(R.string.mod_rep_penalty_custom_hint, penaltyMax),
                    Modifier.padding(horizontal = 24.dp).padding(top = 4.dp),
                    singleLine = true,
                )
            }
            Text(
                if (penaltyTooBig) stringResource(R.string.mod_rep_penalty_too_big, penaltyMax)
                else stringResource(R.string.mod_rep_penalty_note),
                style = Type.body(12),
                color = if (penaltyTooBig) Nocturne.danger else Nocturne.textSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
        SanctionFooter(
            cancel = stringResource(R.string.common_cancel),
            confirm = if (penalty > 0 && !penaltyTooBig) stringResource(R.string.mod_rep_penalty_confirm, confirmLabel, penalty) else confirmLabel,
            confirmStyle = ModButtonStyle.Danger,
            enabled = !penaltyTooBig,
            onCancel = onDismiss,
            onConfirm = { onConfirm(removalText(label, note), if (penaltyMax != null) penalty.coerceAtLeast(0) else 0) },
        )
    }
}

private val REPUTATION_PRESETS = listOf(5, 10, 20, 50, 100)
private const val CUSTOM_PENALTY = -1

// ------------------------------------------------------------------ penalty sheet

private enum class PenaltyScope { Site, Node }

/**
 * 处罚用户: 禁言 / 封号, a length, a reason, and where it applies.
 *
 * Two different server features hide behind one sheet. Site scope is a site
 * penalty (`silence` = cannot post, `suspend` = cannot log in), needs
 * `admin.users`, and notifies and emails the user. Node scope is a node
 * restriction (`mute` = cannot post in the node, `ban` = cannot see it), is
 * open to the node's moderators, and tells the user nothing — so the summary
 * line says so, rather than letting a moderator assume they were warned.
 *
 * @param username who is penalised; node restrictions are addressed by name.
 * @param userId their id. Required for site scope and for the undo offer; with
 *   null only node scope is offered, without undo.
 * @param nodeSlug the node a node-scope restriction applies to. Null, or a node
 *   the viewer does not moderate, leaves site scope only.
 * @param avatarUrl shown in the header; null falls back to a letter.
 * @param subtitle the grey line under the name ("@name · 注册 3 天"); null shows "@username".
 * @param severe start on the heavier kind (封号 / 节点封禁) rather than 禁言.
 * @param activeSiteKinds site kinds (`silence`, `suspend`) already in force. Choosing
 *   one of them changes that penalty's length (`PUT …/penalties/{kind}`) instead of
 *   stacking a new one; such a change has no undo.
 * @param onDismiss the sheet is closed, applied or not.
 * @param onApplied after the server accepted the penalty, before [onDismiss].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PenaltySheet(
    username: String,
    userId: Long?,
    nodeSlug: String?,
    onDismiss: () -> Unit,
    onApplied: () -> Unit = {},
    avatarUrl: String? = null,
    subtitle: String? = null,
    severe: Boolean = false,
    activeSiteKinds: Set<String> = emptySet(),
) {
    val vm: SanctionViewModel = viewModel(key = "sanction")
    val privileges by ServiceLocator.get.moderation.privileges.collectAsState()
    val canSite = privileges.managesUsers && userId != null
    val canNode = nodeSlug != null && privileges.moderates(nodeSlug)

    var scope by rememberSaveable {
        mutableStateOf(if (canNode && !(severe && canSite)) PenaltyScope.Node else PenaltyScope.Site)
    }
    var kindIndex by rememberSaveable { mutableIntStateOf(if (severe) 1 else 0) }
    var daysIndex by rememberSaveable { mutableIntStateOf(PENALTY_DAYS.indexOf(7).coerceAtLeast(0)) }
    var reason by rememberSaveable { mutableStateOf<RemovalReason?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmBan by remember { mutableStateOf(false) }

    if (!canSite && !canNode) {
        LaunchedEffect(Unit) {
            ToastCenter.show(R.string.mod_sheet_penalty_no_scope)
            onDismiss()
        }
        return
    }

    val site = scope == PenaltyScope.Site
    val kind = if (site) listOf("silence", "suspend")[kindIndex] else listOf("mute", "ban")[kindIndex]
    val days = PENALTY_DAYS[daysIndex]
    val permanent = days == 0
    val reasonLabel = reason?.let { stringResource(it.labelRes) }
    val reasonText = when {
        reasonLabel != null -> removalText(reasonLabel, note)
        else -> note.trim()
    }.take(500)
    val updating = site && kind in activeSiteKinds
    // A site penalty without a reason is a 422; the user reads it in the email.
    val ready = !busy && (!site || updating || reasonText.isNotBlank())
    val until = DiscourseFormat.drawMoment(days)
    val severeKind = kind == "suspend" || kind == "ban"

    val kindLabels = if (site) {
        listOf(stringResource(R.string.mod_sheet_kind_silence), stringResource(R.string.mod_sheet_kind_suspend))
    } else {
        listOf(stringResource(R.string.mod_sheet_kind_mute), stringResource(R.string.mod_sheet_kind_ban))
    }
    val explanation = when (kind) {
        "silence" -> stringResource(R.string.mod_sheet_explain_silence)
        "suspend" -> stringResource(R.string.mod_sheet_explain_suspend)
        "mute" -> stringResource(R.string.mod_sheet_explain_mute, nodeSlug.orEmpty())
        else -> stringResource(R.string.mod_sheet_explain_ban, nodeSlug.orEmpty())
    }
    val summary = when (kind) {
        "silence" -> if (permanent) stringResource(R.string.mod_sheet_summary_silence_forever, username)
        else stringResource(R.string.mod_sheet_summary_silence, username, until)
        "suspend" -> if (permanent) stringResource(R.string.mod_sheet_summary_suspend_forever, username)
        else stringResource(R.string.mod_sheet_summary_suspend, username, until)
        "mute" -> if (permanent) stringResource(R.string.mod_sheet_summary_mute_forever, username, nodeSlug.orEmpty())
        else stringResource(R.string.mod_sheet_summary_mute, username, nodeSlug.orEmpty(), until)
        else -> if (permanent) stringResource(R.string.mod_sheet_summary_ban_forever, username, nodeSlug.orEmpty())
        else stringResource(R.string.mod_sheet_summary_ban, username, nodeSlug.orEmpty(), until)
    } + " " + stringResource(if (site) R.string.mod_sheet_summary_notified else R.string.mod_sheet_summary_silent)
    val confirmLabel = when {
        kind == "suspend" && permanent -> stringResource(R.string.mod_sheet_suspend_forever)
        updating -> stringResource(R.string.mod_sheet_penalty_update, penaltyDaysLabel(days))
        else -> stringResource(R.string.mod_sheet_penalty_apply, kindLabels[kindIndex], penaltyDaysLabel(days))
    }

    fun apply() {
        busy = true
        val uid = userId
        vm.run({
            when {
                site && updating -> updatePenalty(uid!!, kind, days, reasonText.ifBlank { null })
                site -> penalize(uid!!, kind, reasonText, days)
                else -> restrictInNode(nodeSlug!!, username, kind, reasonText, days)
            }
        }) { ok ->
            busy = false
            if (!ok) return@run
            onApplied()
            onDismiss()
            when {
                updating -> ToastCenter.show(R.string.mod_sheet_penalty_updated)
                site -> ModUndo.offer(
                    if (kind == "silence") R.string.mod_sheet_done_silence else R.string.mod_sheet_done_suspend,
                    username,
                    afterUndo = onApplied,
                ) { ServiceLocator.get.moderationApi.revokePenalty(uid!!, kind) }
                uid != null -> ModUndo.offer(
                    if (kind == "mute") R.string.mod_sheet_done_mute else R.string.mod_sheet_done_ban,
                    username,
                    nodeSlug!!,
                    afterUndo = onApplied,
                ) { ServiceLocator.get.moderationApi.liftNodeRestriction(nodeSlug, uid) }
                else -> ToastCenter.show(if (kind == "mute") R.string.mod_sheet_done_mute else R.string.mod_sheet_done_ban, username, nodeSlug!!)
            }
        }
    }

    SanctionSheet(stringResource(R.string.mod_sheet_penalty_title), onDismiss) {
        Row(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RemoteAvatar(avatarUrl, username, size = 40.dp)
            Column {
                Text(username, style = Type.body(16, FontWeight.SemiBold), color = Nocturne.text)
                Text(subtitle ?: "@$username", style = Type.body(13), color = Nocturne.textSecondary)
            }
        }
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
            SanctionSegmented(kindLabels, kindIndex) { kindIndex = it }
            Text(explanation, style = Type.body(12), color = Nocturne.textSecondary, modifier = Modifier.padding(top = 6.dp))
        }
        SanctionLabel(stringResource(R.string.mod_sheet_duration))
        FlowRow(
            Modifier.padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PENALTY_DAYS.forEachIndexed { index, value ->
                FilterPill(penaltyDaysLabel(value), index == daysIndex) { daysIndex = index }
            }
        }
        SanctionRow(
            title = stringResource(R.string.mod_sheet_scope),
            icon = null,
            onClick = if (canSite && canNode) {
                { scope = if (site) PenaltyScope.Node else PenaltyScope.Site }
            } else {
                null
            },
            trailing = {
                Text(
                    if (site) stringResource(R.string.mod_sheet_scope_site) else "n/$nodeSlug",
                    style = Type.body(14),
                    color = if (canSite && canNode) Nocturne.accent else Nocturne.textSecondary,
                )
            },
        )
        SanctionLabel(
            stringResource(if (site && !updating) R.string.mod_sheet_reason_required else R.string.mod_sheet_reason_optional),
            Modifier.padding(top = 4.dp),
        )
        FilterPillRow(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp)) {
            RemovalReason.entries.forEach { option ->
                FilterPill(stringResource(option.labelRes), option == reason) {
                    reason = if (reason == option) null else option
                }
            }
        }
        SanctionField(note, { note = it.take(300) }, stringResource(R.string.mod_sheet_delete_note), Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))
        SanctionSummary(summary, danger = severeKind || permanent)
        SanctionFooter(
            cancel = stringResource(R.string.common_cancel),
            confirm = confirmLabel,
            confirmStyle = ModButtonStyle.Danger,
            enabled = ready,
            onCancel = onDismiss,
            onConfirm = { if (kind == "suspend" && permanent) confirmBan = true else apply() },
        )
    }

    if (confirmBan) {
        PermanentBanDialog(username, onDismiss = { confirmBan = false }) {
            confirmBan = false
            apply()
        }
    }
}

/** Permanent suspension asks for the word typed out: a tap is too easy to give. */
@Composable
private fun PermanentBanDialog(username: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val word = stringResource(R.string.mod_sheet_ban_word)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.neutral400,
        icon = { Icon(Lucide.Ban, null, tint = Nocturne.danger) },
        title = {
            Text(
                stringResource(R.string.mod_sheet_ban_confirm_title, username),
                style = Type.heading(22, FontWeight.Normal),
                color = Nocturne.text,
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.mod_sheet_ban_confirm_body), style = Type.body(14), color = Nocturne.textSecondary)
                SanctionField(typed, { typed = it }, stringResource(R.string.mod_sheet_ban_confirm_hint, word), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim().equals(word, ignoreCase = true)) {
                Text(
                    stringResource(R.string.mod_sheet_kind_suspend),
                    color = Nocturne.danger.copy(alpha = if (typed.trim().equals(word, ignoreCase = true)) 1f else 0.4f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = Nocturne.accent) }
        },
    )
}

// ------------------------------------------------------------------ topic sheet

private enum class TopicMode { Main, Move, Delete, Penalty, PenaltySevere }

/**
 * The reader's 版主操作 for a topic. Pin and lock are open to every moderator
 * of the node; feature and move need `topics.curate`. There is no "hide topic"
 * on the server, so the design's row for it is not here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostModerationSheet(topic: ModTopicRef, navigator: Navigator, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val vm: SanctionViewModel = viewModel(key = "sanction")
    val privileges by ServiceLocator.get.moderation.privileges.collectAsState()
    var mode by remember { mutableStateOf(TopicMode.Main) }
    var pinned by remember { mutableStateOf(topic.pinned) }
    var closed by remember { mutableStateOf(topic.closed) }
    var featured by remember { mutableStateOf(topic.featured) }
    var choosingPin by remember { mutableStateOf(false) }

    when (mode) {
        TopicMode.Main -> SanctionSheet(
            stringResource(R.string.mod_sheet_topic_title),
            onDismiss,
            subtitle = listOfNotNull(topic.title, topic.nodeSlug?.let { "n/$it" }).joinToString(" · "),
        ) {
            if (pinned) {
                SanctionRow(
                    stringResource(R.string.mod_sheet_unpin),
                    Lucide.Pin,
                    onClick = {
                        pinned = false
                        vm.run({ setTopicState(topic.topicId, pinned = false) }) { ok -> if (ok) onChanged() else pinned = true }
                    },
                    trailing = { Text(stringResource(R.string.mod_sheet_pinned), style = Type.body(14), color = Nocturne.textSecondary) },
                )
            } else {
                SanctionRow(stringResource(R.string.mod_sheet_pin), Lucide.Pin, onClick = { choosingPin = !choosingPin })
                if (choosingPin) {
                    FlowRow(
                        Modifier.padding(start = 56.dp, end = 24.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // The server's pin lengths: 1, 3, 7, 30 days or permanent.
                        PENALTY_DAYS.forEach { days ->
                            FilterPill(penaltyDaysLabel(days), false) {
                                choosingPin = false
                                pinned = true
                                vm.run({ setTopicState(topic.topicId, pinned = true, pinDays = days) }) { ok ->
                                    if (ok) onChanged() else pinned = false
                                }
                            }
                        }
                    }
                }
            }
            SanctionRow(
                stringResource(R.string.mod_sheet_lock),
                Lucide.Lock,
                onClick = null,
                trailing = {
                    SanctionSwitch(closed) { value ->
                        closed = value
                        vm.run({ setTopicState(topic.topicId, closed = value) }) { ok -> if (ok) onChanged() else closed = !value }
                    }
                },
            )
            if (privileges.curatesTopics) {
                SanctionRow(
                    stringResource(R.string.mod_sheet_feature),
                    Lucide.Star,
                    trailing = {
                        SanctionSwitch(featured) { value ->
                            featured = value
                            vm.run({ setFeatured(topic.topicId, value) }) { ok -> if (ok) onChanged() else featured = !value }
                        }
                    },
                )
                SanctionRow(stringResource(R.string.mod_sheet_move), Lucide.ArrowUpDown, onClick = { mode = TopicMode.Move })
            }
            SanctionDivider()
            SanctionRow(stringResource(R.string.mod_sheet_delete_topic), Lucide.Trash2, danger = true, onClick = { mode = TopicMode.Delete })
            if (topic.authorUsername.isNotBlank()) {
                SanctionDivider()
                SanctionRow(stringResource(R.string.mod_sheet_silence_author), Lucide.VolumeX, onClick = { mode = TopicMode.Penalty })
                SanctionRow(stringResource(R.string.mod_sheet_ban_author), Lucide.Ban, onClick = { mode = TopicMode.PenaltySevere })
            }
        }

        TopicMode.Move -> MoveTopicSheet(topic, vm, onDismiss) {
            onChanged()
            onDismiss()
        }

        TopicMode.Delete -> ConfirmDialog(
            title = stringResource(R.string.mod_sheet_delete_topic_title),
            body = stringResource(R.string.mod_sheet_delete_topic_body),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                vm.run({ deleteTopic(topic.topicId) }) { ok ->
                    if (ok) {
                        ToastCenter.show(R.string.mod_sheet_topic_deleted)
                        onChanged()
                    }
                }
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        TopicMode.Penalty, TopicMode.PenaltySevere -> PenaltySheet(
            username = topic.authorUsername,
            userId = topic.authorId,
            nodeSlug = topic.nodeSlug,
            severe = mode == TopicMode.PenaltySevere,
            onDismiss = onDismiss,
        )
    }
}

private val MOVE_REASONS = listOf(
    "violation" to R.string.mod_sheet_move_violation,
    "wrong_node" to R.string.mod_sheet_move_wrong_node,
    "duplicate" to R.string.mod_sheet_move_duplicate,
    "promotion" to R.string.mod_sheet_move_promotion,
    "other" to R.string.mod_sheet_move_other,
)

@Composable
private fun MoveTopicSheet(topic: ModTopicRef, vm: SanctionViewModel, onDismiss: () -> Unit, onMoved: () -> Unit) {
    val nodes by vm.nodes.collectAsState()
    LaunchedEffect(Unit) { vm.loadNodes() }
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<ApiNode?>(null) }
    var reason by remember { mutableStateOf("wrong_node") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Sections (no parent) cannot hold topics; the server refuses them as `to`.
    val candidates = nodes.orEmpty()
        .filter { it.parentId != null && it.slug != topic.nodeSlug }
        .filter { query.isBlank() || it.name.contains(query.trim(), true) || it.slug.contains(query.trim(), true) }
        .take(40)

    SanctionSheet(stringResource(R.string.mod_sheet_move), onDismiss, subtitle = topic.title) {
        SanctionField(query, { query = it }, stringResource(R.string.mod_sheet_move_search), Modifier.padding(horizontal = 24.dp), singleLine = true)
        Spacer(Modifier.height(8.dp))
        if (nodes == null) {
            Text(
                stringResource(R.string.common_loading),
                style = Type.body(14),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        candidates.forEach { node ->
            SanctionRadio("${node.name} · n/${node.slug}", target?.slug == node.slug) { target = node }
        }
        Spacer(Modifier.height(8.dp))
        SanctionLabel(stringResource(R.string.mod_sheet_move_reason))
        MOVE_REASONS.forEach { (code, label) ->
            SanctionRadio(stringResource(label), reason == code) { reason = code }
        }
        SanctionField(note, { note = it.take(200) }, stringResource(R.string.mod_sheet_delete_note), Modifier.padding(horizontal = 24.dp))
        Text(
            stringResource(R.string.mod_sheet_move_notice),
            style = Type.body(12),
            color = Nocturne.textSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SanctionFooter(
            cancel = stringResource(R.string.common_cancel),
            confirm = stringResource(R.string.mod_sheet_move_confirm),
            confirmStyle = ModButtonStyle.Filled,
            enabled = target != null && !busy,
            onCancel = onDismiss,
            onConfirm = {
                val node = target ?: return@SanctionFooter
                busy = true
                vm.run({ moveTopic(topic.topicId, node.slug, reason, note) }) { ok ->
                    busy = false
                    if (ok) {
                        ToastCenter.show(R.string.mod_sheet_moved, node.slug)
                        onMoved()
                    }
                }
            },
        )
    }
}

// ------------------------------------------------------------------ reply sheet

private enum class ReplyMode { Main, Delete, Penalty, PenaltySevere }

/** The reader's moderator actions on one reply. Edit history is the reader's own entry. */
@Composable
fun ReplyModerationSheet(post: ModPostRef, navigator: Navigator, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val vm: SanctionViewModel = viewModel(key = "sanction")
    var mode by remember { mutableStateOf(ReplyMode.Main) }
    val subtitle = "${post.authorUsername} · “${post.excerpt.take(60)}”"

    when (mode) {
        ReplyMode.Main -> SanctionSheet(stringResource(R.string.mod_sheet_reply_title), onDismiss, subtitle = subtitle) {
            if (post.deleted) {
                SanctionRow(stringResource(R.string.mod_sheet_restore_reply), Lucide.Undo2, onClick = {
                    vm.run({ restorePost(post.postId) }) { ok ->
                        if (ok) {
                            ToastCenter.show(R.string.mod_sheet_reply_restored)
                            onChanged()
                        }
                    }
                    onDismiss()
                })
            } else {
                SanctionRow(
                    stringResource(R.string.mod_sheet_delete_reply),
                    Lucide.Trash2,
                    danger = true,
                    onClick = { mode = ReplyMode.Delete },
                )
            }
            if (post.authorUsername.isNotBlank()) {
                SanctionDivider()
                SanctionRow(stringResource(R.string.mod_sheet_silence_author), Lucide.VolumeX, onClick = { mode = ReplyMode.Penalty })
                SanctionRow(stringResource(R.string.mod_sheet_ban_author), Lucide.Ban, onClick = { mode = ReplyMode.PenaltySevere })
            }
        }

        ReplyMode.Delete -> DeleteContentSheet(
            title = stringResource(R.string.mod_sheet_delete_reply_title),
            subtitle = subtitle,
            confirmLabel = stringResource(R.string.common_delete),
            onDismiss = onDismiss,
        ) { reason, _ ->
            // The reason shows on the placeholder; the author gets no notice from this route.
            vm.run({ deletePost(post.postId, reason) }) { ok ->
                if (ok) {
                    onChanged()
                    ModUndo.offer(R.string.mod_sheet_reply_deleted, afterUndo = onChanged) {
                        ServiceLocator.get.moderationApi.restorePost(post.postId)
                    }
                }
            }
            onDismiss()
        }

        ReplyMode.Penalty, ReplyMode.PenaltySevere -> PenaltySheet(
            username = post.authorUsername,
            userId = post.authorId,
            nodeSlug = post.nodeSlug,
            severe = mode == ReplyMode.PenaltySevere,
            onDismiss = onDismiss,
        )
    }
}

