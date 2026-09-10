package com.nodeloc.app.feature.media

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Minimal GIF89a encoder.
 *
 * Android can decode animated GIFs but cannot write them, and the composer
 * needs "video → GIF" the same way the iOS build does. A fixed 6×6×6 colour
 * cube plus a grey ramp avoids per-frame palette computation entirely: quality
 * is a little below a median-cut palette, but encoding a 5-second clip stays
 * fast enough to run on the device without a progress bar that lies.
 */
object GifEncoder {

    /** 216 web-safe colours plus a 40-step grey ramp = a full 256 table. */
    private val PALETTE: IntArray = buildList {
        for (r in 0..5) for (g in 0..5) for (b in 0..5) {
            add((r * 51 shl 16) or (g * 51 shl 8) or (b * 51))
        }
        for (i in 0 until 40) {
            val v = (i * 255 / 39)
            add((v shl 16) or (v shl 8) or v)
        }
    }.toIntArray()

    /**
     * @param frames source bitmaps, all the same size
     * @param delayMs delay between frames
     */
    fun encode(frames: List<Bitmap>, delayMs: Int): ByteArray {
        require(frames.isNotEmpty()) { "GIF needs at least one frame" }
        val out = ByteArrayOutputStream()
        val width = frames.first().width
        val height = frames.first().height

        writeHeader(out, width, height)
        writeNetscapeLoop(out)
        frames.forEach { frame ->
            val indexed = quantize(frame, width, height)
            writeGraphicControl(out, delayMs)
            writeImageDescriptor(out, width, height)
            LzwEncoder(indexed, 8).encode(out)
        }
        out.write(0x3B) // trailer
        return out.toByteArray()
    }

    private fun writeHeader(out: OutputStream, width: Int, height: Int) {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(out, width)
        writeShort(out, height)
        // Global colour table, 8 bits per channel resolution, 256 entries.
        out.write(0xF7)
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio
        PALETTE.forEach { color ->
            out.write((color shr 16) and 0xFF)
            out.write((color shr 8) and 0xFF)
            out.write(color and 0xFF)
        }
    }

    private fun writeNetscapeLoop(out: OutputStream) {
        out.write(0x21)
        out.write(0xFF)
        out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)
        out.write(1)
        writeShort(out, 0) // loop forever
        out.write(0)
    }

    private fun writeGraphicControl(out: OutputStream, delayMs: Int) {
        out.write(0x21)
        out.write(0xF9)
        out.write(4)
        out.write(0) // no transparency, no disposal
        writeShort(out, (delayMs / 10).coerceAtLeast(2)) // GIF delay is in 1/100s
        out.write(0)
        out.write(0)
    }

    private fun writeImageDescriptor(out: OutputStream, width: Int, height: Int) {
        out.write(0x2C)
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, width)
        writeShort(out, height)
        out.write(0) // no local colour table, not interlaced
    }

    private fun writeShort(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    /** Nearest palette entry per pixel, by rounding each channel to the cube. */
    private fun quantize(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val indexed = ByteArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            indexed[i] = if (r == g && g == b) {
                // Greys get the dedicated ramp; the colour cube is coarse there.
                (216 + (r * 39 / 255)).toByte()
            } else {
                val ri = (r + 25) / 51
                val gi = (g + 25) / 51
                val bi = (b + 25) / 51
                (ri * 36 + gi * 6 + bi).toByte()
            }
        }
        return indexed
    }
}

/**
 * GIF's variable-code-width LZW, emitted in 255-byte sub-blocks. This is the
 * classic algorithm from the spec; the only liberty taken is a plain HashMap
 * for the string table.
 */
private class LzwEncoder(private val pixels: ByteArray, private val colorDepth: Int) {
    private val clearCode = 1 shl colorDepth
    private val endCode = clearCode + 1

    private var codeSize = colorDepth + 1
    private var nextCode = endCode + 1

    private val block = ByteArray(255)
    private var blockLength = 0
    private var bitBuffer = 0
    private var bitCount = 0

    fun encode(out: OutputStream) {
        out.write(colorDepth)
        val table = HashMap<Int, Int>(4096)

        writeCode(out, clearCode)
        var prefix = pixels[0].toInt() and 0xFF

        for (index in 1 until pixels.size) {
            val next = pixels[index].toInt() and 0xFF
            val key = (prefix shl 8) or next
            val existing = table[key]
            if (existing != null) {
                prefix = existing
            } else {
                writeCode(out, prefix)
                if (nextCode < 4096) {
                    table[key] = nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    writeCode(out, clearCode)
                    table.clear()
                    codeSize = colorDepth + 1
                    nextCode = endCode + 1
                }
                prefix = next
            }
        }
        writeCode(out, prefix)
        writeCode(out, endCode)
        flushBits(out)
        flushBlock(out)
        out.write(0) // block terminator
    }

    private fun writeCode(out: OutputStream, code: Int) {
        bitBuffer = bitBuffer or (code shl bitCount)
        bitCount += codeSize
        while (bitCount >= 8) {
            appendByte(out, (bitBuffer and 0xFF).toByte())
            bitBuffer = bitBuffer ushr 8
            bitCount -= 8
        }
    }

    private fun flushBits(out: OutputStream) {
        if (bitCount > 0) {
            appendByte(out, (bitBuffer and 0xFF).toByte())
            bitBuffer = 0
            bitCount = 0
        }
    }

    private fun appendByte(out: OutputStream, value: Byte) {
        block[blockLength++] = value
        if (blockLength == 255) flushBlock(out)
    }

    private fun flushBlock(out: OutputStream) {
        if (blockLength == 0) return
        out.write(blockLength)
        out.write(block, 0, blockLength)
        blockLength = 0
    }
}
