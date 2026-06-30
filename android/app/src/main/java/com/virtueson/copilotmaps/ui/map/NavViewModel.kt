package com.virtueson.copilotmaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.virtueson.copilotmaps.data.AnnouncerState
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.NavProgress
import com.virtueson.copilotmaps.data.Route
import com.virtueson.copilotmaps.data.navProgress
import com.virtueson.copilotmaps.data.nextAnnouncement
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class NavViewModel(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    private val _state = MutableStateFlow<NavUiState>(NavUiState.Inactive)
    val state: StateFlow<NavUiState> = _state.asStateFlow()

    private var route: Route? = null
    private var stepIndex = 0

    private var announcer = AnnouncerState()
    private val _announcements = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val announcements: SharedFlow<String> = _announcements.asSharedFlow()

    private fun announce(progress: NavProgress) {
        val steps = route?.steps ?: return
        val result = nextAnnouncement(steps, progress, announcer)
        announcer = result.state
        result.utterance?.let { _announcements.tryEmit(it) }
    }

    fun start(route: Route) {
        if (route.steps.isEmpty()) return
        this.route = route
        stepIndex = 0
        val first = route.steps[0]
        _state.value = NavUiState.Active(
            instruction = first.instruction,
            maneuver = first.maneuver,
            distanceToTurnMeters = 0,
            remainingDistanceMeters = route.distanceMeters,
            etaEpochSeconds = nowEpochSeconds() + route.durationSeconds,
            arrived = false,
        )
        announcer = AnnouncerState()
        announce(
            NavProgress(
                stepIndex = 0,
                distanceToTurnMeters = 0,
                remainingDistanceMeters = route.distanceMeters,
                arrived = false,
            )
        )
    }

    fun onLocation(point: GeoPoint) {
        val r = route ?: return
        val p = navProgress(r.steps, point, stepIndex)
        stepIndex = p.stepIndex
        val step = r.steps[p.stepIndex]
        val fraction =
            if (r.distanceMeters > 0) p.remainingDistanceMeters.toDouble() / r.distanceMeters else 0.0
        _state.value = NavUiState.Active(
            instruction = step.instruction,
            maneuver = step.maneuver,
            distanceToTurnMeters = p.distanceToTurnMeters,
            remainingDistanceMeters = p.remainingDistanceMeters,
            etaEpochSeconds = nowEpochSeconds() + (r.durationSeconds * fraction).toLong(),
            arrived = p.arrived,
        )
        announce(p)
    }

    fun end() {
        route = null
        stepIndex = 0
        announcer = AnnouncerState()
        _state.value = NavUiState.Inactive
    }
}

class NavViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = NavViewModel() as T
}
