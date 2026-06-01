package com.virtueson.copilotmaps.ui.map

import com.virtueson.copilotmaps.data.Route

sealed interface RoutesState {
    data object Idle : RoutesState
    data object Loading : RoutesState
    data class Loaded(val routes: List<Route>, val selectedId: String) : RoutesState
    data class Error(val message: String) : RoutesState
}
