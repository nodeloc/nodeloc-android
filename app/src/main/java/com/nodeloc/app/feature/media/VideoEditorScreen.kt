package com.nodeloc.app.feature.media

import android.net.Uri
import android.view.TextureView
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.composables.icons.lucide.Crop
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.X
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type as Typo
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The tools along the bottom, in the order the bar shows them. */
private enum class VideoToolId(
    @StringRes val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Crop(R.string.editor_crop, Lucide.Crop),
    Quality(R.string.video_quality, Lucide.Gauge),
    Sound(R.string.video_sound, Lucide.Volume2),
    Gif(R.string.video_gif, Lucide.Film),
}

/**
 * The clip, before it is sent.
 *
 * Same shape as the picture editor on purpose: black ground, the media as
 * large as it will go, a tool row along the bottom and one button at the end
 * of it. Trimming is not one of those tools — the filmstrip is always there,
 * because the length of a clip is not an optional decision the way a crop is.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoEditorDialog(
    source: Uri,
    /** Whether the button at the end sends, or hands back to a composer. */
    sends: Boolean,
    /**
     * What was decided about this clip before, when it is being reopened.
     *
     * Only the trim is left out: it is remade from the clip's own length below,
     * because [initial] arrives before the length is known and a range from a
     * previous session would be checked against nothing.
     */
    initial: VideoEdit? = null,
    onCancel: () -> Unit,
    onDone: (TrimmedMedia) -> Unit = {},
    /**
     * Hand the decisions over and leave, rather than encoding here first.
     *
     * What chat wants: the message belongs in the conversation from the moment
     * it is sent, and a re-encode is minutes to be spent with it already there
     * rather than minutes spent looking at an editor that has finished.
     */
    onSend: ((VideoSendRequest) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var probe by remember { mutableStateOf<VideoSource?>(null) }
    var edit by remember { mutableStateOf(VideoEdit()) }
    var tool by remember { mutableStateOf<VideoToolId?>(null) }
    var playheadMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    // Reading one frame before leaving, which is quick enough not to be worth
    // a number but long enough that the button should not take a second tap.
    var preparing by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        val measured = withContext(Dispatchers.IO) { probeVideo(context, source) }
        if (measured == null) {
            ToastCenter.show(R.string.trim_failed)
            onCancel()
        } else {
            probe = measured
            // The trim is clamped to the clip rather than taken on trust: a
            // range remembered from before is only meaningful against the
            // length it was chosen from.
            edit = (initial ?: VideoEdit()).copy(
                startMs = (initial?.startMs ?: 0L).coerceIn(0L, measured.durationMs),
                endMs = (initial?.endMs ?: measured.durationMs).coerceIn(0L, measured.durationMs),
            )
        }
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(source))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    // Muting the export mutes the preview too, or the decision is made deaf.
    LaunchedEffect(edit.muted) { player.volume = if (edit.muted) 0f else 1f }

    // The preview belongs to the selection, not to the file: playing past the
    // out point shows footage that is about to be thrown away.
    val liveEdit by rememberUpdatedState(edit)
    LaunchedEffect(player) {
        while (true) {
            playheadMs = player.currentPosition
            playing = player.isPlaying
            val bounds = liveEdit
            if (player.isPlaying && player.currentPosition >= bounds.endMs) {
                player.seekTo(bounds.startMs)
                player.pause()
            }
            delay(60)
        }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(EditorGround)) {
            val untouched = probe?.let { VideoEdit(0L, it.durationMs) }
            VideoTopBar(
                canReset = untouched != null && edit != untouched,
                busy = progress != null || preparing,
                onCancel = onCancel,
                onReset = { untouched?.let { edit = it } },
            )

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val measured = probe
                if (measured != null) {
                    VideoStage(
                        player = player,
                        probe = measured,
                        edit = edit,
                        cropping = tool == VideoToolId.Crop,
                        onCrop = { edit = edit.copy(crop = it) },
                    )
                }
                progress?.let { ExportOverlay(it) }
            }

            probe?.let { measured ->
                tool?.let { open ->
                    VideoToolControls(
                        tool = open,
                        probe = measured,
                        edit = edit,
                        onEdit = { edit = it },
                    )
                }

                VideoTimeline(
                    source = source,
                    probe = measured,
                    edit = edit,
                    playheadMs = playheadMs,
                    playing = playing,
                    onEdit = { edit = it },
                    onSeek = { player.seekTo(it) },
                    onPlayPause = {
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            if (player.currentPosition !in edit.startMs..edit.endMs) player.seekTo(edit.startMs)
                            player.play()
                        }
                    },
                )

                VideoTabBar(
                    tool = tool,
                    edit = edit,
                    busy = progress != null || preparing,
                    action = when {
                        tool != null -> EditorAction.FinishTool
                        sends -> EditorAction.Send
                        else -> EditorAction.Confirm
                    },
                    onSelect = { picked ->
                        when (picked) {
                            // Two of these are switches rather than panels:
                            // opening a row to hold one toggle is a row that
                            // says nothing the icon did not already say.
                            VideoToolId.Sound -> edit = edit.copy(muted = !edit.muted)
                            VideoToolId.Gif -> edit = edit.copy(asGif = !edit.asGif)
                            else -> tool = if (tool == picked) null else picked
                        }
                    },
                    onAction = {
                        if (tool != null) {
                            // Inside a tool the button only closes it. Nothing
                            // is exported on the way out: every decision a tool
                            // makes is already in `edit`, and the clip is
                            // encoded once, when the whole thing is sent.
                            tool = null
                        } else if (onSend != null) {
                            // Away immediately: the still is all that is needed
                            // to put the message on screen, and the encoding
                            // happens where the message already is.
                            player.pause()
                            preparing = true
                            scope.launch {
                                val poster = withContext(Dispatchers.IO) {
                                    posterFrame(context, source, edit.startMs, edit)
                                }
                                preparing = false
                                onSend(VideoSendRequest(source, measured, edit, poster))
                            }
                        } else {
                            player.pause()
                            progress = 0f
                            scope.launch {
                                val result = runCatchingCancellable {
                                    if (edit.asGif) {
                                        exportGif(context, source, edit) { progress = it }
                                    } else {
                                        exportClip(context, source, measured, edit) { progress = it }
                                    }
                                }
                                progress = null
                                result
                                    .onSuccess { media ->
                                        // Said here rather than by the server
                                        // after a minute of uploading.
                                        if (media.file.length() > UPLOAD_MAX_BYTES) {
                                            media.file.delete()
                                            ToastCenter.show(R.string.trim_too_large)
                                        } else {
                                            onDone(media)
                                        }
                                    }
                                    .onFailure { ToastCenter.show(R.string.trim_failed) }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun VideoTopBar(canReset: Boolean, busy: Boolean, onCancel: () -> Unit, onReset: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.X,
            stringResource(R.string.common_cancel),
            tint = EditorChromeColor,
            modifier = Modifier.size(22.dp).clickable(enabled = !busy, onClick = onCancel),
        )
        Box(Modifier.weight(1f))
        Icon(
            Lucide.RefreshCw,
            stringResource(R.string.editor_reset),
            tint = if (canReset && !busy) EditorChromeColor else EditorDimColor,
            modifier = Modifier.size(20.dp).clickable(enabled = canReset && !busy, onClick = onReset),
        )
    }
}

/**
 * The picture, and the crop frame over it when that tool is open.
 *
 * The player keeps the source aspect whatever the crop is: cropping a video is
 * choosing a window onto it, and shrinking the picture to the window as it is
 * dragged makes the window impossible to aim.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoStage(
    player: ExoPlayer,
    probe: VideoSource,
    edit: VideoEdit,
    cropping: Boolean,
    onCrop: (CropFraction) -> Unit,
) {
    // Turning is a container flag in the export, so the preview has to do it
    // here or the two disagree about which way up the clip is.
    val displayAspect = if (edit.turned) 1f / probe.aspect else probe.aspect

    // Room for the handles, given up only while there are handles to hold.
    // Watching a clip should use the whole screen; cropping one needs somewhere
    // outside the picture for a fingertip to land.
    val margin by animateDpAsState(if (cropping) CropMargin else 0.dp, label = "crop margin")

    // What of the clip is on screen.
    //
    // The whole frame while cropping, because a crop cannot be adjusted against
    // a picture that has already had the crop taken out of it. Everywhere else
    // it is the kept rectangle, so leaving the tool shows what was decided
    // rather than what it was decided from. Animated, because the shape of the
    // picture changes and a cut between two shapes reads as a glitch.
    val target = if (cropping) CropFraction.Whole else edit.crop ?: CropFraction.Whole
    val left by animateFloatAsState(target.left, label = "crop left")
    val top by animateFloatAsState(target.top, label = "crop top")
    val right by animateFloatAsState(target.right, label = "crop right")
    val bottom by animateFloatAsState(target.bottom, label = "crop bottom")
    val shown = CropFraction(left, top, right, bottom)

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The footprint the kept rectangle occupies, fitted into what is left
        // of the screen once the chrome and the grab margin have had their
        // share. A crop changes the shape of it, not only the size.
        val shownAspect = displayAspect * (shown.width / shown.height)
        val roomWidth = (maxWidth - margin * 2).coerceAtLeast(1.dp)
        val roomHeight = (maxHeight - margin * 2).coerceAtLeast(1.dp)
        val wide = roomWidth / roomHeight > shownAspect
        val shownWidth = if (wide) roomHeight * shownAspect else roomWidth
        val shownHeight = if (wide) roomHeight else roomWidth / shownAspect

        // The whole frame, at the scale that makes the kept part fill the
        // footprint. Most of it hangs off the sides, which is the point.
        val fullWidth = shownWidth / shown.width
        val fullHeight = shownHeight / shown.height

        // What the surface is before it is turned: a quarter turn swaps the two.
        val surfaceWidth = if (edit.turned) fullHeight else fullWidth
        val surfaceHeight = if (edit.turned) fullWidth else fullHeight

        // The margin is part of the box rather than padding around it, because
        // the crop frame is drawn over the whole thing: a handle sitting on the
        // very edge of the picture needs its grab radius to land somewhere.
        Box(
            Modifier.size(shownWidth + margin * 2, shownHeight + margin * 2),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                // Rounds and clips in one: what hangs off the sides is cut here
                // rather than by the surface, which is mostly off screen and
                // has no corners left to round.
                Modifier.size(shownWidth, shownHeight).clip(RoundedCornerShape(Radius.sm)),
                // Centred, and the displacement below is measured from the
                // centre to match. Asking for top-left here did not stop the
                // picture being centred, so a corner-based displacement landed
                // on top of a centring nobody had accounted for and every clip
                // showed its bottom edge whatever had been cropped.
                contentAlignment = Alignment.Center,
            ) {
                // A TextureView, not the PlayerView default.
                //
                // PlayerView draws into a SurfaceView, which is a separate
                // window layer punched through the hierarchy: Compose can place
                // it but cannot transform it, so `rotate` left the picture
                // upright inside a turned frame. A TextureView is an ordinary
                // view and turns, slides and scales with everything else.
                //
                // It also stretches to its bounds rather than letterboxing,
                // which is wanted here: the bounds are already the right shape.
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also(player::setVideoTextureView) },
                    modifier = Modifier
                        // Outermost, so it moves the whole element. Inside the
                        // chain it would slide the picture within its own
                        // bounds and the clip above would take off whatever it
                        // slid past.
                        //
                        // Two terms: the first puts the kept rectangle at the
                        // top left of the footprint, the second undoes the
                        // centring that `rotate` does about the layout box,
                        // which is a different shape from the picture whenever
                        // the clip has been turned.
                        // How far the middle of the kept rectangle is from the
                        // middle of the frame. A crop of the centre needs no
                        // displacement at all, which is worth having as the
                        // case you can check by looking.
                        //
                        // No correction for the turn is needed either: `rotate`
                        // spins about the layout box's own centre, and that
                        // centre is already where the alignment put it.
                        .offset(
                            x = fullWidth * (0.5f - shown.centerX),
                            y = fullHeight * (0.5f - shown.centerY),
                        )
                        // `requiredSize`, not `size`: the surface is deliberately
                        // larger than the footprint offered to it, and `size`
                        // is bounded by what the parent offers — which
                        // flattened a quarter-turned portrait clip into a band
                        // as tall as the landscape box it was about to fill.
                        .requiredSize(surfaceWidth, surfaceHeight)
                        .rotate(edit.rotation.toFloat()),
                )
            }
            // Over the footprint rather than the surface, because the crop is
            // fractions of what is on screen after the turn.
            if (cropping) {
                VideoCropFrame(
                    crop = edit.crop ?: CropFraction.Whole,
                    margin = margin,
                    onCrop = onCrop,
                )
            }
        }
    }
}

/**
 * How far the crop frame reaches past the picture.
 *
 * Wider than the grab radius, so a handle sitting exactly on an edge still has
 * its whole radius to be caught by. Without it the bottom edge and the two
 * bottom corners had only their inner half: everything below the picture fell
 * outside the overlay and never became a touch at all.
 */
private val CropMargin = 44.dp

/**
 * The crop frame over the clip.
 *
 * Simpler than the picture editor's: a video has no zoom and no straighten, so
 * the rectangle is only ever fractions of the box it is drawn in. The drawing
 * itself is shared, because a crop frame that is legible on a white photograph
 * took several passes to arrive at.
 */
@Composable
private fun VideoCropFrame(
    crop: CropFraction,
    /** How far the overlay reaches past the picture on every side. */
    margin: androidx.compose.ui.unit.Dp,
    onCrop: (CropFraction) -> Unit,
) {
    val density = LocalDensity.current
    val slop = with(density) { CropTouch.toPx() }
    val marginPx = with(density) { margin.toPx() }
    val live by rememberUpdatedState(crop)
    val emit by rememberUpdatedState(onCrop)
    var handle by remember { mutableStateOf(CropHandle.None) }
    var guides by remember { mutableStateOf(false) }
    // The picture inside the overlay, which is the overlay less its margin on
    // every side. Fractions are of this, never of the canvas.
    var picture by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    Canvas(
        Modifier
            .fillMaxSize()
            // Measured here rather than read off the draw scope: assigning
            // state during a draw asks for the next frame to draw again.
            .onSizeChanged {
                picture = androidx.compose.ui.geometry.Size(
                    (it.width - marginPx * 2).coerceAtLeast(1f),
                    (it.height - marginPx * 2).coerceAtLeast(1f),
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { at ->
                        handle = handleAt(live, at - Offset(marginPx, marginPx), picture, slop)
                        guides = true
                    },
                    onDragEnd = { guides = false },
                    onDragCancel = { guides = false },
                ) { change, delta ->
                    change.consume()
                    if (picture.width <= 0f || picture.height <= 0f) return@detectDragGestures
                    emit(live.dragged(handle, delta.x / picture.width, delta.y / picture.height))
                }
            },
    ) {
        val width = size.width - marginPx * 2
        val height = size.height - marginPx * 2
        drawCropChrome(
            marginPx + crop.left * width,
            marginPx + crop.top * height,
            marginPx + crop.right * width,
            marginPx + crop.bottom * height,
            guides,
        )
    }
}

internal enum class CropHandle { None, TopLeft, TopRight, BottomRight, BottomLeft, Left, Top, Right, Bottom }

/**
 * Which handle a touch took hold of, or none, which moves the whole window.
 *
 * Corners win over sides where the two overlap: a corner is the finer of the
 * two intentions and the harder of the two to hit by accident.
 */
internal fun handleAt(
    crop: CropFraction,
    at: Offset,
    box: androidx.compose.ui.geometry.Size,
    slop: Float,
): CropHandle {
    if (box.width <= 0f || box.height <= 0f) return CropHandle.None
    val left = crop.left * box.width
    val top = crop.top * box.height
    val right = crop.right * box.width
    val bottom = crop.bottom * box.height

    val corners = listOf(
        CropHandle.TopLeft to Offset(left, top),
        CropHandle.TopRight to Offset(right, top),
        CropHandle.BottomRight to Offset(right, bottom),
        CropHandle.BottomLeft to Offset(left, bottom),
    )
    corners.minByOrNull { hypot(it.second.x - at.x, it.second.y - at.y) }
        ?.takeIf { hypot(it.second.x - at.x, it.second.y - at.y) <= slop }
        ?.let { return it.first }

    val withinRows = at.y >= top - slop && at.y <= bottom + slop
    val withinColumns = at.x >= left - slop && at.x <= right + slop
    if (withinRows && abs(at.x - left) <= slop) return CropHandle.Left
    if (withinRows && abs(at.x - right) <= slop) return CropHandle.Right
    if (withinColumns && abs(at.y - top) <= slop) return CropHandle.Top
    if (withinColumns && abs(at.y - bottom) <= slop) return CropHandle.Bottom
    return CropHandle.None
}

/** Below this the frame has no interior left and no corner can be picked up. */
internal const val CROP_MIN_FRACTION = 0.12f

/**
 * The rectangle after a drag, kept inside the frame and never inverted.
 *
 * Every edge is clamped against the one *opposite* it as it was before the
 * drag, never against a value from this same drag. Only one edge of each pair
 * ever moves, so the opposite one still satisfies the rectangle's own
 * invariant and gives `coerceIn` a range whose ends are the right way round.
 *
 * Clamping against the moved value instead is what made dragging the bottom
 * edge up past the top throw: the range became `0f..-0.07f`, and `coerceIn`
 * refuses a range it cannot order.
 */
internal fun CropFraction.dragged(handle: CropHandle, dx: Float, dy: Float): CropFraction {
    if (handle == CropHandle.None) {
        val moveX = dx.coerceIn(-left, 1f - right)
        val moveY = dy.coerceIn(-top, 1f - bottom)
        return CropFraction(left + moveX, top + moveY, right + moveX, bottom + moveY)
    }
    var l = left
    var t = top
    var r = right
    var b = bottom
    when (handle) {
        CropHandle.TopLeft -> { l += dx; t += dy }
        CropHandle.TopRight -> { r += dx; t += dy }
        CropHandle.BottomRight -> { r += dx; b += dy }
        CropHandle.BottomLeft -> { l += dx; b += dy }
        CropHandle.Left -> l += dx
        CropHandle.Right -> r += dx
        CropHandle.Top -> t += dy
        CropHandle.Bottom -> b += dy
        CropHandle.None -> Unit
    }
    return CropFraction(
        left = l.coerceIn(0f, (right - CROP_MIN_FRACTION).coerceAtLeast(0f)),
        top = t.coerceIn(0f, (bottom - CROP_MIN_FRACTION).coerceAtLeast(0f)),
        right = r.coerceIn((left + CROP_MIN_FRACTION).coerceAtMost(1f), 1f),
        bottom = b.coerceIn((top + CROP_MIN_FRACTION).coerceAtMost(1f), 1f),
    )
}

/** How far along the encoder is, over the picture it is encoding. */
@Composable
private fun ExportOverlay(progress: Float) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${(progress * 100).roundToInt()}%",
            style = Typo.body(28, FontWeight.SemiBold),
            color = Color.White,
        )
    }
}

/** The filmstrip, with the play control and what the clip will cost beside it. */
@Composable
private fun VideoTimeline(
    source: Uri,
    probe: VideoSource,
    edit: VideoEdit,
    playheadMs: Long,
    playing: Boolean,
    onEdit: (VideoEdit) -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.page),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (playing) Lucide.Pause else Lucide.Play,
                stringResource(if (playing) R.string.video_pause else R.string.video_play),
                tint = EditorChromeColor,
                modifier = Modifier.size(22.dp).clickable(onClick = onPlayPause),
            )
            Box(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                VideoFilmstrip(
                    source = source,
                    probe = probe,
                    startMs = edit.startMs,
                    endMs = edit.endMs,
                    playheadMs = playheadMs,
                    onRangeChange = { start, end -> onEdit(edit.copy(startMs = start, endMs = end)) },
                    onSeek = onSeek,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Text(
                // An estimate, and marked as one. The number is the whole
                // reason the quality row is worth having.
                stringResource(
                    R.string.video_summary,
                    formatClipLength(edit.durationMs),
                    formatBytes(estimateBytes(probe, edit)),
                ),
                style = Typo.body(12),
                color = EditorDimColor,
            )
            // Said while the selection can still be changed, rather than
            // discovered afterwards in a clip that stops early.
            if (edit.asGif && edit.durationMs > gifCapSeconds() * 1000L) {
                Text(
                    stringResource(R.string.trim_gif_limit, gifCapSeconds()),
                    style = Typo.body(12),
                    color = Nocturne.accent2,
                )
            }
        }
    }
}

/** Whatever the open tool needs, in one strip above the timeline. */
@Composable
private fun VideoToolControls(
    tool: VideoToolId,
    probe: VideoSource,
    edit: VideoEdit,
    onEdit: (VideoEdit) -> Unit,
) {
    EditorControlRow {
        when (tool) {
            VideoToolId.Crop -> Row(
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Lucide.RotateCw,
                    stringResource(R.string.editor_rotate),
                    tint = EditorChromeColor,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onEdit(edit.copy(rotation = (edit.rotation + 90) % 360)) },
                )
                EditorChip(
                    stringResource(R.string.editor_crop_free),
                    selected = edit.crop == null,
                    onClick = { onEdit(edit.copy(crop = null)) },
                )
            }

            VideoToolId.Quality -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                VideoQuality.entries.forEach { rung ->
                    // A rung above what the clip already is would only scale it
                    // up, which costs a re-encode to add nothing.
                    val useful = rung.shortSide == null || rung.shortSide <= probe.shortSide
                    if (useful) {
                        EditorChip(
                            stringResource(rung.labelRes),
                            selected = edit.quality == rung,
                            onClick = { onEdit(edit.copy(quality = rung)) },
                        )
                    }
                }
            }

            VideoToolId.Sound, VideoToolId.Gif -> Unit
        }
    }
}

@Composable
private fun VideoTabBar(
    tool: VideoToolId?,
    edit: VideoEdit,
    busy: Boolean,
    /** What the button on the right finishes: the open tool, or the clip. */
    action: EditorAction,
    onSelect: (VideoToolId) -> Unit,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.s2, end = Space.page, top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoToolId.entries.forEach { candidate ->
            val on = when (candidate) {
                VideoToolId.Sound -> edit.muted
                VideoToolId.Gif -> edit.asGif
                else -> candidate == tool
            }
            val icon = when {
                candidate == VideoToolId.Sound && edit.muted -> Lucide.VolumeX
                else -> candidate.icon
            }
            Column(
                Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable(enabled = !busy) { onSelect(candidate) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    icon,
                    stringResource(candidate.labelRes),
                    tint = if (on) Nocturne.accent else EditorDimColor,
                    modifier = Modifier.size(22.dp),
                )
                Box(Modifier.height(3.dp))
                Text(
                    stringResource(candidate.labelRes),
                    style = Typo.body(10, if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (on) Nocturne.accent else EditorDimColor,
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
