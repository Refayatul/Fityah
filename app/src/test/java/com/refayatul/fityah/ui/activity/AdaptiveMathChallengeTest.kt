package com.refayatul.fityah.ui.activity

import java.math.BigDecimal
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveMathChallengeTest {
    @Test
    fun correctAnswersIncreaseDifficultyAndCompleteChallenge() {
        val challenge = AdaptiveMathChallenge(Random(7))

        repeat(AdaptiveMathChallenge.DEFAULT_REQUIRED_ANSWERS) { index ->
            val result = challenge.submit(challenge.currentProblem.answer)
            assertTrue(result.wasCorrect)
            assertEquals(index + 1, result.solvedCount)
        }

        assertEquals(6, challenge.difficulty)
        assertEquals(3, challenge.solvedCount)
    }

    @Test
    fun incorrectAnswerKeepsProgressAndCreatesAnotherProblem() {
        val challenge = AdaptiveMathChallenge(Random(11))
        val firstProblem = challenge.currentProblem

        val result = challenge.submit(firstProblem.answer.add(BigDecimal.ONE))

        assertFalse(result.wasCorrect)
        assertFalse(result.isComplete)
        assertEquals(0, result.solvedCount)
        assertEquals(2, challenge.difficulty)
        assertNotEquals(firstProblem, challenge.currentProblem)
    }

    @Test
    fun challengeCompletesOnlyAfterRequiredCorrectAnswers() {
        val challenge = AdaptiveMathChallenge(Random(19))

        repeat(AdaptiveMathChallenge.DEFAULT_REQUIRED_ANSWERS - 1) {
            assertFalse(challenge.submit(challenge.currentProblem.answer).isComplete)
        }

        assertTrue(challenge.submit(challenge.currentProblem.answer).isComplete)
    }

    @Test
    fun configuredQuestionCountControlsCompletion() {
        val challenge = AdaptiveMathChallenge(Random(23), requiredAnswers = 5)

        repeat(4) {
            assertFalse(challenge.submit(challenge.currentProblem.answer).isComplete)
        }

        assertTrue(challenge.submit(challenge.currentProblem.answer).isComplete)
    }

    @Test
    fun configuredLevelControlsStartingDifficulty() {
        val challenge = AdaptiveMathChallenge(Random(29), startingDifficulty = 10)

        assertEquals(10, challenge.difficulty)
        assertTrue(challenge.currentProblem.expression.contains("√"))
        assertTrue(challenge.currentProblem.expression.contains("²"))
        assertTrue(challenge.currentProblem.expression.contains("/"))
    }

    @Test
    fun difficultyDoesNotIncreasePastLevelTen() {
        val challenge = AdaptiveMathChallenge(
            random = Random(31),
            requiredAnswers = 2,
            startingDifficulty = 10
        )

        challenge.submit(challenge.currentProblem.answer)

        assertEquals(10, challenge.difficulty)
    }

    @Test
    fun decimalAnswersAreComparedByValue() {
        val challenge = AdaptiveMathChallenge(Random(37), startingDifficulty = 6)
        val equivalentAnswer = BigDecimal(challenge.currentProblem.answer.toPlainString())

        assertTrue(challenge.submit(equivalentAnswer).wasCorrect)
    }
}
