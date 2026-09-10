package com.nodeloc.app.feature.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.X
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type as Typo
import kotlin.math.roundToInt
import com.composables.icons.lucide.Brush
import com.composables.icons.lucide.Crop
import com.composables.icons.lucide.FlipHorizontal
import com.composables.icons.lucide.Redo2
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Sticker
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Wand
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Grid2x2

/**
 * The editor's own chrome, black whatever the app's theme is.
 *
 * Kept apart from the canvas so the drawing code stays about drawing: this
 * file is buttons, strips and one ruler, none of which know anything about
 * matrices.
 */
@Composable
internal fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canReset: Boolean,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.X,
            stringResource(R.string.common_cancel),
            tint = EditorChromeColor,
            modifier = Modifier.size(22.dp).clickable(onClick = onCancel),
        )
        Box(Modifier.weight(1f))
        Icon(
            Lucide.Undo2,
            stringResource(R.string.common_undo),
            tint = if (canUndo) EditorChromeColor else EditorDimColor,
            modifier = Modifier
                .size(22.dp)
                .clickable(enabled = canUndo, onClick = onUndo),
        )
        Box(Modifier.width(18.dp))
        Icon(
            Lucide.Redo2,
            stringResource(R.string.editor_redo),
            tint = if (canRedo) EditorChromeColor else EditorDimColor,
            modifier = Modifier
                .size(22.dp)
                .clickable(enabled = canRedo, onClick = onRedo),
        )
        Box(Modifier.width(18.dp))
        // Back to the picture as it arrived, without leaving. Undo walks a
        // step at a time and this is the whole way, which after a dozen edits
        // is not the same thing at all.
        Icon(
            Lucide.RefreshCw,
            stringResource(R.string.editor_reset),
            tint = if (canReset) EditorChromeColor else EditorDimColor,
            modifier = Modifier
                .size(20.dp)
                .clickable(enabled = canReset, onClick = onReset),
        )
    }
}

/**
 * Black whatever the app is set to.
 *
 * A photo editor on a white ground makes the interface compete with the
 * picture, and the picture is the only thing on screen that matters. The app
 * theme stops at this dialog edge.
 */
internal val EditorGround = Color(0xFF0B0B0C)
internal val EditorChromeColor = Color(0xFFF2F2F3)
internal val EditorDimColor = Color(0xFF6C6C70)

/** One tool's own controls, above the tab bar. */
@Composable
internal fun EditorControlRow(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.page, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
internal fun EditorChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = Typo.body(13, if (selected) FontWeight.SemiBold else FontWeight.Normal),
        color = if (selected) Nocturne.accent else EditorDimColor,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
internal fun EditorColorStrip(selected: Color, colors: List<Color>, onPick: (Color) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEach { color ->
            Box(
                Modifier
                    .size(if (color == selected) 30.dp else 24.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onPick(color) },
            )
        }
    }
}

/**
 * Brush width as a row of dots the size they draw.
 *
 * A slider would need a track, a thumb and a number to mean anything; three
 * dots at the actual sizes say it without any of that.
 */
@Composable
internal fun EditorWidthPicker(width: Float, widths: List<Float>, onPick: (Float) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        widths.forEach { candidate ->
            // Sized against the largest on offer rather than by the raw
            // number: the mosaic smear is three times the brush, and a flat
            // ceiling made all three dots the same size.
            val largest = widths.maxOrNull() ?: candidate
            val dot = (10f + 16f * (candidate / largest)).dp
            Box(
                Modifier.size(32.dp).clickable { onPick(candidate) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(dot)
                        .clip(CircleShape)
                        .background(if (candidate == width) Nocturne.accent else EditorDimColor),
                )
            }
        }
    }
}

/**
 * The straightening ruler: drag sideways, one notch per degree.
 *
 * A wheel rather than a slider because that is the control this job has had
 * for a decade — you are nudging something a degree or two, not choosing a
 * value out of a range.
 */
@Composable
internal fun EditorStraightenRuler(angle: Float, onChange: (Float) -> Unit) {
    val pxPerDegree = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
    // Read here, not inside the canvas: the theme colours are composable
    // getters and a draw scope is not a composable.
    val accent = Nocturne.accent
    // The gesture is built once and the angle changes under it, so the handler
    // has to look the current one up rather than close over the first.
    val liveAngle = androidx.compose.runtime.rememberUpdatedState(angle)
    Column(Modifier.fillMaxWidth()) {
        Text(
            "${angle.roundToInt()}°",
            style = Typo.body(12, FontWeight.SemiBold),
            color = if (angle == 0f) EditorDimColor else Nocturne.accent,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(pxPerDegree) {
                    detectHorizontalDragGestures { change, drag ->
                        change.consume()
                        onChange((liveAngle.value - drag / pxPerDegree).coerceIn(-45f, 45f))
                    }
                },
        ) {
            val centre = size.width / 2f
            // Every degree gets a notch; every fifth is taller and brighter.
            var degree = -45
            while (degree <= 45) {
                val x = centre + (degree - angle) * pxPerDegree
                if (x >= 0f && x <= size.width) {
                    val major = degree % 5 == 0
                    val height = if (major) size.height * 0.5f else size.height * 0.28f
                    drawLine(
                        color = if (major) EditorChromeColor.copy(alpha = 0.7f) else EditorDimColor,
                        start = Offset(x, size.height / 2f - height / 2f),
                        end = Offset(x, size.height / 2f + height / 2f),
                        strokeWidth = 2f,
                    )
                }
                degree++
            }
            drawLine(
                color = accent,
                start = Offset(centre, size.height * 0.15f),
                end = Offset(centre, size.height * 0.85f),
                strokeWidth = 4f,
            )
        }
    }
}

@Composable
internal fun EditorStickerGrid(emoji: List<String>, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        emoji.forEach { glyph ->
            Text(
                glyph,
                style = Typo.body(24),
                color = EditorChromeColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { onPick(glyph) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
internal fun EditorIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = EditorChromeColor, modifier = Modifier.size(20.dp))
        Text(label, style = Typo.body(10), color = EditorDimColor)
    }
}

@Composable
internal fun TextEntryDialog(initial: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .background(Nocturne.bg, RoundedCornerShape(Radius.lg))
                .padding(Space.page),
        ) {
            Text(
                stringResource(R.string.editor_add_text),
                style = Typo.body(15, FontWeight.SemiBold),
                color = Nocturne.text,
            )
            Box(Modifier.height(Space.s3))
            com.nodeloc.app.core.design.FieldSurface {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = Typo.body(15).copy(color = Nocturne.text),
                    cursorBrush = SolidColor(Nocturne.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.height(Space.s3))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    stringResource(R.string.common_cancel),
                    style = Typo.body(14),
                    color = Nocturne.muted(0.5f),
                    modifier = Modifier.clickable(onClick = onCancel).padding(10.dp),
                )
                Text(
                    stringResource(R.string.common_done),
                    style = Typo.body(14, FontWeight.SemiBold),
                    color = Nocturne.accent,
                    modifier = Modifier.clickable { onConfirm(value) }.padding(10.dp),
                )
            }
        }
    }
}

/**
 * The tool strip along the bottom.
 *
 * Icons with their names under them, one row, the selected one in the accent
 * colour. Aspect ratios used to sit in a second row of their own competing
 * with this one; they live inside the crop tool now, where they belong.
 */
/**
 * The tool row, and the one button that does something at the end of it.
 *
 * Nothing is selected when the editor opens: the picture arrives as it is,
 * and picking up a tool is a decision. The button on the right is whatever
 * finishes the current state — a tool, or the whole thing.
 */
@Composable
internal fun EditorTabBar(
    tool: EditorToolId?,
    busy: Boolean,
    action: EditorAction,
    onSelect: (EditorToolId) -> Unit,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.s2, end = Space.page, top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorToolId.entries.forEach { candidate ->
            val selected = candidate == tool
            Column(
                Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { onSelect(candidate) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    candidate.icon,
                    stringResource(candidate.labelRes),
                    tint = if (selected) Nocturne.accent else EditorDimColor,
                    modifier = Modifier.size(22.dp),
                )
                Box(Modifier.height(3.dp))
                Text(
                    stringResource(candidate.labelRes),
                    style = Typo.body(10, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (selected) Nocturne.accent else EditorDimColor,
                )
            }
        }
        Box(Modifier.weight(1f))
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (busy) EditorDimColor else Nocturne.accent)
                .clickable(enabled = !busy, onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                action.icon,
                stringResource(action.labelRes),
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** What the button at the end of the tool row is for, right now. */
internal enum class EditorAction(
    @androidx.annotation.StringRes val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    /** Leave the tool that is open and go back to the picture. */
    FinishTool(R.string.common_done, Lucide.Check),

    /** Hand the finished picture to the composer. */
    Confirm(R.string.common_done, Lucide.Check),

    /** Send it, there and then. */
    Send(R.string.reader_send, Lucide.Send),
}

/** The tools, in the order the bar shows them. */
internal enum class EditorToolId(
    @androidx.annotation.StringRes val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Crop(R.string.editor_crop, Lucide.Crop),
    Brush(R.string.editor_brush, Lucide.Brush),
    Mosaic(R.string.editor_mosaic, Lucide.Grid2x2),
    Text(R.string.editor_text, Lucide.Type),
    Sticker(R.string.editor_sticker, Lucide.Sticker),
    Filter(R.string.editor_filter, Lucide.Wand),
}

/**
 * Whatever the chosen tool needs, in the strip above the tab bar.
 *
 * Every tool gets the same slot rather than its own layout, so switching tools
 * does not move the picture — which is the thing that made the old two-row
 * arrangement feel unsettled.
 */
@Composable
internal fun EditorToolControls(
    tool: EditorToolId,
    angle: Float,
    filter: PhotoFilter,
    cropAspect: CropAspect,
    paintColor: Color,
    brushWidth: Float,
    textStyle: TextStyle,
    mosaicStyle: MosaicStyle,
    palette: List<Color>,
    stickers: List<String>,
    onAspect: (CropAspect) -> Unit,
    onStraighten: (Float) -> Unit,
    onQuarterTurn: () -> Unit,
    onFlip: () -> Unit,
    onColor: (Color) -> Unit,
    onWidth: (Float) -> Unit,
    onTextStyle: (TextStyle) -> Unit,
    onMosaicStyle: (MosaicStyle) -> Unit,
    onAddText: () -> Unit,
    onSticker: (String) -> Unit,
    onFilter: (PhotoFilter) -> Unit,
) {
    when (tool) {
        EditorToolId.Crop -> Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            EditorStraightenRuler(angle, onStraighten)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.page)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CropAspect.entries.forEach { aspect ->
                    EditorChip(stringResource(aspect.labelRes), aspect == cropAspect) { onAspect(aspect) }
                }
                Box(Modifier.width(8.dp))
                EditorIconButton(Lucide.RotateCw, stringResource(R.string.editor_rotate), onQuarterTurn)
                EditorIconButton(Lucide.FlipHorizontal, stringResource(R.string.editor_flip), onFlip)
            }
        }

        EditorToolId.Brush -> EditorControlRow {
            Column(Modifier.fillMaxWidth()) {
                EditorWidthPicker(brushWidth, listOf(6f, 12f, 24f), onWidth)
                Box(Modifier.height(8.dp))
                EditorColorStrip(paintColor, palette, onColor)
            }
        }

        EditorToolId.Mosaic -> EditorControlRow {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MosaicStyle.entries.forEach { candidate ->
                    EditorChip(stringResource(candidate.labelRes), candidate == mosaicStyle) {
                        onMosaicStyle(candidate)
                    }
                }
            }
        }

        EditorToolId.Text -> EditorControlRow {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextStyle.entries.forEach { style ->
                        EditorChip(stringResource(style.labelRes), style == textStyle) { onTextStyle(style) }
                    }
                    Box(Modifier.weight(1f))
                    EditorIconButton(Lucide.Type, stringResource(R.string.editor_add_text), onAddText)
                }
                Box(Modifier.height(6.dp))
                EditorColorStrip(paintColor, palette, onColor)
            }
        }

        EditorToolId.Sticker -> EditorControlRow { EditorStickerGrid(stickers, onSticker) }

        EditorToolId.Filter -> EditorControlRow {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PhotoFilter.entries.forEach { candidate ->
                    EditorChip(stringResource(candidate.labelRes), candidate == filter) { onFilter(candidate) }
                }
            }
        }
    }
}

// ------------------------------------------------------------ crop frame --

/** Generous on purpose: a corner is a point and a fingertip is about nine. */
internal val CropTouch = 40.dp

/** The line between the corners: thin, because it is only a boundary. */
private val CropHair = 1.dp

/** The brackets: what the frame is actually held by. */
private val CropBar = 3.dp
private val CropBarArm = 20.dp

/**
 * The crop frame, in view space, as both editors draw it.
 *
 * Shared because every part of it is a fix for something: the dimming is heavy
 * so the discarded part stops reading as picture, the hairline is doubled with
 * one dark companion so it survives a white photograph, and the brackets are
 * drawn outward so they never sit on the image at all. That took several
 * passes to arrive at and is not worth arriving at twice.
 */
internal fun DrawScope.drawCropChrome(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    showGuides: Boolean,
) {
    // Heavy on purpose: at anything less the part being thrown away still
    // reads as part of the picture, which is the question being asked.
    val shade = Color.Black.copy(alpha = 0.72f)
    drawRect(shade, Offset.Zero, Size(size.width, top))
    drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
    drawRect(shade, Offset(0f, top), Size(left, bottom - top))
    drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))

    val hair = CropHair.toPx()
    // One dark hairline under the thin one, and nowhere else. It is there for
    // pale pictures, where a white line on white is nothing, but doubling
    // every part of the frame made it muddy, which is the other way to be
    // invisible.
    drawRect(
        color = Color.Black.copy(alpha = 0.3f),
        topLeft = Offset(left - hair, top - hair),
        size = Size(right - left + hair * 2, bottom - top + hair * 2),
        style = Stroke(width = hair),
    )
    drawRect(
        color = Color.White.copy(alpha = 0.65f),
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        style = Stroke(width = hair),
    )

    if (showGuides) {
        val guide = Color.White.copy(alpha = 0.3f)
        for (stepIndex in 1..2) {
            val x = left + (right - left) * stepIndex / 3f
            val y = top + (bottom - top) * stepIndex / 3f
            drawLine(guide, Offset(x, top), Offset(x, bottom), strokeWidth = hair)
            drawLine(guide, Offset(left, y), Offset(right, y), strokeWidth = hair)
        }
    }

    // Handles are drawn *outside* the rectangle, never over the picture.
    //
    // Which is what makes them visible without a dark halo behind every line:
    // outside the frame is either the dimmed part being cut away or the black
    // the picture is inset from, and white reads on both. Inside the frame it
    // is the photograph, where white on a pale one is nothing.
    //
    // Four corners and nothing else. The sides still drag, they are simply not
    // drawn, because eight marks around a picture is a diagram of a crop tool
    // rather than one.
    val bar = CropBar.toPx()
    val half = bar / 2f
    val arm = minOf(CropBarArm.toPx(), (right - left) / 3f, (bottom - top) / 3f)
    listOf(
        Triple(left, top, Offset(1f, 1f)),
        Triple(right, top, Offset(-1f, 1f)),
        Triple(left, bottom, Offset(1f, -1f)),
        Triple(right, bottom, Offset(-1f, -1f)),
    ).forEach { (x, y, dir) ->
        val outX = x - half * dir.x
        val outY = y - half * dir.y
        drawLine(Color.White, Offset(x - bar * dir.x, outY), Offset(x + arm * dir.x, outY), strokeWidth = bar)
        drawLine(Color.White, Offset(outX, y - bar * dir.y), Offset(outX, y + arm * dir.y), strokeWidth = bar)
    }
}
