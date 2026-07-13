package com.vayunmathur.education

import com.vayunmathur.education.content.ChoiceAnswer
import com.vayunmathur.education.content.ContentPack
import com.vayunmathur.education.content.ContentValidator
import com.vayunmathur.education.content.MatchingAnswer
import com.vayunmathur.education.content.MatchingQuestion
import com.vayunmathur.education.content.MultiChoiceAnswer
import com.vayunmathur.education.content.MultipleChoiceQuestion
import com.vayunmathur.education.content.MultipleSelectQuestion
import com.vayunmathur.education.content.NumericAnswer
import com.vayunmathur.education.content.NumericQuestion
import com.vayunmathur.education.content.OrderingAnswer
import com.vayunmathur.education.content.OrderingQuestion
import com.vayunmathur.education.content.Prompt
import com.vayunmathur.education.content.isCorrect
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

class EducationContentTest {

    private fun loadSamplePack(): ContentPack {
        // Unit tests run with the module directory as the working directory.
        val file = File("src/main/assets/content/math_core.json")
        assertTrue("sample pack missing at ${file.absolutePath}", file.exists())
        return json.decodeFromString<ContentPack>(file.readText())
    }

    @Test
    fun samplePack_parsesAndValidates() {
        val pack = loadSamplePack()
        val errors = ContentValidator.validate(pack)
        assertTrue("expected no validation errors but got: $errors", errors.isEmpty())
    }

    @Test
    fun samplePack_exerciseRefsResolve() {
        val pack = loadSamplePack()
        val questionIds = pack.questions.map { it.id }.toSet()
        val referenced = buildList {
            pack.courses.forEach { c ->
                c.challenge?.let { addAll(it.questionIds) }
                c.units.forEach { u ->
                    u.quiz?.let { addAll(it.questionIds) }
                    u.lessons.forEach { l -> l.exercise?.let { addAll(it.questionIds) } }
                }
            }
        }
        assertTrue(referenced.isNotEmpty())
        assertTrue("all referenced questions must exist", questionIds.containsAll(referenced))
    }

    @Test
    fun validator_flagsUnknownSkillReference() {
        val pack = ContentPack(
            id = "bad",
            name = "bad",
            questions = listOf(
                MultipleChoiceQuestion(
                    id = "q1",
                    skillId = "does-not-exist",
                    prompt = Prompt("2 + 2?"),
                    choices = listOf(
                        com.vayunmathur.education.content.Choice("4"),
                        com.vayunmathur.education.content.Choice("5"),
                    ),
                    correctIndex = 0,
                ),
            ),
        )
        val errors = ContentValidator.validate(pack)
        assertTrue(errors.any { it.contains("unknown skill") })
    }

    @Test
    fun grading_multipleChoice() {
        val q = MultipleChoiceQuestion(
            id = "q", skillId = "s", prompt = Prompt("?"),
            choices = listOf(
                com.vayunmathur.education.content.Choice("a"),
                com.vayunmathur.education.content.Choice("b"),
            ),
            correctIndex = 1,
        )
        assertTrue(q.isCorrect(ChoiceAnswer(1)))
        assertFalse(q.isCorrect(ChoiceAnswer(0)))
        assertFalse(q.isCorrect(null))
    }

    @Test
    fun grading_multipleSelect_orderIndependent() {
        val q = MultipleSelectQuestion(
            id = "q", skillId = "s", prompt = Prompt("?"),
            choices = listOf(
                com.vayunmathur.education.content.Choice("a"),
                com.vayunmathur.education.content.Choice("b"),
                com.vayunmathur.education.content.Choice("c"),
            ),
            correctIndices = listOf(0, 2),
        )
        assertTrue(q.isCorrect(MultiChoiceAnswer(setOf(2, 0))))
        assertFalse(q.isCorrect(MultiChoiceAnswer(setOf(0))))
        assertFalse(q.isCorrect(MultiChoiceAnswer(setOf(0, 1, 2))))
    }

    @Test
    fun grading_numeric_withTolerance() {
        val q = NumericQuestion(id = "q", skillId = "s", prompt = Prompt("?"), answer = 5.0, tolerance = 0.1)
        assertTrue(q.isCorrect(NumericAnswer(5.05)))
        assertFalse(q.isCorrect(NumericAnswer(5.2)))
    }

    @Test
    fun grading_ordering_matchesCanonicalOrder() {
        val q = OrderingQuestion(id = "q", skillId = "s", prompt = Prompt("?"), items = listOf("a", "b", "c"))
        assertTrue(q.isCorrect(OrderingAnswer(listOf(0, 1, 2))))
        assertFalse(q.isCorrect(OrderingAnswer(listOf(1, 0, 2))))
    }

    @Test
    fun grading_matching() {
        val q = MatchingQuestion(
            id = "q", skillId = "s", prompt = Prompt("?"),
            left = listOf("x", "y"), right = listOf("1", "2"),
            correctRightForLeft = listOf(1, 0),
        )
        assertTrue(q.isCorrect(MatchingAnswer(listOf(1, 0))))
        assertFalse(q.isCorrect(MatchingAnswer(listOf(0, 1))))
    }

    @Test
    fun starsFor_ratios() {
        assertEquals(3, com.vayunmathur.education.util.EducationViewModel.starsFor(4, 4))
        assertEquals(2, com.vayunmathur.education.util.EducationViewModel.starsFor(3, 4))
        assertEquals(1, com.vayunmathur.education.util.EducationViewModel.starsFor(2, 4))
        assertEquals(0, com.vayunmathur.education.util.EducationViewModel.starsFor(0, 4))
    }

    @Test
    fun nextStreak_transitions() {
        assertEquals(1, com.vayunmathur.education.util.EducationViewModel.nextStreak(0, 0, 100))
        assertEquals(6, com.vayunmathur.education.util.EducationViewModel.nextStreak(5, 99, 100))
        assertEquals(5, com.vayunmathur.education.util.EducationViewModel.nextStreak(5, 100, 100))
        assertEquals(1, com.vayunmathur.education.util.EducationViewModel.nextStreak(5, 90, 100))
    }
}
