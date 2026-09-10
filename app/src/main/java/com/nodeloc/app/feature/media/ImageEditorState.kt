package com.nodeloc.app.feature.media

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import com.nodeloc.app.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The editable document: a source bitmap, how it is being held, and the layers
 * drawn on top of it.
 *
 * Every layer is stored in **source-image coordinates**, never in screen
 * points, so the preview and the exported bitmap are one drawing at two
 * scales. Straightening is a transform applied to that whole space rather than
 * a new bitmap — which is what lets a picture be rotated after it has been
 * drawn on without the drawing sliding off it.
 */
data class BrushStroke(
    val points: List<Offset>,
    val color: Color,
    val width: Float,
)

/**
 * How a covered rectangle is covered.
 *
 * Three of the four are one mechanism: the picture scaled down and back up
 * again, coarse or fine, with the smoothing on or off. Off gives square
 * blocks; on gives a blur, because that is all a blur is at this size. The
 * fourth paints over instead of coarsening, for when nothing about what was
 * there should survive at all.
 */
enum class MosaicStyle(
    @StringRes val labelRes: Int,
    /** Blocks across the long edge; null paints solid instead. */
    val blocks: Int?,
    val smooth: Boolean,
    /**
     * Whether the colours are cut down to a handful of levels as well.
     *
     * What separates a pixel-art look from a plain mosaic. Averaging alone
     * gives large squares that still hold every shade of the photograph;
     * flattening the palette on top is what makes them read as pixels rather
     * than as a picture out of focus.
     */
    val posterize: Boolean = false,
) {
    Blocks(R.string.editor_mosaic_blocks, 34, false),
    Fine(R.string.editor_mosaic_fine, 72, false),
    Pixel(R.string.editor_mosaic_pixel, 26, false, posterize = true),
    Blur(R.string.editor_mosaic_blur, 55, true),
    Solid(R.string.editor_mosaic_solid, null, false),
}

/**
 * A rectangle of the picture, covered.
 *
 * A rectangle rather than a smear: what wants hiding is a face, an address or
 * a name, and all three are rectangles. Held in source coordinates like every
 * other layer, so it turns with the picture and can be undone.
 */
data class MosaicPatch(
    val rect: RectF,
    val style: MosaicStyle,
)

/**
 * The coarsened copies, made when a style is first used and kept after.
 *
 * One is a full-size bitmap; making all of them up front would be tens of
 * megabytes for looks nobody chose.
 */
class MosaicSource(private val source: Bitmap) {
    private val cache = mutableMapOf<MosaicStyle, Bitmap>()

    fun forStyle(style: MosaicStyle): Bitmap? =
        style.blocks?.let { blocks ->
            cache.getOrPut(style) { mosaicBitmap(source, blocks, style.posterize) }
        }
}

/** How a text layer is set off from whatever is behind it. */
enum class TextStyle(@StringRes val labelRes: Int) {
    Plain(R.string.editor_text_plain),
    Outlined(R.string.editor_text_outlined),
    Filled(R.string.editor_text_filled),
}

data class TextLayer(
    val id: Long,
    val text: String,
    val position: Offset,
    val color: Color,
    val size: Float,
    /** Degrees, from two fingers turning it. */
    val angle: Float = 0f,
    val style: TextStyle = TextStyle.Plain,
)

/**
 * An emoji dropped on the picture.
 *
 * A glyph rather than a downloaded image: it draws with the text engine, so it
 * survives being exported with no network and no second failure mode. The
 * site's own custom emoji are pictures on a server and are deliberately not
 * offered here.
 */
data class StickerLayer(
    val id: Long,
    val emoji: String,
    val position: Offset,
    val size: Float,
    val angle: Float = 0f,
)

/** Named looks, each one a colour matrix. Strength is all-or-nothing. */
enum class PhotoFilter(@StringRes val labelRes: Int, val matrix: FloatArray?) {
    None(R.string.editor_filter_none, null),
    Vivid(
        R.string.editor_filter_vivid,
        floatArrayOf(
            1.25f, -0.1f, -0.1f, 0f, 0f,
            -0.1f, 1.25f, -0.1f, 0f, 0f,
            -0.1f, -0.1f, 1.25f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Warm(
        R.string.editor_filter_warm,
        floatArrayOf(
            1.1f, 0f, 0f, 0f, 12f,
            0f, 1.02f, 0f, 0f, 4f,
            0f, 0f, 0.9f, 0f, -6f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Cool(
        R.string.editor_filter_cool,
        floatArrayOf(
            0.92f, 0f, 0f, 0f, -6f,
            0f, 1f, 0f, 0f, 2f,
            0f, 0f, 1.12f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Mono(
        R.string.editor_filter_mono,
        floatArrayOf(
            0.33f, 0.5f, 0.16f, 0f, 0f,
            0.33f, 0.5f, 0.16f, 0f, 0f,
            0.33f, 0.5f, 0.16f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    Faded(
        R.string.editor_filter_faded,
        floatArrayOf(
            0.85f, 0.1f, 0.05f, 0f, 26f,
            0.05f, 0.83f, 0.08f, 0f, 22f,
            0.05f, 0.1f, 0.78f, 0f, 20f,
            0f, 0f, 0f, 1f, 0f,
        ),
    );

    val colorFilter: ColorMatrixColorFilter?
        get() = matrix?.let { ColorMatrixColorFilter(ColorMatrix(it)) }
}

data class ImageEditDocument(
    val source: Bitmap,
    /**
     * In straightened space, not source space.
     *
     * Once the picture can be turned, "the top-left of the crop" is only
     * meaningful after it is standing up — see [basisMatrix].
     */
    val crop: RectF = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
    val strokes: List<BrushStroke> = emptyList(),
    val texts: List<TextLayer> = emptyList(),
    val stickers: List<StickerLayer> = emptyList(),
    val mosaics: List<MosaicPatch> = emptyList(),
    /** Quarter turns from the rotate button. */
    val rotationDegrees: Int = 0,
    /** The fine angle from the ruler, −45°…45°. */
    val angle: Float = 0f,
    val flipped: Boolean = false,
    val filter: PhotoFilter = PhotoFilter.None,
) {
    /**
     * Source space → straightened space: flip, quarter turns, then the ruler.
     *
     * Translated afterwards so the result starts at the origin, which makes
     * "straightened space" a plain rectangle everything else can be measured
     * against.
     */
    fun basisMatrix(): Matrix {
        val w = source.width.toFloat()
        val h = source.height.toFloat()
        val matrix = Matrix()
        if (flipped) matrix.postScale(-1f, 1f, w / 2f, h / 2f)
        matrix.postRotate(rotationDegrees + angle, w / 2f, h / 2f)
        val bounds = RectF(0f, 0f, w, h)
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)
        return matrix
    }

    /** The straightened picture's outline, at the origin. */
    fun basisBounds(): RectF {
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        basisMatrix().mapRect(bounds)
        return bounds
    }

    /**
     * The crop to use after a turn.
     *
     * Turning by anything but a quarter leaves empty corners, so the frame is
     * pulled in to the largest upright rectangle that still lands entirely on
     * the picture. Keeping the old frame instead would export those corners as
     * black.
     */
    fun fittedCrop(): RectF {
        val bounds = basisBounds()
        val radians = Math.toRadians((angle % 90f).toDouble())
        val inner = largestUprightRect(
            source.width.toFloat(),
            source.height.toFloat(),
            radians,
        )
        val width = inner.first.coerceAtMost(bounds.width())
        val height = inner.second.coerceAtMost(bounds.height())
        val cx = bounds.width() / 2f
        val cy = bounds.height() / 2f
        return RectF(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    }
}

/**
 * The largest upright rectangle that fits inside a turned one.
 *
 * The standard construction: at shallow angles the limit is the long side, and
 * past the point where the two constraints cross it is the short one. Without
 * it a straightened photo carries transparent wedges at its corners.
 */
fun largestUprightRect(width: Float, height: Float, angle: Double): Pair<Float, Float> {
    if (width <= 0f || height <= 0f) return 0f to 0f
    val sinA = abs(sin(angle)).toFloat()
    val cosA = abs(cos(angle)).toFloat()
    val widthIsLonger = width >= height
    val longSide = if (widthIsLonger) width else height
    val shortSide = if (widthIsLonger) height else width

    if (shortSide <= 2f * sinA * cosA * longSide || abs(sinA - cosA) < 1e-6f) {
        val half = 0.5f * shortSide
        return if (widthIsLonger) {
            (half / sinA.coerceAtLeast(1e-6f)) to (half / cosA.coerceAtLeast(1e-6f))
        } else {
            (half / cosA.coerceAtLeast(1e-6f)) to (half / sinA.coerceAtLeast(1e-6f))
        }
    }
    val cos2A = cosA * cosA - sinA * sinA
    return ((width * cosA - height * sinA) / cos2A) to ((height * cosA - width * sinA) / cos2A)
}

/** Preset crop shapes; null keeps whatever the image already is. */
enum class CropAspect(@StringRes val labelRes: Int, val ratio: Float?) {
    Free(R.string.editor_crop_free, null),
    Square(R.string.editor_crop_square, 1f),
    Portrait(R.string.editor_crop_portrait, 4f / 5f),
    Landscape(R.string.editor_crop_landscape, 16f / 9f),
}

/**
 * The picture at a coarse resolution, the same size as the original.
 *
 * Down to a fraction and back up with filtering off, which is what makes the
 * blocks square-edged rather than a blur. Built once per picture and kept:
 * doing it per frame would be a full-size allocation on every touch move.
 */
fun mosaicBitmap(source: Bitmap, blocks: Int = MOSAIC_BLOCKS, posterize: Boolean = false): Bitmap {
    val longest = maxOf(source.width, source.height)
    val block = (longest / blocks).coerceAtLeast(2)
    val small = source.scale(
        (source.width / block).coerceAtLeast(1),
        (source.height / block).coerceAtLeast(1),
    )
    // Flattened while it is still small — a few thousand pixels rather than a
    // few million, which is the only reason this can be done per pixel at all.
    val reduced = if (posterize) posterized(small) else small
    val out = createBitmap(source.width, source.height)
    // No FILTER_BITMAP_FLAG: nearest neighbour is the whole effect.
    Canvas(out).drawBitmap(
        reduced,
        null,
        android.graphics.Rect(0, 0, source.width, source.height),
        Paint(),
    )
    return out
}

/** How many levels each channel is cut down to. */
private const val POSTER_LEVELS = 6

/**
 * The same picture with its palette cut down.
 *
 * Rounded to the nearest level rather than truncated: masking the low bits
 * darkens everything, so a posterised photograph came out muddier than it
 * started as well as flatter.
 */
private fun posterized(bitmap: Bitmap): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    for (index in pixels.indices) {
        val colour = pixels[index]
        pixels[index] = (colour and 0xFF000000.toInt()) or
            (level(colour ushr 16 and 0xFF) shl 16) or
            (level(colour ushr 8 and 0xFF) shl 8) or
            level(colour and 0xFF)
    }
    return createBitmap(width, height).also { it.setPixels(pixels, 0, width, 0, 0, width, height) }
}

private fun level(value: Int): Int {
    val steps = POSTER_LEVELS - 1
    return ((value * steps + 127) / 255) * 255 / steps
}

/** How many blocks across the long edge. Coarse enough to actually hide a face. */
const val MOSAIC_BLOCKS = 34

/**
 * Covers each patch, through the same straightening as the picture.
 *
 * Clipped to the rectangle rather than masked: a rectangle needs no round caps
 * and a clip is cheaper than a layer. The coarse bitmap is drawn whole and the
 * clip is what decides how much of it lands.
 */
internal fun drawMosaic(
    canvas: Canvas,
    document: ImageEditDocument,
    patches: List<MosaicPatch>,
    coarse: MosaicSource,
    filter: ColorMatrixColorFilter?,
) {
    if (patches.isEmpty()) return
    patches.forEach { patch ->
        canvas.save()
        canvas.concat(document.basisMatrix())
        canvas.clipRect(patch.rect)
        val bitmap = coarse.forStyle(patch.style)
        if (bitmap == null) {
            canvas.drawRect(
                patch.rect,
                Paint().apply { color = android.graphics.Color.BLACK },
            )
        } else {
            // Filtering only where the style asks for it. Smoothing square
            // blocks — here or in the scale to the screen — turns a mosaic
            // back into a blur, which is a different choice on the same row.
            val paint = if (patch.style.smooth) Paint(Paint.FILTER_BITMAP_FLAG) else Paint()
            paint.colorFilter = filter
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        }
        canvas.restore()
    }
}

object ImageEditRenderer {

    /**
     * Flattens the document into a new bitmap.
     *
     * One matrix does the whole job: the picture and every layer on it are
     * drawn through the same straightening transform, offset so the crop's
     * corner lands at the origin. That is what keeps a stroke on the same
     * eyebrow after the photo has been turned two degrees.
     */
    fun render(document: ImageEditDocument): Bitmap {
        val bounds = document.basisBounds()
        val crop = RectF(document.crop)
        crop.intersect(RectF(0f, 0f, bounds.width(), bounds.height()))
        val width = crop.width().toInt().coerceAtLeast(1)
        val height = crop.height().toInt().coerceAtLeast(1)

        val output = createBitmap(width, height)
        val canvas = Canvas(output)
        canvas.translate(-crop.left, -crop.top)

        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = document.filter.colorFilter
        }
        canvas.drawBitmap(document.source, document.basisMatrix(), imagePaint)

        // Over the picture and under everything else: a covered patch hides
        // part of the photograph, so anything drawn on it belongs on top.
        // Graded with the same filter, or the blocks would keep the colours of
        // a look that has since been turned off.
        canvas.save()
        drawMosaic(
            canvas,
            document,
            document.mosaics,
            MosaicSource(document.source),
            document.filter.colorFilter,
        )
        canvas.restore()

        // Layers are in source space, so they go through the same transform —
        // and are drawn inside save/restore so their own turns do not leak.
        canvas.save()
        canvas.concat(document.basisMatrix())

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        document.strokes.forEach { stroke ->
            if (stroke.points.size < 2) return@forEach
            strokePaint.color = stroke.color.toArgb()
            strokePaint.strokeWidth = stroke.width
            val path = Path()
            stroke.points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            canvas.drawPath(path, strokePaint)
        }

        document.texts.forEach { layer -> drawTextLayer(canvas, layer) }
        document.stickers.forEach { layer -> drawStickerLayer(canvas, layer) }

        canvas.restore()
        return output
    }

    private fun drawTextLayer(canvas: Canvas, layer: TextLayer) {
        canvas.save()
        canvas.rotate(layer.angle, layer.position.x, layer.position.y)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFakeBoldText = true
            textSize = layer.size
        }
        when (layer.style) {
            TextStyle.Plain -> Unit
            TextStyle.Outlined -> {
                // The dark pass first, wider, so the light one sits in it.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = layer.size * 0.12f
                paint.color = android.graphics.Color.BLACK
                canvas.drawText(layer.text, layer.position.x, layer.position.y, paint)
                paint.style = Paint.Style.FILL
            }
            TextStyle.Filled -> {
                val width = paint.measureText(layer.text)
                val pad = layer.size * 0.22f
                val box = RectF(
                    layer.position.x - pad,
                    layer.position.y - layer.size + pad * 0.2f,
                    layer.position.x + width + pad,
                    layer.position.y + pad,
                )
                val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = layer.color.toArgb()
                }
                canvas.drawRoundRect(box, pad, pad, boxPaint)
            }
        }
        paint.color = when (layer.style) {
            TextStyle.Filled -> pickReadable(layer.color)
            else -> layer.color.toArgb()
        }
        canvas.drawText(layer.text, layer.position.x, layer.position.y, paint)
        canvas.restore()
    }

    private fun drawStickerLayer(canvas: Canvas, layer: StickerLayer) {
        canvas.save()
        canvas.rotate(layer.angle, layer.position.x, layer.position.y)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = layer.size }
        canvas.drawText(layer.emoji, layer.position.x, layer.position.y, paint)
        canvas.restore()
    }

    /** Black on a light box, white on a dark one. */
    private fun pickReadable(background: Color): Int {
        val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
        return if (luminance > 0.6f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }

    /**
     * Decodes a picked image the way the camera meant it to be seen.
     *
     * `BitmapFactory` ignores the EXIF orientation tag, and phone cameras lean
     * on it heavily rather than rotating the pixels — so a portrait photo
     * arrived on its side, went into the editor on its side, and was posted
     * that way. Downscaled here too, because every caller wants that and one
     * of them used to forget.
     */
    fun decodeUpright(context: Context, uri: Uri, maxEdge: Int = 2048): Bitmap? {
        val resolver = context.contentResolver

        // Bounds first, so nothing full-size is ever held. Decoding a modern
        // phone photo outright is ~110 MB at 12 MP and four times that on a
        // 48 MP sensor — and rotating it allocates a second copy of the same
        // size before the downscale that was supposed to make it small.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), maxEdge)
        }

        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val upright = runCatching { decoded.applyExifOrientation(orientation) }.getOrNull() ?: return null
        return downscale(upright, maxEdge)
    }

    /** The largest power of two that still leaves the long edge above [maxEdge]. */
    private fun sampleSizeFor(longestEdge: Int, maxEdge: Int): Int {
        if (longestEdge <= 0) return 1
        var sample = 1
        while (longestEdge / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    private fun Bitmap.applyExifOrientation(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return this
        }
        // Not swallowed into `this`: returning the unrotated bitmap would put
        // the photo back on its side, silently, which is the whole failure this
        // function exists to prevent. Better to fail the pick and say so.
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    /** PNG where the source was one: JPEG would flatten transparency onto black. */
    fun encodePng(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /** JPEG for photos: the composer uploads bytes, not a Bitmap. */
    fun encode(bitmap: Bitmap, quality: Int = 90): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Downscales so the longest edge fits [maxEdge]. Uploading a 12-megapixel
     * original costs the user's data plan for pixels the forum will resize
     * away anyway.
     */
    fun downscale(bitmap: Bitmap, maxEdge: Int = 2048): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        return bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
        )
    }
}
