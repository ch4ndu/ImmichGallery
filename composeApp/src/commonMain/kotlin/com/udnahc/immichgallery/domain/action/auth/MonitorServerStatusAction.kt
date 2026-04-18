package com.udnahc.immichgallery.domain.action.auth

import com.udnahc.immichgallery.data.repository.ServerStatusRepository
import kotlinx.coroutines.CoroutineScope

class MonitorServerStatusAction(private val repository: ServerStatusRepository) {
    operator fun invoke(scope: CoroutineScope) = repository.startMonitoring(scope)
}
