package com.udnahc.immichgallery.ui.screen.people

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.Person
import com.udnahc.immichgallery.domain.usecase.people.GetPeopleUseCase
import com.udnahc.immichgallery.ui.model.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

@Immutable
data class PeopleState(
    val people: List<Person> = emptyList(),
    val filteredPeople: List<Person> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = false,
    val error: UiMessage? = null,
    val bannerError: UiMessage? = null,
    val lastSyncedAt: Long? = null,
    val isBuilding: Boolean = false,
    val isSyncing: Boolean = false
)

class PeopleViewModel(
    private val getPeopleUseCase: GetPeopleUseCase
) : ViewModel() {

    private val log = logging("PeopleViewModel")
    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(PeopleState())
    val state: StateFlow<PeopleState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(getPeopleUseCase.observe(), query) { people, query ->
                people to people.filterByQuery(query)
            }
                .flowOn(Dispatchers.Default)
                .collect { (people, filteredPeople) ->
                    log.d { "Room emitted ${people.size} people" }
                    _state.update {
                        it.copy(
                            people = people,
                            filteredPeople = filteredPeople
                        )
                    }
                }
        }

        // Initial sync from network
        syncFromServer()
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
        this.query.value = query
    }

    fun refreshAll() {
        syncFromServer()
    }

    fun dismissBannerError() {
        _state.update { it.copy(bannerError = null) }
    }

    private fun syncFromServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasCachedPeople = getPeopleUseCase.hasCachedPeople()

            if (!hasCachedPeople) {
                _state.update { it.copy(isBuilding = true, error = null) }
            } else {
                _state.update { it.copy(isSyncing = true, bannerError = null) }
            }

            val lastSync = getPeopleUseCase.getLastSyncedAt()
            _state.update { it.copy(lastSyncedAt = lastSync) }

            getPeopleUseCase.sync().fold(
                onSuccess = {
                    log.d { "Synced people from server" }
                    _state.update { it.copy(isBuilding = false, isSyncing = false, error = null) }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to sync people from server" }
                    if (!hasCachedPeople) {
                        _state.update {
                            it.copy(
                                isBuilding = false,
                                isSyncing = false,
                                error = UiMessage.NoConnectionToServer
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                isSyncing = false,
                                bannerError = UiMessage.CannotConnectToServer
                            )
                        }
                    }
                }
            )
        }
    }

    private fun List<Person>.filterByQuery(query: String): List<Person> =
        if (query.isBlank()) this else filter { it.name.contains(query, ignoreCase = true) }
}
