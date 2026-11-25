package com.nodeloc.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.network.friendlyMessage

/** The server's limit on a transfer's note (points/transfer.go `maxMemoLength`). */
private const val MEMO_MAX = 200

private val QUICK_AMOUNTS = listOf(10L, 50L, 100L)

/**
 * 转赠: who, how much, an optional note, then a confirm step that spells out
 * what leaves the balance — the fee is paid on top, so the amount typed is
 * not what is taken, and a transfer cannot be taken back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSheet(viewModel: EnergyViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var to by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val doneAtOpen = remember { state.transfer.doneCount }

    LaunchedEffect(state.transfer.doneCount) {
        if (state.transfer.doneCount != doneAtOpen) onDismiss()
    }

    val rule = state.transferRule
    val balance = state.balance ?: 0L
    val target = to.trim().removePrefix("u/").removePrefix("U/").trim()
    val amount = amountText.trim().toLongOrNull()?.takeIf { it > 0 }
    val fee = if (amount != null && rule != null) rule.feeOf(amount) else 0L

    val toError: String? = when {
        target.isEmpty() -> stringResource(R.string.energy_tf_err_to)
        target.equals(viewModel.me, ignoreCase = true) -> stringResource(R.string.energy_tf_err_self)
        else -> null
    }
    val amountError: String? = when {
        amount == null -> stringResource(R.string.energy_tf_err_amount)
        rule != null && (amount < rule.min || amount > rule.max) ->
            stringResource(R.string.energy_tf_err_range, formatCount(rule.min), formatCount(rule.max))
        amount + fee > balance -> stringResource(
            if (fee > 0) R.string.energy_tf_err_balance_fee else R.string.energy_tf_err_balance,
            formatCount(balance),
        )
        else -> null
    }
    // A tier that may not transfer at all is served max 0; say so up front
    // instead of letting every amount fail the range check.
    val closed = rule != null && rule.max <= 0

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.energy_tf_title), style = Type.heading(20, FontWeight.Bold), color = Nocturne.text)
            Text(
                when {
                    rule == null -> stringResource(R.string.energy_tf_rule_unknown, formatCount(balance))
                    closed -> stringResource(R.string.energy_tf_closed)
                    rule.feePercent > 0 -> stringResource(
                        R.string.energy_tf_rule_fee,
                        "${rule.feePercent}%",
                        formatCount(rule.min),
                        formatCount(rule.max),
                        formatCount(balance),
                    )
                    else -> stringResource(R.string.energy_tf_rule_free, formatCount(rule.min), formatCount(rule.max), formatCount(balance))
                },
                style = Type.body(14),
                color = Nocturne.textSecondary,
            )

            Field(stringResource(R.string.energy_tf_to), error = toError.takeIf { tried }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("u/", style = Type.body(15), color = Nocturne.textSecondary)
                    TextInput(to, { to = it; viewModel.clearTransferError() }, stringResource(R.string.energy_tf_to_hint), KeyboardType.Ascii)
                }
            }

            Field(stringResource(R.string.energy_tf_amount), error = amountError.takeIf { tried }) {
                TextInput(
                    amountText,
                    { raw -> amountText = raw.filter(Char::isDigit).take(12); viewModel.clearTransferError() },
                    null,
                    KeyboardType.Number,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK_AMOUNTS.filter { rule == null || (it >= rule.min && it <= rule.max) }.forEach { n ->
                    QuickAmount(n, selected = amount == n) { amountText = n.toString(); viewModel.clearTransferError() }
                }
            }
            if (amount != null && fee > 0) {
                Text(
                    stringResource(R.string.energy_tf_fee_line, formatCount(amount), formatCount(fee), formatCount(amount + fee)),
                    style = Type.body(13),
                    color = Nocturne.textSecondary,
                )
            }

            Field(stringResource(R.string.energy_tf_memo), error = null, hint = stringResource(R.string.energy_tf_optional)) {
                TextInput(memo, { memo = it.take(MEMO_MAX) }, stringResource(R.string.energy_tf_memo_hint), KeyboardType.Text, singleLine = false)
            }

            state.transfer.error?.let { error ->
                val context = LocalContext.current
                val message = remember(error) { error.friendlyMessage(context) }
                Text(
                    message,
                    style = Type.body(13),
                    color = Nocturne.danger,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Nocturne.dangerBg).padding(10.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.common_cancel), modifier = Modifier.weight(1f), block = true, onClick = onDismiss)
                PrimaryButton(
                    if (amount != null) stringResource(R.string.energy_tf_submit_n, formatCount(amount)) else stringResource(R.string.energy_transfer),
                    modifier = Modifier.weight(1f),
                    block = true,
                    enabled = !closed && !state.transfer.isSending,
                ) {
                    tried = true
                    if (toError == null && amountError == null) confirming = true
                }
            }
        }
    }

    if (confirming && amount != null) {
        AlertDialog(
            onDismissRequest = { if (!state.transfer.isSending) confirming = false },
            containerColor = Nocturne.bg,
            title = { Text(stringResource(R.string.energy_tf_confirm_title), style = Type.heading(17), color = Nocturne.text) },
            text = {
                Text(
                    stringResource(R.string.energy_tf_confirm_body, target, formatCount(amount), formatCount(amount + fee)),
                    style = Type.body(14),
                    color = Nocturne.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.transfer.isSending,
                    onClick = {
                        confirming = false
                        viewModel.transfer(target, amount, memo.trim())
                    },
                ) { Text(stringResource(R.string.energy_tf_confirm), color = Nocturne.accent, style = Type.body(14, FontWeight.Bold)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.common_cancel), color = Nocturne.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun Field(label: String, error: String?, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = Type.body(13, FontWeight.SemiBold), color = Nocturne.text)
            hint?.let { Text(it, style = Type.body(13), color = Nocturne.textTertiary) }
        }
        FieldSurface { content() }
        error?.let { Text(it, style = Type.body(12), color = Nocturne.danger) }
    }
}

@Composable
private fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    hint: String?,
    keyboard: KeyboardType,
    singleLine: Boolean = true,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 4,
        textStyle = Type.body(15).copy(color = Nocturne.text),
        cursorBrush = SolidColor(Nocturne.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && hint != null) Text(hint, style = Type.body(15), color = Nocturne.textTertiary)
                inner()
            }
        },
    )
}

@Composable
private fun QuickAmount(value: Long, selected: Boolean, onClick: () -> Unit) {
    val ground by animatedColor(if (selected) Nocturne.accent100 else Nocturne.neutral300, "quickGround")
    val ink by animatedColor(if (selected) Nocturne.accent700 else Nocturne.text, "quickInk")
    val press = rememberPressSource()
    Box(
        Modifier
            .pressScale(press)
            .height(36.dp)
            .clip(CircleShape)
            .background(ground)
            .semantics { this.selected = selected }
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(formatCount(value), style = Type.body(14, FontWeight.SemiBold), color = ink)
    }
}
