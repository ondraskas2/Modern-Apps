package com.vayunmathur.education.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
fun CoursePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, courseId: String) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val content = viewModel.content
    val course = content.course(courseId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(course?.title ?: "Course") },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        if (course == null) {
            MissingContent(padding, "This course is unavailable.")
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            if (course.description.isNotBlank()) {
                item {
                    Text(
                        course.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(course.units, key = { it.id }) { unit ->
                val skills = content.skillIdsOfUnit(unit)
                val deadline = viewModel.deadlineFor(ModuleType.UNIT, unit.id)
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { backStack.add(Route.UnitScreen(unit.id)) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(unit.title, style = MaterialTheme.typography.titleMedium)
                            StarRow(averageStars(skills, progress))
                        }
                        Text(
                            "${unit.lessons.size} lessons",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        deadline?.let {
                            Row(Modifier.padding(top = 8.dp)) { DeadlineChip(it.dueEpochDay) }
                        }
                    }
                }
            }
            course.challenge?.let { challenge ->
                item {
                    FilledTonalButton(
                        onClick = { backStack.add(Route.Quiz(challenge.id)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(challenge.title.ifBlank { "Course challenge" })
                    }
                }
            }
        }
    }
}

@Composable
fun MissingContent(padding: androidx.compose.foundation.layout.PaddingValues, message: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
