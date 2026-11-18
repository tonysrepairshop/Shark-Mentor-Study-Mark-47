package com.example.sharkmentor.data.model

/**
 * Data models, anti-cheat structures, badge tiers, and reward tier calculators for
 * Shark League Peer Learning & Question Chat (`league_chats/{roomId}`).
 */

enum class ChatChannelType {
    GROUP,
    DM,
    STUDY_GROUP,
    DIRECT_MESSAGE;

    val isGroupChannel: Boolean
        get() = this == GROUP || this == STUDY_GROUP
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
    PENDING_APPROVAL,
    SOLVED,
    SOLVED_APPROVED,
    AI_SOLVED,
    CLOSED_BY_AI,
    EXPIRED;

    val isClosed: Boolean
        get() = this == SOLVED ||
            this == SOLVED_APPROVED ||
            this == AI_SOLVED ||
            this == CLOSED_BY_AI
}

enum class SolutionMethod {
    TEXT,
    DRAWING,
    PHOTO,
    AI_FALLBACK
}

enum class BadgeTier(
    val displayName: String,
    val minMentorPoints: Int
) {
    BRONZE(displayName = "Bronz Köpekbalığı", minMentorPoints = 100),
    SILVER(displayName = "Gümüş Köpekbalığı", minMentorPoints = 350),
    GOLD(displayName = "Altın Köpekbalığı", minMentorPoints = 800),
    MEGALODON(displayName = "Megalodon Mentor", minMentorPoints = 1800);

    companion object {
        fun fromPoints(points: Int): BadgeTier = when {
            points >= MEGALODON.minMentorPoints -> MEGALODON
            points >= GOLD.minMentorPoints -> GOLD
            points >= SILVER.minMentorPoints -> SILVER
            else -> BRONZE
        }
    }
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
const val QUESTION_AUTO_APPROVE_WINDOW_MILLIS: Long = 24L * 60L * 60_000L
const val QUESTION_AI_SWEEP_WINDOW_MILLIS: Long = QUESTION_AUTO_APPROVE_WINDOW_MILLIS
const val MAX_WEEKLY_QUESTION_BONUS_SECONDS: Long = 9000L
const val MAX_DAILY_REWARDED_SOLUTIONS_PER_OWNER: Int = 2
const val MAX_REJECTED_ATTEMPTS_PER_QUESTION: Int = 2
const val MAX_SHOWCASE_BADGES: Int = 5

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

/**
 * Represents a chat room document in `league_chats/{roomId}`.
 */
data class LeagueChatRoom(
    val roomId: String = "",
    val type: ChatChannelType = ChatChannelType.GROUP,
    val title: String = "",
    val groupCode: String? = null,
    val participantIds: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val mutedBy: List<String> = emptyList(),
    val themePresetId: Int = 0,
    val dmStatus: DmRequestStatus = DmRequestStatus.ACCEPTED,
    val requesterUid: String = "",
    val receiverUid: String = "",
    val lastMessagePreview: String = "",
    val lastMessageAtMillis: Long = System.currentTimeMillis(),
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun isMutedFor(uid: String): Boolean = uid in mutedBy

    fun toMap(): Map<String, Any?> = mapOf(
        "roomId" to roomId,
        "type" to if (type.isGroupChannel) ChatChannelType.GROUP.name else ChatChannelType.DM.name,
        "title" to title,
        "groupCode" to groupCode,
        "participantIds" to participantIds,
        "participantNames" to participantNames,
        "mutedBy" to mutedBy.distinct(),
        "themePresetId" to themePresetId,
        "dmStatus" to dmStatus.name,
        "status" to dmStatus.name,
        "requesterUid" to requesterUid,
        "receiverUid" to receiverUid,
        "lastMessagePreview" to lastMessagePreview,
        "lastMessageAtMillis" to lastMessageAtMillis,
        "createdAtMillis" to createdAtMillis
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(idOverride: String, map: Map<String, Any?>): LeagueChatRoom {
            val rawType = (map["type"] as? String).orEmpty()
            val resolvedType = runCatching { ChatChannelType.valueOf(rawType) }
                .getOrDefault(ChatChannelType.GROUP)

            val rawDmStatus = (map["dmStatus"] as? String ?: map["status"] as? String).orEmpty()
            val resolvedDmStatus = runCatching { DmRequestStatus.valueOf(rawDmStatus) }
                .getOrDefault(DmRequestStatus.ACCEPTED)

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

            val mutedList = (map["mutedBy"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.distinct()
                .orEmpty()

            return LeagueChatRoom(
                roomId = (map["roomId"] as? String)?.ifBlank { idOverride } ?: idOverride,
                type = resolvedType,
                title = (map["title"] as? String).orEmpty(),
                groupCode = map["groupCode"] as? String,
                participantIds = participants,
                participantNames = namesMap,
                mutedBy = mutedList,
                themePresetId = (map["themePresetId"] as? Number)?.toInt() ?: 0,
                dmStatus = resolvedDmStatus,
                requesterUid = (map["requesterUid"] as? String).orEmpty(),
                receiverUid = (map["receiverUid"] as? String).orEmpty(),
                lastMessagePreview = (map["lastMessagePreview"] as? String).orEmpty(),
                lastMessageAtMillis = (map["lastMessageAtMillis"] as? Number)?.toLong()
                    ?: System.currentTimeMillis(),
                createdAtMillis = (map["createdAtMillis"] as? Number)?.toLong()
                    ?: System.currentTimeMillis()
            )
        }
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
    val autoApproveAt: Long = sharedAtMillis + QUESTION_AUTO_APPROVE_WINDOW_MILLIS,
    val durationMinutes: Int = QUESTION_REWARD_WINDOW_MINUTES.toInt(),
    val status: QuestionStatus = QuestionStatus.OPEN,
    val approvedSolutionId: String? = null,
    val approvedSolverUid: String? = null,
    val approvedSolverName: String? = null,
    val solutionsCount: Int = 0,
    val aiAccessedByUids: List<String> = emptyList(),
    val rejectedSolverCounts: Map<String, Int> = emptyMap(),
    val aiFallbackText: String? = null
) {
    /**
     * Returns true if [uid] is eligible to submit a rewarded peer solution:
     * - Not the question owner
     * - Did not open Shark AI hint/solution on this question ([aiAccessedByUids])
     * - Has fewer than [MAX_REJECTED_ATTEMPTS_PER_QUESTION] (2) rejected attempts on this question
     */
    fun canUserSubmitRewardedSolution(uid: String): Boolean {
        if (uid.isBlank() || uid == ownerUid) return false
        if (uid in aiAccessedByUids) return false
        val rejectedCount = rejectedSolverCounts[uid] ?: 0
        return rejectedCount < MAX_REJECTED_ATTEMPTS_PER_QUESTION
    }

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
        "autoApproveAt" to autoApproveAt,
        "durationMinutes" to durationMinutes,
        "status" to status.name,
        "approvedSolutionId" to approvedSolutionId,
        "approvedSolverUid" to approvedSolverUid,
        "approvedSolverName" to approvedSolverName,
        "solutionsCount" to solutionsCount,
        "aiAccessedByUids" to aiAccessedByUids.distinct(),
        "rejectedSolverCounts" to rejectedSolverCounts,
        "aiFallbackText" to aiFallbackText
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>?): QuestionAttachment? {
            if (map == null) return null
            val sharedAt = (map["sharedAtMillis"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val expiresAt = (map["expiresAtMillis"] as? Number)?.toLong()
                ?: (sharedAt + QUESTION_REWARD_WINDOW_MILLIS)
            val autoApprove = (map["autoApproveAt"] as? Number)?.toLong()
                ?: (sharedAt + QUESTION_AUTO_APPROVE_WINDOW_MILLIS)
            val statusStr = (map["status"] as? String).orEmpty()
            val resolvedStatus = runCatching { QuestionStatus.valueOf(statusStr) }
                .getOrDefault(QuestionStatus.OPEN)

            val aiAccessed = (map["aiAccessedByUids"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.distinct()
                .orEmpty()

            val rejectedCounts = (map["rejectedSolverCounts"] as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (k, v) ->
                    val key = k as? String ?: return@mapNotNull null
                    val count = (v as? Number)?.toInt() ?: return@mapNotNull null
                    key to count
                }
                ?.toMap()
                .orEmpty()

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
                autoApproveAt = autoApprove,
                durationMinutes = (map["durationMinutes"] as? Number)?.toInt()
                    ?: QUESTION_REWARD_WINDOW_MINUTES.toInt(),
                status = resolvedStatus,
                approvedSolutionId = map["approvedSolutionId"] as? String,
                approvedSolverUid = map["approvedSolverUid"] as? String,
                approvedSolverName = map["approvedSolverName"] as? String,
                solutionsCount = (map["solutionsCount"] as? Number)?.toInt() ?: 0,
                aiAccessedByUids = aiAccessed,
                rejectedSolverCounts = rejectedCounts,
                aiFallbackText = map["aiFallbackText"] as? String
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
    val method: SolutionMethod = SolutionMethod.DRAWING,
    val annotatedImageUrl: String = "",
    val explanationText: String = "",
    val submittedAtMillis: Long = System.currentTimeMillis(),
    val autoApproveAt: Long = submittedAtMillis + QUESTION_AUTO_APPROVE_WINDOW_MILLIS,
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
        "method" to method.name,
        "annotatedImageUrl" to annotatedImageUrl,
        "explanationText" to explanationText,
        "submittedAtMillis" to submittedAtMillis,
        "autoApproveAt" to autoApproveAt,
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
            val methodStr = (map["method"] as? String).orEmpty()
            val resolvedMethod = runCatching { SolutionMethod.valueOf(methodStr) }
                .getOrDefault(SolutionMethod.DRAWING)
            val submittedAt = (map["submittedAtMillis"] as? Number)?.toLong()
                ?: System.currentTimeMillis()
            val autoApprove = (map["autoApproveAt"] as? Number)?.toLong()
                ?: (submittedAt + QUESTION_AUTO_APPROVE_WINDOW_MILLIS)

            return SolutionSubmission(
                solutionId = (map["solutionId"] as? String).orEmpty(),
                questionMessageId = (map["questionMessageId"] as? String).orEmpty(),
                questionOwnerUid = (map["questionOwnerUid"] as? String).orEmpty(),
                solverUid = (map["solverUid"] as? String).orEmpty(),
                solverName = (map["solverName"] as? String).orEmpty(),
                method = resolvedMethod,
                annotatedImageUrl = (map["annotatedImageUrl"] as? String).orEmpty(),
                explanationText = (map["explanationText"] as? String).orEmpty(),
                submittedAtMillis = submittedAt,
                autoApproveAt = autoApprove,
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
    val channelType: ChatChannelType = ChatChannelType.GROUP,
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
        "roomId" to channelId,
        "channelId" to channelId,
        "channelType" to channelType.name,
        "senderUid" to senderUid,
        "senderName" to senderName,
        "senderAvatarPresetId" to senderAvatarPresetId,
        "senderShowcaseBadges" to senderShowcaseBadges.take(MAX_SHOWCASE_BADGES),
        "showcaseBadgeIds" to senderShowcaseBadges.take(MAX_SHOWCASE_BADGES),
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
                .getOrDefault(ChatChannelType.GROUP)

            val msgTypeStr = (map["messageType"] as? String).orEmpty()
            val msgType = runCatching { MessageType.valueOf(msgTypeStr) }
                .getOrDefault(MessageType.TEXT)

            val badges = ((map["showcaseBadgeIds"] as? List<*>) ?: (map["senderShowcaseBadges"] as? List<*>))
                ?.mapNotNull { it as? String }
                ?.take(MAX_SHOWCASE_BADGES)
                .orEmpty()

            val qMap = map["questionAttachment"] as? Map<String, Any?>
            val sMap = map["solutionSubmission"] as? Map<String, Any?>

            return LeagueChatMessage(
                id = (map["id"] as? String)?.ifBlank { idOverride } ?: idOverride,
                channelId = ((map["roomId"] as? String) ?: (map["channelId"] as? String)).orEmpty(),
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
    val mutedBy: List<String> = emptyList(),
    val themePresetId: Int = 0,
    val lastMessagePreview: String = "",
    val lastMessageAtMillis: Long = System.currentTimeMillis(),
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "roomId" to threadId,
        "threadId" to threadId,
        "type" to ChatChannelType.DM.name,
        "participantIds" to participantIds,
        "participantNames" to participantNames,
        "requesterUid" to requesterUid,
        "requesterName" to requesterName,
        "receiverUid" to receiverUid,
        "receiverName" to receiverName,
        "status" to status.name,
        "dmStatus" to status.name,
        "mutedBy" to mutedBy.distinct(),
        "themePresetId" to themePresetId,
        "lastMessagePreview" to lastMessagePreview,
        "lastMessageAtMillis" to lastMessageAtMillis,
        "createdAtMillis" to createdAtMillis
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(idOverride: String, map: Map<String, Any?>): DirectMessageThread {
            val statusStr = ((map["status"] as? String) ?: (map["dmStatus"] as? String)).orEmpty()
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

            val mutedList = (map["mutedBy"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.distinct()
                .orEmpty()

            return DirectMessageThread(
                threadId = ((map["threadId"] as? String) ?: (map["roomId"] as? String))
                    ?.ifBlank { idOverride } ?: idOverride,
                participantIds = participants,
                participantNames = namesMap,
                requesterUid = (map["requesterUid"] as? String).orEmpty(),
                requesterName = (map["requesterName"] as? String).orEmpty(),
                receiverUid = (map["receiverUid"] as? String).orEmpty(),
                receiverName = (map["receiverName"] as? String).orEmpty(),
                status = status,
                mutedBy = mutedList,
                themePresetId = (map["themePresetId"] as? Number)?.toInt() ?: 0,
                lastMessagePreview = (map["lastMessagePreview"] as? String).orEmpty(),
                lastMessageAtMillis = (map["lastMessageAtMillis"] as? Number)?.toLong()
                    ?: System.currentTimeMillis(),
                createdAtMillis = (map["createdAtMillis"] as? Number)?.toLong()
                    ?: System.currentTimeMillis()
            )
        }
    }
}

/**
 * Represents a badge document in `students/{uid}/badges/{badgeId}`.
 */
data class StudentBadge(
    val badgeId: String = "",
    val title: String = "",
    val description: String = "",
    val tier: BadgeTier = BadgeTier.BRONZE,
    val progress: Int = 0,
    val target: Int = 1,
    val unlocked: Boolean = false,
    val unlockedAtMillis: Long? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "badgeId" to badgeId,
        "title" to title,
        "description" to description,
        "tier" to tier.name,
        "progress" to progress,
        "target" to target,
        "unlocked" to unlocked,
        "unlockedAtMillis" to unlockedAtMillis
    )

    companion object {
        fun fromMap(idOverride: String, map: Map<String, Any?>): StudentBadge {
            val tierStr = (map["tier"] as? String).orEmpty()
            val resolvedTier = runCatching { BadgeTier.valueOf(tierStr) }.getOrDefault(BadgeTier.BRONZE)
            return StudentBadge(
                badgeId = (map["badgeId"] as? String)?.ifBlank { idOverride } ?: idOverride,
                title = (map["title"] as? String).orEmpty(),
                description = (map["description"] as? String).orEmpty(),
                tier = resolvedTier,
                progress = (map["progress"] as? Number)?.toInt() ?: 0,
                target = ((map["target"] as? Number)?.toInt() ?: 1).coerceAtLeast(1),
                unlocked = (map["unlocked"] as? Boolean) ?: false,
                unlockedAtMillis = (map["unlockedAtMillis"] as? Number)?.toLong()
            )
        }
    }
}

/**
 * Represents a user block entry in `user_blocks/{uid}/blocked/{targetUid}`.
 */
data class UserBlockRecord(
    val blockerUid: String = "",
    val blockedUid: String = "",
    val blockedName: String = "",
    val reason: String = "",
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "blockerUid" to blockerUid,
        "blockedUid" to blockedUid,
        "blockedName" to blockedName,
        "reason" to reason,
        "createdAtMillis" to createdAtMillis
    )
}

/**
 * Represents a moderation report document in `moderation_reports/{reportId}`.
 */
data class ModerationReport(
    val reportId: String = "",
    val reporterUid: String = "",
    val reportedUid: String = "",
    val roomId: String = "",
    val messageId: String? = null,
    val reason: String = "",
    val details: String = "",
    val status: String = "OPEN",
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "reportId" to reportId,
        "reporterUid" to reporterUid,
        "reportedUid" to reportedUid,
        "roomId" to roomId,
        "messageId" to messageId,
        "reason" to reason,
        "details" to details,
        "status" to status,
        "createdAtMillis" to createdAtMillis
    )
}

data class MentorStats(
    val mentorPoints: Int = 0,
    val pendingMentorPoints: Int = 0,
    val weeklyQuestionBonusSeconds: Long = 0L,
    val solvedTotalCount: Int = 0,
    val goldenWindowSolveCount: Int = 0,
    val unlockedBadges: List<String> = emptyList(),
    val showcaseBadgeIds: List<String> = emptyList(),
    val badgeTier: BadgeTier = BadgeTier.fromPoints(mentorPoints)
) {
    val showcaseBadges: List<String>
        get() = trimmedShowcaseBadges

    val cappedWeeklyQuestionBonusSeconds: Long
        get() = weeklyQuestionBonusSeconds.coerceIn(0L, MAX_WEEKLY_QUESTION_BONUS_SECONDS)

    val trimmedShowcaseBadges: List<String>
        get() = showcaseBadgeIds.distinct().take(MAX_SHOWCASE_BADGES)

    fun toMap(): Map<String, Any?> = mapOf(
        "mentorPoints" to mentorPoints.coerceAtLeast(0),
        "pendingMentorPoints" to pendingMentorPoints.coerceAtLeast(0),
        "weeklyQuestionBonusSeconds" to cappedWeeklyQuestionBonusSeconds,
        "solvedTotalCount" to solvedTotalCount.coerceAtLeast(0),
        "goldenWindowSolveCount" to goldenWindowSolveCount.coerceAtLeast(0),
        "unlockedBadges" to unlockedBadges.distinct(),
        "showcaseBadgeIds" to trimmedShowcaseBadges,
        "showcaseBadges" to trimmedShowcaseBadges,
        "badgeTier" to badgeTier.name
    )

    companion object {
        fun fromMap(map: Map<String, Any?>?): MentorStats {
            if (map == null) return MentorStats()
            val unlocked = (map["unlockedBadges"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.distinct()
                .orEmpty()
            val rawShowcase = ((map["showcaseBadgeIds"] as? List<*>) ?: (map["showcaseBadges"] as? List<*>))
                ?.mapNotNull { it as? String }
                ?.distinct()
                ?.take(MAX_SHOWCASE_BADGES)
                .orEmpty()
            val bonusSec = ((map["weeklyQuestionBonusSeconds"] as? Number)?.toLong() ?: 0L)
                .coerceIn(0L, MAX_WEEKLY_QUESTION_BONUS_SECONDS)
            val pts = ((map["mentorPoints"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
            val tierStr = (map["badgeTier"] as? String).orEmpty()
            val resolvedTier = runCatching { BadgeTier.valueOf(tierStr) }
                .getOrDefault(BadgeTier.fromPoints(pts))

            return MentorStats(
                mentorPoints = pts,
                pendingMentorPoints = ((map["pendingMentorPoints"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                weeklyQuestionBonusSeconds = bonusSec,
                solvedTotalCount = ((map["solvedTotalCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                goldenWindowSolveCount = ((map["goldenWindowSolveCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                unlockedBadges = unlocked,
                showcaseBadgeIds = rawShowcase,
                badgeTier = resolvedTier
            )
        }
    }
}
