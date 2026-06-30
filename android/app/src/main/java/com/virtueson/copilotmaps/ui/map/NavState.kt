package com.virtueson.copilotmaps.ui.map

sealed interface NavUiState {
    data object Inactive : NavUiState
    data class Active(
        val instruction: String,
        val maneuver: String,
        val distanceToTurnMeters: Int,
        val remainingDistanceMeters: Int,
        val etaEpochSeconds: Long,
        val arrived: Boolean,
    ) : NavUiState
}
