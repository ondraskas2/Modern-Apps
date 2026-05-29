package com.vayunmathur.pdf

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.pdf.ui.CapturePdfScreen
import com.vayunmathur.pdf.ui.PdfViewerScreen
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
            var data by rememberSaveable { mutableStateOf(intentData) }
            var password: String? by rememberSaveable { mutableStateOf(null) }
            var isCapturing by rememberSaveable { mutableStateOf(false) }

            val pdfDocument by pdfViewModel.pdfDocument.collectAsState()
            val passwordRequired by pdfViewModel.passwordRequired.collectAsState()
            val passwordError by pdfViewModel.passwordError.collectAsState()

            val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { data = it }
            }

            LaunchedEffect(data, password) {
                val uri = data
                if (uri != null) {
                    pdfViewModel.loadDocument(uri, password)
                }
            }

            DynamicTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (data == null && !isCapturing) {
                        InitialScreen(
                            onOpenPdf = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                            onCapturePdf = { isCapturing = true }
                        )
                    } else if (isCapturing) {
                        CapturePdfScreen(
                            viewModel = pdfViewModel,
                            onBack = { isCapturing = false },
                            onPdfCreated = { uri ->
                                data = uri
                                isCapturing = false
                            }
                        )
                    } else {
                        val onBack = {
                            if (startedWithIntent) {
                                finish()
                            } else {
                                data = null
                                password = null
                                pdfViewModel.clearDocument()
                            }
                        }

                        pdfDocument?.let {
                            PdfViewerScreen(
                                pdfDocument = it,
                                pdfName = data?.lastPathSegment ?: "pdf",
                                viewModel = pdfViewModel,
                                onBack = onBack
                            )
                        } ?: run {
                            BackHandler { onBack() }
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                        }
                    }

                    if (passwordRequired) {
                        PasswordDialog(
                            errorMessage = passwordError,
                            onPasswordEntered = { password = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InitialScreen(onOpenPdf: () -> Unit, onCapturePdf: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onOpenPdf, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.open_pdf))
            }
            Button(onClick = onCapturePdf, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.capture_pdf))
            }
        }
    }
}

@Composable
fun PasswordDialog(
    onPasswordEntered: (String) -> Unit,
    errorMessage: String? = null
) {
    var password by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { /* Prevent dismissing by clicking outside */ },
        title = { Text(stringResource(R.string.password_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.password_dialog_message))
                if (errorMessage != null) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password_label)) },
                    singleLine = true,
                    isError = errorMessage != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onPasswordEntered(password) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = null
    )
}

fun isAdvancedPdfSupported(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ||
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 13
}
