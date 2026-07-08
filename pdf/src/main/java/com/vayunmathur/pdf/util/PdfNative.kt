package com.vayunmathur.pdf.util

/**
 * JNI bridge to the native Rust PDF renderer (`libpdf_render.so`, built from
 * `pdf/rust/`). Loads the library once; [isAvailable] is false if the native
 * lib is missing for the current ABI so the safe viewer can show a clean error
 * instead of crashing.
 *
 * All entry points are blocking and must be called off the main thread. Handles
 * returned by [openDocument] are opaque; pass 0 to mean "no document".
 */
object PdfNative {

    val isAvailable: Boolean =
        try {
            System.loadLibrary("pdf_render")
            android.util.Log.i("PdfNative", "libpdf_render loaded")
            true
        } catch (t: Throwable) {
            android.util.Log.e("PdfNative", "System.loadLibrary(pdf_render) failed", t)
            false
        }

    /**
     * Parse [data] (the raw PDF bytes) and return an opaque handle, or 0 on
     * parse failure or if the document is encrypted (v1 does not decrypt).
     */
    external fun openDocument(data: ByteArray): Long

    /** Number of pages in the document behind [handle], or 0 if unknown. */
    external fun getPageCount(handle: Long): Int

    /**
     * Render page [index] (0-based) into the serialized primitive buffer
     * consumed by [SafePdfParser], or `null` on any error.
     */
    external fun renderPage(handle: Long, index: Int): ByteArray?

    /** Release the document behind [handle]. Safe to call with 0. */
    external fun closeDocument(handle: Long)

    // --- Editing: annotations, forms, save ---------------------------------

    /** Serialized annotations on [page] for the overlay/hit-testing. */
    external fun listAnnotations(handle: Long, page: Int): ByteArray?

    /** Serialized AcroForm widget fields on [page]. */
    external fun listFormFields(handle: Long, page: Int): ByteArray?

    /** Add a FreeText annotation; returns its id (0 on failure). */
    external fun addTextAnnotation(
        handle: Long, page: Int,
        x0: Float, y0: Float, x1: Float, y1: Float,
        argb: Int, size: Float, text: String,
    ): Long

    external fun addHighlight(
        handle: Long, page: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int,
    ): Long

    external fun addRectAnnotation(
        handle: Long, page: Int,
        x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, lineWidth: Float,
    ): Long

    /** [pts] are flat page-space x,y pairs of one ink stroke. */
    external fun addInkAnnotation(
        handle: Long, page: Int, argb: Int, lineWidth: Float, pts: FloatArray,
    ): Long

    external fun addImageStamp(
        handle: Long, page: Int,
        x0: Float, y0: Float, x1: Float, y1: Float,
        imgW: Int, imgH: Int, jpeg: ByteArray,
    ): Long

    external fun updateAnnotationRect(
        handle: Long, annotId: Long, x0: Float, y0: Float, x1: Float, y1: Float,
    ): Boolean

    external fun updateTextAnnotation(handle: Long, annotId: Long, text: String): Boolean

    external fun deleteAnnotation(handle: Long, page: Int, annotId: Long): Boolean

    external fun setTextField(handle: Long, widgetId: Long, value: String): Boolean

    external fun setCheckbox(handle: Long, widgetId: Long, on: Boolean): Boolean

    /** Serialize the modified document to PDF bytes, or null on failure. */
    external fun saveDocument(handle: Long): ByteArray?

    /** Serialized document outline (bookmarks), or null. */
    external fun listOutline(handle: Long): ByteArray?
}
