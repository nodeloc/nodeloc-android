package com.nodeloc.app.feature.moderation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Type

/*
 * Building blocks for the moderation sheets and action bars, drawn to the
 * Android artboards: 28dp-top sheets on the grey surface, 56/72 list rows with
 * bare icons, 40-high pill buttons, the orange filled button for anything
 * destructive.
 */

/** A Material modal sheet on the design's grey ground, title and optional subtitle on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SanctionSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Nocturne.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)) {
                Text(title, style = Type.heading(22, FontWeight.Normal), color = Nocturne.text)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = Type.body(14),
                        color = Nocturne.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            content()
        }
    }
}

/** A sheet list row: bare icon, title, optional supporting line, trailing slot. */
@Composable
internal fun SanctionRow(
    title: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val ink = if (danger) Nocturne.danger else Nocturne.text
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = if (supporting == null) 56.dp else 72.dp)
            .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (danger) Nocturne.danger else Nocturne.textSecondary, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body(16), color = ink.copy(alpha = if (enabled) 1f else 0.4f))
            if (supporting != null) {
                Text(supporting, style = Type.body(14), color = Nocturne.textSecondary)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun SanctionDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Nocturne.divider))
}

@Composable
internal fun SanctionSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Nocturne.accent,
            checkedThumbColor = Nocturne.bg,
            uncheckedTrackColor = Nocturne.neutral400,
            uncheckedThumbColor = Nocturne.textTertiary,
            uncheckedBorderColor = Nocturne.textTertiary,
        ),
    )
}

/** The green label above a group inside a sheet ("时长", "删除理由"). */
@Composable
internal fun SanctionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Type.body(14, FontWeight.Medium),
        color = Nocturne.accent,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
    )
}

/** A radio choice row, 48 high. */
@Composable
internal fun SanctionRadio(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .height(48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Nocturne.accent, unselectedColor = Nocturne.textSecondary),
        )
        Spacer(Modifier.size(4.dp))
        Text(text, style = Type.body(16), color = Nocturne.text)
    }
}

/** A single- or multi-line text input with a floating-style label above it. */
@Composable
internal fun SanctionField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(shape)
            .border(BorderStroke(1.dp, Nocturne.textTertiary), shape)
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = Type.body(16), color = Nocturne.textTertiary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = Type.body(16).copy(color = Nocturne.text),
            cursorBrush = SolidColor(Nocturne.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

enum class ModButtonStyle { Text, Outlined, Filled, Danger }

/** The design's 40-high pill button in its four looks. */
@Composable
internal fun ModButton(
    text: String,
    style: ModButtonStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val onFill = if (Nocturne.isDark) Nocturne.neutral900 else Nocturne.neutral100
    val (ground, ink) = when (style) {
        ModButtonStyle.Text, ModButtonStyle.Outlined -> Color.Transparent to Nocturne.accent
        ModButtonStyle.Filled -> Nocturne.accent to onFill
        ModButtonStyle.Danger -> Nocturne.danger to onFill
    }
    Box(
        modifier
            .height(40.dp)
            .clip(shape)
            .background(ground.copy(alpha = if (enabled || ground == Color.Transparent) ground.alpha else 0.4f))
            .then(if (style == ModButtonStyle.Outlined) Modifier.border(1.dp, Nocturne.textTertiary, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (style == ModButtonStyle.Outlined) 16.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body(14, FontWeight.Medium), color = ink.copy(alpha = if (enabled) 1f else 0.5f), maxLines = 1)
    }
}

/** Cancel / confirm at the foot of a sheet, right-aligned. */
@Composable
internal fun SanctionFooter(
    cancel: String,
    confirm: String,
    confirmStyle: ModButtonStyle,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        ModButton(cancel, ModButtonStyle.Text, onClick = onCancel)
        ModButton(confirm, confirmStyle, enabled = enabled, onClick = onConfirm)
    }
}

/** The tinted box that says in words what is about to happen. */
@Composable
internal fun SanctionSummary(text: String, danger: Boolean) {
    Box(
        Modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) Nocturne.dangerBg else Nocturne.warningBg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text, style = Type.body(14), color = if (danger) Nocturne.danger else Nocturne.warning)
    }
}

/** A Material segmented button pair (禁言 | 封号). */
@Composable
internal fun SanctionSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            val shape = when {
                options.size == 1 -> RoundedCornerShape(20.dp)
                index == 0 -> RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
                index == options.lastIndex -> RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
                else -> RoundedCornerShape(0.dp)
            }
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(shape)
                    .background(if (on) Nocturne.selected else Color.Transparent)
                    .border(1.dp, Nocturne.textTertiary, shape)
                    .clickable(role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.body(14, FontWeight.Medium), color = if (on) Nocturne.accent700 else Nocturne.text)
            }
        }
    }
}
