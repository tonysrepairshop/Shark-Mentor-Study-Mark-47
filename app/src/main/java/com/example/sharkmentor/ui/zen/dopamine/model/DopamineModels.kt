package com.example.sharkmentor.ui.zen.dopamine.model

import androidx.annotation.Keep

/**
 * Persistent Firestore-backed state for the Bio-Shield & Dopamine Protocol.
 * Stored at: students/{uid}/meta/dopamineProtocol
 */
@Keep
data class DopamineProtocolState(
    val isActive: Boolean = false,
    val activatedAt: Long = 0L,
    val expiresAt: Long = 0L,           // activatedAt + 30 days in ms
    val hashedPin: String = "",
    val unlockRequestedAt: Long? = null, // Epoch ms when deactivation was requested
    val is24hCoolingOff: Boolean = false
) {
    /** Days remaining out of the 30-day commitment (1-based, returns 0 when expired/inactive). */
    val remainingDays: Int
        get() {
            if (!isActive || expiresAt <= 0L) return 0
            val diff = expiresAt - System.currentTimeMillis()
            return if (diff > 0) (diff / (1000L * 60 * 60 * 24)).toInt() + 1 else 0
        }

    /** Hours left in the 24-hour cooling-off window (1-based, returns 0 when window closed). */
    val coolingOffHoursLeft: Int
        get() {
            val reqAt = unlockRequestedAt ?: return 0
            val elapsed = System.currentTimeMillis() - reqAt
            val total24h = 24L * 60 * 60 * 1000
            val left = total24h - elapsed
            return if (left > 0) (left / (1000L * 60 * 60)).toInt() + 1 else 0
        }
}

/**
 * Single message in the evening reflection AI chat.
 * [sender] is either "user" or "ai".
 */
@Keep
data class ReflectionMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String = "user",
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
