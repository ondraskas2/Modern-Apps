package com.vayunmathur.education.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.education.content.Answer
import com.vayunmathur.education.content.Band
import com.vayunmathur.education.content.ContentRepository
import com.vayunmathur.education.content.Exercise
import com.vayunmathur.education.content.Grades
import com.vayunmathur.education.content.ModuleType
import com.vayunmathur.education.content.Question
import com.vayunmathur.education.content.isCorrect
import com.vayunmathur.education.data.Deadline
import com.vayunmathur.education.data.DeadlineDao
import com.vayunmathur.education.data.Learner
import com.vayunmathur.education.data.LearnerDao
import com.vayunmathur.education.data.SkillProgress
import com.vayunmathur.education.data.SkillProgressDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.LocalDate

/**
 * The single hub for the Education app: exposes the (immutable) content spine
 * plus the learner's mutable state (progress, streak, deadlines), and owns the
 * quiz-grading, streak, PIN, onboarding and parent-mode logic.
 */
class EducationViewModel(
    application: Application,
    val content: ContentRepository,
    private val learnerDao: LearnerDao,
    private val skillProgressDao: SkillProgressDao,
    private val deadlineDao: DeadlineDao,
) : AndroidViewModel(application) {

    val learner: StateFlow<Learner?> = learnerDao.getFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val progress: StateFlow<Map<String, SkillProgress>> = skillProgressDao.getAllFlow()
        .map { list -> list.associateBy { it.skillId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val deadlines: StateFlow<List<Deadline>> = deadlineDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Band -------------------------------------------------------------

    /** The active band for [learner], honoring a manual override. */
    fun bandOf(learner: Learner?): Band {
        if (learner == null) return Band.SCHOLAR
        learner.bandOverride?.let { name ->
            runCatching { Band.valueOf(name) }.getOrNull()?.let { return it }
        }
        return Grades.bandForGrade(learner.gradeLevel)
    }

    // --- Onboarding + parent edits ---------------------------------------

    fun completeOnboarding(name: String, gradeLevel: Int, pin: String) {
        updateLearner {
            it.copy(
                name = name.trim(),
                gradeLevel = gradeLevel,
                pinHash = hashPin(pin),
                onboarded = true,
            )
        }
    }

    fun setName(name: String) = updateLearner { it.copy(name = name.trim()) }
    fun setAvatar(avatar: String) = updateLearner { it.copy(avatar = avatar) }
    fun setGrade(gradeLevel: Int) = updateLearner { it.copy(gradeLevel = gradeLevel) }
    fun setDailyGoal(goal: Int) = updateLearner { it.copy(dailyGoal = goal.coerceAtLeast(1)) }
    fun setBandOverride(band: Band?) = updateLearner { it.copy(bandOverride = band?.name) }
    fun setPin(pin: String) = updateLearner { it.copy(pinHash = hashPin(pin)) }

    /** Verifies a parent PIN against the stored hash. */
    fun verifyPin(pin: String): Boolean {
        val stored = learner.value?.pinHash ?: return false
        return stored == hashPin(pin)
    }

    private inline fun updateLearner(crossinline transform: (Learner) -> Learner) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = learnerDao.get() ?: Learner()
            learnerDao.upsert(transform(current))
        }
    }

    // --- Deadlines (parent mode) -----------------------------------------

    fun deadlineFor(type: ModuleType, moduleId: String): Deadline? =
        deadlines.value.firstOrNull { it.moduleType == type.name && it.moduleId == moduleId }

    fun setDeadline(type: ModuleType, moduleId: String, dueEpochDay: Long, note: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = deadlineDao.getFor(type.name, moduleId)
            val row = existing?.copy(dueEpochDay = dueEpochDay, note = note)
                ?: Deadline(moduleType = type.name, moduleId = moduleId, dueEpochDay = dueEpochDay, note = note)
            deadlineDao.upsert(row)
        }
    }

    fun removeDeadline(deadline: Deadline) {
        viewModelScope.launch(Dispatchers.IO) { deadlineDao.delete(deadline) }
    }

    // --- Quiz grading -----------------------------------------------------

    /** Pure grading of an attempt: no side effects (call [commitResult] to persist). */
    fun grade(questions: List<Question>, answers: Map<String, Answer?>): QuizResult {
        val perSkillCorrect = mutableMapOf<String, Int>()
        val perSkillTotal = mutableMapOf<String, Int>()
        var correct = 0
        for (q in questions) {
            val ok = q.isCorrect(answers[q.id])
            if (ok) correct++
            perSkillTotal[q.skillId] = (perSkillTotal[q.skillId] ?: 0) + 1
            if (ok) perSkillCorrect[q.skillId] = (perSkillCorrect[q.skillId] ?: 0) + 1
        }
        val perSkillStars = perSkillTotal.mapValues { (skill, total) ->
            starsFor(perSkillCorrect[skill] ?: 0, total)
        }
        return QuizResult(
            total = questions.size,
            correct = correct,
            stars = starsFor(correct, questions.size),
            perSkillStars = perSkillStars,
        )
    }

    /** Persists a graded [result]: best-of star per skill, streak, and total stars. */
    fun commitResult(result: QuizResult) {
        viewModelScope.launch(Dispatchers.IO) {
            val today = LocalDate.now().toEpochDay()
            for ((skillId, stars) in result.perSkillStars) {
                val existing = skillProgressDao.get(skillId)
                skillProgressDao.upsert(
                    SkillProgress(
                        skillId = skillId,
                        stars = maxOf(existing?.stars ?: 0, stars),
                        attempts = (existing?.attempts ?: 0) + 1,
                        lastPracticedEpochDay = today,
                    )
                )
            }
            // Recompute total stars from the source of truth after the upserts.
            val totalStars = skillProgressDao.getAll().sumOf { it.stars }
            val current = learnerDao.get() ?: Learner()
            learnerDao.upsert(
                current.copy(
                    streakCount = nextStreak(current.streakCount, current.lastActivityEpochDay, today),
                    lastActivityEpochDay = today,
                    totalStars = totalStars,
                )
            )
        }
    }

    companion object {
        /** Consecutive-day streak update given last activity day and today. */
        fun nextStreak(current: Int, lastEpochDay: Long, today: Long): Int = when {
            lastEpochDay == today -> maxOf(current, 1)
            lastEpochDay == today - 1 -> current + 1
            else -> 1
        }

        /** Maps a correct/total ratio to a 0-3 star (lite mastery) rating. */
        fun starsFor(correct: Int, total: Int): Int {
            if (total == 0) return 0
            val ratio = correct.toDouble() / total
            return when {
                ratio >= 1.0 -> 3
                ratio >= 0.7 -> 2
                ratio >= 0.4 -> 1
                else -> 0
            }
        }

        private fun hashPin(pin: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Outcome of grading one exercise attempt. */
data class QuizResult(
    val total: Int,
    val correct: Int,
    val stars: Int,
    val perSkillStars: Map<String, Int>,
)

class EducationViewModelFactory(
    private val application: Application,
    private val content: ContentRepository,
    private val learnerDao: LearnerDao,
    private val skillProgressDao: SkillProgressDao,
    private val deadlineDao: DeadlineDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(EducationViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return EducationViewModel(application, content, learnerDao, skillProgressDao, deadlineDao) as T
    }
}
