package com.virtueson.copilotmaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.PlacesRepository
import com.virtueson.copilotmaps.data.PlacesResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PlaceCategory(val query: String) {
    GAS("gas station"),
    FOOD("restaurant"),
}

class PlacesViewModel(
    private val repository: PlacesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<PlacesState>(PlacesState.Idle)
    val state: StateFlow<PlacesState> = _state.asStateFlow()

    fun search(category: PlaceCategory, origin: GeoPoint, routePolyline: String?) {
        _state.value = PlacesState.Loading
        viewModelScope.launch {
            _state.value = when (val result = repository.searchPlaces(category.query, origin, routePolyline)) {
                is PlacesResult.Success -> PlacesState.Loaded(
                    places = result.places,
                    fellBackToNearby = routePolyline != null && result.mode == "nearby",
                )
                is PlacesResult.Failure -> PlacesState.Error(result.reason)
            }
        }
    }

    fun clear() {
        _state.value = PlacesState.Idle
    }
}

class PlacesViewModelFactory(
    private val repository: PlacesRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PlacesViewModel(repository) as T
}
