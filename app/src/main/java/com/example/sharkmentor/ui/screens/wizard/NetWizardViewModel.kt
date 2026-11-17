package com.example.sharkmentor.ui.screens.wizard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.repository.NetPredictionRepository
import com.example.sharkmentor.data.repository.NetPredictionRepositoryImpl
import com.example.sharkmentor.domain.model.prediction.NetWizardUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NetWizardViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: NetPredictionRepository = NetPredictionRepositoryImpl(
        netProjectionDao = SharkMentorDatabase.getDatabase(application).netProjectionDao(),
        examNetDao = SharkMentorDatabase.getDatabase(application).monthlyExamNetDao(),
        goalsDao = SharkMentorDatabase.getDatabase(application).goalsDao(),
        concreteTargetDao = SharkMentorDatabase.getDatabase(application).concreteTargetDao()
    )
) : AndroidViewModel(application) {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val uiState: StateFlow<NetWizardUiState> = repository.getNetProjectionStream()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = NetWizardUiState(isLoading = true)
        )

    fun refreshProjection() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isRefreshing.value = true
            repository.refreshProjection()
            _isRefreshing.value = false
        }
    }
}

