package com.virtueson.copilotmaps.ui.map

import com.virtueson.copilotmaps.data.Place

sealed interface PlacesState {
    data object Idle : PlacesState
    data object Loading : PlacesState
    data class Loaded(val places: List<Place>, val fellBackToNearby: Boolean) : PlacesState
    data class Error(val message: String) : PlacesState
}
