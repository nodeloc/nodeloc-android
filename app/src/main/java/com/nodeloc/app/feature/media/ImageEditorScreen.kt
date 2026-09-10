package com.nodeloc.app.feature.media

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush as GradientBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Brush
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Crop
import com.composables.icons.lucide.FlipHorizontal
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Redo2
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.Wand
import com.composables.icons.lucide.X
import com.nodeloc.app.R
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type as Typo
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween

/** Enough hues to be useful, and pure white and black which a spectrum lacks. */
private val PaintPalette = listOf(
    Color.White, Color.Black,
    Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00),
    Color(0xFF34C759), Color(0xFF00C7BE), Color(0xFF007AFF),
    Color(0xFF5856D6), Color(0xFFFF2D55), Color(0xFF8E8E93),
)

private val StickerEmoji = listOf(
    "😀", "😂", "🥹", "😍", "🤔", "😎", "😭", "🥳", "😴", "🤯",
    "👍", "👎", "👏", "🙏", "💪", "👀", "🔥", "✨", "💯", "❤️",
    "🎉", "🎁", "⭐", "⚡", "☀️", "🌙", "🍺", "☕", "🍜", "🚀",
)

/**
 * Image editor: straighten, crop, draw, write, decorate and grade, then hand
 * the flattened bitmap back to whoever asked for it.
 *
 * Every gesture is converted into source-image coordinates before it is
 * stored, so the export is the preview at a different scale. The straightening
 * transform is applied to that whole space rather than baked into a new
 * bitmap, which is what lets a picture be turned after it has been drawn on
 * without the drawing sliding off it.
 */
@Composable
fun ImageEditorDialog(
    source: Bitmap,
    /**
     * Whether finishing here sends the picture or hands it back.
     *
     * Chat has nowhere else to put it, so the editor is the send screen: no
     * tool is open when it appears, the tools are things you step into and out
     * of, and the button at the bottom right sends. The composer already has a
     * place for the picture to land, so there it just says done.
     */
    sends: Boolean = false,
    onCancel: () -> Unit,
    onDone: (Bitmap) -> Unit,
) {
    // History is whole documents. They share the one source bitmap, so a step
    // costs a handful of references rather than a copy of the photograph.
    // Kept apart from the history, which is trimmed as it grows: reset has to
    // reach the picture as it arrived however long ago that was.
    val original = remember(source) { ImageEditDocument(source = source) }
    var history by remember(source) { mutableStateOf(listOf(original)) }
    var step by remember(source) { mutableIntStateOf(0) }
    val document = history[step]

    /**
     * The document as it stands *now*, for gesture handlers to read.
     *
     * `document` above is a value captured when the handler was built, and a
     * drag lasts many frames: reading it would apply every delta to the state
     * the finger started from, so the last frame would win and the rest would
     * be thrown away. `history` and `step` are delegated state, so going back
     * through them reads live.
     */
    fun live(): ImageEditDocument = history[step]

    fun commit(next: ImageEditDocument) {
        history = history.take(step + 1).takeLast(HISTORY_DEPTH) + next
        step = history.lastIndex
    }

    // Nothing selected to begin with: the picture arrives as it is, and
    // picking up a tool is a decision rather than where you land.
    var tool by remember { mutableStateOf<EditorToolId?>(null) }
    var paintColor by remember { mutableStateOf(PaintPalette.first()) }
    var brushWidth by remember { mutableFloatStateOf(12f) }
    var textStyle by remember { mutableStateOf(TextStyle.Plain) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    /** The rectangle being dragged out, in source coordinates. */
    var currentMosaic by remember { mutableStateOf<RectF?>(null) }
    var mosaicStyle by remember { mutableStateOf(MosaicStyle.Blocks) }

    /**
     * The picture at block resolution, built once.
     *
     * Full size, so it lines up with the original pixel for pixel and can be
     * drawn through the same straightening.
     */
    val coarse = remember(source) { MosaicSource(source) }
    var cropAspect by remember { mutableStateOf(CropAspect.Free) }
    var draggingCrop by remember { mutableStateOf(false) }

    /**
     * What the view is zoomed to while cropping.
     *
     * The crop itself, brought up to size after every drag rather than during
     * one — moving the frame under a finger that is also being moved is how a
     * crop tool stops feeling like it is attached to anything. Null until the
     * tool is first opened, when it takes the crop as it stands.
     */
    var viewRect by remember(source) { mutableStateOf<RectF?>(null) }
    var rendering by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(EditorGround)) {
            EditorTopBar(
                canUndo = step > 0,
                canRedo = step < history.lastIndex,
                canReset = document != original,
                onCancel = onCancel,
                onUndo = { if (step > 0) step-- },
                onRedo = { if (step < history.lastIndex) step++ },
                onReset = {
                    history = listOf(original)
                    step = 0
                    viewRect = null
                    cropAspect = CropAspect.Free
                },
            )

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bounds = remember(document.rotationDegrees, document.angle, document.flipped) {
                    document.basisBounds()
                }
                val boxWidth = constraints.maxWidth.toFloat()
                val boxHeight = constraints.maxHeight.toFloat()
                // What the screen is showing. Inside the crop tool that is the
                // whole picture, because the frame has to be draggable over the
                // parts being cut; everywhere else it is the crop itself.
                //
                // Without this the preview stayed the original after a crop was
                // finished — the rectangle only existed at export time, so the
                // editor kept showing what had just been cut away.
                val visible = if (tool == EditorToolId.Crop) {
                    viewRect ?: document.crop
                } else {
                    document.crop
                }
                // Room around the picture while cropping. The frame sits on its
                // edge, so half of every handle's reach used to fall outside the
                // area that receives touches — the corners nearest the edge of
                // the screen could only be caught from one side, which is what
                // made the bottom two so hard to take hold of.
                val marginPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                    CROP_MARGIN.toPx()
                }
                val margin = if (tool == EditorToolId.Crop) marginPx else 0f
                val targetFit = minOf(
                    (boxWidth - margin * 2f) / visible.width(),
                    (boxHeight - margin * 2f) / visible.height(),
                )
                val targetOriginX = (boxWidth - visible.width() * targetFit) / 2f - visible.left * targetFit
                val targetOriginY = (boxHeight - visible.height() * targetFit) / 2f - visible.top * targetFit

                // Animated, because the jump between one framing and the next
                // is otherwise a cut — and a cut in the middle of cropping
                // reads as the picture having changed rather than the view of
                // it. Nothing animates during a drag: the target only moves
                // when a finger comes off.
                val spec = tween<Float>(durationMillis = 260, easing = FastOutSlowInEasing)
                val fit by animateFloatAsState(targetFit, spec, label = "cropFit")
                val originX by animateFloatAsState(targetOriginX, spec, label = "cropOriginX")
                val originY by animateFloatAsState(targetOriginY, spec, label = "cropOriginY")

                fun toBasis(point: Offset) = Offset((point.x - originX) / fit, (point.y - originY) / fit)

                // Layers are kept in source coordinates, so a touch has to come
                // all the way back through the straightening as well.
                val inverse = remember(document.rotationDegrees, document.angle, document.flipped) {
                    Matrix().also { document.basisMatrix().invert(it) }
                }
                fun toSource(point: Offset): Offset {
                    val basis = toBasis(point)
                    val mapped = floatArrayOf(basis.x, basis.y)
                    inverse.mapPoints(mapped)
                    return Offset(mapped[0], mapped[1])
                }

                val measurer = rememberTextMeasurer()

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(tool, cropAspect, fit, document.rotationDegrees, document.angle) {
                            if (tool != EditorToolId.Crop) return@pointerInput
                            var corner = CROP_NONE
                            detectDragGestures(
                                onDragStart = { at ->
                                    draggingCrop = true
                                    corner = cropHandleAt(live().crop, toBasis(at), CROP_TOUCH.toPx() / fit)
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    val now = live()
                                    val moved = now.crop.dragged(
                                        corner = corner,
                                        by = Offset(drag.x / fit, drag.y / fit),
                                        bounds = bounds,
                                        ratio = cropAspect.ratio,
                                    )
                                    // Straight onto history's tail rather than a
                                    // step per frame: a drag is one edit.
                                    history = history.take(step) + now.copy(crop = moved)
                                    step = history.lastIndex
                                },
                                onDragEnd = {
                                    draggingCrop = false
                                    corner = CROP_NONE
                                    // Bring what was just chosen up to size.
                                    viewRect = live().crop
                                },
                                onDragCancel = {
                                    draggingCrop = false
                                    corner = CROP_NONE
                                    viewRect = live().crop
                                },
                            )
                        }
                        .pointerInput(tool, mosaicStyle, fit) {
                            if (tool != EditorToolId.Mosaic) return@pointerInput
                            var anchor = Offset.Zero
                            detectDragGestures(
                                onDragStart = { at ->
                                    anchor = toSource(at)
                                    currentMosaic = RectF(anchor.x, anchor.y, anchor.x, anchor.y)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val here = toSource(change.position)
                                    currentMosaic = RectF(
                                        minOf(anchor.x, here.x),
                                        minOf(anchor.y, here.y),
                                        maxOf(anchor.x, here.x),
                                        maxOf(anchor.y, here.y),
                                    )
                                },
                                onDragEnd = {
                                    val drawn = currentMosaic
                                    currentMosaic = null
                                    // A stray tap is not a rectangle. The floor
                                    // is in source pixels, so it is the same
                                    // gesture whatever the picture's size.
                                    if (drawn != null &&
                                        drawn.width() > MOSAIC_MIN &&
                                        drawn.height() > MOSAIC_MIN
                                    ) {
                                        val now = live()
                                        commit(
                                            now.copy(
                                                mosaics = now.mosaics +
                                                    MosaicPatch(drawn, mosaicStyle),
                                            ),
                                        )
                                    }
                                },
                                onDragCancel = { currentMosaic = null },
                            )
                        }
                        .pointerInput(tool, paintColor, brushWidth, fit) {
                            if (tool != EditorToolId.Brush) return@pointerInput
                            detectDragGestures(
                                onDragStart = { at -> currentStroke = listOf(toSource(at)) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentStroke = currentStroke + toSource(change.position)
                                },
                                onDragEnd = {
                                    if (currentStroke.size > 1) {
                                        val now = live()
                                        commit(
                                            now.copy(
                                                strokes = now.strokes + BrushStroke(
                                                    points = currentStroke,
                                                    color = paintColor,
                                                    width = brushWidth / fit,
                                                ),
                                            ),
                                        )
                                    }
                                    currentStroke = emptyList()
                                },
                            )
                        }
                        .pointerInput(tool, fit) {
                            // Text and stickers share one gesture: whichever
                            // layer was nearest the first finger moves, turns
                            // and grows until the fingers come off.
                            if (tool != EditorToolId.Text && tool != EditorToolId.Sticker) return@pointerInput
                            var grabbed: Long? = null
                            detectTransformGestures(
                                onGesture = { centroid, pan, zoom, rotation ->
                                    val now = live()
                                    val here = toSource(centroid)
                                    if (grabbed == null) {
                                        grabbed = nearestLayerId(now, here, LAYER_GRAB / fit)
                                    }
                                    val id = grabbed ?: return@detectTransformGestures
                                    val panned = Offset(pan.x / fit, pan.y / fit)
                                    history = history.take(step) + now.movedLayer(id, panned, zoom, rotation)
                                    step = history.lastIndex
                                },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        // Clipped to what is being shown, so a stroke or a
                        // sticker sitting outside the crop does not spill onto
                        // the black around it.
                        // Cropping shows the whole picture — the part being
                        // cut is dimmed, not hidden, or there is no telling
                        // what is being given up and nowhere to drag a corner
                        // back out to. Everywhere else, only the crop.
                        val drawn = if (tool == EditorToolId.Crop) bounds else document.crop
                        clipRect(
                            left = originX + drawn.left * fit,
                            top = originY + drawn.top * fit,
                            right = originX + drawn.right * fit,
                            bottom = originY + drawn.bottom * fit,
                        ) {
                            withTransform({
                                translate(originX, originY)
                                scale(fit, fit, pivot = Offset.Zero)
                            }) {
                                drawEditedImage(document)
                                drawMosaicLayer(
                                    document,
                                    coarse,
                                    currentMosaic?.let { MosaicPatch(it, mosaicStyle) },
                                )
                                drawLayers(document, currentStroke, paintColor, brushWidth / fit)
                            }
                        }
                        if (tool == EditorToolId.Crop) {
                            drawCropOverlay(
                                crop = document.crop,
                                origin = Offset(originX, originY),
                                fit = fit,
                                showGuides = draggingCrop,
                                measurer = measurer,
                            )
                        }
                    }
                }
            }

            tool?.let { open ->
            EditorToolControls(
                tool = open,
                angle = document.angle,
                filter = document.filter,
                cropAspect = cropAspect,
                paintColor = paintColor,
                brushWidth = brushWidth,
                textStyle = textStyle,
                palette = PaintPalette,
                stickers = StickerEmoji,
                onAspect = { aspect ->
                    cropAspect = aspect
                    val shaped = cropRect(document.basisBounds(), aspect)
                    commit(document.copy(crop = shaped))
                    viewRect = shaped
                },
                onStraighten = { degrees ->
                    // Onto history's tail, not a step per notch: turning the
                    // ruler is one edit however long the finger stays down.
                    val turned = live().copy(angle = degrees)
                    val fitted = turned.fittedCrop()
                    history = history.take(step) + turned.copy(crop = fitted)
                    step = history.lastIndex
                    viewRect = fitted
                },
                onQuarterTurn = {
                    val turned = document.copy(rotationDegrees = (document.rotationDegrees + 90) % 360)
                    val fitted = turned.fittedCrop()
                    commit(turned.copy(crop = fitted))
                    viewRect = fitted
                },
                onFlip = {
                    val flipped = document.copy(flipped = !document.flipped)
                    val fitted = flipped.fittedCrop()
                    commit(flipped.copy(crop = fitted))
                    viewRect = fitted
                },
                onColor = { paintColor = it },
                onWidth = { brushWidth = it },
                mosaicStyle = mosaicStyle,
                onMosaicStyle = { mosaicStyle = it },
                onTextStyle = { textStyle = it },
                onAddText = { pendingText = "" },
                onSticker = { emoji ->
                    val centre = Offset(document.crop.centerX(), document.crop.centerY())
                    val mapped = floatArrayOf(centre.x, centre.y)
                    Matrix().also { document.basisMatrix().invert(it) }.mapPoints(mapped)
                    commit(
                        document.copy(
                            stickers = document.stickers + StickerLayer(
                                id = System.nanoTime(),
                                emoji = emoji,
                                position = Offset(mapped[0], mapped[1]),
                                size = source.width * 0.18f,
                            ),
                        ),
                    )
                },
                onFilter = { commit(document.copy(filter = it)) },
            )
            }

            EditorTabBar(
                tool = tool,
                busy = rendering,
                action = when {
                    tool != null -> EditorAction.FinishTool
                    sends -> EditorAction.Send
                    else -> EditorAction.Confirm
                },
                onSelect = { picked ->
                    tool = if (tool == picked) null else picked
                    if (tool == EditorToolId.Crop) viewRect = live().crop
                },
                onAction = {
                    // Inside a tool the button only closes it. The picture is
                    // never rendered on the way out of a tool — every edit is
                    // already in the document.
                    if (tool != null) {
                        tool = null
                    } else {
                        rendering = true
                        scope.launch {
                            val output = withContext(Dispatchers.Default) {
                                runCatchingCancellable { ImageEditRenderer.render(document) }.getOrNull()
                            }
                            rendering = false
                            if (output == null) {
                                ToastCenter.show(R.string.error_action_failed)
                            } else {
                                onDone(output)
                            }
                        }
                    }
                },
            )
        }
    }

    pendingText?.let { initial ->
        TextEntryDialog(
            initial = initial,
            onCancel = { pendingText = null },
            onConfirm = { value ->
                pendingText = null
                if (value.isNotBlank()) {
                    val centre = Offset(document.crop.centerX(), document.crop.centerY())
                    val mapped = floatArrayOf(centre.x, centre.y)
                    Matrix().also { document.basisMatrix().invert(it) }.mapPoints(mapped)
                    commit(
                        document.copy(
                            texts = document.texts + TextLayer(
                                id = System.nanoTime(),
                                text = value,
                                position = Offset(mapped[0], mapped[1]),
                                color = paintColor,
                                size = source.width * 0.08f,
                                style = textStyle,
                            ),
                        ),
                    )
                }
            },
        )
    }
}

private const val HISTORY_DEPTH = 24
private const val CROP_NONE = -1
private const val CROP_EDGE_LEFT = 4
private const val CROP_EDGE_TOP = 5
private const val CROP_EDGE_RIGHT = 6
private const val CROP_EDGE_BOTTOM = 7

/**
 * How near a finger has to land to take hold of a handle.
 *
 * Generous on purpose. A corner is a point and a fingertip is about nine
 * millimetres across, so a target the size of the thing drawn is a target that
 * misses — which is what made this hard to use.
 */
private val CROP_TOUCH = 40.dp
/** The line between the corners: thin, because it is only a boundary. */
private val CROP_HAIR = 1.dp

/** The brackets and side bars: what the frame is actually held by. */
private val CROP_BAR = 3.dp
private val CROP_BAR_ARM = 20.dp

/**
 * Room left around the crop.
 *
 * Two jobs. Every handle can be reached from both sides, which is what the
 * corners nearest the edge of the screen lacked. And enough of the picture
 * beyond the frame stays on screen to show what is being cut and to leave
 * somewhere to drag a corner back out to.
 */
private val CROP_MARGIN = 40.dp

/** How near a finger has to land, in view points, to take hold of a layer. */
private const val LAYER_GRAB = 90f

/** Smallest covered rectangle worth keeping, in source pixels. */
private const val MOSAIC_MIN = 24f

/** Smallest crop worth keeping, in basis pixels. */
private const val CROP_MIN = 48f

// ---------------------------------------------------------------- drawing --

/**
 * The coarsened picture, showing only where it has been smeared over.
 *
 * Graded with the same filter as the picture under it, or turning a filter on
 * would leave the blocks holding the colours of the one before.
 */
private fun DrawScope.drawMosaicLayer(
    document: ImageEditDocument,
    coarse: MosaicSource,
    live: MosaicPatch?,
) {
    val patches = document.mosaics + listOfNotNull(live)
    if (patches.isEmpty()) return
    val canvas = drawContext.canvas.nativeCanvas
    canvas.save()
    drawMosaic(canvas, document, patches, coarse, document.filter.colorFilter)
    canvas.restore()
}

/** The photograph, straightened and graded. */
private fun DrawScope.drawEditedImage(document: ImageEditDocument) {
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = document.filter.colorFilter
    }
    drawContext.canvas.nativeCanvas.drawBitmap(document.source, document.basisMatrix(), paint)
}

/**
 * Strokes, words and stickers, drawn through the same straightening as the
 * picture under them — which is the whole reason they stay put when it turns.
 */
private fun DrawScope.drawLayers(
    document: ImageEditDocument,
    liveStroke: List<Offset>,
    liveColor: Color,
    liveWidth: Float,
) {
    val canvas = drawContext.canvas.nativeCanvas
    canvas.save()
    canvas.concat(document.basisMatrix())

    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    (document.strokes + listOfNotNull(
        liveStroke.takeIf { it.size > 1 }?.let { BrushStroke(it, liveColor, liveWidth) },
    )).forEach { stroke ->
        strokePaint.color = stroke.color.toArgb()
        strokePaint.strokeWidth = stroke.width
        val path = android.graphics.Path()
        stroke.points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        canvas.drawPath(path, strokePaint)
    }

    document.texts.forEach { layer ->
        canvas.save()
        canvas.rotate(layer.angle, layer.position.x, layer.position.y)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            isFakeBoldText = true
            textSize = layer.size
        }
        if (layer.style == TextStyle.Filled) {
            val width = paint.measureText(layer.text)
            val pad = layer.size * 0.22f
            val boxPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = layer.color.toArgb()
            }
            canvas.drawRoundRect(
                android.graphics.RectF(
                    layer.position.x - pad,
                    layer.position.y - layer.size + pad * 0.2f,
                    layer.position.x + width + pad,
                    layer.position.y + pad,
                ),
                pad, pad, boxPaint,
            )
            paint.color = if (layer.color.luminance() > 0.6f) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        } else {
            if (layer.style == TextStyle.Outlined) {
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = layer.size * 0.12f
                paint.color = android.graphics.Color.BLACK
                canvas.drawText(layer.text, layer.position.x, layer.position.y, paint)
                paint.style = android.graphics.Paint.Style.FILL
            }
            paint.color = layer.color.toArgb()
        }
        canvas.drawText(layer.text, layer.position.x, layer.position.y, paint)
        canvas.restore()
    }

    document.stickers.forEach { layer ->
        canvas.save()
        canvas.rotate(layer.angle, layer.position.x, layer.position.y)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = layer.size
        }
        canvas.drawText(layer.emoji, layer.position.x, layer.position.y, paint)
        canvas.restore()
    }
    canvas.restore()
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

/**
 * The crop frame, in view space.
 *
 * Drawn after the picture rather than inside its transform: the frame belongs
 * to the screen, not to the photograph, and turning it with the image would be
 * wrong. Guides appear only while a finger is down — a permanent grid is noise
 * on a picture nobody is currently cropping.
 */
private fun DrawScope.drawCropOverlay(
    crop: RectF,
    origin: Offset,
    fit: Float,
    showGuides: Boolean,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val left = origin.x + crop.left * fit
    val top = origin.y + crop.top * fit
    val right = origin.x + crop.right * fit
    val bottom = origin.y + crop.bottom * fit

    drawCropChrome(left, top, right, bottom, showGuides)

    if (showGuides) {
        val hair = CROP_HAIR.toPx()
        val fl = left
        val ft = top
        val label = "${crop.width().roundToInt()} × ${crop.height().roundToInt()}"
        val measured = measurer.measure(
            AnnotatedString(label),
            style = ComposeTextStyle(color = Color.White, fontSize = 11.sp),
        )
        val pad = 5.dp.toPx()
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.5f),
            topLeft = Offset(fl + pad, ft + pad),
            size = Size(measured.size.width + pad * 2, measured.size.height + pad),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
        drawText(measured, topLeft = Offset(fl + pad * 2, ft + pad * 1.5f))
    }
}

// --------------------------------------------------------------- geometry --

/**
 * Which handle a touch took hold of: a corner, one side, or none of them —
 * and none means the whole rectangle moves.
 *
 * Corners win over sides where the two overlap, because a corner is the finer
 * of the two intentions and the one that is harder to hit by accident.
 */
private fun cropHandleAt(rect: RectF, at: Offset, slop: Float): Int {
    val corners = listOf(
        Offset(rect.left, rect.top),
        Offset(rect.right, rect.top),
        Offset(rect.right, rect.bottom),
        Offset(rect.left, rect.bottom),
    )
    corners.withIndex().minByOrNull { (_, corner) -> hypot(corner.x - at.x, corner.y - at.y) }
        ?.takeIf { hypot(it.value.x - at.x, it.value.y - at.y) <= slop }
        ?.let { return it.index }

    val withinRows = at.y >= rect.top - slop && at.y <= rect.bottom + slop
    val withinColumns = at.x >= rect.left - slop && at.x <= rect.right + slop
    if (withinRows && abs(at.x - rect.left) <= slop) return CROP_EDGE_LEFT
    if (withinRows && abs(at.x - rect.right) <= slop) return CROP_EDGE_RIGHT
    if (withinColumns && abs(at.y - rect.top) <= slop) return CROP_EDGE_TOP
    if (withinColumns && abs(at.y - rect.bottom) <= slop) return CROP_EDGE_BOTTOM
    return CROP_NONE
}

/**
 * The rectangle after a drag, kept on the picture and never inverted.
 *
 * With an aspect chosen the corner drags the width and the height follows,
 * otherwise picking 1:1 and adjusting would quietly stop being 1:1.
 */
private fun RectF.dragged(corner: Int, by: Offset, bounds: RectF, ratio: Float?): RectF {
    val maxX = bounds.width()
    val maxY = bounds.height()
    if (corner == CROP_NONE) {
        val dx = by.x.coerceIn(-left, maxX - right)
        val dy = by.y.coerceIn(-top, maxY - bottom)
        return RectF(left + dx, top + dy, right + dx, bottom + dy)
    }

    var l = left
    var t = top
    var r = right
    var b = bottom
    when (corner) {
        0 -> { l += by.x; t += by.y }
        1 -> { r += by.x; t += by.y }
        2 -> { r += by.x; b += by.y }
        3 -> { l += by.x; b += by.y }
        CROP_EDGE_LEFT -> l += by.x
        CROP_EDGE_RIGHT -> r += by.x
        CROP_EDGE_TOP -> t += by.y
        else -> b += by.y
    }
    l = l.coerceIn(0f, maxX)
    r = r.coerceIn(0f, maxX)
    t = t.coerceIn(0f, maxY)
    b = b.coerceIn(0f, maxY)
    val pullsLeftEdge = corner == 0 || corner == 3 || corner == CROP_EDGE_LEFT
    val pullsTopEdge = corner == 0 || corner == 1 || corner == CROP_EDGE_TOP
    if (r - l < CROP_MIN) if (pullsLeftEdge) l = r - CROP_MIN else r = l + CROP_MIN
    if (b - t < CROP_MIN) if (pullsTopEdge) t = b - CROP_MIN else b = t + CROP_MIN

    if (ratio != null) {
        val width = (r - l).coerceAtLeast(CROP_MIN)
        val height = width / ratio
        if (pullsTopEdge) t = b - height else b = t + height
        if (t < 0f) { t = 0f; b = height }
        if (b > maxY) { b = maxY; t = maxY - height }
    }
    return RectF(l.coerceIn(0f, maxX), t.coerceIn(0f, maxY), r.coerceIn(0f, maxX), b.coerceIn(0f, maxY))
}

private fun cropRect(bounds: RectF, aspect: CropAspect): RectF {
    val w = bounds.width()
    val h = bounds.height()
    val ratio = aspect.ratio ?: return RectF(0f, 0f, w, h)
    return if (w / h > ratio) {
        val width = h * ratio
        val left = (w - width) / 2f
        RectF(left, 0f, left + width, h)
    } else {
        val height = w / ratio
        val top = (h - height) / 2f
        RectF(0f, top, w, top + height)
    }
}

/** Whichever text or sticker sits nearest the finger, if any is near enough. */
private fun nearestLayerId(document: ImageEditDocument, at: Offset, slop: Float): Long? {
    val candidates = document.texts.map { it.id to it.position } +
        document.stickers.map { it.id to it.position }
    val nearest = candidates.minByOrNull { (_, position) -> hypot(position.x - at.x, position.y - at.y) }
        ?: return null
    return nearest.first.takeIf { hypot(nearest.second.x - at.x, nearest.second.y - at.y) <= slop }
}

private fun ImageEditDocument.movedLayer(
    id: Long,
    pan: Offset,
    zoom: Float,
    rotation: Float,
): ImageEditDocument = copy(
    texts = texts.map {
        if (it.id != id) it
        else it.copy(
            position = Offset(it.position.x + pan.x, it.position.y + pan.y),
            size = (it.size * zoom).coerceIn(source.width * 0.02f, source.width * 0.6f),
            angle = it.angle + rotation,
        )
    },
    stickers = stickers.map {
        if (it.id != id) it
        else it.copy(
            position = Offset(it.position.x + pan.x, it.position.y + pan.y),
            size = (it.size * zoom).coerceIn(source.width * 0.04f, source.width * 0.9f),
            angle = it.angle + rotation,
        )
    },
)
