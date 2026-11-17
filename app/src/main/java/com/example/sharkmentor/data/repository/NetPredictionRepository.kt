package com.example.sharkmentor.data.repository

import com.example.sharkmentor.domain.model.prediction.NetWizardUiState
import kotlinx.coroutines.flow.Flow

interface NetPredictionRepository {
    fun getNetProjectionStream(): Flow<NetWizardUiState>
    suspend fun refreshProjection(): Result<Unit>
}
