package com.vayunmathur.pdf

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.pdf.ui.CapturePdfScreen
import com.vayunmathur.pdf.ui.CutGlueScreen
import com.vayunmathur.pdf.ui.SafePdfViewerScreen
import com.vayunmathur.pdf.util.PdfViewModel

class MainActivity : ComponentActivity() {
    private val pdfViewModel: PdfViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val intentData: Uri? = intent.data

        if (!isAdvancedPdfSupported()) {
            setContent {
                DynamicTheme {
                    Scaffold { paddingValues ->
                        Box(Modifier.padding(paddingValues).fillMaxSize()) {
                            Text(stringResource(R.string.unsupported_version), Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
            return
        }

        setContent {
            val startedWithIntent = remember { intentData != null }
            var data: Uri? by rememberSaveable { mutableStateOf(intentData) }
            var isCapturing by rememberSaveable { mutableStateOf(false) }
            var isCutGlue by rememberSaveable { mutableStateOf(false) }

            val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { data = it }
            }

            DynamicTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isCutGlue) {
                        CutGlueScreen(onBack = { isCutGlue = false })
                    } else if (isCapturing) {
                        CapturePdfScreen(
                            viewModel = pdfViewModel,
                            onBack = { isCapturing = false },
                            onPdfCreated = { uri ->
                                data = uri
                                isCapturing = false
                            }
                        )
                    } else if (data != null) {
                        SafePdfViewerScreen(
                            uri = data!!,
                            onBack = {
                                if (startedWithIntent) finish() else data = null
                            }
                        )
                    } else {
                        InitialScreen(
                            onOpenPdf = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                            onCapturePdf = { isCapturing = true },
                            onCutGlue = { isCutGlue = true }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InitialScreen(onOpenPdf: () -> Unit, onCapturePdf: () -> Unit, onCutGlue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onOpenPdf, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.open_pdf))
            }
            Button(onClick = onCapturePdf, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.capture_pdf))
            }
            Button(onClick = onCutGlue, Modifier.padding(16.dp)) {
                Text("PDF cut and glue")
            }
        }
    }
}

fun isAdvancedPdfSupported(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ||
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 13
}
