package com.virtueson.copilotmaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.RoutesRepository
import com.virtueson.copilotmaps.data.RoutesResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RouteViewModel(
    private val repository: RoutesRepository,
    private val languageProvider: () -> String? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow<RoutesState>(RoutesState.Idle)
    val state: StateFlow<RoutesState> = _state.asStateFlow()

    fun planRoutes(origin: GeoPoint, destination: GeoPoint) {
        _state.value = RoutesState.Loading
        viewModelScope.launch {
            _state.value = when (val result = repository.planRoutes(origin, destination, languageProvider())) {
                is RoutesResult.Success -> RoutesState.Loaded(result.routes, result.routes.first().id)
                is RoutesResult.Failure -> RoutesState.Error(result.reason)
            }
        }
    }

    fun selectRoute(id: String) {
        val current = _state.value
        if (current is RoutesState.Loaded) {
            _state.value = current.copy(selectedId = id)
        }
    }

    /** Clear the planned route(s), returning to the idle map. */
    fun clear() {
        _state.value = RoutesState.Idle
    }
}

class RouteViewModelFactory(
    private val repository: RoutesRepository,
    private val languageProvider: () -> String? = { null },
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RouteViewModel(repository, languageProvider) as T
}
