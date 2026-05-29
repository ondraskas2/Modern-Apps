package com.vayunmathur.passwords.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.util.tryOrDefault
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.util.PasswordsViewModel
import com.vayunmathur.passwords.util.TOTP
import androidx.compose.runtime.collectAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuPage(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    passwordsViewModel: PasswordsViewModel,
    passphrase: String,
) {
    val now by passwordsViewModel.tickerFlow.collectAsState()
    ListPage<Password, Route, Route.PasswordEditPage>(backStack, viewModel, "Passwords", {
        Text(it.name.ifBlank {"(no name)"})
    }, {
        Text(it.userId)
    }, { Route.PasswordPage(it) }, { Route.PasswordEditPage(0) }, Route.Settings, otherActions = {
        BackupButtons(
            dbConfigs = listOf("passwords-db" to passphrase),
            extraFiles = emptyList()
        )
    }, trailingContent = {
        if(it.totpSecret.isNullOrBlank()) return@ListPage
        val secret = it.totpSecret
        val timeBucket = now / 1000 / 30
        val currentCode = remember(secret, timeBucket) {
            tryOrDefault("----") { TOTP.generate(secret, timeBucket * 30) }
        }
        val progress = 1f - (now / 30000f) % 1f
        Row(Modifier.clickable {
            passwordsViewModel.copyToClipboard("totp", currentCode)
        }.wrapContentHeight(), verticalAlignment = Alignment.CenterVertically) {
            Text(currentCode, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(8.dp))
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator({progress}, Modifier.size(40.dp))
                Icon(painterResource(R.drawable.content_copy_24px), contentDescription = "Copy TOTP", Modifier.size(16.dp))
            }
        }
    }, searchEnabled = true)
}
