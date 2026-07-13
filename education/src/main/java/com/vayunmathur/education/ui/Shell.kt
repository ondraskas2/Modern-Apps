package com.vayunmathur.education.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.content.Band
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * Band dispatchers. The same content spine is rendered by a different
 * "shell" per band. Home and Course are the divergent entry surfaces; deeper
 * screens (Unit/Lesson/Quiz/Results) are shared by the Explorer and Scholar
 * shells. K-2 has its own fully bespoke, audio-first flow (see K2* screens)
 * and does not use the shared Course/Unit/Lesson screens.
 */
@Composable
fun HomePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    when (activeBand(viewModel)) {
        Band.SCHOLAR -> ScholarHomePage(backStack, viewModel)
        Band.ELEMENTARY -> ExplorerHomePage(backStack, viewModel)
        Band.K2 -> K2HomePage(backStack, viewModel)
    }
}

@Composable
fun CoursePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, courseId: String) {
    when (activeBand(viewModel)) {
        Band.SCHOLAR -> ScholarCoursePage(backStack, viewModel, courseId)
        else -> ExplorerCoursePage(backStack, viewModel, courseId)
    }
}

@Composable
private fun activeBand(viewModel: EducationViewModel): Band {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    return viewModel.bandOf(learner)
}
