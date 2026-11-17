package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.core.ai.GeminiCoreService
import com.example.sharkmentor.core.analytics.MasteryScoreEngine
import com.example.sharkmentor.core.analytics.OverallMasteryReport
import com.example.sharkmentor.core.scoring.DailyScoreCalculator
import com.example.sharkmentor.core.widget.WidgetUpdateManager
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.ConcreteTargetEntity
import com.example.sharkmentor.data.local.entity.DailyReflectionEntity
import com.example.sharkmentor.data.local.entity.FocusSessionEntity
import com.example.sharkmentor.data.local.entity.MonthlyExamNetEntity
import com.example.sharkmentor.data.local.entity.TargetType
import com.example.sharkmentor.data.repository.PlanRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import kotlin.math.round

sealed class AiAdviceState {
    object Idle : AiAdviceState()
    object Loading : AiAdviceState()
    data class Success(val advice: String) : AiAdviceState()
    data class Error(val message: String) : AiAdviceState()
}

data class SubjectTimeStat(
    val subjectName: String,
    val totalSeconds: Long,
    val percentage: Float, // 0.0f - 100.0f
    val formattedDuration: String // e.g. "4 sa 20 dk"
)

@Immutable
data class AnalyticsUiState(
    val isLoading: Boolean = true,
    val dailyScore: Float = 0f,
    val isRefreshing: Boolean = false,
    val todayFocusMinutes: Long = 0L,
    val todayPhoneLifts: Int = 0,
    val todayScreenOnMinutes: Int = 0,
    val modeBreakdown: Map<String, Int> = emptyMap(),
    val weeklyFocusMinutes: Map<Int, Int> = emptyMap(),
    val subjectDistribution: List<SubjectTimeStat> = emptyList(),
    val masteryReport: OverallMasteryReport? = null,
    val aiAdviceState: AiAdviceState = AiAdviceState.Idle
)

class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {

    private var repository: PlanRepository? = null
    private val aiService = GeminiCoreService(application)
    
    private val _dailyScore = MutableStateFlow(0f)
    val dailyScore: StateFlow<Float> = _dailyScore.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    private val _isRefreshing = MutableStateFlow(false)
    private val _todayFocusMinutes = MutableStateFlow(0L)
    private val _todayPhoneLifts = MutableStateFlow(0)
    private val _todayScreenOnMinutes = MutableStateFlow(0)
    private val _modeBreakdown = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _weeklyFocusMinutes = MutableStateFlow<Map<Int, Int>>(emptyMap())
    private val _subjectDistribution = MutableStateFlow<List<SubjectTimeStat>>(emptyList())
    private val _masteryReport = MutableStateFlow<OverallMasteryReport?>(null)
    private val _aiAdviceState = MutableStateFlow<AiAdviceState>(AiAdviceState.Idle)

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<AnalyticsUiState> = combine(
        listOf(
            _isLoading, _dailyScore, _isRefreshing, _todayFocusMinutes, _todayPhoneLifts, _todayScreenOnMinutes,
            _modeBreakdown, _weeklyFocusMinutes, _subjectDistribution, _masteryReport, _aiAdviceState
        )
    ) { params ->
        AnalyticsUiState(
            isLoading = params[0] as Boolean,
            dailyScore = params[1] as Float,
            isRefreshing = params[2] as Boolean,
            todayFocusMinutes = params[3] as Long,
            todayPhoneLifts = params[4] as Int,
            todayScreenOnMinutes = params[5] as Int,
            modeBreakdown = params[6] as Map<String, Int>,
            weeklyFocusMinutes = params[7] as Map<Int, Int>,
            subjectDistribution = params[8] as List<SubjectTimeStat>,
            masteryReport = params[9] as OverallMasteryReport?,
            aiAdviceState = params[10] as AiAdviceState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AnalyticsUiState()
    )

    private val _latestReflection = MutableStateFlow<DailyReflectionEntity?>(null)
    private val _concreteTarget = MutableStateFlow<ConcreteTargetEntity?>(null)
    private val _latestMonthlyNet = MutableStateFlow<MonthlyExamNetEntity?>(null)

    private val _todayBounds = MutableStateFlow<Pair<Long, Long>?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val database = SharkMentorDatabase.getDatabase(application)
            val repo = PlanRepository(database.targetDao(), database.sessionDao(), database.planFolderDao())
            repository = repo
            val goalsDao = database.goalsDao()
            val errorDao = database.errorQuestionDao()
            val reflectionDao = database.dailyReflectionDao()
            val concreteTargetDao = database.concreteTargetDao()
            val monthlyExamNetDao = database.monthlyExamNetDao()
            
            launch {
                while(true) {
                    val bounds = DailyScoreCalculator.getTodayBounds()
                    if (_todayBounds.value != bounds) {
                        _todayBounds.value = bounds
                    }
                    delay(60_000L)
                }
            }

            val jobs = mutableListOf<Job>()
            launch {
                _todayBounds.collect { bounds ->
                    if (bounds == null) return@collect
                    jobs.forEach { it.cancel() }
                    jobs.clear()
                    
                    jobs.add(launch {
                        repo.getTodayTotalFocusMinutes(bounds.first, bounds.second).collect { _todayFocusMinutes.value = it?.toLong() ?: 0L }
                    })
                    jobs.add(launch {
                        repo.getTodayTotalPhoneLifts(bounds.first, bounds.second).collect { _todayPhoneLifts.value = it ?: 0 }
                    })
                    jobs.add(launch {
                        repo.getTodayTotalScreenOnMinutes(bounds.first, bounds.second).collect { _todayScreenOnMinutes.value = it ?: 0 }
                    })
                }
            }
            
            launch {
                repo.getAllSessionsSorted().collect { sessions ->
                    _modeBreakdown.value = sessions.groupBy { it.modeName }
                        .mapValues { entry -> entry.value.sumOf { (it.durationSeconds / 60).toInt() } }
                }
            }
            launch {
                repo.getAllSessionsSorted().collect { sessions ->
                    val result = mutableMapOf<Int, Int>()
                    val cal = Calendar.getInstance()
                    for (i in 0..6) {
                        result[cal.get(Calendar.DAY_OF_WEEK)] = 0
                        cal.add(Calendar.DAY_OF_YEAR, -1)
                    }
                    val cutoff = cal.timeInMillis
                    sessions.filter { it.completedAt >= cutoff }.forEach { session ->
                        val sessionCal = Calendar.getInstance().apply { timeInMillis = session.completedAt }
                        val dayOfWeek = sessionCal.get(Calendar.DAY_OF_WEEK)
                        result[dayOfWeek] = (result[dayOfWeek] ?: 0) + (session.durationSeconds / 60).toInt()
                    }
                    _weeklyFocusMinutes.value = result

                    // Subject distribution calculation
                    val totalSecs = sessions.sumOf { it.durationSeconds }
                    if (totalSecs > 0L) {
                        val stats = sessions
                            .groupBy { it.modeName.trim().replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString() } }
                            .mapNotNull { (subject, sessionList) ->
                                val subjectSecs = sessionList.sumOf { it.durationSeconds }
                                if (subjectSecs > 0) {
                                    val pct = (subjectSecs.toFloat() / totalSecs) * 100f
                                    val hours = subjectSecs / 3600
                                    val mins = (subjectSecs % 3600) / 60
                                    val formatted = if (hours > 0) "${hours} sa ${mins} dk" else "${mins} dk"
                                    SubjectTimeStat(
                                        subjectName = subject,
                                        totalSeconds = subjectSecs,
                                        percentage = round(pct * 10f) / 10f,
                                        formattedDuration = formatted
                                    )
                                } else null
                            }
                            .sortedByDescending { it.totalSeconds }
                        _subjectDistribution.value = stats
                    } else {
                        _subjectDistribution.value = emptyList()
                    }
                    _isLoading.value = false
                }
            }
            
            launch {
                reflectionDao.getRecentReflectionsFlow().collect { reflections ->
                    _latestReflection.value = reflections.firstOrNull()
                }
            }
            launch {
                concreteTargetDao.getTargetFlow().collect { target ->
                    _concreteTarget.value = target
                }
            }
            launch {
                monthlyExamNetDao.getAllNetsFlow().collect { nets ->
                    _latestMonthlyNet.value = nets.firstOrNull()
                }
            }
            
            launch {
                combine(
                    goalsDao.getAllTopicGoalsFlow(),
                    goalsDao.getAllResourcesFlow(),
                    errorDao.getAllQuestions(),
                    _todayFocusMinutes,
                    reflectionDao.getRecentReflectionsFlow()
                ) { topics, resources, errorQuestions, focusMinutes, reflections ->
                    val avgScore = if (reflections.isNotEmpty()) reflections.map { it.productivityScore }.average().toFloat() else null
                    MasteryScoreEngine.calculate(topics, resources, errorQuestions, focusMinutes, avgScore)
                }.collect { report ->
                    _masteryReport.value = report
                }
            }
            
            refreshDailyScore()
        }
    }

    fun refreshDailyScore() {
        viewModelScope.launch(Dispatchers.Main) {
            _isRefreshing.value = true
            try {
                val data = withContext(Dispatchers.IO) {
                    val bounds = _todayBounds.value ?: DailyScoreCalculator.getTodayBounds()
                    val minutes = repository?.getTodayTotalFocusMinutes(bounds.first, bounds.second)?.first() ?: 0
                    val stats = repository?.getTaskStatsByTypeAndDate(TargetType.SHORT_TERM, bounds.first, bounds.second)?.first()
                    val lifts = repository?.getTodayTotalPhoneLifts(bounds.first, bounds.second)?.first() ?: 0
                    val screenMinutes = repository?.getTodayTotalScreenOnMinutes(bounds.first, bounds.second)?.first() ?: 0
                    
                    val completed = stats?.completed ?: 0
                    val total = stats?.total ?: 0
                    
                    Triple(minutes, Pair(completed, total), Pair(lifts, screenMinutes))
                }
                
                val minutes = data.first
                val completed = data.second.first
                val total = data.second.second
                val lifts = data.third.first
                val screenMinutes = data.third.second

                delay(500)
                
                _dailyScore.value = DailyScoreCalculator.calculate(minutes.toInt(), completed, total, lifts, screenMinutes)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refreshAnalytics() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            delay(500)
            try {
                _masteryReport.value?.let { report ->
                    WidgetUpdateManager.syncWidgetData(
                        context = getApplication(),
                        masteryScore = report.overallScore,
                        todayFocusMinutes = report.totalFocusMinutes,
                        pendingReviews = report.pendingReviewCount
                    )
                }
            } catch (e: Exception) {
                // Fallback
            }
            _isRefreshing.value = false
        }
    }

    fun refreshAiAdvice() {
        val report = _masteryReport.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _aiAdviceState.value = AiAdviceState.Loading
            
            val weakestSubject = report.subjectBreakdown.minByOrNull { it.score }?.subjectName
            
            val result = aiService.generateGoalProjectionAdvice(
                overallScore = report.overallScore,
                weakestSubject = weakestSubject,
                focusMinutes = report.totalFocusMinutes,
                reflectionNote = _latestReflection.value?.reflectionNote,
                concreteTargetSummary = _concreteTarget.value?.aiAnalysisSummary,
                latestMonthlyNets = _latestMonthlyNet.value?.netDetailsJson
            )
            
            result.onSuccess { advice ->
                _aiAdviceState.value = AiAdviceState.Success(advice)
            }.onFailure { error ->
                _aiAdviceState.value = AiAdviceState.Error(error.localizedMessage ?: "AI Tavsiyesi Alınamadı.")
            }
        }
    }
}
