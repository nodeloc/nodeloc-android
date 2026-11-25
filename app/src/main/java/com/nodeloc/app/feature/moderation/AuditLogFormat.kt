package com.nodeloc.app.feature.moderation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nodeloc.app.R
import com.nodeloc.app.core.network.ApiAuditLog
import com.nodeloc.app.core.network.ApiNodeModLog
import kotlinx.serialization.json.JsonObject

/**
 * One row of either log. The site log names its target by id and current
 * name; the node log only by username, so [targetId] is null there — which is
 * also why nothing in the node log can be undone from here.
 */
data class LogEntry(
    val id: Long,
    val actor: String?,
    val action: String,
    val targetType: String?,
    val targetId: Long?,
    val targetName: String?,
    val details: JsonObject?,
    val createdAt: String?,
)

internal fun ApiAuditLog.toEntry() = LogEntry(
    id = id,
    actor = actor,
    action = action,
    targetType = targetType,
    targetId = targetId,
    targetName = targetName.ifBlank { null },
    details = details,
    createdAt = createdAt,
)

internal fun ApiNodeModLog.toEntry() = LogEntry(
    id = id,
    actor = actor.ifBlank { null },
    action = action,
    targetType = null,
    targetId = null,
    targetName = target?.ifBlank { null },
    details = details,
    createdAt = createdAt,
)

/** What reverses an entry, per the contract's inverse table. Only the ones the app can call. */
enum class LogUndo { RevokeSilence, RevokeSuspend, RestorePost }

internal fun LogEntry.undo(): LogUndo? {
    if (targetId == null) return null
    return when (action) {
        "user.silence" -> LogUndo.RevokeSilence
        "user.suspend" -> LogUndo.RevokeSuspend
        "review.delete_post", "review.hide_post" -> LogUndo.RestorePost
        else -> null
    }
}

/**
 * The entry as a sentence: "moss 禁言了 像素君 7 天".
 *
 * An action the app does not know still gets a line — the raw action string —
 * because the log is where a moderator checks what happened, and a missing row
 * would be worse than an ugly one.
 */
@Composable
internal fun logSentence(entry: LogEntry): String {
    val actor = entry.actor ?: stringResource(R.string.mod_log_system)
    val target = entry.targetName ?: entry.details.str("username") ?: entry.details.str("slug") ?: ""
    val days = entry.details.long("days")?.toInt()
    val forever = days == 0 || entry.details.bool("forever") == true
    val title = entry.details.str("title")
    fun timed(@androidx.annotation.StringRes timed: Int, @androidx.annotation.StringRes permanent: Int): Pair<Int, Boolean> =
        if (forever || days == null) permanent to false else timed to true
    return when (entry.action) {
        "user.silence" -> timed(R.string.mod_log_silence_days, R.string.mod_log_silence_forever).let { (res, withDays) ->
            if (withDays) stringResource(res, actor, target, days ?: 0) else stringResource(res, actor, target)
        }
        "user.suspend" -> timed(R.string.mod_log_suspend_days, R.string.mod_log_suspend_forever).let { (res, withDays) ->
            if (withDays) stringResource(res, actor, target, days ?: 0) else stringResource(res, actor, target)
        }
        "restrict.mute" -> timed(R.string.mod_log_mute_days, R.string.mod_log_mute_forever).let { (res, withDays) ->
            if (withDays) stringResource(res, actor, target, days ?: 0) else stringResource(res, actor, target)
        }
        "restrict.ban" -> timed(R.string.mod_log_ban_days, R.string.mod_log_ban_forever).let { (res, withDays) ->
            if (withDays) stringResource(res, actor, target, days ?: 0) else stringResource(res, actor, target)
        }
        "user.unsilence" -> stringResource(R.string.mod_log_unsilence, actor, target)
        "user.unsuspend" -> stringResource(R.string.mod_log_unsuspend, actor, target)
        "user.update_penalty" -> stringResource(R.string.mod_log_update_penalty, actor, target)
        "user.set_role" -> stringResource(
            R.string.mod_log_set_role,
            actor,
            target,
            when (val role = entry.details.str("to")) {
                "admin" -> stringResource(R.string.mod_entry_role_admin)
                "moderator" -> stringResource(R.string.mod_entry_role_moderator)
                "user" -> stringResource(R.string.mod_log_role_user)
                else -> role.orEmpty()
            },
        )
        "user.delete", "user.auto_delete" -> stringResource(R.string.mod_log_user_delete, actor, target)
        "restrict.lift" -> stringResource(R.string.mod_log_restrict_lift, actor, target)
        "review.delete_post" -> stringResource(R.string.mod_log_review_delete, actor)
        "review.hide_post" -> stringResource(R.string.mod_log_review_hide, actor)
        "review.reject" -> stringResource(R.string.mod_log_review_reject, actor)
        "review.ignore" -> stringResource(R.string.mod_log_review_ignore, actor)
        "review.approve" -> stringResource(R.string.mod_log_review_approve, actor)
        "review.confirm" -> stringResource(R.string.mod_log_review_confirm, actor)
        "review.reopen" -> stringResource(R.string.mod_log_review_reopen, actor)
        "review.restore_post", "post.restore" -> stringResource(R.string.mod_log_restore_post, actor)
        "review.flag_link" -> stringResource(R.string.mod_log_flag_link, actor)
        "review.warn_node", "node.warn" -> stringResource(R.string.mod_log_node_warn, actor, target)
        "review.read_only_node", "node.read_only" -> stringResource(R.string.mod_log_node_read_only, actor, target)
        "review.ban_node", "node.banned" -> stringResource(R.string.mod_log_node_ban, actor, target)
        "node.lift" -> stringResource(R.string.mod_log_node_lift, actor, target)
        "node.archive" -> stringResource(R.string.mod_log_node_archive, actor, target)
        "node.unarchive" -> stringResource(R.string.mod_log_node_unarchive, actor, target)
        "post.delete" -> stringResource(R.string.mod_log_post_delete, actor)
        "topic.delete" -> stringResource(R.string.mod_log_topic_delete, actor)
        "topic.move" -> entry.details.str("to")?.let { stringResource(R.string.mod_log_topic_move_to, actor, it) }
            ?: stringResource(R.string.mod_log_topic_move, actor)
        "topic.pin", "topic.pin_until" -> stringResource(R.string.mod_log_topic_pin, actor)
        "topic.unpin" -> stringResource(R.string.mod_log_topic_unpin, actor)
        "topic.close" -> stringResource(R.string.mod_log_topic_close, actor)
        "topic.open" -> stringResource(R.string.mod_log_topic_open, actor)
        "post.lock" -> stringResource(R.string.mod_log_post_lock, actor)
        "post.unlock" -> stringResource(R.string.mod_log_post_unlock, actor)
        "post.collapse" -> stringResource(R.string.mod_log_post_collapse, actor)
        "post.expand" -> stringResource(R.string.mod_log_post_expand, actor)
        "moderator.add" -> stringResource(R.string.mod_log_moderator_add, actor, target)
        "moderator.remove" -> stringResource(R.string.mod_log_moderator_remove, actor, target)
        "member.remove" -> stringResource(R.string.mod_log_member_remove, actor, target)
        "rules.update" -> stringResource(R.string.mod_log_rules_update, actor)
        "access.update" -> stringResource(R.string.mod_log_access_update, actor)
        "risk.false_positive", "risk.lenient" -> stringResource(R.string.mod_log_appeal_accept, actor, target)
        "risk.appeal_reject" -> stringResource(R.string.mod_log_appeal_reject, actor, target)
        else -> when {
            entry.action.endsWith(".update") -> stringResource(R.string.mod_log_settings_update, actor)
            else -> listOf(actor, entry.action, target).filter { it.isNotBlank() }.joinToString(" ")
        }
    }.let { sentence -> if (title != null && entry.action.startsWith("topic.")) "$sentence · $title" else sentence }
}

/** The grey line under a sentence: node, reason or note, then the time. */
@Composable
internal fun logMeta(entry: LogEntry, time: String): String {
    val details = entry.details
    val node = details.str("node") ?: details.str("slug")?.takeIf { entry.action.startsWith("node.").not() }
    val from = details.str("from")?.takeIf { entry.action == "topic.move" }
    val reason = details.str("reason")?.let { code ->
        // Report and move reasons arrive as codes; penalty reasons as free text.
        when (code) {
            "spam", "harassment", "off_topic", "copyright", "inappropriate", "illegal" -> stringResource(reportReasonLabel(code))
            else -> code
        }
    } ?: details.str("note")
    return listOfNotNull(
        node?.let { stringResource(R.string.mod_dash_node_slug, it) },
        from?.let { stringResource(R.string.mod_log_from_node, it) },
        reason?.take(80),
        time.ifEmpty { null },
    ).joinToString(" · ")
}
