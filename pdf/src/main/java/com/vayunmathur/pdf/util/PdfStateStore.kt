package com.vayunmathur.pdf.util
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.pdf.PdfPoint
import androidx.pdf.view.PdfView

object PdfStateStore {
    private const val PREFS_NAME = "pdf_viewer_state"

    fun save(context: Context, uri: Uri, pdfView: PdfView) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = uri.toString()
        val page = pdfView.firstVisiblePage
        val zoom = pdfView.zoom
        val value = "$zoom,$page,0.0,0.0"
        prefs.edit { putString(key, value) }
    }

    fun restore(context: Context, uri: Uri): (suspend (PdfView) -> Unit)? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = uri.toString()
        val value = prefs.getString(key, null) ?: return null
        val parts = value.split(',')
        if (parts.size < 3) return null
        val page = parts[1].toIntOrNull() ?: 0
        val left = parts[2].toFloatOrNull() ?: 0f
        val top = parts[3].toFloatOrNull() ?: 0f
        return {
            it.scrollToPage(page)
            it.scrollToPosition(PdfPoint(page, left, top))
        }
    }
}
