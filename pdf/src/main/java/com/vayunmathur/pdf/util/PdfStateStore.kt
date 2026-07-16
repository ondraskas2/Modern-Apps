package com.vayunmathur.pdf.util
import android.content.Context
import android.net.Uri
import androidx.core.content.edit

object PdfStateStore {
    private const val PREFS_NAME = "pdf_viewer_state"

    // --- Safe (Rust) viewer: remember the first-visible page per document. ---

    fun saveSafePage(context: Context, uri: Uri, page: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putInt("safe_page_" + uri.toString(), page) }
    }

    fun restoreSafePage(context: Context, uri: Uri): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            prefs.getInt("safe_page_" + uri.toString(), 0)
        } catch (_: ClassCastException) {
            0
        }
    }
}
