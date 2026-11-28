package com.nodeloc.app.feature.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.nodeloc.app.R
import java.io.File

/**
 * The invite poster, drawn on the device — nothing leaves it to make one.
 *
 * It is for the person who receives it, so it speaks to them, in the words of
 * the invitee's landing page (Mobile-InviteLanding): who invites them, what
 * nodeloc is, what joining with this code gives them (only when the site
 * grants a welcome), the code and the QR, and how to use them. Light whatever
 * the app's appearance, 360 wide at 3×: 1080 px.
 */
object InvitePoster {
    private const val SCALE = 3f
    private const val WIDTH = 360f
    private const val PAD = 24f

    private val ink = "#0F1A1C".toColorInt()
    private val muted = "#576F76".toColorInt()
    private val green = "#029A68".toColorInt()
    private val ring = "#5CC79A".toColorInt()

    /** The reader's avatar, fetched first: the poster is drawn in one pass. */
    suspend fun loadAvatar(context: Context, url: String?): Bitmap? {
        url ?: return null
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        return (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    fun render(context: Context, inviter: String, avatar: Bitmap?, code: String, link: String, welcome: Long): Bitmap {
        // Laid out once to find the height, then drawn at that height.
        val probe = Canvas()
        val height = draw(context, probe, inviter, avatar, code, link, welcome, measureOnly = true)
        val bitmap = createBitmap((WIDTH * SCALE).toInt(), (height * SCALE).toInt())
        val canvas = Canvas(bitmap)
        canvas.scale(SCALE, SCALE)
        draw(context, canvas, inviter, avatar, code, link, welcome, measureOnly = false)
        return bitmap
    }

    /** Draws the poster in points; returns the height it needed. */
    private fun draw(
        context: Context,
        canvas: Canvas,
        inviter: String,
        avatar: Bitmap?,
        code: String,
        link: String,
        welcome: Long,
        measureOnly: Boolean,
    ): Float {
        val contentWidth = (WIDTH - PAD * 2).toInt()
        if (!measureOnly) {
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawRect(0f, 0f, WIDTH, 6f, Paint().apply { color = green })
        }
        var y = PAD

        // The mark and the name: the current two-colour knot, then the word.
        val mark = ContextCompat.getDrawable(context, R.drawable.ic_tab_nodes_selected)
        if (!measureOnly && mark != null) {
            mark.setBounds(PAD.toInt(), y.toInt(), (PAD + 30).toInt(), (y + 30).toInt())
            mark.draw(canvas)
        }
        val word = text(20f, ink, bold = true)
        if (!measureOnly) canvas.drawText("nodeloc", PAD + 30 + 8, y + 22, word)
        y += 30 + 26

        // Who is inviting them.
        val avatarSize = 44f
        if (!measureOnly) drawAvatar(canvas, avatar, inviter, PAD, y, avatarSize)
        val who = layout(context.getString(R.string.invite_poster_from, inviter), text(16f, ink, bold = true), contentWidth - 56, maxLines = 2)
        if (!measureOnly) {
            canvas.save()
            canvas.translate(PAD + avatarSize + 12, y + (avatarSize - who.height).coerceAtLeast(0f) / 2)
            who.draw(canvas)
            canvas.restore()
        }
        y += maxOf(avatarSize, who.height.toFloat()) + 18

        // What nodeloc is.
        y = block(canvas, layout(context.getString(R.string.invite_poster_headline), text(26f, ink, bold = true), contentWidth), y, measureOnly) + 6
        y = block(canvas, layout(context.getString(R.string.invite_poster_sub), text(14f, muted), contentWidth), y, measureOnly)

        // What joining with this code gives them, when the site gives anything.
        if (welcome > 0) {
            y += 18
            val title = layout(context.getString(R.string.invite_poster_welcome, welcome), text(15f, ink, bold = true), contentWidth - 74)
            val detail = layout(context.getString(R.string.invite_poster_welcome_d), text(12f, muted), contentWidth - 74)
            val boxHeight = maxOf(38f, (title.height + 2 + detail.height).toFloat()) + 24
            if (!measureOnly) {
                val box = RectF(PAD, y, WIDTH - PAD, y + boxHeight)
                canvas.drawRoundRect(box, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#FFF6EC".toColorInt() })
                canvas.drawRoundRect(box, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = "#F7D3A8".toColorInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                })
                canvas.drawCircle(PAD + 12 + 19, y + boxHeight / 2, 19f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#FF9C38".toColorInt() })
                val bolt = ContextCompat.getDrawable(context, R.drawable.ic_poster_bolt)
                bolt?.setTint("#0B1F18".toColorInt())
                bolt?.setBounds((PAD + 12 + 10).toInt(), (y + boxHeight / 2 - 9).toInt(), (PAD + 12 + 28).toInt(), (y + boxHeight / 2 + 9).toInt())
                bolt?.draw(canvas)
                canvas.save()
                canvas.translate(PAD + 12 + 38 + 12, y + 12)
                title.draw(canvas)
                canvas.translate(0f, title.height + 2f)
                detail.draw(canvas)
                canvas.restore()
            }
            y += boxHeight
        }

        // The code and the QR, and how to use them.
        y += 22
        val qrBox = 116f + 20
        if (!measureOnly) {
            val frame = RectF(PAD, y, PAD + qrBox, y + qrBox)
            canvas.drawRoundRect(frame, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = "#DCE3E6".toColorInt()
                style = Paint.Style.STROKE
                strokeWidth = 1f
            })
            qr(link, (116 * SCALE).toInt())?.let { bitmap ->
                canvas.drawBitmap(bitmap, null, RectF(PAD + 10, y + 10, PAD + 10 + 116, y + 10 + 116), Paint().apply { isFilterBitmap = false })
            }
        }
        val textX = PAD + qrBox + 16
        val textWidth = (WIDTH - PAD - textX).toInt()
        val label = layout(context.getString(R.string.invite_code_label), text(12f, muted), textWidth)
        val codeText = layout(code, text(24f, ink, bold = true, mono = true), textWidth, maxLines = 1)
        val how = layout(context.getString(R.string.invite_poster_how), text(12f, muted), textWidth)
        val columnHeight = label.height + 4 + codeText.height + 4 + how.height
        if (!measureOnly) {
            canvas.save()
            canvas.translate(textX, y + (qrBox - columnHeight).coerceAtLeast(0f) / 2)
            label.draw(canvas)
            canvas.translate(0f, label.height + 4f)
            codeText.draw(canvas)
            canvas.translate(0f, codeText.height + 4f)
            how.draw(canvas)
            canvas.restore()
        }
        y += maxOf(qrBox, columnHeight.toFloat())
        return y + PAD
    }

    private fun block(canvas: Canvas, layout: StaticLayout, y: Float, measureOnly: Boolean): Float {
        if (!measureOnly) {
            canvas.save()
            canvas.translate(PAD, y)
            layout.draw(canvas)
            canvas.restore()
        }
        return y + layout.height
    }

    private fun drawAvatar(canvas: Canvas, avatar: Bitmap?, name: String, x: Float, y: Float, size: Float) {
        val cx = x + size / 2
        val cy = y + size / 2
        if (avatar != null) {
            val shader = BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = size / minOf(avatar.width, avatar.height)
            shader.setLocalMatrix(android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate(x - (avatar.width * scale - size) / 2, y - (avatar.height * scale - size) / 2)
            })
            canvas.drawCircle(cx, cy, size / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            canvas.drawCircle(cx, cy, size / 2 - 1, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ring
                style = Paint.Style.STROKE
                strokeWidth = 2f
            })
        } else {
            canvas.drawCircle(cx, cy, size / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green })
            val letter = text(18f, android.graphics.Color.WHITE, bold = true).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText(name.take(1).uppercase(), cx, cy - (letter.descent() + letter.ascent()) / 2, letter)
        }
    }

    private fun text(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int = Int.MAX_VALUE): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(if (maxLines == Int.MAX_VALUE) null else android.text.TextUtils.TruncateAt.END)
            .build()

    /** The link as a QR code, black on white, with no quiet zone of its own: the frame provides it. */
    fun qr(text: String, size: Int): Bitmap? = runCatching {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix.get(index % matrix.width, index / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Hands the poster to the share sheet through the app's FileProvider (`cache/shared/`). */
    fun share(context: Context, bitmap: Bitmap, title: String) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "nodeloc-invite.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, title)) }
    }
}
