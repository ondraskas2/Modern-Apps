package com.vayunmathur.education.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.education.Route
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.education.util.LocalNarrator
import com.vayunmathur.library.util.NavBackStack

/** K-2 reward: celebratory stars and praise, no numbers or percentages. */
@Composable
fun K2RewardPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, stars: Int) {
    val narrator = LocalNarrator.current
    LaunchedEffect(Unit) { narrator?.speak("You did it! Great job!") }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text("🦉", fontSize = 96.sp)
            Text("⭐".repeat(stars.coerceIn(1, 3)), fontSize = 56.sp)
            Text(
                "You did it!",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = {
                    narrator?.stop()
                    backStack.reset(Route.Home)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("🏠  Home", fontSize = 24.sp)
            }
        }
    }
}
