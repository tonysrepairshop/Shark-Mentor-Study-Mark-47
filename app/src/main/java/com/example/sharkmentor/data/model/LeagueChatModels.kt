package com.example.sharkmentor.data.model

/**
 * Data models and reward tier calculators for Shark League Peer Learning & Question Chat
 * (Study Group channels + Request-Approved Direct Messages).
 */

enum class ChatChannelType {
    STUDY_GROUP,
    DIRECT_MESSAGE
}

enum class DmRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}

enum class MessageType {
    TEXT,
    QUESTION_CARD,
    SOLUTION_CARD,
    AI_FALLBACK_SOLUTION
}

enum class QuestionStatus {
    OPEN,
    SOLVED_APPROVED,
    CLOSED_BY_AI,
    EXPIRED
}

enum class RewardTier(
    val mentorPoints: Int,
    val bonusFocusSeconds: Long,
    val windowLabel: String
) {
    GOLD_100(mentorPoints = 100, bonusFocusSeconds = 900L, windowLabel = "Altın Pencere (0-15 dk)"),
    SILVER_70(mentorPoints = 70, bonusFocusSeconds = 600L, windowLabel = "Gümüş Pencere (15-60 dk)"),
    BRONZE_40(mentorPoints = 40, bonusFocusSeconds = 360L, windowLabel = "Bronz Pencere (60-105 dk)"),
    EXPIRED_0(mentorPoints = 0, bonusFocusSeconds = 0L, windowLabel = "Süresi Doldu (>105 dk)")
}

enum class SolutionVerifyStatus {
    PENDING_ESCROW,
    APPROVED,
    REJECTED
}

const val QUESTION_REWARD_WINDOW_MINUTES: Long = 105L
const val QUESTION_REWARD_WINDOW_MILLIS: Long = QUESTION_REWARD_WINDOW_MINUTES * 60_000L
const val QUESTION_AI_SWEEP_WINDOW_MILLIS: Long = 24L * 60L * 60_000L
const val MAX_WEEKLY_QUESTION_BONUS_SECONDS: Long = 9000L
const val MAX_DAILY_REWARDED_SOLUTIONS_PER_OWNER: Int = 2
const val MAX_SHOWCASE_BADGES: Int = 3

/**
 * Calculates the peer-learning reward tier based on elapsed time between when the question
 * was shared ([sharedAtMillis]) and when the solution was submitted ([submittedAtMillis]):
 * - 0..15 minutes   -> [RewardTier.GOLD_100] (+100 Mentor Points & +900 sec focus bonus)
 * - 15..60 minutes  -> [RewardTier.SILVER_70] (+70 Mentor Points & +600 sec focus bonus)
 * - 60..105 minutes -> [RewardTier.BRONZE_40] (+40 Mentor Points & +360 sec focus bonus)
 * - >105 minutes    -> [RewardTier.EXPIRED_0] (0 Points & 0 sec)
 */
fun calculateRewardTier(sharedAtMillis: Long, submittedAtMillis: Long): RewardTier {
    val elapsedMillis = (submittedAtMillis - sharedAtMillis).coerceAtLeast(0L)
    val elapsedMinutes = elapsedMillis / 60_000.0
    return when {
        elapsedMinutes <= 15.0 -> RewardTier.GOLD_100
        elapsedMinutes <= 60.0 -> RewardTier.SILVER_70
        elapsedMinutes <= 105.0 -> RewardTier.BRONZE_40
        else -> RewardTier.EXPIRED_0
    }
}

data class QuestionAttachment(
    val questionId: String = "",
    val localErrorQuestionId: Long? = null,
    val ownerUid: String = "",
    val ownerName: String = "",
    val subject: String = "Genel",
    val topic: String = "Genel",
    val questionImageUrl: String = "",
    val noteText: String = "",
    val sharedAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long = sharedAtMillis + QUESTION_REWARD_WINDOW_MILLIS,
    val durationMinutes: Int = QUESTION_REWARD_WINDOW_MINUTES.toInt(),
    val status: QuestionStatus = QuestionStatus.OPEN,
    val approvedSolutionId: String? = null,
    val approvedSolverUid: String? = null,
    val approvedSolverName: String? = null,
    val solutionsCount: Int = 0
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "questionId" to questionId,
        "localErrorQuestionId" to localErrorQuestionId,
        "ownerUid" to ownerUid,
        "ownerName" to ownerName,
        "subject" to subject,
        "topic" to topic,
        "questionImageUrl" to questionImageUrl,
        "noteText" to noteText,
        "sharedAtMillis" to sharedAtMillis,
        "expiresAtMillis" to expiresAtMillis,
        "durationMinutes" to durationMinutes,
        "status" to status.name,
        "approvedSolutionId" to approvedSolutionId,
        "approvedSolverUid" to approvedSolverUid,
        "approvedSolverName" to approvedSolverName,
        "solutionsCount" to solutionsCount
    )

    companion object {
        fun fromMap(map: Map<String, Any?>?): QuestionAttachment? {
            if (map == null) return null
            val sharedAt = (map["sharedAtMillis"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val expiresAt = (map["expiresAtMillis"] as? Number)?.toLong()
                ?: (sharedAt + QUESTION_REWARD_WINDOW_MILLIS)
            val statusStr = (map["status"] as? String).orEmpty()
            val resolvedStatus = runCatching { QuestionStatus.valueOf(statusStr) }
                .getOrDefault(QuestionStatus.OPEN)

            return QuestionAttachment(
                questionId = (map["questionId"] as? String).orEmpty(),
                localErrorQuestionId = (map["localErrorQuestionId"] as? Number)?.toLong(),
                ownerUid = (map["ownerUid"] as? String).orEmpty(),
                ownerName = (map["ownerName"] as? String).orEmpty(),
                subject = (map["subject"] as? String)?.ifBlank { "Genel" } ?: "Genel",
                topic = (map["topic"] as? String)?.ifBlank { "Genel" } ?: "Genel",
                questionImageUrl = (map["questionImageUrl"] as? String).orEmpty(),
                noteText = (map["noteText"] as? String).orEmpty(),
                sharedAtMillis = sharedAt,
                expiresAtMillis = expiresAt,
                durationMinutes = (map["durationMinutes"] as? Number)?.toInt()
                    ?: QUESTION_REWARD_WINDOW_MINUTES.toInt(),
                status = resolvedStatus,
                approvedSolutionId = map["approvedSolutionId"] as? String,
                approvedSolverUid = map["approvedSolverUid"] as? String,
                approvedSolverName = map["approvedSolverName"] as? String,
                solutionsCount = (map["solutionsCount"] as? Number)?.toInt() ?: 0
            )
        }
    }
}

data class SolutionSubmission(
    val solutionId: String = "",
    val questionMessageId: String = "",
    val questionOwnerUid: String = "",
    val solverUid: String = "",
    val solverName: String = "",
    val annotatedImageUrl: String = "",
    val explanationText: String = "",
    val submittedAtMillis: Long = System.currentTimeMillis(),
    val rewardTier: RewardTier = RewardTier.GOLD_100,
    val escrowPoints: Int = rewardTier.mentorPoints,
    val potentialBonusSeconds: Long = rewardTier.bonusFocusSeconds,
    val verifyStatus: SolutionVerifyStatus = SolutionVerifyStatus.PENDING_ESCROW,
    val verifiedAtMillis: Long? = null,
    val awardedPoints: Int = 0,
    val awardedBonusSeconds: Long = 0L
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "solutionId" to solutionId,
        "questionMessageId" to questionMessageId,
        "questionOwnerUid" to questionOwnerUid,
        "solverUid" to solverUid,
        "solverName" to solverName,
        "annotatedImageUrl" to annotatedImageUrl,
        "explanationText" to explanationText,
        "submittedAtMillis" to submittedAtMillis,
        "rewardTier" to rewardTier.name,
        "escrowPoints" to escrowPoints,
        "potentialBonusSeconds" to potentialBonusSeconds,
        "verifyStatus" to verifyStatus.name,
        "verifiedAtMillis" to verifiedAtMillis,
        "awardedPoints" to awardedPoints,
        "awardedBonusSeconds" to awardedBonusSeconds
    )

    companion object {
        fun fromMap(map: Map<String, Any?>?): SolutionSubmission? {
            if (map == null) return null
            val tierStr = (map["rewardTier"] as? String).orEmpty()
            val tier = runCatching { RewardTier.valueOf(tierStr) }.getOrDefault(RewardTier.GOLD_100)
            val statusStr = (map["verifyStatus"] as? String).orEmpty()
            val verifyStatus = runCatching { SolutionVerifyStatus.valueOf(statusStr) }
                .getOrDefault(SolutionVerifyStatus.PENDING_ESCROW)

            return SolutionSubmission(
                solutionId = (map["solutionId"] as? String).orEmpty(),
                questionMessageId = (map["questionMessageId"] as? String).orEmpty(),
                questionOwnerUid = (map["questionOwnerUid"] as? String).orEmpty(),
                solverUid = (map["solverUid"] as? String).orEmpty(),
                solverName = (map["solverName"] as? String).orEmpty(),
                annotatedImageUrl = (map["annotatedImageUrl"] as? String).orEmpty(),
                explanationText = (map["explanationText"] as? String).orEmpty(),
                submittedAtMillis = (map["submittedAtMillis"] as? Number)?.toLong()
                    ?: System.currentTimeMillis(),
                rewardTier = tier,
                escrowPoints = (map["escrowPoints"] as? Number)?.toInt() ?: tier.mentorPoints,
                potentialBonusSeconds = (map["potentialBonusSeconds"] as? Number)?.toLong()
                    ?: tier.bonusFocusSeconds,
                verifyStatus = verifyStatus,
                verifiedAtMillis = (map["verifiedAtMillis"] as? Number)?.toLong(),
                awardedPoints = (map["awardedPoints"] as? Number)?.toInt() ?: 0,
                awardedBonusSeconds = (map["awardedBonusSeconds"] as? Number)?.toLong() ?: 0L
            )
        }
    }
}

data class LeagueChatMessage(
    val id: String = "",
    val channelId: String = "",
    val channelType: ChatChannelType = ChatChannelType.STUDY_GROUP,
    val senderUid: String = "",
    val senderName: String = "",
    val senderAvatarPresetId: Int = 0,
    val senderShowcaseBadges: List<String> = emptyList(),
    val messageType: MessageType = MessageType.TEXT,
    val text: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val questionAttachment: QuestionAttachment? = null,
    val solutionSubmission: SolutionSubmission? = null,
    val replyToQuestionMessageId: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "channelId" to channelId,
        "channelType" to channelType.name,
        "senderUid" to senderUid,
        "senderName" to senderName,
        "senderAvatarPresetId" to senderAvatarPresetId,
        "senderShowcaseBadges" to senderShowcaseBadges.take(MAX_SHOWCASE_BADGES),
        "messageType" to messageType.name,
        "text" to text,
        "createdAtMillis" to createdAtMillis,
        "questionAttachment" to questionAttachment?.toMap(),
        "solutionSubmission" to solutionSubmission?.toMap(),
        "replyToQuestionMessageId" to replyToQuestionMessageId
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(idOverride: String, map: Map<String, Any?>): LeagueChatMessage {
            val chTypeStr = (map["channelType"] as? String).orEmpty()
            val chType = runCatching { ChatChannelType.valueOf(chTypeStr) }
                .getOrDefault(ChatChannelType.STUDY_GROUP)

            val msgTypeStr = (map["messageType"] as? String).orEmpty()
            val msgType = runCatching { MessageType.valueOf(msgTypeStr) }
                .getOrDefault(MessageType.TEXT)

            val badges = (map["senderShowcaseBadges"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.take(MAX_SHOWCASE_BADGES)
                .orEmpty()

            val qMap = map["questionAttachment"] as? Map<String, Any?>
            val sMap = map["solutionSubmission"] as? Map<String, Any?>

            return LeagueChatMessage(
                id = (map["id"] as? String)?.ifBlank { idOverride } ?: idOverride,
                channelId = (map["channelId"] as? String).orEmpty(),
                channelType = chType,
                senderUid = (map["senderUid"] as? String).orEmpty(),
                senderName = (map["senderName"] as? String).orEmpty(),
                senderAvatarPresetId = (map["senderAvatarPresetId"] as? Number)?.toInt() ?: 0,
                senderShowcaseBadges = badges,
                messageType = msgType,
                text = (map["text"] as? String).orEmpty(),
                createdAtMillis = (map["createdAtMillis"] as? Number)?.toLong()
                    ?: System.currentTimeMillis(),
                questionAttachment = QuestionAttachment.fromMap(qMap),
                solutionSubmission = SolutionSubmission.fromMap(sMap),
                replyToQuestionMessageId = map["replyToQuestionMessageId"] as? String
            )
        }
    }
}

data class DirectMessageThread(
    val threadId: String = "",
    val participantIds: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val requesterUid: String = "",
    val requesterName: String = "",
    val receiverUid: String = "",
    val receiverName: String = "",
    val status: DmRequestStatus = DmRequestStatus.PENDING,
    val lastMessagePreview: String = "",
    val lastMessageAtMillis: Long = System.currentTimeMillis(),
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "threadId" to threadId,
        "participantIds" to participantIds,
        "participantNames" to participantNames,
        "requesterUid" to requesterUid,
        "requesterName" to requesterName,
        "receiverUid" to receiverUid,
        "receiverName" to receiverName,
        "status" to status.name,
        "lastMessagePreview" to lastMessagePreview,
        "lastMessageAtMillis" to lastMessageAtMillis,
        "createdAtMillis" to createdAtMillis
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(idOverride: String, map: Map<String, Any?>): DirectMessageThread {
            val statusStr = (map["status"] as? String).orEmpty()
            val status = runCatching { DmRequestStatus.valueOf(statusStr) }
                .getOrDefault(DmRequestStatus.PENDING)

            val participants = (map["participantIds"] as? List<*>)
                ?.mapNotNull { it as? String }
                .orEmpty()

            val namesMap = (map["participantNames"] as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (k, v) ->
                    val key = k as? String ?: return@mapNotNull null
                    val value = v as? String ?: return@mapNotNull null
                    key to value
                }
                ?.toMap()
                .orEmpty()

            return DirectMessageThread(
                threadId = (map["threadId"] as? String)?.ifBlank { idOverride } ?: idOverride,
                participantIds = participants,
                participantNames = namesMap,
                requesterUid = (map["requesterUid"] as? String).orEmpty(),
                requesterName = (map["requesterName"] as? String).orEmpty(),
                receiverUid = (map["receiverUid"] as? String).orEmpty(),
                receiverName = (map["receiverName"] as? String).orEmpty(),
                status = status,
                lastMessagePreview = (map["lastMessagePreview"] as? String).orEmpty(),
                lastMessageAtMillis = (map["lastMessageAtMillis"] as? Number)?.toLong()
                    ?: System.currentTimeMillis(),
                createdAtMillis = (map["createdAtMillis"] as? Number)?.toLong()
                    ?: System.currentTimeMillis()
            )
        }
    }
}

data class MentorStats(
    val mentorPoints: Int = 0,
    val pendingMentorPoints: Int = 0,
    val weeklyQuestionBonusSeconds: Long = 0L,
    val solvedTotalCount: Int = 0,
    val goldenWindowSolveCount: Int = 0,
    val unlockedBadges: List<String> = emptyList(),
    val showcaseBadges: List<String> = emptyList()
) {
    val cappedWeeklyQuestionBonusSeconds: Long
        get() = weeklyQuestionBonusSeconds.coerceIn(0L, MAX_WEEKLY_QUESTION_BONUS_SECONDS)

    val trimmedShowcaseBadges: List<String>
        get() = showcaseBadges.distinct().take(MAX_SHOWCASE_BADGES)

    fun toMap(): Map<String, Any?> = mapOf(
        "mentorPoints" to mentorPoints.coerceAtLeast(0),
        "pendingMentorPoints" to pendingMentorPoints.coerceAtLeast(0),
        "weeklyQuestionBonusSeconds" to cappedWeeklyQuestionBonusSeconds,
        "solvedTotalCount" to solvedTotalCount.coerceAtLeast(0),
        "goldenWindowSolveCount" to goldenWindowSolveCount.coerceAtLeast(0),
        "unlockedBadges" to unlockedBadges.distinct(),
        "showcaseBadges" to trimmedShowcaseBadges
    )

    companion object {
        fun fromMap(map: Map<String, Any?>?): MentorStats {
            if (map == null) return MentorStats()
            val unlocked = (map["unlockedBadges"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.distinct()
                .orEmpty()
            val showcase = (map["showcaseBadges"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.distinct()
                ?.take(MAX_SHOWCASE_BADGES)
                .orEmpty()
            val bonusSec = ((map["weeklyQuestionBonusSeconds"] as? Number)?.toLong() ?: 0L)
                .coerceIn(0L, MAX_WEEKLY_QUESTION_BONUS_SECONDS)

            return MentorStats(
                mentorPoints = ((map["mentorPoints"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                pendingMentorPoints = ((map["pendingMentorPoints"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                weeklyQuestionBonusSeconds = bonusSec,
                solvedTotalCount = ((map["solvedTotalCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                goldenWindowSolveCount = ((map["goldenWindowSolveCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                unlockedBadges = unlocked,
                showcaseBadges = showcase
            )
        }
    }
}
