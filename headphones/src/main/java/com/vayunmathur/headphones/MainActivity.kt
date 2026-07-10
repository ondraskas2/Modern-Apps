package com.vayunmathur.headphones

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.headphones.bluetooth.ConnectionState
import com.vayunmathur.headphones.service.HeadphonesService
import com.vayunmathur.headphones.ui.EqualizerPage
import com.vayunmathur.headphones.ui.HomePage
import com.vayunmathur.headphones.ui.SettingsPage
import com.vayunmathur.headphones.ui.SupportedDevicesPage
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reconnect to a previously-chosen device on launch.
        HeadphonesService.startIfConfigured(this)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                PermissionsChecker(requiredPermissions(), stringResource(R.string.grant_permissions)) {
                    App()
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }
}

/** Single supported-devices page until a device is chosen; the tabbed UI once connecting. */
@Composable
fun App() {
    val state by HeadphonesService.state.collectAsState()
    val hasDevice = state.deviceAddress != null || state.connection != ConnectionState.Disconnected
    if (hasDevice) Navigation() else SupportedDevicesPage()
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route

    @Serializable
    data object Equalizer : Route

    @Serializable
    data object Settings : Route
}

@Composable
private fun mainPages() = listOf(
    BottomBarItem(stringResource(R.string.label_home), Route.Home, R.drawable.home_24px),
    BottomBarItem(stringResource(R.string.label_equalizer), Route.Equalizer, R.drawable.tune_24px),
    BottomBarItem(stringResource(R.string.label_settings), Route.Settings, R.drawable.settings_24px),
)

@Composable
fun Navigation() {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val currentPage = backStack.backStack.last()
    MainNavigation(
        backStack,
        bottomBar = { BottomNavBar(backStack, mainPages(), currentPage) },
    ) {
        entry<Route.Home>(metadata = ListPage()) { HomePage() }
        entry<Route.Equalizer>(metadata = ListPage()) { EqualizerPage() }
        entry<Route.Settings>(metadata = ListPage()) { SettingsPage() }
    }
}
