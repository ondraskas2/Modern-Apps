package com.vayunmathur.messages.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.R
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.telegram.TelegramClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramLoginScreen(backStack: NavBackStack<Route>) {
    val state by TelegramClient.state.collectAsState()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is TelegramClient.State.Connected) backStack.pop()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_telegram_title)) },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val s = state) {
                is TelegramClient.State.Idle,
                is TelegramClient.State.NeedsSetup -> {
                    Text(
                        stringResource(R.string.setup_telegram_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text(stringResource(R.string.setup_telegram_phone_label)) },
                        placeholder = { Text(stringResource(R.string.setup_telegram_phone_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { TelegramClient.submitPhoneNumber(phone.trim()) },
                        enabled = phone.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(stringResource(R.string.setup_telegram_send_code), fontWeight = FontWeight.SemiBold)
                    }
                }

                is TelegramClient.State.Connecting -> {
                    Text(
                        stringResource(R.string.setup_telegram_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.height(40.dp),
                        strokeWidth = 3.dp,
                    )
                }

                is TelegramClient.State.AwaitingCode -> {
                    Text(
                        stringResource(R.string.setup_telegram_code_sent, s.phone),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.setup_telegram_code_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { TelegramClient.submitCode(code.trim()) },
                        enabled = code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(stringResource(R.string.setup_telegram_verify), fontWeight = FontWeight.SemiBold)
                    }
                }

                is TelegramClient.State.AwaitingPassword -> {
                    Text(
                        stringResource(R.string.setup_telegram_2fa_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.hint.isNotBlank()) {
                        Text(
                            stringResource(R.string.setup_telegram_2fa_hint, s.hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.setup_telegram_password_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { TelegramClient.submitPassword(password) },
                        enabled = password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(stringResource(R.string.setup_telegram_sign_in), fontWeight = FontWeight.SemiBold)
                    }
                }

                is TelegramClient.State.Connected -> {
                    Text("Connected to Telegram!")
                }

                is TelegramClient.State.Disconnected -> {
                    Text(
                        stringResource(R.string.setup_telegram_disconnected, s.reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { TelegramClient.start() },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text("Retry", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
