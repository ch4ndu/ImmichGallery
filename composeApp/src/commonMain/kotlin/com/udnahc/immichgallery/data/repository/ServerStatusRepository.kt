package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.remote.ImmichApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ServerStatusRepository(
    private val apiService: ImmichApiService
) {
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var monitoringJob: Job? = null

    fun startMonitoring(scope: CoroutineScope) {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            while (true) {
                _isOnline.value = try {
                    apiService.ping()
                    true
                } catch (_: Exception) {
                    false
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    companion object {
        private const val PING_INTERVAL_MS = 30_000L
    }
}
