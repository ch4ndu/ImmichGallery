package com.udnahc.immichgallery.domain.usecase.auth

import com.udnahc.immichgallery.data.repository.ServerStatusRepository
import kotlinx.coroutines.flow.StateFlow

class GetServerStatusUseCase(private val repository: ServerStatusRepository) {
    operator fun invoke(): StateFlow<Boolean> = repository.isOnline
}
