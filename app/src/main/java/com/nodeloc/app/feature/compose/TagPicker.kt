package com.nodeloc.app.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Hash
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.X
import com.nodeloc.app.R
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.network.ApiTag

/**
 * The tags beside the node pill, as the board draws them: each chosen tag a
 * filled chip, then an outlined "+ 标签" that opens the picker. Tapping a chosen
 * tag opens the picker too, where it can be taken off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComposerTagChips(tags: List<String>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        tags.forEach { tag ->
            Box(
                Modifier
                    .height(22.dp)
                    .clip(CircleShape)
                    .background(Nocturne.neutral400)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tag, style = Type.body(12, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
            }
        }
        val press = rememberPressSource()
        Row(
            Modifier
                .pressScale(press)
                .height(22.dp)
                .clip(CircleShape)
                .border(1.dp, Nocturne.divider, CircleShape)
                .clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onOpen)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(Lucide.Plus, null, tint = Nocturne.textSecondary, modifier = Modifier.size(12.dp))
            // The board drops the word once there is a tag to its left: the
            // chips already say what the row is.
            if (tags.isEmpty()) {
                Text(stringResource(R.string.compose_tags_add), style = Type.body(12), color = Nocturne.textSecondary)
            }
        }
    }
}

/**
 * Picks the topic's tags: search or type one, see the chosen ones with a way
 * to take each off, and the common or matching tags with how often they are
 * used. Staff-only tags are shown but not offered to anyone else, and a typed
 * name nobody has used yet can be created unless the node keeps a fixed list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TagPickerSheet(
    state: ComposeState,
    onQuery: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val searchFocus = remember { FocusRequester() }
    val query = state.tagQuery.trim().removePrefix("#").trim()
    val restricted = state.posting?.tagsRestricted == true
    val exact = state.tagSuggestions.any { it.name.equals(query, ignoreCase = true) }
    val canCreate = query.isNotEmpty() && !exact && !restricted

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).imePadding()) {
            Column(Modifier.padding(horizontal = Space.page)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.compose_tags_add), style = Type.heading(20, FontWeight.Bold), color = Nocturne.text)
                        Text(stringResource(R.string.compose_tags_subtitle), style = Type.body(13), color = Nocturne.textSecondary)
                    }
                    Text(
                        stringResource(R.string.compose_tags_count, state.tags.size),
                        style = Type.body(13, FontWeight.Medium),
                        color = Nocturne.textTertiary,
                    )
                }
                FieldSurface(Modifier.padding(top = 12.dp)) {
                    BasicTextField(
                        value = state.tagQuery,
                        onValueChange = { if (it.length <= MAX_TAG_LENGTH + 1) onQuery(it) },
                        singleLine = true,
                        textStyle = Type.body(15).copy(color = Nocturne.text),
                        cursorBrush = SolidColor(Nocturne.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        // Enter adds what was typed — a match if there is one, else
                        // a new tag where the node allows it.
                        keyboardActions = KeyboardActions(onDone = {
                            val match = state.tagSuggestions.firstOrNull { it.name.equals(query, ignoreCase = true) }
                            when {
                                match != null -> if (!match.staffOnly || state.canUseStaffTags) onAdd(match.name)
                                canCreate -> onAdd(query)
                            }
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        decorationBox = { inner ->
                            if (state.tagQuery.isEmpty()) {
                                Text(stringResource(R.string.compose_tags_search_hint), style = Type.body(15), color = Nocturne.textTertiary)
                            }
                            inner()
                        },
                    )
                }
                if (state.tags.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.tags.forEach { tag -> ChosenTag(tag) { onRemove(tag) } }
                    }
                }
                val notes = listOfNotNull(
                    stringResource(R.string.compose_tags_full).takeIf { state.tagLimitHit },
                    state.posting?.minTags?.takeIf { it > 0 && state.tagsMissing > 0 }
                        ?.let { stringResource(R.string.compose_tags_min, it) },
                    stringResource(R.string.compose_tags_restricted).takeIf { restricted },
                )
                notes.forEach { note ->
                    Text(
                        note,
                        style = Type.body(13),
                        color = if (state.tagLimitHit && note == notes.first()) Nocturne.danger else Nocturne.textSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    stringResource(if (query.isEmpty()) R.string.compose_tags_common else R.string.compose_tags_matches),
                    style = Type.body(13, FontWeight.Bold),
                    color = Nocturne.textSecondary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (canCreate) {
                    item(key = "create") {
                        SuggestionRow(
                            label = stringResource(R.string.compose_tags_create, query),
                            note = null,
                            enabled = true,
                            create = true,
                        ) { onAdd(query) }
                    }
                }
                items(state.tagSuggestions, key = { it.name }) { tag ->
                    val chosen = state.tags.any { it.equals(tag.name, ignoreCase = true) }
                    val enabled = !chosen && (!tag.staffOnly || state.canUseStaffTags)
                    SuggestionRow(
                        label = tag.label?.takeIf { it.isNotBlank() && it != tag.name }?.let { "$it · ${tag.name}" } ?: tag.name,
                        note = if (tag.staffOnly && !state.canUseStaffTags) {
                            stringResource(R.string.compose_tags_staff_only)
                        } else {
                            tag.topicCount?.toString()
                        },
                        enabled = enabled,
                        create = false,
                    ) { onAdd(tag.name) }
                }
                if (query.isNotEmpty() && state.tagSuggestions.isEmpty() && !state.tagSuggestionsLoading && !restricted) {
                    item(key = "none") {
                        Text(
                            stringResource(R.string.compose_tags_none),
                            style = Type.body(13),
                            color = Nocturne.textTertiary,
                            modifier = Modifier.padding(horizontal = Space.page, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
}

@Composable
private fun ChosenTag(tag: String, onRemove: () -> Unit) {
    val description = stringResource(R.string.compose_tags_remove, tag)
    Row(
        Modifier
            .height(30.dp)
            .clip(CircleShape)
            .background(Nocturne.neutral400)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(role = Role.Button, onClick = onRemove)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(tag, style = Type.body(13, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
        Icon(Lucide.X, null, tint = Nocturne.textSecondary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun SuggestionRow(label: String, note: String?, enabled: Boolean, create: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.page),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val ink = if (enabled) Nocturne.text else Nocturne.textTertiary
        Icon(
            if (create) Lucide.Plus else Lucide.Hash,
            null,
            tint = if (create) Nocturne.accent else Nocturne.textTertiary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            style = Type.body(15, if (create) FontWeight.SemiBold else FontWeight.Normal),
            color = if (create) Nocturne.accent else ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        note?.let { Text(it, style = Type.body(13), color = Nocturne.textTertiary, maxLines = 1) }
    }
}

/** The server's longest tag (content.MaxTagLength). */
private const val MAX_TAG_LENGTH = 20
