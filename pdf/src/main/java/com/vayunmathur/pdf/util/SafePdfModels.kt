package com.vayunmathur.pdf.util

import androidx.compose.ui.geometry.Offset

/**
 * A single drawing primitive decoded from the native renderer, in PDF page
 * space (origin bottom-left). [SafePdfViewerScreen] applies the Y-flip and the
 * fit-to-width scale when drawing.
 *
 * Colors are packed ARGB ([Int]) matching Android's color ints.
 */
sealed interface PdfPrimitive {
    /** A run of text with its baseline origin, on-page size and color. */
    data class Text(
        val origin: Offset,
        val size: Float,
        val color: Int,
        val text: String,
    ) : PdfPrimitive

    /** A filled polygon (one subpath). */
    data class FillPath(
        val color: Int,
        val evenOdd: Boolean,
        val points: List<Offset>,
    ) : PdfPrimitive

    /** A stroked polyline (one subpath). [dash] is empty for a solid line. */
    data class StrokePath(
        val color: Int,
        val width: Float,
        val dash: FloatArray,
        val dashPhase: Float,
        val points: List<Offset>,
    ) : PdfPrimitive

    /**
     * A raster image. [ctm] is the 6-element PDF matrix (a,b,c,d,e,f) mapping
     * the unit square to page space; [bitmap] is the decoded image (null if it
     * could not be decoded).
     */
    data class Image(
        val ctm: FloatArray,
        val bitmap: android.graphics.Bitmap?,
    ) : PdfPrimitive
}

/** One decoded page: its PDF page dimensions plus the primitives to draw. */
data class SafePdfPage(
    val width: Float,
    val height: Float,
    val primitives: List<PdfPrimitive>,
)

/** An annotation on a page (from the native listing), in page space. */
data class SafeAnnotation(
    val id: Long,
    val subtype: Int, // 1 FreeText, 2 Highlight, 3 Square, 4 Ink, 5 Stamp, 6 Widget, ...
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val color: Int,
    val contents: String,
)

/** An AcroForm widget field on a page, in page space. */
data class SafeFormField(
    val id: Long,
    val type: Int, // 0 text, 1 checkbox/button, 2 choice, 3 other
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val name: String,
    val value: String,
    val checked: Boolean,
)

/** One entry in the document outline (bookmarks). */
data class SafeOutlineItem(
    val level: Int,
    val page: Int,
    val title: String,
)
