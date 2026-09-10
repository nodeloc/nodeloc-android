package com.nodeloc.app.feature.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size shown under the filmstrip is the only number a person has to go on
 * when choosing a quality, so it has to agree with the length shown beside it
 * and move the way the rungs say it will. On the device the two disagreed and
 * neither the screenshots nor the arithmetic settled which was wrong; these
 * pin the answer where it can be read.
 */
class VideoEditTest {

    private val portrait = VideoSource(
        durationMs = 2_786L,
        width = 1080,
        height = 1920,
        hasAudio = true,
        bitrate = 9_600_000,
    )

    private fun whole(quality: VideoQuality) =
        VideoEdit(startMs = 0L, endMs = portrait.durationMs, quality = quality)

    @Test
    fun `the length under the strip is the length the size is computed from`() {
        val edit = whole(VideoQuality.Medium)
        assertEquals("2.8s", formatClipLength(edit.durationMs))

        // 720p plus 128kbps of audio, over the same 2.786 seconds.
        val expected = ((2_500_000L + 128_000L) * 2786L / 1000L / 8L)
        assertEquals(expected, estimateBytes(portrait, edit), expected / 50.0)
    }

    @Test
    fun `each rung costs about what its bitrate says`() {
        val low = estimateBytes(portrait, whole(VideoQuality.Low))
        val medium = estimateBytes(portrait, whole(VideoQuality.Medium))
        val high = estimateBytes(portrait, whole(VideoQuality.High))
        assertTrue("480p under 720p", low < medium)
        assertTrue("720p under 1080p", medium < high)

        // The audio rides along at a fixed rate, so the ratio between rungs is
        // a little flatter than the ratio between their video bitrates.
        val ratio = medium.toDouble() / low
        assertEquals(2_628_000.0 / 1_328_000.0, ratio, 0.02)
    }

    @Test
    fun `original is the file itself, not a rung`() {
        val edit = whole(VideoQuality.Original)
        assertFalse("nothing to re-encode", edit.reencodes(portrait))
        // Source bitrate over the whole clip, which is the file size back.
        assertEquals(9_600_000L * 2786L / 1000L / 8L, estimateBytes(portrait, edit), 40_000.0)
    }

    @Test
    fun `a turn alone does not force a re-encode`() {
        val turned = whole(VideoQuality.Original).copy(rotation = 90)
        assertFalse(turned.reencodes(portrait))
        assertEquals(1920, turned.displayWidth(portrait))
        assertEquals(1080, turned.displayHeight(portrait))
    }

    @Test
    fun `the cap is measured against the crop, not the original`() {
        val edit = whole(VideoQuality.High).copy(
            // A slice a third as wide: 360 across, under every rung.
            crop = CropFraction(0.33f, 0f, 0.66f, 1f),
        )
        assertTrue("a crop always re-encodes", edit.reencodes(portrait))
        assertEquals(356, edit.croppedShortSide(portrait))
    }

    @Test
    fun `a turned crop measures the side it actually has`() {
        val edit = whole(VideoQuality.High).copy(
            rotation = 90,
            crop = CropFraction(0f, 0.25f, 1f, 0.75f),
        )
        // Turned, the frame is 1920x1080; half its height is 540.
        assertEquals(540, edit.croppedShortSide(portrait))
    }

    @Test
    fun `an empty selection costs nothing and says so`() {
        val edit = VideoEdit(startMs = 500L, endMs = 500L)
        assertEquals(0L, estimateBytes(portrait, edit))
    }

    @Test
    fun `muting takes the audio out of the estimate`() {
        val heard = estimateBytes(portrait, whole(VideoQuality.Medium))
        val silent = estimateBytes(portrait, whole(VideoQuality.Medium).copy(muted = true))
        assertTrue(silent < heard)
        assertEquals(128_000L * 2786L / 1000L / 8L, (heard - silent), 2_000.0)
    }

    @Test
    fun `a minute reads as minutes and seconds`() {
        assertEquals("12.4s", formatClipLength(12_400L))
        assertEquals("1:04", formatClipLength(64_000L))
    }

    @Test
    fun `sizes cross over to megabytes where a reader expects`() {
        assertEquals("840 KB", formatBytes(860_160L))
        assertEquals("1.0 MB", formatBytes(1_048_576L))
    }

    // ------------------------------------------------------- the crop frame --

    /**
     * Dragging an edge past the one opposite it used to throw.
     *
     * Each edge was clamped against the *moved* value of its opposite, so
     * pulling the bottom up above the top asked `coerceIn` for `0f..-0.07f`
     * and it refused a range it could not order. It read on the device as the
     * bottom edge being unusable.
     */
    @Test
    fun `an edge dragged past its opposite stops rather than throwing`() {
        val whole = CropFraction.Whole
        val flattened = whole.dragged(CropHandle.Bottom, dx = 0f, dy = -2f)
        assertTrue("bottom stays below top", flattened.bottom > flattened.top)
        assertEquals(CROP_MIN_FRACTION, flattened.height, 0.001f)

        val squashed = whole.dragged(CropHandle.Right, dx = -2f, dy = 0f)
        assertTrue("right stays beyond left", squashed.right > squashed.left)
        assertEquals(CROP_MIN_FRACTION, squashed.width, 0.001f)
    }

    @Test
    fun `every handle survives being dragged to both extremes`() {
        val corners = listOf(0.4f, -0.4f, 3f, -3f)
        for (handle in CropHandle.entries) {
            for (dx in corners) {
                for (dy in corners) {
                    val result = CropFraction(0.2f, 0.2f, 0.8f, 0.8f).dragged(handle, dx, dy)
                    assertTrue("$handle stays on the frame", result.left >= -0.001f && result.right <= 1.001f)
                    assertTrue("$handle stays on the frame", result.top >= -0.001f && result.bottom <= 1.001f)
                    assertTrue("$handle keeps an interior", result.width > 0f && result.height > 0f)
                }
            }
        }
    }

    @Test
    fun `dragging the middle moves the window without resizing it`() {
        val start = CropFraction(0.2f, 0.2f, 0.6f, 0.6f)
        val moved = start.dragged(CropHandle.None, dx = 0.1f, dy = 0.1f)
        assertEquals(start.width, moved.width, 0.001f)
        assertEquals(start.height, moved.height, 0.001f)
        assertEquals(0.3f, moved.left, 0.001f)

        // And it stops at the edge rather than sliding off it.
        val pushed = start.dragged(CropHandle.None, dx = 5f, dy = 0f)
        assertEquals(1f, pushed.right, 0.001f)
        assertEquals(start.width, pushed.width, 0.001f)
    }

    /**
     * A corner sitting on the very edge of the picture is caught from outside
     * it. The overlay reaches past the picture for exactly this reason, and
     * without it the bottom corners had only their inner half.
     */
    @Test
    fun `a corner is grabbed from beyond the edge of the picture`() {
        val picture = Size(600f, 400f)
        val slop = 40f
        assertEquals(
            CropHandle.BottomLeft,
            handleAt(CropFraction.Whole, Offset(-12f, 420f), picture, slop),
        )
        assertEquals(
            CropHandle.Bottom,
            handleAt(CropFraction.Whole, Offset(300f, 425f), picture, slop),
        )
        // Well inside is nothing, which moves the whole window.
        assertEquals(
            CropHandle.None,
            handleAt(CropFraction.Whole, Offset(300f, 200f), picture, slop),
        )
    }

    /**
     * The preview places the kept rectangle by its centre.
     *
     * A crop of the middle must come out needing no displacement whatever: that
     * is the case a person can check by looking, and it is the one that was
     * wrong. Placing from a corner instead meant the displacement was applied
     * on top of a centring that was happening anyway, and every clip showed its
     * bottom edge no matter what had been cropped.
     */
    @Test
    fun `a crop of the middle sits where the picture already is`() {
        val middle = CropFraction(0.2f, 0.3f, 0.8f, 0.7f)
        assertEquals(0.5f, middle.centerX, 0.0001f)
        assertEquals(0.5f, middle.centerY, 0.0001f)
    }

    @Test
    fun `an off-centre crop is displaced towards the part it keeps`() {
        // The top third: its middle is a sixth down, so the frame slides down
        // by a third of itself to bring that under the window.
        val topThird = CropFraction(0f, 0f, 1f, 0.33f)
        assertEquals(0.165f, topThird.centerY, 0.0001f)
        assertEquals(0.335f, 0.5f - topThird.centerY, 0.0001f)

        // And the bottom third the same distance the other way.
        val bottomThird = CropFraction(0f, 0.67f, 1f, 1f)
        assertEquals(-0.335f, 0.5f - bottomThird.centerY, 0.0001f)
    }

    private fun assertEquals(expected: Long, actual: Long, tolerance: Double) {
        assertEquals(expected.toDouble(), actual.toDouble(), tolerance)
    }
}
