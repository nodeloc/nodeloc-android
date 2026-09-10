package com.nodeloc.app.feature.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.FilledAccentButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.util.DiscourseFormat

/**
 * Red envelope setup.
 *
 * The plugin has no markdown form: these values are staged here and posted as a
 * second request once the topic id exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedEnvelopeSheet(
    initial: RedEnvelopeDraft,
    onDismiss: () -> Unit,
    onConfirm: (RedEnvelopeDraft) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var points by remember { mutableStateOf(initial.totalPoints.toString()) }
    var count by remember { mutableStateOf(initial.totalCount.toString()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Text(
                stringResource(R.string.compose_envelope),
                style = Type.heading(18, FontWeight.SemiBold),
                color = Nocturne.text,
            )
            NumberField(stringResource(R.string.envelope_total_points), points) { points = it }
            NumberField(stringResource(R.string.envelope_total_count), count) { count = it }
            FilledAccentButton(stringResource(R.string.common_confirm)) {
                onConfirm(
                    RedEnvelopeDraft(
                        totalPoints = points.toIntOrNull()?.coerceAtLeast(1) ?: initial.totalPoints,
                        totalCount = count.toIntOrNull()?.coerceAtLeast(1) ?: initial.totalCount,
                    ),
                )
            }
        }
    }
}

/**
 * Lottery setup, carrying the same fields `POST /lottery` reads.
 *
 * The draw is entered as a number of days rather than a calendar time: the
 * server wants an absolute `draw_at`, which this converts at submit, and a day
 * count is a control a thumb can use. The resulting moment is spelled out
 * under the field so nothing is left implied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotterySheet(
    initial: LotteryDraft,
    onDismiss: () -> Unit,
    onConfirm: (LotteryDraft) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val defaultLevelName = stringResource(R.string.lottery_default_level)
    var title by remember { mutableStateOf(initial.title) }
    var levels by remember {
        mutableStateOf(initial.levels.ifEmpty { listOf(LotteryLevelDraft(name = defaultLevelName)) })
    }
    var minParticipants by remember { mutableStateOf(initial.minParticipants.toString()) }
    var maxParticipants by remember {
        mutableStateOf(initial.maxParticipants.takeIf { it > 0 }?.toString().orEmpty())
    }
    var minTickets by remember { mutableStateOf(initial.minTicketsPerUser.toString()) }
    var maxTickets by remember { mutableStateOf(initial.maxTicketsPerUser.toString()) }
    var trustLevel by remember { mutableStateOf(initial.minTrustLevel.toString()) }
    var drawDays by remember { mutableStateOf(initial.drawDays.toString()) }

    val draft = LotteryDraft(
        title = title.trim(),
        minParticipants = minParticipants.toIntOrNull() ?: 0,
        maxParticipants = maxParticipants.toIntOrNull() ?: 0,
        minTicketsPerUser = minTickets.toIntOrNull() ?: 0,
        maxTicketsPerUser = maxTickets.toIntOrNull() ?: 0,
        minTrustLevel = trustLevel.toIntOrNull()?.coerceIn(0, 4) ?: 0,
        drawDays = drawDays.toIntOrNull() ?: 0,
        levels = levels.map { it.copy(name = it.name.ifBlank { defaultLevelName }) },
    )
    val problem = draft.error

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.page)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text(
                stringResource(R.string.lottery_title),
                style = Type.heading(18, FontWeight.SemiBold),
                color = Nocturne.text,
            )
            TextField(stringResource(R.string.lottery_field_title), title) { title = it }

            Text(
                stringResource(R.string.lottery_field_levels),
                style = Type.body(13, FontWeight.Medium),
                color = Nocturne.muted(0.6f),
            )
            levels.forEachIndexed { index, level ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        Column(Modifier.weight(1.4f)) {
                            TextField(stringResource(R.string.lottery_level_name_hint), level.name) { value ->
                                levels = levels.mapIndexed { i, l -> if (i == index) l.copy(name = value) else l }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            NumberField(stringResource(R.string.lottery_field_quantity), level.quantity.toString()) { value ->
                                levels = levels.mapIndexed { i, l ->
                                    if (i == index) l.copy(quantity = value.toIntOrNull()?.coerceAtLeast(1) ?: 1) else l
                                }
                            }
                        }
                    }
                    TextField(stringResource(R.string.lottery_field_prize), level.prize) { value ->
                        levels = levels.mapIndexed { i, l -> if (i == index) l.copy(prize = value) else l }
                    }
                    // The last tier keeps no remove button: a lottery with no
                    // prize is not a lottery.
                    if (levels.size > 1) {
                        Text(
                            stringResource(R.string.lottery_remove_level),
                            style = Type.body(12),
                            color = Nocturne.danger,
                            modifier = Modifier
                                .clickable { levels = levels.filterIndexed { i, _ -> i != index } }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.lottery_add_level),
                style = Type.body(13, FontWeight.Medium),
                color = Nocturne.accent,
                modifier = Modifier
                    .clickable { levels = levels + LotteryLevelDraft(name = defaultLevelName) }
                    .padding(vertical = 4.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Column(Modifier.weight(1f)) {
                    NumberField(stringResource(R.string.lottery_field_min), minParticipants) { minParticipants = it }
                }
                Column(Modifier.weight(1f)) {
                    NumberField(stringResource(R.string.lottery_field_max_participants), maxParticipants) { maxParticipants = it }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Column(Modifier.weight(1f)) {
                    NumberField(stringResource(R.string.lottery_field_min_tickets), minTickets) { minTickets = it }
                }
                Column(Modifier.weight(1f)) {
                    NumberField(stringResource(R.string.lottery_field_max_tickets), maxTickets) { maxTickets = it }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Column(Modifier.weight(1f)) {
                    NumberField(stringResource(R.string.lottery_field_trust_level), trustLevel) { trustLevel = it }
                }
                Column(Modifier.weight(1f)) {
                    NumberField(stringResource(R.string.lottery_field_draw_days), drawDays) { drawDays = it }
                }
            }
            draft.drawDays.takeIf { it in 1..LOTTERY_MAX_DRAW_DAYS }?.let { days ->
                Text(
                    stringResource(R.string.lottery_draws_at, DiscourseFormat.drawMoment(days)),
                    style = Type.body(12),
                    color = Nocturne.muted(0.45f),
                )
            }

            problem?.let {
                Text(lotteryErrorText(it), style = Type.body(13), color = Nocturne.danger)
            }
            FilledAccentButton(stringResource(R.string.common_confirm), enabled = problem == null) {
                onConfirm(draft)
            }
        }
    }
}

@Composable
private fun lotteryErrorText(error: LotteryDraftError): String = when (error) {
    LotteryDraftError.Title -> stringResource(R.string.lottery_error_title)
    LotteryDraftError.Prize -> stringResource(R.string.lottery_error_prize)
    LotteryDraftError.MinParticipants -> stringResource(R.string.lottery_error_min_participants)
    LotteryDraftError.MaxBelowMin -> stringResource(R.string.lottery_error_max_below_min)
    LotteryDraftError.MinTicketsAboveMax -> stringResource(R.string.lottery_error_min_tickets)
    LotteryDraftError.DrawDays -> stringResource(R.string.lottery_error_draw_days, LOTTERY_MAX_DRAW_DAYS)
}

@Composable
private fun TextField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Type.body(12), color = Nocturne.muted(0.55f))
        FieldSurface {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Type.body(15).copy(color = Nocturne.text),
                cursorBrush = SolidColor(Nocturne.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Type.body(12), color = Nocturne.muted(0.55f))
        FieldSurface {
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.filter(Char::isDigit).take(7)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = Type.body(15).copy(color = Nocturne.text),
                cursorBrush = SolidColor(Nocturne.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
