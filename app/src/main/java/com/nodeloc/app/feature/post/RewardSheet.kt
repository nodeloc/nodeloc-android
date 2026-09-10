package com.nodeloc.app.feature.post

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.FilledAccentButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type

/** 打赏 — discourse-reward. Amount plus an optional note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardSheet(onDismiss: () -> Unit, onConfirm: (amount: Int, note: String?) -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var amount by remember { mutableIntStateOf(10) }
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Text(stringResource(R.string.reader_reward_energy), style = Type.heading(18, FontWeight.SemiBold), color = Nocturne.text)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                listOf(5, 10, 20, 50, 100).forEach { preset ->
                    val selected = amount == preset
                    Text(
                        preset.toString(),
                        style = Type.body(14, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (selected) Nocturne.bg else Nocturne.text,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) Nocturne.accent else Nocturne.surface)
                            .clickable { amount = preset }
                            .padding(horizontal = Space.page, vertical = 8.dp),
                    )
                }
            }
            FieldSurface {
                BasicTextField(
                    value = note,
                    onValueChange = { note = it },
                    textStyle = Type.body(14).copy(color = Nocturne.text),
                    cursorBrush = SolidColor(Nocturne.accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (note.isEmpty()) {
                            Text(stringResource(R.string.reader_reward_note_hint), style = Type.body(14), color = Nocturne.muted(0.35f))
                        }
                        inner()
                    },
                )
            }
            FilledAccentButton(stringResource(R.string.reader_reward_amount, amount)) { onConfirm(amount, note.takeIf { it.isNotBlank() }) }
        }
    }
}

/** The reward ledger for a post — who gave what, and any note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardDetailSheet(
    rewards: List<com.nodeloc.app.core.model.PostReward>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text(stringResource(R.string.reader_reward_detail), style = Type.heading(18, FontWeight.SemiBold), color = Nocturne.text)
            rewards.filter { it.amount > 0 }.forEach { reward ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    com.nodeloc.app.core.design.RemoteAvatar(
                        com.nodeloc.app.core.network.DiscourseConfig.avatarUrl(reward.avatarTemplate, 80),
                        reward.username?.take(1)?.uppercase() ?: "?",
                        size = 28.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            reward.username.orEmpty(),
                            style = Type.body(13, FontWeight.Medium),
                            color = Nocturne.text,
                        )
                        reward.note?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = Type.body(12), color = Nocturne.muted(0.5f))
                        }
                    }
                    Text("+${reward.amount}", style = Type.body(13, FontWeight.SemiBold), color = Nocturne.accent2)
                }
            }
            if (rewards.none { it.amount > 0 }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                    Text(stringResource(R.string.reader_reward_empty), style = Type.body(13), color = Nocturne.muted(0.45f))
                }
            }
        }
    }
}
