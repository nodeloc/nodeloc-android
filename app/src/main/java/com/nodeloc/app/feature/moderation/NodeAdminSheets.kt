package com.nodeloc.app.feature.moderation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Crown
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.ApiNodeAccess
import com.nodeloc.app.core.network.ApiNodeAccessGroup
import com.nodeloc.app.core.network.ApiNodeModerator
import com.nodeloc.app.core.network.ApiNodeRule

/** The server's limits on node rules (appearance.go): 15 rules, 100-rune titles, 500-rune descriptions. */
private const val MAX_RULES = 15
private const val MAX_RULE_TITLE = 100
private const val MAX_RULE_DESCRIPTION = 500

private fun String.runes(): Int = codePointCount(0, length)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeAdminSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Nocturne.bg,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                title,
                style = Type.heading(20),
                color = Nocturne.text,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
            content()
        }
    }
}

@Composable
internal fun NodeAdminModeratorsSheet(moderators: List<ApiNodeModerator>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    NodeAdminSheet(stringResource(R.string.mod_node_team), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            moderators.forEach { moderator ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(moderator.username) }
                        .defaultMinSize(minHeight = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Avatar(moderator.username, size = 36.dp)
                    Text(moderator.username, style = Type.body(16), color = Nocturne.text, modifier = Modifier.weight(1f))
                    if (moderator.owner) {
                        Icon(Lucide.Crown, null, tint = Nocturne.warning, modifier = Modifier.size(16.dp))
                        StatusPill(stringResource(R.string.mod_node_owner_badge), PillTone.Warning)
                    }
                }
            }
            Text(
                stringResource(R.string.mod_node_team_hint),
                style = Type.body(13),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Reading for a moderator, editing for the owner. The server replaces the
 * whole list on save, so edits collect here and go in one request — a save
 * per tap would mean a half-reordered list whenever one of them failed.
 */
@Composable
internal fun NodeAdminRulesSheet(
    rules: List<ApiNodeRule>,
    editable: Boolean,
    saving: Boolean,
    onSave: (List<ApiNodeRule>) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = remember(rules) { mutableStateListOf(*rules.toTypedArray()) }
    // -1 is "a new rule"; null is no dialog.
    var editing by remember { mutableStateOf<Int?>(null) }
    val changed = draft.toList() != rules

    NodeAdminSheet(stringResource(R.string.mod_node_rules), onDismiss) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            if (draft.isEmpty()) {
                Text(
                    stringResource(R.string.mod_node_rules_empty),
                    style = Type.body(14),
                    color = Nocturne.textSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            draft.forEachIndexed { index, rule ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (editable) Modifier.clickable { editing = index } else Modifier)
                        .padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = Type.body(16, FontWeight.Bold),
                        color = Nocturne.accent,
                        modifier = Modifier.width(28.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(rule.title, style = Type.body(16), color = Nocturne.text)
                        rule.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = Type.body(14), color = Nocturne.textSecondary)
                        }
                    }
                    if (editable) {
                        RuleIcon(Lucide.ArrowUp, stringResource(R.string.mod_node_rule_up), enabled = index > 0) {
                            draft.add(index - 1, draft.removeAt(index))
                        }
                        RuleIcon(Lucide.ArrowDown, stringResource(R.string.mod_node_rule_down), enabled = index < draft.lastIndex) {
                            draft.add(index + 1, draft.removeAt(index))
                        }
                        RuleIcon(Lucide.Trash2, stringResource(R.string.common_delete)) { draft.removeAt(index) }
                    }
                }
            }
        }
        if (editable) {
            HorizontalDivider(color = Nocturne.divider)
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { editing = -1 }, enabled = draft.size < MAX_RULES) {
                    Icon(Lucide.Plus, null, tint = Nocturne.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.mod_node_rule_add),
                        style = Type.body(14, FontWeight.Medium),
                        color = if (draft.size < MAX_RULES) Nocturne.accent else Nocturne.textTertiary,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (saving) {
                    NodelocLoader(height = 24.dp)
                } else {
                    TextButton(onClick = { onSave(draft.toList()) }, enabled = changed) {
                        Text(
                            stringResource(R.string.common_save),
                            style = Type.body(14, FontWeight.Medium),
                            color = if (changed) Nocturne.accent else Nocturne.textTertiary,
                        )
                    }
                }
            }
        }
    }

    editing?.let { index ->
        RuleDialog(
            initial = draft.getOrNull(index),
            onDone = { rule ->
                if (index in draft.indices) draft[index] = rule else draft.add(rule)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun RuleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) Nocturne.textSecondary else Nocturne.neutral400, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RuleDialog(initial: ApiNodeRule?, onDone: (ApiNodeRule) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    val titleOk = title.trim().runes() in 1..MAX_RULE_TITLE
    val descriptionOk = description.trim().runes() <= MAX_RULE_DESCRIPTION
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Nocturne.accent,
        unfocusedBorderColor = Nocturne.divider,
        focusedLabelColor = Nocturne.accent,
        unfocusedLabelColor = Nocturne.textSecondary,
        cursorColor = Nocturne.accent,
        focusedTextColor = Nocturne.text,
        unfocusedTextColor = Nocturne.text,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.bg,
        title = {
            Text(
                stringResource(if (initial == null) R.string.mod_node_rule_add else R.string.mod_node_rule_edit),
                style = Type.heading(20),
                color = Nocturne.text,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.mod_node_rule_title)) },
                    singleLine = true,
                    isError = title.trim().runes() > MAX_RULE_TITLE,
                    supportingText = { Text("${title.trim().runes()}/$MAX_RULE_TITLE") },
                    colors = colors,
                    textStyle = Type.body(16),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.mod_node_rule_description)) },
                    minLines = 3,
                    maxLines = 6,
                    isError = !descriptionOk,
                    supportingText = { Text("${description.trim().runes()}/$MAX_RULE_DESCRIPTION") },
                    colors = colors,
                    textStyle = Type.body(15),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDone(ApiNodeRule(title.trim(), description.trim().ifEmpty { null })) },
                enabled = titleOk && descriptionOk,
            ) {
                Text(
                    stringResource(R.string.common_done),
                    style = Type.body(14, FontWeight.Medium),
                    color = if (titleOk && descriptionOk) Nocturne.accent else Nocturne.textTertiary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), style = Type.body(14, FontWeight.Medium), color = Nocturne.textSecondary)
            }
        },
    )
}

/** Read-only: the list's rules (who may hold which level) are easy to get wrong in a phone form. */
@Composable
internal fun NodeAdminAccessSheet(access: ApiNodeAccess, onEditOnWeb: () -> Unit, onDismiss: () -> Unit) {
    val groups = access.groups.associateBy { it.subject }
    NodeAdminSheet(stringResource(R.string.mod_node_access), onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(if (access.entries.isEmpty()) R.string.mod_node_access_open else R.string.mod_node_access_explain),
                style = Type.body(14),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            access.entries.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        accessSubjectLabel(entry.subject, groups[entry.subject]),
                        style = Type.body(16),
                        color = Nocturne.text,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(accessLevelLabel(entry.level), if (entry.level >= 4) PillTone.Info else PillTone.Neutral)
                }
            }
        }
        HorizontalDivider(color = Nocturne.divider)
        TextButton(onClick = onEditOnWeb, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) {
            Icon(Lucide.ExternalLink, null, tint = Nocturne.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.mod_node_edit_on_web), style = Type.body(14, FontWeight.Medium), color = Nocturne.accent)
        }
    }
}

@Composable
private fun accessSubjectLabel(subject: String, group: ApiNodeAccessGroup?): String = when (group?.kind) {
    "everyone" -> stringResource(R.string.mod_node_subject_everyone)
    "members" -> stringResource(R.string.mod_node_subject_members)
    "moderators" -> stringResource(R.string.mod_node_subject_moderators)
    "level" -> stringResource(R.string.mod_node_subject_level, group.level ?: subject.substringAfter(':').toIntOrNull() ?: 0)
    else -> group?.name?.takeIf { it.isNotBlank() } ?: subject
}

@Composable
private fun accessLevelLabel(level: Int): String = when (level) {
    1 -> stringResource(R.string.mod_node_level_view)
    2 -> stringResource(R.string.mod_node_level_reply)
    3 -> stringResource(R.string.mod_node_level_topic)
    else -> stringResource(R.string.mod_node_level_moderate)
}
