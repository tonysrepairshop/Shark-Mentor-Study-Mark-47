package com.example.sharkmentor.domain.engine

import com.example.sharkmentor.domain.model.prediction.ExamCategory
import com.example.sharkmentor.domain.model.prediction.NetProjectionResult
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lightweight utility for basic net calculations and formatting.
 * AI-driven projections are now calculated asynchronously via [AiNetPredictionEngine].
 */
object NetPredictionEngine {

    fun Float.roundToOneDecimal(): Float = ((this * 10f).roundToInt() / 10f)

    fun calculateBasicAverage(nets: List<Float>): Float {
        val validNets = nets.filter { !it.isNaN() && it > 0f }
        if (validNets.isEmpty()) return 0f
        return (validNets.sum() / validNets.size).roundToOneDecimal()
    }

    fun calculateNetGap(targetNet: Float, currentNet: Float): Float {
        return max(0f, targetNet - currentNet).roundToOneDecimal()
    }

    fun fallbackEmptyResult(category: ExamCategory, targetNet: Float): NetProjectionResult {
        val roundedTarget = targetNet.roundToOneDecimal()
        return NetProjectionResult(
            category = category,
            currentAverageNet = 0f,
            targetNet = roundedTarget,
            projectedNet = 0f,
            netGap = roundedTarget,
            momentumScore = 0f,
            unlockedNetCapacity = 0f,
            topPriorityTopics = emptyList(),
            diagnosticSummary = "${category.name} konusu veya denemesi henüz girilmedi."
        )
    }
}
