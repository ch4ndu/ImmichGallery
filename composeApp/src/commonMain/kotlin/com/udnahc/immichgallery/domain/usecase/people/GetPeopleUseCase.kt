package com.udnahc.immichgallery.domain.usecase.people

import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.domain.model.Person
import kotlinx.coroutines.flow.Flow

class GetPeopleUseCase(
    private val repository: PeopleRepository
) {
    fun observe(): Flow<List<Person>> = repository.observePeople()

    suspend fun sync(): Result<Unit> = repository.syncPeople()

    suspend fun getLastSyncedAt(): Long? = repository.getLastSyncedAt()

    suspend fun hasCachedPeople(): Boolean = repository.hasCachedPeople()
}
