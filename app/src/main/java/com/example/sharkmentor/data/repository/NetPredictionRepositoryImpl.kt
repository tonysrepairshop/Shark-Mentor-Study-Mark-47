package com.example.sharkmentor.data.repository

import com.example.sharkmentor.data.local.dao.ConcreteTargetDao
import com.example.sharkmentor.data.local.dao.GoalsDao
import com.example.sharkmentor.data.local.dao.MonthlyExamNetDao
import com.example.sharkmentor.data.local.dao.NetProjectionDao
import com.example.sharkmentor.data.local.entity.MonthlyExamNetEntity
import com.example.sharkmentor.data.local.entity.NetProjectionEntity
import com.example.sharkmentor.data.remote.GeminiRepository
import com.example.sharkmentor.domain.engine.AiNetPredictionEngine
import com.example.sharkmentor.domain.model.prediction.ExamCategory
import com.example.sharkmentor.domain.model.prediction.NetGapRecommendation
import com.example.sharkmentor.domain.model.prediction.NetProjectionResult
import com.example.sharkmentor.domain.model.prediction.NetWizardUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray

class NetPredictionRepositoryImpl(
    private val netProjectionDao: NetProjectionDao,
    private val examNetDao: MonthlyExamNetDao,
    private val goalsDao: GoalsDao,
    private val concreteTargetDao: ConcreteTargetDao,
    private val aiNetPredictionEngine: AiNetPredictionEngine = AiNetPredictionEngine(GeminiRepository())
) : NetPredictionRepository {

    override fun getNetProjectionStream(): Flow<NetWizardUiState> {
        return netProjectionDao.getLatestProjection().map { entity ->
            if (entity == null) {
                NetWizardUiState(
                    isLoading = false,
                    tytProjection = null,
                    aytProjection = null,
                    weeklyComplianceRatio = 1.0f,
                    error = null
                )
            } else {
                entity.toUiState()
            }
        }
    }

    override suspend fun refreshProjection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val examNets = examNetDao.getAllNetsFlow().firstOrNull() ?: emptyList()
            val topicGoals = goalsDao.getAllTopicGoalsFlow().firstOrNull() ?: emptyList()
            val concreteTarget = concreteTargetDao.getTargetFlow().firstOrNull()

            val sortedExams = examNets.sortedBy { it.createdAtMillis }
            val recentDenemes = sortedExams.mapNotNull { exam ->
                val type = if (isTytExam(exam)) "TYT" else if (isAytExam(exam)) "AYT" else exam.examType
                val net = extractNetValue(exam)
                if (net != null) type to net else null
            }

            val topicMasteryList = topicGoals.map { entity ->
                val score = if (entity.currentLevel <= 5) entity.currentLevel * 20 else entity.currentLevel
                Triple(entity.subject, entity.topic, score.coerceIn(0, 100))
            }

            val tytTarget = parseTargetNet(concreteTarget?.aiAnalysisSummary, isTyt = true) ?: 85.0f
            val aytTarget = parseTargetNet(concreteTarget?.aiAnalysisSummary, isTyt = false) ?: 60.0f

            val aiResult = aiNetPredictionEngine.requestAiProjection(
                tytTarget = tytTarget,
                aytTarget = aytTarget,
                recentDenemes = recentDenemes,
                topicMasteryList = topicMasteryList
            )

            if (aiResult.isSuccess) {
                val projectionEntity = aiResult.getOrThrow()
                netProjectionDao.saveProjection(projectionEntity)
                Result.success(Unit)
            } else {
                Result.failure(aiResult.exceptionOrNull() ?: Exception("AI Projeksiyon üretilemedi."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isTytExam(entity: MonthlyExamNetEntity): Boolean {
        return entity.examType.equals("TYT", ignoreCase = true)
    }

    private fun isAytExam(entity: MonthlyExamNetEntity): Boolean {
        return entity.examType.equals("AYT", ignoreCase = true)
    }

    private fun extractNetValue(entity: MonthlyExamNetEntity): Float? {
        val text = entity.netDetailsJson
        val match = Regex("""(?:Toplam Net|Net):\s*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("""(\d+(?:[.,]\d+)?)""").find(text)
        return match?.groupValues?.get(1)?.replace(",", ".")?.toFloatOrNull()
    }

    private fun parseTargetNet(summary: String?, isTyt: Boolean): Float? {
        if (summary.isNullOrBlank()) return null
        val keyword = if (isTyt) "TYT" else "AYT"
        val match = Regex("""$keyword\D*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(summary)
        return match?.groupValues?.get(1)?.replace(",", ".")?.toFloatOrNull()
    }

    private fun NetProjectionEntity.toUiState(): NetWizardUiState {
        val priorityTopicsList = try {
            val jsonArray = JSONArray(priorityTopicsJson)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                val cat = obj.optString("category", "TYT")
                val subject = obj.optString("subject", "")
                val topic = obj.optString("topic", "")
                val boost = obj.optDouble("potentialBoost", 0.0).toFloat()
                cat to NetGapRecommendation(
                    subjectName = subject,
                    topicName = topic,
                    potentialNetBoost = boost,
                    priorityScore = i + 1
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

        val tytTopics = priorityTopicsList.filter { it.first.equals("TYT", ignoreCase = true) || it.first.isBlank() }.map { it.second }
        val aytTopics = priorityTopicsList.filter { it.first.equals("AYT", ignoreCase = true) }.map { it.second }
        val defaultTopics = priorityTopicsList.map { it.second }

        return NetWizardUiState(
            isLoading = false,
            tytProjection = NetProjectionResult(
                category = ExamCategory.TYT,
                currentAverageNet = tytCurrentNet,
                targetNet = tytTargetNet,
                projectedNet = tytProjectedNet,
                netGap = tytNetGap,
                momentumScore = 0f,
                unlockedNetCapacity = 0f,
                topPriorityTopics = if (tytTopics.isNotEmpty()) tytTopics else defaultTopics,
                diagnosticSummary = strategicSummary
            ),
            aytProjection = NetProjectionResult(
                category = ExamCategory.AYT,
                currentAverageNet = aytCurrentNet,
                targetNet = aytTargetNet,
                projectedNet = aytProjectedNet,
                netGap = aytNetGap,
                momentumScore = 0f,
                unlockedNetCapacity = 0f,
                topPriorityTopics = if (aytTopics.isNotEmpty()) aytTopics else defaultTopics,
                diagnosticSummary = strategicSummary
            ),
            weeklyComplianceRatio = 1.0f,
            error = null
        )
    }
}
