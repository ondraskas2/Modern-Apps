package com.vayunmathur.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.launcher.ui.HomeScreen
import com.vayunmathur.library.ui.DynamicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val viewModel: LauncherViewModel = viewModel()
                HomeScreen(viewModel)
            }
        }
    }
}
