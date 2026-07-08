package com.vayunmathur.pdf.util

import androidx.compose.ui.geometry.Offset
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the compact little-endian primitive buffer produced by the native
 * renderer ([PdfNative.renderPage]) into a [SafePdfPage].
 *
 * Wire format (must stay in sync with `pdf/rust/src/lib.rs` `wire` module):
 * ```
 * header: f32 pageWidth, f32 pageHeight, u32 primitiveCount
 * per primitive: u8 tag, then payload
 *   1 Text:   f32 x, f32 y, f32 size, u32 argb, u16 len, [utf8 bytes]
 *   2 Fill:   u32 argb, u16 nPts, [f32 x, f32 y]...
 *   3 Stroke: u32 argb, f32 width, u16 nPts, [f32 x, f32 y]...
 * ```
 * Pure function → unit-testable with no Android dependencies beyond [Offset].
 */
object SafePdfParser {

    private const val TAG_TEXT = 1
    private const val TAG_FILL = 2
    private const val TAG_STROKE = 3
    private const val TAG_IMAGE = 4

    fun parse(bytes: ByteArray): SafePdfPage {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val width = buf.float
        val height = buf.float
        val count = buf.int // primitive count (u32, fits in Int in practice)

        val primitives = ArrayList<PdfPrimitive>(count.coerceAtLeast(0))
        repeat(count) {
            when (val tag = buf.get().toInt()) {
                TAG_TEXT -> {
                    val x = buf.float
                    val y = buf.float
                    val size = buf.float
                    val argb = buf.int
                    val len = buf.short.toInt() and 0xFFFF
                    val strBytes = ByteArray(len)
                    buf.get(strBytes)
                    primitives.add(
                        PdfPrimitive.Text(
                            origin = Offset(x, y),
                            size = size,
                            color = argb,
                            text = String(strBytes, Charsets.UTF_8),
                        )
                    )
                }

                TAG_FILL -> {
                    val argb = buf.int
                    val evenOdd = buf.get().toInt() != 0
                    primitives.add(PdfPrimitive.FillPath(argb, evenOdd, readPoints(buf)))
                }

                TAG_STROKE -> {
                    val argb = buf.int
                    val strokeWidth = buf.float
                    val nDash = buf.get().toInt() and 0xFF
                    val dash = FloatArray(nDash) { buf.float }
                    val dashPhase = buf.float
                    primitives.add(
                        PdfPrimitive.StrokePath(argb, strokeWidth, dash, dashPhase, readPoints(buf))
                    )
                }

                TAG_IMAGE -> {
                    val ctm = FloatArray(6) { buf.float }
                    val w = buf.int
                    val h = buf.int
                    val format = buf.get().toInt()
                    val len = buf.int
                    val data = ByteArray(len)
                    buf.get(data)
                    primitives.add(PdfPrimitive.Image(ctm, decodeBitmap(w, h, format, data)))
                }

                else -> throw IllegalArgumentException("Unknown primitive tag: $tag")
            }
        }

        return SafePdfPage(width, height, primitives)
    }

    /** Decode the annotation listing buffer from `listAnnotations`. */
    fun parseAnnotations(bytes: ByteArray): List<SafeAnnotation> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeAnnotation>(count.coerceAtLeast(0))
        repeat(count) {
            val id = buf.long
            val subtype = buf.get().toInt()
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val color = buf.int
            val contents = readString(buf)
            out.add(SafeAnnotation(id, subtype, x0, y0, x1, y1, color, contents))
        }
        return out
    }

    /** Decode the form-field listing buffer from `listFormFields`. */
    fun parseFormFields(bytes: ByteArray): List<SafeFormField> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeFormField>(count.coerceAtLeast(0))
        repeat(count) {
            val id = buf.long
            val type = buf.get().toInt()
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val name = readString(buf)
            val value = readString(buf)
            val checked = buf.get().toInt() != 0
            out.add(SafeFormField(id, type, x0, y0, x1, y1, name, value, checked))
        }
        return out
    }

    private fun readString(buf: ByteBuffer): String {
        val len = buf.short.toInt() and 0xFFFF
        val b = ByteArray(len)
        buf.get(b)
        return String(b, Charsets.UTF_8)
    }

    private fun readPoints(buf: ByteBuffer): List<Offset> {
        val n = buf.short.toInt() and 0xFFFF
        val points = ArrayList<Offset>(n)
        repeat(n) {
            val x = buf.float
            val y = buf.float
            points.add(Offset(x, y))
        }
        return points
    }

    /** Decode an image payload: format 1 = JPEG bytes, 0 = raw RGBA8888. */
    private fun decodeBitmap(w: Int, h: Int, format: Int, data: ByteArray): android.graphics.Bitmap? {
        return runCatching {
            when (format) {
                1 -> android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                0 -> {
                    if (w <= 0 || h <= 0 || data.size < w * h * 4) return null
                    val pixels = IntArray(w * h)
                    var p = 0
                    for (i in pixels.indices) {
                        val r = data[p].toInt() and 0xFF
                        val g = data[p + 1].toInt() and 0xFF
                        val b = data[p + 2].toInt() and 0xFF
                        val a = data[p + 3].toInt() and 0xFF
                        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        p += 4
                    }
                    android.graphics.Bitmap.createBitmap(
                        pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888
                    )
                }
                else -> null
            }
        }.getOrNull()
    }
}
