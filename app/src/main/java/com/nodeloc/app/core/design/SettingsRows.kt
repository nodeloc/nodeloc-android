package com.nodeloc.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide

/**
 * The settings row family.
 *
 * Grouped cards: 14dp radius, 16dp from the screen edge, rows padded 16×13 —
 * one definition so every settings page in the app is the same shape.
 */
@Composable
fun SettingsSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s3)) {
        if (title != null) {
            SectionKicker(title, Modifier.padding(start = 4.dp, bottom = Space.s2))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(Nocturne.surface),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                style = Type.body(11),
                color = Nocturne.muted(0.4f),
                modifier = Modifier.padding(start = 4.dp, top = Space.s2),
            )
        }
    }
}

@Composable
private fun RowFrame(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        content = content,
    )
}

@Composable
fun SettingsNavRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    RowFrame(onClick) {
        if (icon != null) {
            Icon(icon, null, tint = tint ?: Nocturne.muted(0.55f), modifier = Modifier.size(19.dp))
        }
        Text(title, style = Type.body(15), color = tint ?: Nocturne.text, modifier = Modifier.weight(1f))
        if (detail != null) {
            Text(detail, style = Type.body(13), color = Nocturne.muted(0.45f))
        }
        Icon(Lucide.ChevronRight, null, tint = Nocturne.muted(0.3f), modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    RowFrame {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body(15), color = Nocturne.text)
            if (detail != null) {
                Text(detail, style = Type.body(12), color = Nocturne.muted(0.45f))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Nocturne.bg,
                checkedTrackColor = Nocturne.accent,
                uncheckedTrackColor = Nocturne.neutral400,
            ),
        )
    }
}

@Composable
fun SettingsValueRow(title: String, value: String, modifier: Modifier = Modifier) {
    RowFrame {
        Text(title, style = Type.body(15), color = Nocturne.text, modifier = Modifier.weight(1f))
        Text(value, style = Type.body(13), color = Nocturne.muted(0.45f))
    }
}

@Composable
fun SettingsTextRow(
    title: String,
    value: String,
    placeholder: String = "",
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
        Text(title, style = Type.body(12), color = Nocturne.muted(0.5f))
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = Type.body(15).copy(color = Nocturne.text),
            cursorBrush = SolidColor(Nocturne.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = Type.body(15), color = Nocturne.muted(0.3f))
                }
                inner()
            },
        )
    }
}

/** A picker row plus its bottom-sheet option list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsPickerRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    detail: (@Composable (T) -> String?)? = null,
    onSelect: (T) -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    SettingsNavRow(title = title, detail = label(selected)) { sheetOpen = true }

    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = Nocturne.bg,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                Text(
                    title,
                    style = Type.heading(17, FontWeight.SemiBold),
                    color = Nocturne.text,
                    modifier = Modifier.padding(horizontal = Space.page, vertical = Space.s3),
                )
                options.forEach { option ->
                    val isSelected = option == selected
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(option)
                                sheetOpen = false
                            }
                            .padding(horizontal = Space.page, vertical = 13.dp),
                    ) {
                        Text(
                            label(option),
                            style = Type.body(15, if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (isSelected) Nocturne.accent else Nocturne.text,
                        )
                        detail?.invoke(option)?.let {
                            Text(it, style = Type.body(12), color = Nocturne.muted(0.45f))
                        }
                    }
                }
            }
        }
    }
}

/** Divider between rows inside one card. */
@Composable
fun SettingsRowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Nocturne.divider))
}
