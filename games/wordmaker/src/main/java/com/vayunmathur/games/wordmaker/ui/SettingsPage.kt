package com.vayunmathur.games.wordmaker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.util.WordMakerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(viewModel: WordMakerViewModel, onBack: () -> Unit) {
    val tapToSpell by viewModel.tapToSpell.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(android.R.drawable.ic_menu_revert), contentDescription = "Back")
                    }
                },
                actions = {
                    com.vayunmathur.library.ui.BackupButtons(
                        datastoreNames = listOf("settings")
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tap_to_spell), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.tap_to_spell_description), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = tapToSpell,
                    onCheckedChange = { viewModel.setTapToSpell(it) }
                )
            }
        }
    }
}
