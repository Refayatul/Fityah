package com.refayatul.fityah.ui.activity

import java.math.BigDecimal
import kotlin.random.Random

internal data class AdaptiveMathProblem(
    val expression: String,
    val answer: BigDecimal
)

internal data class AdaptiveMathResult(
    val wasCorrect: Boolean,
    val isComplete: Boolean,
    val solvedCount: Int
)

internal class AdaptiveMathChallenge(
    private val random: Random = Random.Default,
    val requiredAnswers: Int = DEFAULT_REQUIRED_ANSWERS,
    startingDifficulty: Int = DEFAULT_DIFFICULTY
) {
    var solvedCount: Int = 0
        private set

    var difficulty: Int = startingDifficulty.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
        private set

    var currentProblem: AdaptiveMathProblem = createProblem()
        private set

    fun submit(answer: BigDecimal): AdaptiveMathResult {
        val wasCorrect = answer.compareTo(currentProblem.answer) == 0
        if (wasCorrect) {
            solvedCount++
            difficulty = (difficulty + 1).coerceAtMost(MAX_DIFFICULTY)
        } else {
            difficulty = (difficulty - 1).coerceAtLeast(MIN_DIFFICULTY)
        }

        val isComplete = solvedCount >= requiredAnswers
        val nextProblem = if (isComplete) null else createProblem()
        if (nextProblem != null) {
            currentProblem = nextProblem
        }

        return AdaptiveMathResult(
            wasCorrect = wasCorrect,
            isComplete = isComplete,
            solvedCount = solvedCount
        )
    }

    private fun createProblem(): AdaptiveMathProblem {
        return when (difficulty) {
            1 -> createSmallAdditionOrSubtraction()
            2 -> createLargeAdditionOrSubtraction()
            3 -> createSmallMultiplication()
            4 -> createLargeMultiplication()
            5 -> createMultiStepProblem()
            6 -> createDecimalProblem()
            7 -> createFractionProblem()
            8 -> createRootProblem()
            9 -> createPowerAndFractionProblem()
            else -> createExpertProblem()
        }
    }

    private fun createSmallAdditionOrSubtraction(): AdaptiveMathProblem {
        val first = random.nextInt(2, 20)
        val second = random.nextInt(1, first + 1)
        return if (random.nextBoolean()) {
            createProblem("$first + $second", first + second)
        } else {
            createProblem("$first − $second", first - second)
        }
    }

    private fun createLargeAdditionOrSubtraction(): AdaptiveMathProblem {
        val first = random.nextInt(20, 100)
        val second = random.nextInt(10, first + 1)
        return if (random.nextBoolean()) {
            createProblem("$first + $second", first + second)
        } else {
            createProblem("$first − $second", first - second)
        }
    }

    private fun createSmallMultiplication(): AdaptiveMathProblem {
        val first = random.nextInt(3, 13)
        val second = random.nextInt(3, 13)
        return createProblem("$first × $second", first * second)
    }

    private fun createLargeMultiplication(): AdaptiveMathProblem {
        val first = random.nextInt(12, 31)
        val second = random.nextInt(4, 16)
        return createProblem("$first × $second", first * second)
    }

    private fun createMultiStepProblem(): AdaptiveMathProblem {
        val first = random.nextInt(8, 21)
        val second = random.nextInt(4, 13)
        val third = random.nextInt(10, 51)
        val product = first * second
        return if (random.nextBoolean()) {
            createProblem("($first × $second) + $third", product + third)
        } else {
            createProblem("($first × $second) − $third", product - third)
        }
    }

    private fun createDecimalProblem(): AdaptiveMathProblem {
        val decimal = randomDecimal()
        val multiplier = random.nextInt(6, 20)
        val answer = decimal.multiply(multiplier.toBigDecimal())
        return AdaptiveMathProblem(
            "${decimal.toPlainString()} × $multiplier",
            answer
        )
    }

    private fun createFractionProblem(): AdaptiveMathProblem {
        val denominators = listOf(4, 5, 8, 10, 16, 20, 25)
        val firstDenominator = denominators.random(random)
        val secondDenominator = denominators.random(random)
        val firstNumerator = random.nextInt(2, firstDenominator * 2)
        val secondNumerator = random.nextInt(2, secondDenominator * 2)
        val answer = firstNumerator.toBigDecimal().divide(firstDenominator.toBigDecimal())
            .add(secondNumerator.toBigDecimal().divide(secondDenominator.toBigDecimal()))
        return AdaptiveMathProblem(
            "$firstNumerator/$firstDenominator + $secondNumerator/$secondDenominator",
            answer
        )
    }

    private fun createRootProblem(): AdaptiveMathProblem {
        val root = random.nextInt(12, 51)
        val multiplier = random.nextInt(6, 21)
        val offset = random.nextInt(30, 201)
        return createProblem(
            "(√${root * root} × $multiplier) + $offset",
            root * multiplier + offset
        )
    }

    private fun createPowerAndFractionProblem(): AdaptiveMathProblem {
        val base = random.nextInt(14, 31)
        val firstDenominator = random.nextInt(6, 16)
        val secondDenominator = random.nextInt(6, 16)
        val firstNumerator = random.nextInt(2, firstDenominator)
        val secondNumerator = random.nextInt(2, secondDenominator)
        val multiplier = leastCommonMultiple(firstDenominator, secondDenominator) *
            random.nextInt(2, 7)
        val fractionResult = firstNumerator * (multiplier / firstDenominator) +
            secondNumerator * (multiplier / secondDenominator)
        return createProblem(
            "$base² − (($firstNumerator/$firstDenominator + " +
                "$secondNumerator/$secondDenominator) × $multiplier)",
            base * base - fractionResult
        )
    }

    private fun createExpertProblem(): AdaptiveMathProblem {
        val base = random.nextInt(18, 36)
        var root = random.nextInt(8, 21)
        if ((base - root) % 2 != 0) {
            root++
        }
        val difference = base * base - root
        val denominators = (2..12).filter { difference % it == 0 }
        val denominator = denominators.random(random)
        val numerator = random.nextInt(3, 13)
        val decimal = randomDecimal()
        val fractionResult = difference / denominator * numerator
        return AdaptiveMathProblem(
            "(($base² − √${root * root}) × $numerator/$denominator) + ${decimal.toPlainString()}",
            fractionResult.toBigDecimal().add(decimal)
        )
    }

    private fun randomDecimal(): BigDecimal {
        var tenths = random.nextInt(101, 1_000)
        if (tenths % 10 == 0) {
            tenths++
        }
        return BigDecimal.valueOf(tenths.toLong(), 1)
    }

    private fun createProblem(expression: String, answer: Int): AdaptiveMathProblem {
        return AdaptiveMathProblem(expression, answer.toBigDecimal())
    }

    private fun leastCommonMultiple(first: Int, second: Int): Int {
        return first / greatestCommonDivisor(first, second) * second
    }

    private fun greatestCommonDivisor(first: Int, second: Int): Int {
        var larger = first
        var smaller = second
        while (smaller != 0) {
            val remainder = larger % smaller
            larger = smaller
            smaller = remainder
        }
        return larger
    }

    companion object {
        const val DEFAULT_REQUIRED_ANSWERS = 3
        const val DEFAULT_DIFFICULTY = 3
        private const val MIN_DIFFICULTY = 1
        private const val MAX_DIFFICULTY = 10
    }
}
