package com.nodeloc.app.feature.media

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Share
import com.composables.icons.lucide.ThumbsUp
import com.composables.icons.lucide.X
import androidx.annotation.StringRes
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.ActionPillStyle
import com.nodeloc.app.core.design.LocalActionPillStyle
import com.nodeloc.app.core.design.PostActionRow
import com.nodeloc.app.core.design.PostActionState
import com.nodeloc.app.core.design.ReportDialog
import com.nodeloc.app.core.design.ReportTarget
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.ToastHost
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.html.PostImage
import com.nodeloc.app.core.model.Post
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.DiscourseFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * What the viewer can say about where the picture came from.
 *
 * Optional because the reader and chat open the same viewer on a picture with
 * no row behind it; without this the chrome is just the close button and the
 * overflow.
 */
data class ImageViewerSource(
    val node: String,
    val title: String,
    val authorName: String,
    val avatarUrl: String?,
    val avatarLetter: String,
    val likeCount: Int,
    val commentCount: Int,
    /**
     * The post behind the picture, where the caller has one to act on. Absent
     * for a chat attachment, which belongs to no post — and where it is absent
     * the bar falls back to reading the two counts out.
     */
    val actions: PostActionState? = null,
)

/**
 * What the viewer's action row does.
 *
 * Every callback is handed the row it belongs to. The video feed swipes on
 * through clips from topics the host screen never opened, so which post a tap
 * meant is a question only [PostActionState.topicId] answers.
 */
data class ViewerActions(
    val onLike: (PostActionState) -> Unit,
    val onCastVote: (PostActionState, VoteDirection, String?) -> Unit,
    val onComment: (PostActionState) -> Unit,
    val onRepost: (PostActionState) -> Unit,
    val onReward: (PostActionState) -> Unit,
)

/** A row's pictures, the one that was tapped, and who to credit them to. */
data class PostImageViewer(
    val images: List<String>,
    val index: Int,
    val source: ImageViewerSource,
)

/**
 * The one resolver both callers go through.
 *
 * Unresolvable URLs are dropped rather than kept as blanks — a blank is a page
 * that can never load and a counter that overstates itself — so the tapped
 * position has to be remapped onto whatever survived.
 */
private fun viewerFor(urls: List<String?>, tapped: Int, source: ImageViewerSource): PostImageViewer? {
    val resolved = urls.mapIndexedNotNull { position, raw ->
        DiscourseConfig.absoluteUrl(raw)?.let { position to it }
    }
    if (resolved.isEmpty()) return null
    return PostImageViewer(
        images = resolved.map { it.second },
        index = resolved.indexOfFirst { it.first == tapped }.coerceAtLeast(0),
        source = source,
    )
}

/** A feed row's pictures — the row already knows everything the bar shows. */
fun Post.imageViewerAt(index: Int): PostImageViewer? = viewerFor(
    // Expanded rows fall back to `imageUrl` when the topic carried no media
    // list of its own.
    urls = media.map { it.fullSizeUrl }.ifEmpty { listOf(imageUrl) },
    tapped = index,
    source = ImageViewerSource(
        node = node,
        title = title,
        authorName = authorName?.takeIf { it.isNotBlank() } ?: authorUsername.orEmpty(),
        avatarUrl = avatarUrl,
        avatarLetter = avatarLetter,
        likeCount = baseVotes,
        commentCount = comments,
    ),
)

/**
 * Pictures parsed out of a post's HTML, which is how the reader has them.
 *
 * [source] describes the *topic*, for the OP's pictures and a reply's alike:
 * the bar carries a title and a comment count, and pairing those with a reply's
 * own author and likes would be three facts about one post and two about
 * another.
 */
fun imageViewerFor(
    images: List<PostImage>,
    tapped: PostImage,
    source: ImageViewerSource,
): PostImageViewer? = viewerFor(
    urls = images.map { it.fullSizeUrl },
    tapped = images.indexOf(tapped),
    source = source,
)

/**
 * Full-screen image viewer.
 *
 * The signature gesture is pull-to-dismiss: dragging down shrinks the image and
 * fades the backdrop so the page underneath shows through. It is disabled while
 * zoomed in, where a vertical drag has to mean panning — the two cannot share a
 * direction without one of them feeling broken.
 */
@Composable
fun ImageViewerOverlay(
    images: List<String>,
    startIndex: Int,
    source: ImageViewerSource? = null,
    /** Absent where the host has nothing for the post's buttons to do. */
    actions: ViewerActions? = null,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            // Without this the dialog lays out inside the system bars, so a
            // viewer calling itself full-screen left the status bar strip in
            // the host page's colour with the backdrop stopping short of it —
            // and, because the image is moved by a graphics layer rather than
            // by layout, a panned image then drew over that strip.
            decorFitsSystemWindows = false,
        ),
    ) {
        LightSystemBarIcons()

        var reporting by remember { mutableStateOf<Int?>(null) }
        val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { images.size }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current

        // A plain float, deliberately. As an Animatable this was driven by a
        // coroutine launched per pointer event, and every event arriving in one
        // frame read the same not-yet-updated value — so all but the last one
        // was dropped and the image lagged behind the finger. Only the release
        // needs to animate, and that is the one place a coroutine is used.
        var dragY by remember { mutableFloatStateOf(0f) }
        var settle by remember { mutableStateOf<Job?>(null) }
        var zoomed by remember { mutableStateOf(false) }
        var chromeVisible by remember { mutableStateOf(true) }

        val context = LocalContext.current
        // Held rather than saved straight away: below Android 10 the write
        // needs a permission, and the answer arrives in a callback.
        var saveRequest by remember { mutableStateOf<String?>(null) }
        val permission = rememberLauncherForActivityResult(RequestPermission()) { granted ->
            if (!granted) {
                saveRequest = null
                ToastCenter.show(R.string.viewer_save_failed)
            }
        }
        LaunchedEffect(saveRequest) {
            val target = saveRequest ?: return@LaunchedEffect
            if (MediaSaver.needsLegacyPermission &&
                ContextCompat.checkSelfPermission(context, WRITE_EXTERNAL_STORAGE) != PERMISSION_GRANTED
            ) {
                permission.launch(WRITE_EXTERNAL_STORAGE)
                return@LaunchedEffect
            }
            MediaSaver.save(context, target)
                .onSuccess { ToastCenter.show(R.string.viewer_saved) }
                .onFailure { ToastCenter.show(R.string.viewer_save_failed) }
            saveRequest = null
        }

        val dismissThresholdPx = with(density) { 130.dp.toPx() }
        // Shrinks to 85% and fades the backdrop to 20% at most, so the page
        // underneath is visible but the image still reads as the subject.
        val progress = (dragY / (dismissThresholdPx * 3f)).coerceIn(0f, 1f)
        val backdropAlpha = (1f - progress).coerceIn(0.2f, 1f)
        val shrink = 1f - progress * 0.15f

        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = backdropAlpha))) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableImage(
                    url = images[page],
                    // Only the page being dragged follows the finger; the
                    // pager's neighbours are not part of the gesture.
                    dragY = if (page == pagerState.currentPage) dragY else 0f,
                    shrink = if (page == pagerState.currentPage) shrink else 1f,
                    onZoomChanged = { zoomed = it },
                    onTap = { chromeVisible = !chromeVisible },
                    onDragStart = { settle?.cancel() },
                    onDragDelta = { delta -> dragY = (dragY + delta).coerceAtLeast(0f) },
                    onDragEnd = { velocity ->
                        if (dragY > dismissThresholdPx || velocity > 2200f) {
                            onDismiss()
                        } else {
                            settle = scope.launch { animate(dragY, 0f) { value, _ -> dragY = value } }
                        }
                    },
                )
            }

            // One tap hides the lot and the next brings it back, so the
            // picture can be looked at without furniture over it.
            AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                ViewerTopBar(
                    node = source?.node,
                    counter = if (images.size > 1) "${pagerState.currentPage + 1}/${images.size}" else null,
                    onClose = onDismiss,
                    onShare = { shareWebUrl(context, images[pagerState.currentPage], source?.title) },
                    onSave = { saveRequest = images[pagerState.currentPage] },
                    onReport = flagTarget(source)?.let { id -> { reporting = id } },
                )
            }

            reporting?.let { ReportDialog(ReportTarget.Post(it), source?.authorName) { reporting = null } }

            // See the same line in the video viewer: the activity's toast host
            // is behind this window, so "saved to your gallery" was landing
            // somewhere nobody could see it.
            ToastHost()

            if (source != null) {
                AnimatedVisibility(
                    chromeVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    ViewerBottomBar(source, actions)
                }
            }
        }
    }
}

/**
 * The post this media hangs off, if there is one to flag and someone signed in
 * to flag it. Flagging while signed out is refused by the server, and an option
 * that always fails is worse than one that is not there.
 */
internal fun flagTarget(source: ImageViewerSource?): Int? =
    source?.actions?.postId?.takeIf { ServiceLocator.get.client.auth.isAuthenticated }

/**
 * Close, where the picture is from, and the overflow. Laid over a gradient
 * rather than a bar: on a photo that runs to the edge, white-on-white icons
 * are otherwise invisible, and a solid bar would crop the picture.
 */
@Composable
internal fun BoxScope.ViewerTopBar(
    node: String?,
    counter: String?,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    /** "Save image" is a lie over a clip; the video viewer says its own word. */
    @StringRes saveLabel: Int = R.string.viewer_save_image,
    /**
     * Absent where there is nothing to flag — a chat attachment, or a viewer
     * opened by someone not signed in, who has no standing to flag anything.
     */
    onReport: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        ViewerIconButton(Lucide.X, stringResource(R.string.common_close), Modifier.align(Alignment.CenterStart), onClose)

        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (node != null) {
                Text(node, style = Type.body(14, FontWeight.SemiBold), color = Color.White)
            }
            if (counter != null) {
                Text(counter, style = Type.body(11), color = Color.White.copy(alpha = 0.7f))
            }
        }

        Box(Modifier.align(Alignment.CenterEnd)) {
            ViewerIconButton(
                Lucide.EllipsisVertical,
                stringResource(R.string.viewer_more),
                Modifier,
            ) { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_share)) },
                    leadingIcon = { Icon(Lucide.Share, null, Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(saveLabel)) },
                    leadingIcon = { Icon(Lucide.Download, null, Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onSave() },
                )
                if (onReport != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_report)) },
                        leadingIcon = { Icon(Lucide.Flag, null, Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onReport() },
                    )
                }
            }
        }
    }
}

/** Who posted it and how the post is doing — the row this picture came from. */
@Composable
internal fun ViewerBottomBar(source: ImageViewerSource, actions: ViewerActions? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
            .navigationBarsPadding()
            .padding(horizontal = Space.page, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            RemoteAvatar(source.avatarUrl, source.avatarLetter, size = 28.dp)
            Text(
                source.authorName,
                style = Type.body(13, FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            source.title,
            style = Type.body(14),
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // The post's own buttons, in the post's own style: what is under the
        // picture here and what is under it in the reader are the same row, so
        // they are the same composable rather than two that agree for now.
        val post = source.actions
        if (post != null && actions != null) {
            // The page's pills are a light grey slab, which over a photograph
            // is a row of bright blocks competing with it. Here they are barely
            // there — enough to hold a white glyph off a white frame.
            CompositionLocalProvider(LocalActionPillStyle provides ActionPillStyle.OverMedia) {
                PostActionRow(
                    state = post,
                    onLike = { actions.onLike(post) },
                    onCastVote = { direction, face -> actions.onCastVote(post, direction, face) },
                    onComment = { actions.onComment(post) },
                    onRepost = { actions.onRepost(post) },
                    onReward = { actions.onReward(post) },
                )
            }
        } else {
            // Nothing to press — a chat attachment, or a caller that has not
            // been given the post behind the picture. Read the counts out.
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                ViewerMetric(
                    Lucide.ThumbsUp,
                    DiscourseFormat.count(source.likeCount),
                    stringResource(R.string.reader_like),
                )
                ViewerMetric(
                    Lucide.MessageCircle,
                    DiscourseFormat.count(source.commentCount),
                    stringResource(R.string.reader_comment),
                )
            }
        }
    }
}

@Composable
private fun ViewerMetric(icon: ImageVector, value: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
        Text(value, style = Type.body(12), color = Color.White.copy(alpha = 0.75f))
    }
}

/** 44dp of touch target around an 20dp icon; the icon alone is half that. */
@Composable
internal fun ViewerIconButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** The backdrop is black whichever theme the app is in, so the bar icons
 *  have to be light in both — the host page's setting is usually the opposite. */
@Composable
internal fun LightSystemBarIcons() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}

@Composable
private fun ZoomableImage(
    url: String,
    dragY: Float,
    shrink: Float,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val isZoomed = zoom > 1.02f

    val painter = rememberAsyncImagePainter(url)
    val state by painter.state.collectAsState()

    LaunchedEffect(isZoomed) { onZoomChanged(isZoomed) }

    /**
     * How far the image may be moved on each axis: half of however much of it
     * overflows the screen, and zero on an axis where it does not overflow at
     * all.
     *
     * This used to be computed from the container rather than the picture. A
     * `Fit` image is letterboxed on one axis, so on that axis the allowance was
     * pure invention — a wide screenshot could be dragged clean out of the
     * frame, leaving the viewer showing nothing but black.
     */
    fun maxPan(atZoom: Float): Offset {
        val intrinsic = painter.intrinsicSize
        if (containerSize.width == 0 || containerSize.height == 0) return Offset.Zero
        if (intrinsic == Size.Unspecified || intrinsic.width <= 0f || intrinsic.height <= 0f) return Offset.Zero
        val fit = min(containerSize.width / intrinsic.width, containerSize.height / intrinsic.height)
        val drawnWidth = intrinsic.width * fit * atZoom
        val drawnHeight = intrinsic.height * fit * atZoom
        return Offset(
            x = max(0f, (drawnWidth - containerSize.width) / 2f),
            y = max(0f, (drawnHeight - containerSize.height) / 2f),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            // The image is placed by a graphics layer, which does not clip to
            // its parent: without this a panned image spills over the bars.
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (zoom > 1.02f) {
                            zoom = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            zoom = 2.5f
                        }
                    },
                )
            }
            // Pinch and pan, but never a one-finger drag while at 1x.
            //
            // detectTransformGestures consumes any drag past touch slop, so
            // installed unconditionally it swallowed the horizontal swipes the
            // pager needs — changing picture barely worked. At 1x only a second
            // finger may start a zoom; everything else belongs to the pager or
            // to the dismiss gesture below.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canTransform = zoom > 1.02f || event.changes.count { it.pressed } > 1
                        if (canTransform) {
                            val gestureZoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            zoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                            if (zoom > 1.02f) {
                                val limit = maxPan(zoom)
                                offsetX = (offsetX + pan.x).coerceIn(-limit.x, limit.x)
                                offsetY = (offsetY + pan.y).coerceIn(-limit.y, limit.y)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // Installed only at 1×, which is exactly when a downward drag means
            // "dismiss" rather than "pan".
            .then(
                if (isZoomed) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        // Tracked, because the dismiss check reads a velocity
                        // and this passed a constant zero — so a quick flick
                        // down did nothing unless it also travelled far enough
                        // to pass the distance threshold.
                        val velocityTracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = {
                                velocityTracker.resetTracking()
                                onDragStart()
                            },
                            onVerticalDrag = { change, delta ->
                                velocityTracker.addPointerInputChange(change)
                                change.consume()
                                onDragDelta(delta)
                            },
                            onDragEnd = { onDragEnd(velocityTracker.calculateVelocity().y) },
                            onDragCancel = { onDragEnd(0f) },
                        )
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom * shrink
                    scaleY = zoom * shrink
                    translationX = offsetX
                    translationY = offsetY + dragY
                },
        )

        // On black, an image that never arrives is indistinguishable from one
        // that is still arriving, and both are indistinguishable from a bug.
        when (state) {
            is AsyncImagePainter.State.Loading -> NodelocLoader(height = 44.dp, tint = Color.White)
            is AsyncImagePainter.State.Error -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.image_load_failed),
                    style = Type.body(14),
                    color = Color.White.copy(alpha = 0.85f),
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable { painter.restart() }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.common_retry), style = Type.body(14), color = Color.White)
                }
            }
            else -> Unit
        }
    }
}
