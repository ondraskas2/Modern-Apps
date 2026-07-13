package com.vayunmathur.education.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.education.Route
import com.vayunmathur.education.content.VideoRef
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, lessonId: String) {
    val content = viewModel.content
    val lesson = content.lesson(lessonId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lesson?.title ?: "Lesson") },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        if (lesson == null) {
            MissingContent(padding, "This lesson is unavailable.")
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (lesson.videos.isNotEmpty()) {
                Text("Watch", style = MaterialTheme.typography.titleMedium)
                lesson.videos.forEach { VideoRow(it) }
            }
            lesson.exercise?.let { exercise ->
                Button(
                    onClick = { backStack.add(Route.Quiz(exercise.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(exercise.title.ifBlank { "Start exercise" })
                }
            }
        }
    }
}

@Composable
private fun VideoRow(video: VideoRef) {
    val context = LocalContext.current
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.bodyLarge)
                if (video.durationSeconds > 0) {
                    Text(
                        formatDuration(video.durationSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = {
                val uri = "https://www.youtube.com/watch?v=${video.youtubeId}".toUri()
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }) {
                IconPlay()
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
