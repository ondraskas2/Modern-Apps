package com.vayunmathur.education.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsPage(
    backStack: NavBackStack<Route>,
    viewModel: EducationViewModel,
    total: Int,
    correct: Int,
    stars: Int,
) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Results") },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            StarRow(stars)
            Text(
                "$correct / $total correct",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                encouragement(stars),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
            )
            learner?.let { StreakChip(it.streakCount) }

            Button(
                onClick = { backStack.pop() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Keep going") }
            OutlinedButton(
                onClick = { backStack.reset(Route.Home) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Home") }
        }
    }
}

private fun encouragement(stars: Int): String = when (stars) {
    3 -> "Perfect! You mastered this."
    2 -> "Great work!"
    1 -> "Good effort — keep going!"
    else -> "Keep practicing — you've got this!"
}
