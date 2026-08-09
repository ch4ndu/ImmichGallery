package com.udnahc.immichgallery.domain.action.auth

import com.udnahc.immichgallery.data.repository.ServerStatusRepository

class StopServerStatusMonitoringAction(private val repository: ServerStatusRepository) {
    operator fun invoke() = repository.stopMonitoring()
}
