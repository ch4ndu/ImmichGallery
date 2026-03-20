package com.udnahc.immichgallery.ui.screen.people

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.Person
import com.udnahc.immichgallery.domain.usecase.people.GetPeopleUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

@Immutable
data class PeopleState(
    val people: List<Person> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class PeopleViewModel(
    private val getPeopleUseCase: GetPeopleUseCase
) : ViewModel() {

    private val log = logging()
    private val _state = MutableStateFlow(PeopleState())
    val state: StateFlow<PeopleState> = _state.asStateFlow()

    init {
        loadPeople()
    }

    fun loadPeople() {
        viewModelScope.launch(Dispatchers.IO) {
            log.d { "Loading people..." }
            _state.update { it.copy(isLoading = true, error = null) }
            getPeopleUseCase().fold(
                onSuccess = { people ->
                    log.d { "Loaded ${people.size} people" }
                    _state.update { it.copy(people = people, isLoading = false) }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to load people" }
                    _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load people") }
                }
            )
        }
    }
}
