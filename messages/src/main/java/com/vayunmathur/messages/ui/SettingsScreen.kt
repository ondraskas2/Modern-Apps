package com.vayunmathur.messages.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.R
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.util.MessagesSessionManager
import com.vayunmathur.messages.util.MessagesViewModel
import com.vayunmathur.messages.util.SourceConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backStack: NavBackStack<Route>,
    vm: MessagesViewModel,
) {
    val states by vm.connectionStates.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.settings_section_sources),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SourceSection(
                title = stringResource(R.string.source_messages),
                state = states[MessageSource.MESSAGES_WEB] ?: SourceConnectionState.Idle,
                onConfigure = { backStack.add(Route.PairMessages) },
                onDisconnect = { MessagesSessionManager.stop(MessageSource.MESSAGES_WEB) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SourceSection(
                title = stringResource(R.string.source_voice),
                state = states[MessageSource.VOICE] ?: SourceConnectionState.Idle,
                onConfigure = { backStack.add(Route.LoginVoice) },
                onDisconnect = { MessagesSessionManager.stop(MessageSource.VOICE) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SourceSection(
                title = stringResource(R.string.source_telegram),
                state = states[MessageSource.TELEGRAM] ?: SourceConnectionState.Idle,
                onConfigure = { backStack.add(Route.LoginTelegram) },
                onDisconnect = { MessagesSessionManager.stop(MessageSource.TELEGRAM) },
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            OutlinedButton(
                onClick = { vm.forceResync() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_resync))
            }
        }
    }
}

@Composable
private fun SourceSection(
    title: String,
    state: SourceConnectionState,
    onConfigure: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(
            describe(state),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onConfigure, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.inbox_setup_action))
        }
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.padding(top = 4.dp)) {
            Text("Disconnect")
        }
    }
}

private fun describe(state: SourceConnectionState): String = when (state) {
    SourceConnectionState.Idle -> "Not set up"
    is SourceConnectionState.NeedsSetup -> "Setup required"
    is SourceConnectionState.Pairing -> "Waiting for QR scan…"
    SourceConnectionState.Connecting -> "Connecting…"
    SourceConnectionState.Connected -> "Connected"
    is SourceConnectionState.Disconnected -> "Disconnected: ${state.reason}"
}
