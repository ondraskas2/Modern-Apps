package com.vayunmathur.notes.util

import android.content.Context
import android.util.Base64
import com.vayunmathur.library.util.SerializedStroke
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.data.body
import kotlin.math.roundToInt

/**
 * Exports a note as a SINGLE self-contained Markdown document:
 *  - text blocks are emitted verbatim,
 *  - images are inlined as base64 `data:` URIs (no external files),
 *  - drawings are converted to an inline `<svg>` element.
 *
 * Export-only: the stored JSON blocks stay the source of truth; this never
 * touches storage. Reads image files, so call it off the main thread.
 */
fun exportNoteMarkdown(context: Context, note: Note): String =
    note.body().blocks
        .map { block ->
            when (block) {
                is NoteBlock.Text -> block.markdown
                is NoteBlock.Image -> imageMarkdown(context, block)
                is NoteBlock.Ink -> inkSvg(block)
            }
        }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
        .trim() + "\n"

/** An image inlined as an HTML `<img>` with a base64 data URI, honoring [NoteBlock.Image.widthFraction]. */
private fun imageMarkdown(context: Context, block: NoteBlock.Image): String {
    val bytes = try {
        NoteImageStore.fileFor(context, block.fileName).readBytes()
    } catch (e: Exception) {
        return ""
    }
    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val widthPercent = (block.widthFraction * 100).roundToInt().coerceIn(1, 100)
    return "<img src=\"data:image/jpeg;base64,$base64\" style=\"width:$widthPercent%\" />"
}

/** A drawing as an inline `<svg>`, one `<path>` per stroke, using the raw point coordinates. */
private fun inkSvg(block: NoteBlock.Ink): String {
    // Points are in the drawing canvas' pixel space. Size the SVG to bound them
    // (so it stays self-contained and keeps the right proportions); fall back to
    // the canvas height for an empty drawing.
    var width = 0f
    var height = 0f
    block.strokes.forEach { stroke ->
        stroke.points.forEach { p ->
            if (p.x > width) width = p.x
            if (p.y > height) height = p.y
        }
    }
    width = width.coerceAtLeast(1f)
    height = height.coerceAtLeast(block.heightDp.toFloat())

    val paths = block.strokes.mapNotNull { strokePath(it) }.joinToString("\n")
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${num(width)}\" height=\"${num(height)}\" " +
        "viewBox=\"0 0 ${num(width)} ${num(height)}\">\n$paths\n</svg>"
}

/** Builds an SVG `<path>` ("M x y L x y ...") for one stroke, or null if it has no points. */
private fun strokePath(stroke: SerializedStroke): String? {
    if (stroke.points.isEmpty()) return null
    val d = stroke.points.mapIndexed { i, p ->
        "${if (i == 0) "M" else "L"} ${num(p.x)} ${num(p.y)}"
    }.joinToString(" ")
    return "<path d=\"$d\" fill=\"none\" stroke=\"${cssColor(stroke.brushColor)}\" " +
        "stroke-width=\"${num(stroke.brushSize)}\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />"
}

/** An ARGB color int as a CSS `rgba(...)`, preserving alpha (e.g. highlighter transparency). */
private fun cssColor(argb: Int): String {
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return "rgba($r,$g,$b,${num(a / 255f)})"
}

/** Formats a float compactly (drops the ".0" for whole numbers) to keep the output small. */
private fun num(value: Float): String {
    val rounded = (value * 100).roundToInt() / 100f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}
