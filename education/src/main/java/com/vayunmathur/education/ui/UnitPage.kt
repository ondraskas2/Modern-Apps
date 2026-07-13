package com.vayunmathur.education.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.content.ModuleType
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, unitId: String) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val content = viewModel.content
    val unit = content.unit(unitId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(unit?.title ?: "Unit") },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        if (unit == null) {
            MissingContent(padding, "This unit is unavailable.")
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            val deadline = viewModel.deadlineFor(ModuleType.UNIT, unit.id)
            deadline?.let {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        DeadlineChip(it.dueEpochDay)
                    }
                }
            }
            items(unit.lessons, key = { it.id }) { lesson ->
                val skills = lesson.exercise?.let { content.skillIdsOf(it) } ?: emptyList()
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { backStack.add(Route.LessonScreen(lesson.id)) },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(lesson.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    append("${lesson.videos.size} video${if (lesson.videos.size == 1) "" else "s"}")
                                    if (lesson.exercise != null) append(" · exercise")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StarRow(averageStars(skills, progress))
                    }
                }
            }
            unit.quiz?.let { quiz ->
                item {
                    FilledTonalButton(
                        onClick = { backStack.add(Route.Quiz(quiz.id)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(quiz.title.ifBlank { "Unit quiz" })
                    }
                }
            }
        }
    }
}
