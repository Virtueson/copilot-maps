package com.virtueson.copilotmaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.virtueson.copilotmaps.data.AnnouncerState
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.NavProgress
import com.virtueson.copilotmaps.data.Route
import com.virtueson.copilotmaps.data.distanceToRouteMeters
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

    private var offRouteCount = 0
    private var rerouting = false
    private var lastRerouteAttemptSec = 0L
    private val _rerouteRequest = MutableSharedFlow<GeoPoint>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val rerouteRequest: SharedFlow<GeoPoint> = _rerouteRequest.asSharedFlow()

    private val offRouteMeters = 50.0
    private val offRouteFixes = 3
    private val rerouteBackoffSec = 10L

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
        offRouteCount = 0
        rerouting = false
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
            rerouting = rerouting,
        )
        announce(p)

        if (!rerouting) {
            val off = distanceToRouteMeters(point, r.points) > offRouteMeters
            offRouteCount = if (off) offRouteCount + 1 else 0
            if (offRouteCount >= offRouteFixes &&
                nowEpochSeconds() - lastRerouteAttemptSec >= rerouteBackoffSec
            ) {
                rerouting = true
                lastRerouteAttemptSec = nowEpochSeconds()
                offRouteCount = 0
                _state.value = (_state.value as? NavUiState.Active)?.copy(rerouting = true) ?: _state.value
                _announcements.tryEmit("Rerouting")
                _rerouteRequest.tryEmit(r.steps.last().location)
            }
        }
    }

    /** Result of a reroute request: adopt [newRoute] and continue (silently, no
     *  departure cue), or announce failure and keep the current route. */
    fun onRerouteResult(newRoute: Route?) {
        rerouting = false
        if (newRoute != null && newRoute.steps.isNotEmpty()) {
            route = newRoute
            stepIndex = 0
            offRouteCount = 0
            announcer = AnnouncerState(startedSpoken = true) // skip the "Head…" departure cue
            val first = newRoute.steps[0]
            _state.value = NavUiState.Active(
                instruction = first.instruction,
                maneuver = first.maneuver,
                distanceToTurnMeters = 0,
                remainingDistanceMeters = newRoute.distanceMeters,
                etaEpochSeconds = nowEpochSeconds() + newRoute.durationSeconds,
                arrived = false,
                rerouting = false,
            )
        } else {
            _announcements.tryEmit("Rerouting failed")
            _state.value = (_state.value as? NavUiState.Active)?.copy(rerouting = false) ?: _state.value
        }
    }

    fun end() {
        route = null
        stepIndex = 0
        offRouteCount = 0
        rerouting = false
        announcer = AnnouncerState()
        _state.value = NavUiState.Inactive
    }
}

class NavViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = NavViewModel() as T
}
