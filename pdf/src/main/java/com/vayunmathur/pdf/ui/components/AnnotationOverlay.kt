package com.vayunmathur.pdf.ui.components

import android.graphics.RectF
import android.util.SparseArray
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.pdf.PdfDocument
import androidx.pdf.annotation.AnnotationsView
import androidx.pdf.annotation.PdfViewportState
import androidx.pdf.annotation.content.KeyedPdfAnnotation
import androidx.pdf.compose.PdfViewerState

/**
 * Overlays an [AnnotationsView] on top of the Compose [androidx.pdf.compose.PdfViewer] so that
 * annotations embedded in the document (ink, highlights, stamps) render and can be selected/edited
 * by the library. The overlay mirrors the viewer's viewport (visible pages, per-page bounds and
 * zoom) and feeds the annotations for the visible pages via [AnnotationsView.updateDisplayState].
 */
@Composable
fun AnnotationOverlay(
    pdfDocument: PdfDocument,
    pdfState: PdfViewerState,
    modifier: Modifier = Modifier,
) {
    val pageSizes = remember(pdfDocument) { mutableMapOf<Int, PdfDocument.PageInfo>() }
    val pageAnnotationCache = remember(pdfDocument) { mutableMapOf<Int, List<KeyedPdfAnnotation>>() }
    var annotationsView by remember { mutableStateOf<AnnotationsView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            AnnotationsView(context).apply {
                interactionMode = AnnotationsView.AnnotationMode.Select
                annotationsView = this
            }
        },
    )

    val firstVisiblePage = pdfState.firstVisiblePage
    val visiblePagesCount = pdfState.visiblePagesCount
    val zoom = pdfState.zoom
    // firstVisiblePageOffset is snapshot state; reading it makes this recompute while scrolling.
    val scrollOffset = pdfState.firstVisiblePageOffset

    LaunchedEffect(annotationsView, firstVisiblePage, visiblePagesCount, zoom, scrollOffset) {
        val view = annotationsView ?: return@LaunchedEffect
        if (visiblePagesCount <= 0) return@LaunchedEffect

        val pageBounds = SparseArray<RectF>()
        val pageAnnotations = SparseArray<List<KeyedPdfAnnotation>>()

        for (page in firstVisiblePage until firstVisiblePage + visiblePagesCount) {
            val offset = pdfState.getVisiblePageOffset(page) ?: continue

            val info = pageSizes.getOrPut(page) { pdfDocument.getPageInfo(page) }
            pageBounds.put(
                page,
                RectF(
                    offset.x,
                    offset.y,
                    offset.x + info.width * zoom,
                    offset.y + info.height * zoom,
                ),
            )
            pageAnnotations.put(
                page,
                pageAnnotationCache.getOrPut(page) { pdfDocument.getAnnotationsForPage(page) },
            )
        }

        view.updateDisplayState(
            PdfViewportState(firstVisiblePage, visiblePagesCount, pageBounds, zoom),
            pageAnnotations,
        )
    }
}
