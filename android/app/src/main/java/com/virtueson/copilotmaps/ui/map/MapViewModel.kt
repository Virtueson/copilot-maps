package com.virtueson.copilotmaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virtueson.copilotmaps.location.LocationProvider
import com.virtueson.copilotmaps.location.LocationResult
import com.virtueson.copilotmaps.location.LocationSample
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class MapViewModel(
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _location = MutableStateFlow<LocationSample?>(null)
    val location: StateFlow<LocationSample?> = _location.asStateFlow()
    private var locationJob: Job? = null

    /** Call once permission is known to be granted. */
    fun onPermissionGranted() {
        _uiState.value = MapUiState.Loading
        viewModelScope.launch {
            _uiState.value = when (val result = locationProvider.getCurrentLocation()) {
                is LocationResult.Success ->
                    MapUiState.Located(result.latitude, result.longitude)
                is LocationResult.Failure ->
                    MapUiState.Error(result.reason)
            }
        }
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationProvider.locationUpdates()
                .catch { /* keep last position; stream errors are non-fatal */ }
                .collect { _location.value = it }
        }
    }

    /** Call when permission is denied or not yet granted. */
    fun onPermissionDenied() {
        _uiState.value = MapUiState.PermissionNeeded
    }
}

/** Manual DI: builds a MapViewModel with the given provider (no Hilt yet). */
class MapViewModelFactory(
    private val locationProvider: LocationProvider,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MapViewModel(locationProvider) as T
}
