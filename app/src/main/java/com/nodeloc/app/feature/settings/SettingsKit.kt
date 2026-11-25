package com.nodeloc.app.feature.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.nodeloc.app.R
import com.nodeloc.app.core.design.LocalReduceMotion
import com.nodeloc.app.core.design.Motion
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource

/*
 * The 2026-11 settings page's own row family. Not the grouped-card rows in
 * core/design/SettingsRows.kt: this board is flat — headed sections of
 * 60-high rows on hairlines, with the control on the right — and the other
 * pages that still use the cards would change shape if those were restyled.
 */

/** A section heading: 18 bold, with the board's 28 above. */
@Composable
internal fun SetHeading(text: String, modifier: Modifier = Modifier, top: Int = 28) {
    Text(
        text,
        style = Type.heading(18, FontWeight.Bold),
        color = Nocturne.text,
        modifier = modifier.padding(top = top.dp, bottom = 4.dp),
    )
}

/** Heading plus a line explaining it, as the notification tab opens each part. */
@Composable
internal fun SetIntro(title: String, detail: String, top: Int = 32) {
    Column(Modifier.padding(top = top.dp)) {
        Text(title, style = Type.heading(18, FontWeight.Bold), color = Nocturne.text)
        Text(detail, style = Type.body(13), color = Nocturne.textSecondary, modifier = Modifier.padding(top = 2.dp))
    }
}

/** One setting: label and its explanation on the left, the control on the right, a hairline under it. */
@Composable
internal fun SetRow(
    label: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    labelColor: Color = Nocturne.text,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 6.dp)
                .alpha(if (dim) 0.5f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = Type.body(15, FontWeight.Medium), color = labelColor)
                if (!desc.isNullOrEmpty()) {
                    Text(desc, style = Type.body(13), color = Nocturne.textSecondary)
                }
            }
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral400))
    }
}

/** The current value as a quiet button with a chevron: tapping it edits the value. */
@Composable
internal fun SetValueButton(text: String, onClick: () -> Unit) {
    val press = rememberPressSource()
    Row(
        Modifier
            .pressScale(press)
            .widthIn(max = 200.dp)
            .height(44.dp)
            .clip(CircleShape)
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text,
            style = Type.body(14),
            color = Nocturne.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Lucide.ChevronRight, null, tint = Nocturne.textSecondary, modifier = Modifier.size(18.dp))
    }
}

enum class SetButtonTone { Outline, Primary, Danger, Soft, Plain, PlainDanger }

/**
 * The board's row buttons. 44 high in rows ([compact] false), 36 inside the
 * notification channel cards.
 */
@Composable
internal fun SetButton(
    text: String,
    tone: SetButtonTone = SetButtonTone.Outline,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val press = rememberPressSource()
    val ground = when (tone) {
        SetButtonTone.Primary -> Nocturne.accent
        SetButtonTone.Soft -> Nocturne.neutral400
        else -> Color.Transparent
    }
    val ink = when (tone) {
        SetButtonTone.Primary -> if (Nocturne.isDark) Nocturne.neutral900 else Nocturne.neutral100
        SetButtonTone.Danger, SetButtonTone.PlainDanger -> Nocturne.danger
        else -> Nocturne.text
    }
    val border = when (tone) {
        SetButtonTone.Outline -> Nocturne.textTertiary
        SetButtonTone.Danger -> Nocturne.danger
        else -> null
    }
    Box(
        Modifier
            .pressScale(press)
            .height(if (compact) 36.dp else 44.dp)
            .clip(CircleShape)
            .background(ground)
            .then(if (border != null) Modifier.border(BorderStroke(1.dp, border), CircleShape) else Modifier)
            .clickable(interactionSource = press, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 16.dp)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body(if (compact) 13 else 14, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

/**
 * The board's switch: a 52×32 track inside a 56×44 target. Material's own
 * Switch is a different shape (thumb grows on press, icon slot) and would sit
 * beside these rows as the one control drawn from another kit.
 */
@Composable
internal fun SetSwitch(
    checked: Boolean,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val track by animatedColor(if (checked) Nocturne.accent else Nocturne.textTertiary, "switchTrack")
    val knob by animateDpAsState(
        if (checked) 20.dp else 0.dp,
        if (LocalReduceMotion.current) tween(0) else tween(Motion.COLOR_MILLIS),
        label = "switchKnob",
    )
    val on = stringResource(R.string.settings2_on)
    val off = stringResource(R.string.settings2_off)
    Box(
        Modifier
            .size(width = 56.dp, height = 44.dp)
            .clickable(role = Role.Switch, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label; stateDescription = if (checked) on else off }
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(CircleShape)
                .background(track),
        ) {
            Box(
                Modifier
                    .padding(4.dp)
                    .offset(x = knob)
                    .size(24.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/** A segmented choice: a grey capsule holding the options, the chosen one in ink. */
@Composable
internal fun <T> SetSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Nocturne.neutral400)
            .padding(3.dp)
            .alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val chosen = value == selected
            val ground by animatedColor(if (chosen) Nocturne.text else Color.Transparent, "segGround")
            val ink by animatedColor(if (chosen) Nocturne.bg else Nocturne.text, "segInk")
            val press = rememberPressSource()
            Box(
                Modifier
                    .pressScale(press)
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(ground)
                    .semantics { this.selected = chosen }
                    .clickable(interactionSource = press, indication = ripple(), enabled = enabled, role = Role.RadioButton) {
                        if (!chosen) onSelect(value)
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.body(13, FontWeight.Bold), color = ink, maxLines = 1)
            }
        }
    }
}

/** The board's tick box, 24 square with 7 corners, inside a 58×44 cell. */
@Composable
internal fun SetCheckCell(
    checked: Boolean,
    available: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val ground by animatedColor(
        when {
            !available -> Nocturne.neutral400
            checked -> Nocturne.accent
            else -> Nocturne.bg
        },
        "cellGround",
    )
    val edge by animatedColor(
        when {
            !available -> Nocturne.neutral400
            checked -> Nocturne.accent
            else -> Nocturne.textTertiary
        },
        "cellEdge",
    )
    val on = stringResource(R.string.settings2_on)
    val off = stringResource(R.string.settings2_off)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp)
    Box(
        Modifier
            .size(width = 58.dp, height = 44.dp)
            .clickable(role = Role.Checkbox, onClickLabel = label, onClick = onClick)
            .semantics { stateDescription = if (checked && available) on else off },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(shape)
                .background(ground)
                .border(BorderStroke(2.dp, edge), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked && available) {
                Icon(Lucide.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Options for a value too long for a segmented control, chosen from a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SetChoiceButton(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SetValueButton(options.firstOrNull { it.first == selected }?.second.orEmpty()) { open = true }
    if (!open) return
    ModalBottomSheet(
        onDismissRequest = { open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Nocturne.bg,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(
                title,
                style = Type.heading(17, FontWeight.Bold),
                color = Nocturne.text,
                modifier = Modifier.padding(horizontal = Space.page, vertical = 12.dp),
            )
            options.forEach { (value, label) ->
                val chosen = value == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .semantics { this.selected = chosen }
                        .clickable(role = Role.RadioButton) {
                            open = false
                            if (!chosen) onSelect(value)
                        }
                        .padding(horizontal = Space.page),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = Type.body(16, if (chosen) FontWeight.Bold else FontWeight.Normal),
                        color = if (chosen) Nocturne.accent else Nocturne.text,
                        modifier = Modifier.weight(1f),
                    )
                    if (chosen) Icon(Lucide.Check, null, tint = Nocturne.accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** One input in a [SetFormDialog]. */
internal data class SetField(
    val label: String,
    val initial: String = "",
    val password: Boolean = false,
    val keyboard: KeyboardType = KeyboardType.Text,
    val multiline: Boolean = false,
    val maxLength: Int? = null,
    val required: Boolean = true,
)

/**
 * The web page's `ask()`: a Material dialog with a line of explanation and a
 * few fields, confirmed only once every required one is filled.
 */
@Composable
internal fun SetFormDialog(
    title: String,
    fields: List<SetField>,
    confirmLabel: String,
    onDismiss: () -> Unit,
    detail: String? = null,
    danger: Boolean = false,
    onConfirm: (List<String>) -> Unit,
) {
    val values = remember { mutableStateListOf(*fields.map { it.initial }.toTypedArray()) }
    val ready = fields.indices.all { i ->
        val field = fields[i]
        val value = values[i]
        (!field.required || value.isNotBlank()) && (field.maxLength == null || value.codePointCount(0, value.length) <= field.maxLength)
    }
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
        title = { Text(title, style = Type.heading(20), color = Nocturne.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (detail != null) Text(detail, style = Type.body(14), color = Nocturne.textSecondary)
                fields.forEachIndexed { i, field ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { values[i] = it },
                        label = { Text(field.label) },
                        singleLine = !field.multiline,
                        minLines = if (field.multiline) 3 else 1,
                        maxLines = if (field.multiline) 6 else 1,
                        visualTransformation = if (field.password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (field.password) KeyboardType.Password else field.keyboard),
                        supportingText = field.maxLength?.let { max -> { Text("${values[i].codePointCount(0, values[i].length)}/$max") } },
                        isError = field.maxLength != null && values[i].codePointCount(0, values[i].length) > field.maxLength,
                        colors = colors,
                        textStyle = Type.body(16),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(values.toList()) }, enabled = ready) {
                Text(
                    confirmLabel,
                    style = Type.body(14, FontWeight.Medium),
                    color = when {
                        !ready -> Nocturne.textTertiary
                        danger -> Nocturne.danger
                        else -> Nocturne.accent
                    },
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

/** A column with the page's side margins, for the tab bodies. */
@Composable
internal fun SetPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, bottom = 32.dp), content = content)
}
