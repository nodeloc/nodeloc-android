package com.nodeloc.app.feature.post

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ConfirmDialog
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.SkeletonLine
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.ApiRevisions
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.TextDiff
import com.nodeloc.app.core.util.rethrowIfCancellation
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.launch

/** One state of the post: v1 is the original, every revision adds the next. */
private data class PostVersion(
    val number: Int,
    val raw: String,
    val title: String?,
    val editor: String,
    val createdAt: String?,
    val reason: String,
    val byAuthor: Boolean,
    /** The version before this one; null for v1. */
    val previousRaw: String?,
    val previousTitle: String?,
)

/**
 * The server lists revisions as before/after pairs, oldest first, and the
 * original is not an entry of its own — it is the first revision's "before".
 */
private fun versionsOf(data: ApiRevisions): List<PostVersion> {
    val first = data.revisions.firstOrNull()
    val out = mutableListOf(
        PostVersion(
            number = 1,
            raw = first?.raw?.getOrNull(0).orEmpty(),
            title = first?.title?.getOrNull(0),
            editor = data.author,
            createdAt = data.postedAt,
            reason = "",
            byAuthor = true,
            previousRaw = null,
            previousTitle = null,
        ),
    )
    data.revisions.forEach { revision ->
        val previous = out.last()
        out += PostVersion(
            number = revision.number.takeIf { it > 0 } ?: (previous.number + 1),
            raw = revision.raw.getOrNull(1) ?: previous.raw,
            title = revision.title?.getOrNull(1) ?: previous.title,
            editor = revision.editor,
            createdAt = revision.createdAt,
            reason = revision.reason,
            byAuthor = revision.byAuthor,
            previousRaw = revision.raw.getOrNull(0) ?: previous.raw,
            previousTitle = revision.title?.getOrNull(0) ?: previous.title,
        )
    }
    return out
}

/**
 * 编辑历史 (design: Android · 日常 5). Visible to the author, the node's
 * moderators and staff only — the server answers 403 to anyone else, and the
 * entry point is only shown to them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditHistorySheet(postId: Int, onDismiss: () -> Unit, onRolledBack: () -> Unit) {
    val api = ServiceLocator.get.moderationApi
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<ApiRevisions?>(null) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var compare by remember { mutableStateOf(true) }
    var confirmRollback by remember { mutableStateOf(false) }
    var rollingBack by remember { mutableStateOf(false) }

    LaunchedEffect(postId) {
        runCatchingCancellable { api.revisions(postId.toLong()) }
            .onSuccess { data = it }
            .onFailure { error ->
                failed = true
                ToastCenter.showError(error)
            }
    }

    val versions = remember(data) { data?.let(::versionsOf).orEmpty() }
    val current = versions.lastOrNull()
    val shown = versions.firstOrNull { it.number == selected } ?: current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Nocturne.surface,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.history_title),
                style = Type.heading(22, FontWeight.Normal),
                color = Nocturne.text,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
            when {
                failed -> Text(
                    stringResource(R.string.history_unavailable),
                    style = Type.body(14),
                    color = Nocturne.textSecondary,
                    modifier = Modifier.padding(24.dp),
                )
                data == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonLine(widthFraction = 0.6f)
                    SkeletonLine()
                    SkeletonLine(widthFraction = 0.8f)
                }
                shown != null -> {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        versions.asReversed().forEach { version ->
                            VersionCard(version, isCurrent = version == current, selected = version == shown) {
                                selected = version.number
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (shown.previousRaw != null) {
                        ModeToggle(compare) { compare = it }
                        Spacer(Modifier.height(12.dp))
                    }
                    VersionBody(shown, compare = compare && shown.previousRaw != null)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LegendSwatch(Nocturne.accent100, stringResource(R.string.history_added))
                        LegendSwatch(Nocturne.dangerBg, stringResource(R.string.history_removed))
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.history_visibility), style = Type.body(12), color = Nocturne.textSecondary)
                    }
                    if (data?.canRestore == true && shown != current) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { confirmRollback = true },
                            enabled = !rollingBack,
                            border = BorderStroke(1.dp, Nocturne.textTertiary),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(40.dp),
                        ) {
                            if (rollingBack) NodelocLoader(height = 16.dp)
                            else Text(
                                stringResource(R.string.history_rollback, shown.number),
                                style = Type.body(14, FontWeight.Medium),
                                color = Nocturne.accent,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmRollback && shown != null) {
        ConfirmDialog(
            title = stringResource(R.string.history_rollback, shown.number),
            body = stringResource(R.string.history_rollback_body),
            confirmLabel = stringResource(R.string.history_rollback_confirm),
            onConfirm = {
                confirmRollback = false
                rollingBack = true
                val reason = ServiceLocator.get.appContext.getString(R.string.history_rollback, shown.number)
                scope.launch {
                    try {
                        // Only the opening post has a title, and sending one for a
                        // reply would be refused.
                        api.rollback(postId.toLong(), shown.raw, shown.title.takeIf { current?.title != null }, reason)
                        ToastCenter.show(R.string.history_rolled_back)
                        onRolledBack()
                        onDismiss()
                    } catch (error: Throwable) {
                        error.rethrowIfCancellation()
                        ToastCenter.showError(error)
                    } finally {
                        rollingBack = false
                    }
                }
            },
            onDismiss = { confirmRollback = false },
        )
    }
}

@Composable
private fun VersionCard(version: PostVersion, isCurrent: Boolean, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .defaultMinSize(minWidth = 104.dp)
            .clip(shape)
            .background(if (selected) Nocturne.accent100 else Nocturne.bg)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Nocturne.accent300 else Nocturne.divider, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "v${version.number}",
                style = Type.body(14, FontWeight.Bold),
                color = if (selected) Nocturne.accent700 else Nocturne.text,
            )
            when {
                isCurrent -> StatusPill(stringResource(R.string.history_current), PillTone.Accent)
                !version.byAuthor -> StatusPill(stringResource(R.string.mod_entry_role_moderator), PillTone.Info)
            }
        }
        Text(
            "${version.editor} · ${DiscourseFormat.relative(version.createdAt)}",
            style = Type.body(12),
            color = Nocturne.textSecondary,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            summaryOf(version),
            style = Type.body(12),
            color = Nocturne.textTertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun summaryOf(version: PostVersion): String {
    val before = version.previousRaw ?: return stringResource(R.string.history_original)
    if (version.reason.isNotBlank()) return version.reason
    var added = 0
    var removed = 0
    TextDiff.chars(before, version.raw).forEach {
        when (it.op) {
            TextDiff.Op.Added -> added += it.text.length
            TextDiff.Op.Removed -> removed += it.text.length
            TextDiff.Op.Same -> Unit
        }
    }
    if (added == 0 && removed == 0) return stringResource(R.string.history_title_only)
    return stringResource(R.string.history_char_delta, added, removed)
}

@Composable
private fun ModeToggle(compare: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(40.dp)) {
        SegmentHalf(stringResource(R.string.history_compare), compare, RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)) { onChange(true) }
        SegmentHalf(stringResource(R.string.history_full), !compare, RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)) { onChange(false) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SegmentHalf(
    label: String,
    selected: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(if (selected) Nocturne.accent200 else Nocturne.surface)
            .border(1.dp, Nocturne.textTertiary, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.body(14, FontWeight.Medium), color = if (selected) Nocturne.accent800 else Nocturne.text)
    }
}

@Composable
private fun VersionBody(version: PostVersion, compare: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Nocturne.bg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            listOf("v${version.number}", version.editor, DiscourseFormat.relative(version.createdAt), version.reason)
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = Type.body(12),
            color = Nocturne.textSecondary,
        )
        val title = version.title
        if (title != null) {
            val before = version.previousTitle
            Text(
                if (compare && before != null && before != title) diffText(TextDiff.chars(before, title))
                else buildAnnotatedString { append(title) },
                style = Type.body(19, FontWeight.SemiBold),
                color = Nocturne.text,
            )
        }
        val before = version.previousRaw
        if (!compare || before == null) {
            Text(version.raw, style = Type.body(15), color = Nocturne.text)
        } else {
            TextDiff.paragraphs(before, version.raw).forEach { block ->
                when (block) {
                    is TextDiff.Block.Unchanged -> Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        Box(Modifier.width(2.dp).fillMaxWidth().background(Nocturne.neutral400))
                        Text(
                            stringResource(R.string.history_unchanged, block.count),
                            style = Type.body(12),
                            color = Nocturne.textTertiary,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    is TextDiff.Block.Changed -> Text(diffText(block.spans), style = Type.body(15), color = Nocturne.text)
                }
            }
        }
    }
}

@Composable
private fun diffText(spans: List<TextDiff.Span>) = buildAnnotatedString {
    val added = SpanStyle(background = Nocturne.accent100, color = Nocturne.accent700)
    val removed = SpanStyle(background = Nocturne.dangerBg, color = Nocturne.danger, textDecoration = TextDecoration.LineThrough)
    spans.forEach { span ->
        when (span.op) {
            TextDiff.Op.Same -> append(span.text)
            TextDiff.Op.Added -> withStyle(added) { append(span.text) }
            TextDiff.Op.Removed -> withStyle(removed) { append(span.text) }
        }
    }
}

@Composable
private fun LegendSwatch(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = Type.body(12), color = Nocturne.textSecondary)
    }
}

