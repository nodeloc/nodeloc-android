package com.nodeloc.app.feature.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.X
import com.composables.icons.lucide.VolumeX
import com.composables.icons.lucide.Volume2
import com.nodeloc.app.BuildConfig
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.BlurredMediaBackdrop
import androidx.core.content.ContextCompat
import com.nodeloc.app.core.design.PostActionState
import com.nodeloc.app.core.design.ReportDialog
import com.nodeloc.app.core.design.ReportTarget
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.ToastHost
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Radius
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global mute, shared by every inline player.
 *
 * Feed video autoplays muted — unmuting one video is a statement about all of
 * them, exactly as it works on iOS, so the state cannot live per player.
 */
object VideoMuteState {
    private val _muted = MutableStateFlow(true)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    fun toggle() {
        _muted.value = !_muted.value
    }
}

/**
 * Whether a clip is playing full screen.
 *
 * The screen the viewer opened from is still composed underneath it, and an
 * inline player decides for itself whether it is on screen — which, behind a
 * dialog, it still believes it is. So the post went on playing its own copy of
 * the clip under the one being watched, silent only for as long as the global
 * mute held: unmuting the viewer unmuted the post as well, and the first clip
 * could still be heard three swipes into the feed.
 *
 * A count rather than a flag, because closing one viewer while another is
 * opening must not hand the screens behind them a false all-clear.
 */
object VideoViewerState {
    private var depth = 0
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    fun enter() {
        depth += 1
        _open.value = true
    }

    fun leave() {
        depth = (depth - 1).coerceAtLeast(0)
        _open.value = depth > 0
    }
}

/**
 * Inline video, wherever a post shows one.
 *
 * It works out for itself whether it is on screen rather than being told, which
 * is what every caller kept getting wrong: the node list and both search
 * screens never passed the flag at all, so their videos loaded a first frame
 * and sat there. Sixty percent visible, because a player peeking over the fold
 * should not start.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun InlineVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    /** Usually the clip's poster: its own first frame, blurred behind it. */
    backdropUrl: String? = null,
    /**
     * Required for the player to be tappable at all: `PlayerView` swallows
     * touches, so a row that only made its *own* surface clickable never heard
     * about a tap that landed on the video — a card whose picture is a clip
     * could not be opened by tapping the clip.
     */
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val muted by VideoMuteState.muted.collectAsState()
    var onScreen by remember { mutableStateOf(false) }

    val player = remember(url) {
        buildPlayer(context).apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
            prepare()
        }
    }

    val viewerOpen by VideoViewerState.open.collectAsState()
    LaunchedEffect(onScreen, viewerOpen) { player.playWhenReady = onScreen && !viewerOpen }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.md))
            .onGloballyPositioned { coordinates ->
                val height = coordinates.size.height
                onScreen = height > 0 && coordinates.boundsInWindow().height >= height * 0.6f
            }
            .then(
                if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
            ),
    ) {
        BlurredMediaBackdrop(backdropUrl, Modifier.fillMaxSize())
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    // A portrait clip in a landscape card leaves bands at the
                    // sides, and PlayerView paints them — its own background
                    // and, until the first frame lands, its shutter. Both are
                    // opaque black by default, which would bury the backdrop.
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { VideoMuteState.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (muted) Lucide.VolumeX else Lucide.Volume2,
                stringResource(if (muted) R.string.media_unmute else R.string.media_mute),
                tint = Color.White,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * Every player in the app, built the one way.
 *
 * Through the app's own OkHttp client, because ExoPlayer's default HTTP stack
 * carries no cookies and an upload here can need the session: the two videos in
 * n/show answer 404 to an anonymous request while the images beside them answer
 * 200. The player had no listener either, so that 404 arrived as a black
 * rectangle and nothing else — no message, no log, in a screen that otherwise
 * never lets a failure pass without saying so.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun buildPlayer(context: Context): ExoPlayer {
    val http = OkHttpDataSource.Factory(ServiceLocator.get.client.http)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (BuildConfig.DEBUG) Log.w("VideoPlayer", "playback failed: ${error.errorCodeName}", error)
                    ToastCenter.show(R.string.media_video_failed)
                }
            })
        }
}

/**
 * Full-screen player, laid out like the image viewer because it opens from the
 * same places and gets closed the same way: close at top left, where the clip
 * came from at top centre, the overflow at top right, and along the bottom the
 * transport and then the post it belongs to.
 *
 * A tap on the picture hides the lot and the next brings it back, exactly as
 * the image viewer does. Playing and pausing belongs to the one button that
 * says so: a whole-screen tap target for it would fire on every attempt to get
 * the furniture out of the way.
 *
 * Swiping up plays whatever discourse-anyvideo suggests next, resolved by
 * [VideoFeedQueue] while this clip runs. Swiping down walks back through the
 * clips already watched, and only on the first one — the clip the post itself
 * holds — does it leave for the screen underneath.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoViewerOverlay(
    url: String,
    source: ImageViewerSource? = null,
    /** Held out of the suggestions: nobody wants the clip they are watching. */
    topicId: Int? = null,
    /** Absent where the host has nothing for the post's buttons to do. */
    actions: ViewerActions? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Only the suggestions are held. The clip this opened on is rebuilt from
    // what the host passes in every time, so a vote cast on it in here shows
    // the count the reader arrived at rather than the one it opened with.
    val suggestions = remember(url) { mutableStateListOf<VideoFeedItem>() }
    val opening = VideoFeedItem(topicId, url, source)
    val queue = remember(url) { VideoFeedQueue(topicId) }
    var index by remember(url) { mutableIntStateOf(0) }
    var exhausted by remember(url) { mutableStateOf(false) }

    fun itemAt(position: Int): VideoFeedItem? = when {
        position < 0 -> null
        position == 0 -> opening
        else -> suggestions.getOrNull(position - 1)
    }

    // One ahead and no further. The next clip costs two requests and both have
    // to be paid before the finger moves; a deeper queue would spend them on
    // clips nobody ever swipes to.
    LaunchedEffect(suggestions, index) {
        if (exhausted || index < suggestions.size) return@LaunchedEffect
        val next = queue.next()
        if (next == null) exhausted = true else suggestions += next
    }

    val current = itemAt(index) ?: opening
    val previous = itemAt(index - 1)
    val upcoming = itemAt(index + 1)
    val players = remember(url) { VideoFeedPlayers(context) }
    DisposableEffect(players) { onDispose { players.release() } }
    val currentPlayer = players.player(current.url)

    // Every player the swipe can reach is alive at once, so exactly one of them
    // may be running: a clip swiped past is kept now that the feed can be
    // walked back, and kept is not paused. Volume goes first — a player built
    // for the clip below starts at full, and setting it after the play would
    // let a muted feed shout for a frame.
    val muted by VideoMuteState.muted.collectAsState()
    LaunchedEffect(players, previous?.url, current.url, upcoming?.url, muted) {
        players.retain(listOfNotNull(previous?.url, current.url, upcoming?.url))
        players.volume(if (muted) 0f else 1f)
        players.playOnly(current.url)
    }

    // The screen behind is still composed, and its own inline player still
    // reckons itself on screen, so without this the post underneath keeps
    // running its copy of the clip — inaudible only for as long as the global
    // mute holds, and the viewer's unmute button lifts that for both.
    DisposableEffect(Unit) {
        VideoViewerState.enter()
        onDispose { VideoViewerState.leave() }
    }

    // A vote on a clip swiped to. The host's view model speaks for its own
    // topic alone, so this one is cast here and written back into the item the
    // row is drawn from — put up first and taken down again on failure, the
    // way every other vote in the app behaves. Everything else a suggestion's
    // buttons do needs a sheet or a composer, so it goes back to the host.
    fun castVote(post: PostActionState, tapped: VoteDirection, face: String?) {
        val score = post.voteScore
        if (post.topicId == topicId || post.postId == null || score == null ||
            !ServiceLocator.get.client.auth.isAuthenticated
        ) {
            actions?.onCastVote(post, tapped, face)
            return
        }
        val position = suggestions.indexOfFirst { it.source?.actions?.postId == post.postId }
        if (position < 0) return
        // Picking a face always casts that direction; only the bare arrow
        // toggles a vote back off.
        val next = if (face != null) tapped else post.voteDirection.after(tapped)
        if (next == VoteDirection.Down && !post.canVoteDown) {
            ToastCenter.show(R.string.vote_down_not_allowed)
            return
        }

        fun write(state: PostActionState) {
            val item = suggestions.getOrNull(position) ?: return
            suggestions[position] = item.copy(source = item.source?.copy(actions = state))
        }

        write(post.copy(voteDirection = next, voteScore = score + post.voteDirection.stepTo(next)))
        scope.launch {
            runCatchingCancellable { ServiceLocator.get.client.castVote(post.postId, next, face) }
                .onSuccess { result ->
                    write(
                        post.copy(
                            voteDirection = VoteDirection.from(result.voteDirection),
                            voteScore = result.voteScore ?: score,
                        ),
                    )
                }
                .onFailure {
                    write(post)
                    ToastCenter.showError(it)
                }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        LightSystemBarIcons()
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
                .onFailure { ToastCenter.show(R.string.viewer_save_video_failed) }
            saveRequest = null
        }

        var chromeVisible by remember { mutableStateOf(true) }
        var reporting by remember { mutableStateOf<Int?>(null) }
        var viewport by remember { mutableIntStateOf(0) }
        // A plain float, not an Animatable: the drag would then have to reach it
        // through a coroutine per delta, and a snapTo still queued when the finger
        // lifts cancels the switch animation — and with it the swipe.
        var offset by remember { mutableFloatStateOf(0f) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { viewport = it.height },
        ) {
            val pages = listOfNotNull(
                previous?.let { -1 to it },
                0 to current,
                upcoming?.let { 1 to it },
            )
            pages.forEach { (slot, item) ->
                // Keyed by URL, so a clip keeps its surface — and the seconds
                // it has spent buffering or playing — as a swipe moves it from
                // one slot to the next.
                key(item.url) {
                    VideoSurface(
                        players.player(item.url),
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationY = offset + slot * viewport.toFloat() },
                    )
                }
            }

            // The gestures ride above the clips and below the chrome.
            // `PlayerView` swallows touches, so a handler on the parent never
            // hears them, and one on the clip itself would be dragged out from
            // under the finger by the very translation it is producing.
            Box(
                Modifier
                    .matchParentSize()
                    .draggable(
                        state = rememberDraggableState { delta ->
                            val height = viewport.toFloat()
                            // With nothing resolved below, the drag still
                            // gives — just enough to answer the finger — and
                            // springs back. Downwards it always gives fully:
                            // on the first clip that travel is the way out.
                            val ceiling = if (upcoming == null) -height * STALL_FRACTION else -height
                            offset = (offset + delta).coerceIn(ceiling, height)
                        },
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            val height = viewport.toFloat().coerceAtLeast(1f)
                            val up = offset < -height * SWITCH_FRACTION || velocity < -FLING_VELOCITY
                            val down = offset > height * SWITCH_FRACTION || velocity > FLING_VELOCITY
                            when {
                                up && upcoming != null -> {
                                    animate(offset, -height, velocity, tween(SWITCH_MILLIS)) { value, _ ->
                                        offset = value
                                    }
                                    index += 1
                                    offset = 0f
                                }
                                down && previous != null -> {
                                    animate(offset, height, velocity, tween(SWITCH_MILLIS)) { value, _ ->
                                        offset = value
                                    }
                                    index -= 1
                                    offset = 0f
                                }
                                // Nothing above this one, so down is the way
                                // back to the post it was opened from.
                                down -> onDismiss()
                                else -> {
                                    // Only once the site has actually run out:
                                    // a swipe during the two requests is early,
                                    // not refused.
                                    if (exhausted && offset < 0f) {
                                        ToastCenter.show(R.string.media_no_more_videos)
                                    }
                                    animate(offset, 0f, velocity, tween(SWITCH_MILLIS)) { value, _ ->
                                        offset = value
                                    }
                                }
                            }
                        },
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { chromeVisible = !chromeVisible },
            )

            AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                ViewerTopBar(
                    node = current.source?.node,
                    counter = null,
                    onClose = onDismiss,
                    onShare = { shareWebUrl(context, current.url, current.source?.title) },
                    onSave = { saveRequest = current.url },
                    saveLabel = R.string.viewer_save_video,
                    // The clip on screen, which past the first swipe is not the
                    // post this viewer was opened from.
                    onReport = flagTarget(current.source)?.let { id -> { reporting = id } },
                )
            }

            reporting?.let { ReportDialog(ReportTarget.Post(it), current.source?.authorName) { reporting = null } }

            // The app's own toast host lives in the activity's window, which
            // this dialog covers: "saved to your gallery" was being posted to
            // a window nobody could see. One here puts it in front of the clip
            // it is talking about. Both read the same state, and only the one
            // on top is ever visible, so there is no double.
            ToastHost()

            AnimatedVisibility(
                chromeVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    Modifier.background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))),
                    ),
                ) {
                    PlaybackBar(currentPlayer)
                    current.source?.let {
                        ViewerBottomBar(it, actions?.copy(onCastVote = ::castVote))
                    }
                }
            }
        }
    }
}

/** Past this much of the screen, the drag has chosen; before it, it springs back. */
private const val SWITCH_FRACTION = 0.18f

/** How far an upward drag gives when there is nothing below it yet. */
private const val STALL_FRACTION = 0.08f

/** px/s at which a flick decides on its own, however short it was. */
private const val FLING_VELOCITY = 900f

private const val SWITCH_MILLIS = 220

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                // Our own transport, so the built-in one would only be a
                // second set of controls fighting for the same taps.
                useController = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { it.player = player },
        modifier = modifier,
    )
}

/**
 * The players the feed needs: the clip on screen and the ones a single swipe
 * either way can reach.
 *
 * Kept by URL rather than by position, because the swipe changes the position
 * of every clip and rebuilding on each one would throw away the buffering that
 * the whole gesture depends on — a swipe that lands on a spinner is the one
 * thing this feed cannot afford.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class VideoFeedPlayers(private val context: Context) {
    private val players = mutableMapOf<String, ExoPlayer>()

    fun player(url: String): ExoPlayer = players.getOrPut(url) {
        buildPlayer(context).apply {
            setMediaItem(MediaItem.fromUri(url))
            // A clip that stops on its last frame leaves nothing to look at
            // and no way back to the start; the feed loops until the next swipe.
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
            prepare()
        }
    }

    fun retain(urls: Collection<String>) {
        players.keys.filterNot { it in urls }.toList().forEach { players.remove(it)?.release() }
    }

    /** The one clip on screen runs; the ones a swipe away wait where they are. */
    fun playOnly(url: String) {
        players.forEach { (key, player) -> player.playWhenReady = key == url }
    }

    fun volume(level: Float) {
        players.values.forEach { it.volume = level }
    }

    fun release() {
        players.values.forEach { it.release() }
        players.clear()
    }
}

/**
 * Scrubber, elapsed and remaining, and the one button.
 *
 * The scrubber is ExoPlayer's own [DefaultTimeBar] rather than a Material
 * slider: a slider is built for choosing a value and looks it — a fat pill of a
 * thumb and a dot at the far end — where this is the bar every video player
 * has, and it draws the buffered range for free.
 *
 * Polled rather than driven by a listener because there is no callback for "the
 * playhead moved" — position only ever changes by the clock running.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlaybackBar(player: ExoPlayer) {
    // Keyed on the player, because the feed swaps it under this bar: a clip
    // paused before a swipe would otherwise leave the next one — which starts
    // playing — showing a play button and a playhead frozen where the last one
    // stopped.
    var playing by remember(player) { mutableStateOf(player.playWhenReady) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var buffered by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    // The bar owns the playhead while a finger is on it, or the poll below
    // drags the thumb back out from under the thumb.
    var scrubbing by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playing = playWhenReady
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            buffered = player.bufferedPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: 0L
            delay(200)
        }
    }

    val muted by VideoMuteState.muted.collectAsState()

    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.s3, vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerIconButton(
            if (playing) Lucide.Pause else Lucide.Play,
            stringResource(if (playing) R.string.common_pause else R.string.common_play),
        ) { player.playWhenReady = !player.playWhenReady }
        Text(clock(position), style = Type.body(11), color = Color.White.copy(alpha = 0.8f))
        AndroidView(
            factory = { ctx ->
                DefaultTimeBar(ctx).apply {
                    setPlayedColor(android.graphics.Color.WHITE)
                    setScrubberColor(android.graphics.Color.WHITE)
                    setBufferedColor(0x66FFFFFF)
                    setUnplayedColor(0x33FFFFFF)
                    addListener(object : TimeBar.OnScrubListener {
                        override fun onScrubStart(timeBar: TimeBar, position: Long) {
                            scrubbing = true
                        }

                        override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit

                        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                            scrubbing = false
                            if (!canceled) player.seekTo(position)
                        }
                    })
                }
            },
            update = { bar ->
                bar.setDuration(duration)
                bar.setBufferedPosition(buffered)
                if (!scrubbing) bar.setPosition(position)
            },
            modifier = Modifier.weight(1f).padding(horizontal = Space.s3),
        )
        Text(clock(duration), style = Type.body(11), color = Color.White.copy(alpha = 0.8f))
        // Shares the state the feed's players read, so silencing a clip here
        // keeps it silent when the list scrolls past the next one.
        ViewerIconButton(
            if (muted) Lucide.VolumeX else Lucide.Volume2,
            stringResource(if (muted) R.string.media_unmute else R.string.media_mute),
        ) { VideoMuteState.toggle() }
    }
}

/** `m:ss`, and `h:mm:ss` only once there is an hour to show. */
private fun clock(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}
