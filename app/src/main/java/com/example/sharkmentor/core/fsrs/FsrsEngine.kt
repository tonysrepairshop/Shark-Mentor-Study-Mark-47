package com.example.sharkmentor.core.fsrs

import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity

enum class FsrsRating(val value: Int) {
    AGAIN(1), // Total recall lapse: reset interval, increment lapse count
    HARD(2),  // Recalled with significant cognitive effort / hint
    GOOD(3),  // Successful standard active recall
    EASY(4)   // Instant effortless recall
}

object FsrsEngine {
    fun calculateNextReview(
        entity: ErrorQuestionEntity,
        rating: FsrsRating,
        reviewTimeMillis: Long = System.currentTimeMillis()
    ): ErrorQuestionEntity {
        var stability = entity.stability
        var difficulty = entity.difficulty
        val lapses: Int
        val state: Int
        val intervalMillis: Long

        when (rating) {
            FsrsRating.AGAIN -> {
                lapses = entity.lapses + 1
                stability = (stability * 0.5).coerceAtLeast(0.1)
                difficulty = (difficulty + 0.8).coerceAtMost(10.0)
                state = 3 // Relearning
                intervalMillis = 10 * 60 * 1000L // 10 minutes immediate queue
            }
            FsrsRating.HARD -> {
                lapses = entity.lapses
                stability = (stability * 1.2).coerceAtLeast(0.2)
                difficulty = (difficulty + 0.2).coerceAtMost(10.0)
                state = 2 // Review
                intervalMillis = 1L * 24 * 60 * 60 * 1000L // 1 day
            }
            FsrsRating.GOOD -> {
                lapses = entity.lapses
                stability = (stability * 2.5).coerceAtLeast(1.0)
                difficulty = entity.difficulty
                state = 2 // Review
                intervalMillis = (stability * 24 * 60 * 60 * 1000L).toLong()
            }
            FsrsRating.EASY -> {
                lapses = entity.lapses
                stability = (stability * 4.0).coerceAtLeast(2.0)
                difficulty = (difficulty - 0.5).coerceAtLeast(1.0)
                state = 2 // Review
                intervalMillis = (stability * 24 * 60 * 60 * 1000L).toLong()
            }
        }

        return entity.copy(
            reps = entity.reps + 1,
            lapses = lapses,
            stability = stability,
            difficulty = difficulty,
            state = state,
            lastReviewMillis = reviewTimeMillis,
            dueDateMillis = reviewTimeMillis + intervalMillis
        )
    }
}
