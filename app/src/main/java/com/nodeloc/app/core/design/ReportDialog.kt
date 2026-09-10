package com.nodeloc.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.model.PostActionType
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.launch

/**
 * Discourse's own flag, in the app.
 *
 * The reasons are the site's, fetched with everything else in `site.json` and
 * shown in its wording: this deployment has added two of its own to the seven
 * that ship with Discourse, and a list written out here would have been wrong
 * the day a moderator edited one. Some reasons will not go without a note —
 * `require_message` — which is the server's rule and not a guess made here.
 *
 * What it posts is what the web's flag dialog posts: `post_actions` with the
 * reason's own id.
 */
@Composable
fun ReportDialog(
    /**
     * What is being reported.
     *
     * Chat and posts share the reasons and the dialog andin nothing a reader
     * would notice; they differ only in which endpoint takes them and what
     * that endpoint calls the reason's id.
     */
    target: ReportTarget,
    /**
     * Whoever wrote it. One reason is addressed *to* them — the server sends
     * its name with the interpolation still in it, `@%{username}`, and it is
     * the client's job to fill it in.
     */
    authorName: String? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reasons by remember { mutableStateOf<List<PostActionType>>(emptyList()) }
    var chosen by remember { mutableStateOf<PostActionType?>(null) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(target) {
        val site = ServiceLocator.get.siteRepository
        site.siteResponse()
        // A reason naming somebody the app cannot name is dropped rather than
        // shown with a hole in it.
        reasons = site.postFlagTypes().filter { it.title(authorName) != null }
    }

    val ready = chosen != null && !sending && (chosen?.requireMessage != true || note.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.bg,
        title = { Text(stringResource(R.string.report_title), style = Type.heading(17), color = Nocturne.text) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                reasons.forEach { reason ->
                    ReportReason(reason, authorName, reason == chosen) {
                        chosen = reason
                        // Only some reasons carry a note; keeping the last one
                        // typed would attach it to a reason that never asked.
                        if (!reason.requireMessage) note = ""
                    }
                }
                if (chosen?.requireMessage == true) {
                    ReportNote(note) { note = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    val reason = chosen ?: return@TextButton
                    sending = true
                    scope.launch {
                        runCatchingCancellable {
                            val client = ServiceLocator.get.client
                            val text = note.takeIf { it.isNotBlank() }
                            when (target) {
                                is ReportTarget.Post -> client.flagPost(target.postId, reason.id, text)
                                is ReportTarget.ChatMessage ->
                                    client.flagChatMessage(target.channelId, target.messageId, reason.id, text)
                            }
                        }
                            .onSuccess {
                                ToastCenter.show(R.string.report_sent)
                                onDismiss()
                            }
                            .onFailure {
                                sending = false
                                ToastCenter.showError(it)
                            }
                    }
                },
            ) {
                Text(
                    stringResource(R.string.reader_report),
                    color = if (ready) Nocturne.danger else Nocturne.muted(0.3f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = Nocturne.muted(0.6f))
            }
        },
    )
}

@Composable
private fun ReportReason(
    reason: PostActionType,
    authorName: String?,
    selected: Boolean,
    onPick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(if (selected) Nocturne.selected else Nocturne.surface)
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            reason.title(authorName).orEmpty(),
            style = Type.body(14, if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) Nocturne.accent else Nocturne.text,
        )
        reason.blurb()?.let {
            Text(it, style = Type.body(12), color = Nocturne.muted(0.55f))
        }
    }
}

@Composable
private fun ReportNote(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = Type.body(14).copy(color = Nocturne.text),
        cursorBrush = SolidColor(Nocturne.accent),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(Radius.md))
            .border(1.dp, Nocturne.divider, RoundedCornerShape(Radius.md))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(
                    stringResource(R.string.report_note_hint),
                    style = Type.body(14),
                    color = Nocturne.muted(0.32f),
                )
            }
            inner()
        },
    )
}

/**
 * The line under the reason's name.
 *
 * The server's descriptions carry markup — two of them link the guidelines —
 * and this is one line in a dialog, not a post: the tags come out rather than a
 * parser going in.
 */
private fun PostActionType.blurb(): String? {
    val raw = shortDescription?.takeIf { it.isNotBlank() } ?: description
    return raw
        ?.replace(TAG, "")
        ?.replace("&quot;", "\"")
        ?.replace("&#39;", "'")
        ?.replace("&lt;", "<")
        ?.replace("&gt;", ">")
        ?.replace("&amp;", "&")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private val TAG = Regex("<[^>]+>")

/**
 * The reason's name with `@%{username}` filled in, or null where it names
 * somebody this screen does not know.
 */
private fun PostActionType.title(author: String?): String? {
    if (!name.contains(PLACEHOLDER)) return name
    val who = author?.takeIf { it.isNotBlank() } ?: return null
    return name.replace(PLACEHOLDER, who)
}

private const val PLACEHOLDER = "%{username}"

/** What a report is about. */
sealed interface ReportTarget {
    data class Post(val postId: Int) : ReportTarget
    data class ChatMessage(val channelId: Int, val messageId: Int) : ReportTarget
}
