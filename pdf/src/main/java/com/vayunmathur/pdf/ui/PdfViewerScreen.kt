package com.vayunmathur.pdf.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentContainerView
import androidx.pdf.EditablePdfDocument
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.content.ExternalLink
import androidx.pdf.ink.EditablePdfViewerFragment
import androidx.pdf.models.FormEditInfo
import androidx.pdf.view.PdfView
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.util.PdfStateStore
import com.vayunmathur.pdf.util.PdfViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalPdfApi::class)
class AppPdfViewerFragment : EditablePdfViewerFragment() {
    var loadedDocument: EditablePdfDocument? = null
        private set
    var pdfViewRef: PdfView? = null
        private set

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        loadedDocument = document as? EditablePdfDocument
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        Log.e("AppPdfViewer", "onLoadDocumentError", error)
    }

    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        pdfViewRef = pdfView
    }
}

@OptIn(ExperimentalPdfApi::class)
@Composable
fun PdfViewerScreen(
    documentUri: Uri,
    pdfName: String,
    viewModel: PdfViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = context as AppCompatActivity

    var loadedDocument by remember { mutableStateOf<EditablePdfDocument?>(null) }
    var changesMade by remember { mutableStateOf(false) }

    val pdfSavedMessage = stringResource(R.string.pdf_saved)
    val pdfSaveErrorMessage = stringResource(R.string.pdf_save_error)

    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { target ->
            loadedDocument?.let { doc ->
                viewModel.saveDocumentChanges(doc, target)
            }
        }
    }

    LaunchedEffect(documentUri) {
        viewModel.pdfWriteResults.collect { result ->
            if (result.targetUri == documentUri) return@collect
            val msg = if (result.success) pdfSavedMessage else pdfSaveErrorMessage
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler { onBack() }

    val containerId = remember { View.generateViewId() }
    val fragmentTag = "pdf_viewer"

    LaunchedEffect(documentUri) {
        while (loadedDocument == null) {
            val fragment = activity.supportFragmentManager
                .findFragmentByTag(fragmentTag) as? AppPdfViewerFragment
            fragment?.loadedDocument?.let {
                loadedDocument = it
                viewModel.buildLinkIndex(it)
            }
            if (loadedDocument == null) delay(200)
        }
    }

    LaunchedEffect(documentUri) {
        var pdfView: PdfView? = null
        while (pdfView == null) {
            val fragment = activity.supportFragmentManager
                .findFragmentByTag(fragmentTag) as? AppPdfViewerFragment
            pdfView = fragment?.pdfViewRef
            if (pdfView == null) delay(200)
        }

        delay(300)

        pdfView.fastScrollVerticalThumbDrawable =
            ContextCompat.getDrawable(context, R.drawable.pdf_fast_scroll_thumb)!!
        pdfView.fastScrollPageIndicatorBackgroundDrawable =
            ContextCompat.getDrawable(context, R.drawable.pdf_page_indicator_background)!!
        pdfView.fastScrollPageIndicatorMarginEnd =
            (42 * context.resources.displayMetrics.density).toInt()
        pdfView.fastScrollVerticalThumbMarginEnd = 0

        pdfView.addOnFormWidgetInfoUpdatedListener(object : PdfView.OnFormWidgetInfoUpdatedListener {
            override fun onFormWidgetInfoUpdated(editInfo: FormEditInfo) {
                changesMade = true
            }
        })

        val linkDestinations = viewModel.linkDestinations
        pdfView.setLinkClickListener(object : PdfView.LinkClickListener {
            override fun onLinkClicked(link: ExternalLink): Boolean {
                val uri = link.uri
                if (uri.scheme == "file") {
                    val dests = linkDestinations.value
                    val destPage = dests[uri.toString()]
                    if (destPage != null) {
                        pdfView.scrollToPage(destPage)
                        return true
                    }
                    val pathOnly = uri.buildUpon().fragment(null).build().toString()
                    val fallback = dests.entries
                        .firstOrNull { it.key.startsWith(pathOnly) }?.value
                    if (fallback != null) {
                        pdfView.scrollToPage(fallback)
                        return true
                    }
                    return true
                }
                val fragment = uri.fragment
                if (fragment != null) {
                    val doc = loadedDocument
                    val page = Regex("page=(\\d+)").find(fragment)
                        ?.groupValues?.get(1)?.toIntOrNull()
                        ?: fragment.toIntOrNull()
                    if (page != null && doc != null && page in 1..doc.pageCount) {
                        pdfView.scrollToPage(page - 1)
                        return true
                    }
                }
                if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                return true
            }
        })

        PdfStateStore.restore(context, documentUri)?.let { restoreAction ->
            // Wait for document to be loaded in PdfView before scrolling
            while (pdfView.pdfDocument == null) delay(200)
            restoreAction(pdfView)
        }

        while (true) {
            delay(2000)
            PdfStateStore.save(context, documentUri, pdfView)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FragmentContainerView(ctx).apply { id = containerId }
            },
            update = { container ->
                val fm = activity.supportFragmentManager
                val existing = fm.findFragmentByTag(fragmentTag) as? AppPdfViewerFragment
                if (existing != null) {
                    if (existing.documentUri != documentUri) {
                        existing.documentUri = documentUri
                    }
                } else {
                    val fragment = AppPdfViewerFragment()
                    fm.beginTransaction()
                        .replace(containerId, fragment, fragmentTag)
                        .commitNow()
                    container.post { fragment.documentUri = documentUri }
                }
            }
        )

        // Overlay: back button
        SmallFloatingActionButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) { IconNavigation(onBack) }

        // Overlay: save-as & share
        Row(
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallFloatingActionButton({ downloadLauncher.launch(pdfName) }) { IconSave() }
            SmallFloatingActionButton({
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, documentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(intent, resources.getString(R.string.share_pdf))
                )
            }) { IconShare() }
        }

        // Overlay: save form/annotation changes FAB
        val fragment = activity.supportFragmentManager
            .findFragmentByTag(fragmentTag) as? AppPdfViewerFragment
        if (changesMade || fragment?.hasUnsavedChanges == true) {
            FloatingActionButton(
                onClick = {
                    changesMade = false
                    fragment?.applyDraftEdits()
                    loadedDocument?.let { viewModel.saveDocumentChanges(it, documentUri) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { IconSave() }
        }
    }
}
