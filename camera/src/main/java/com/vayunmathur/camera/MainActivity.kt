package com.vayunmathur.camera

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.camera.ui.CameraScreen
import com.vayunmathur.camera.ui.SettingsPage
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Camera : Route
    @Serializable
    data object Settings : Route
}

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DynamicTheme {
                PermissionsChecker(
                    permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    text = getString(R.string.grant_camera_permission)
                ) {
                    val backStack = rememberNavBackStack<Route>(Route.Camera)
                    MainNavigation(backStack) {
                        entry<Route.Camera> {
                            CameraScreen(backStack, viewModel)
                        }
                        entry<Route.Settings>(metadata = DialogPage()) {
                            SettingsPage(backStack, viewModel)
                        }
                    }
                }
            }
        }
    }
}
