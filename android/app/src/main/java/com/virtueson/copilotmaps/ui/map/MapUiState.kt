package com.virtueson.copilotmaps.ui.map

/** Everything the map screen can be showing at any moment. */
sealed interface MapUiState {
    data object Loading : MapUiState
    data object PermissionNeeded : MapUiState
    data class Located(val latitude: Double, val longitude: Double) : MapUiState
    data class Error(val message: String) : MapUiState
}
