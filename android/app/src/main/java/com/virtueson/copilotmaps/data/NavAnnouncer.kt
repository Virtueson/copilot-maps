package com.virtueson.copilotmaps.data

/** Remembers which cues have already been spoken so none repeats. */
data class AnnouncerState(
    val startedSpoken: Boolean = false,
    val preparedStep: Int = -1,
    val nowStep: Int = -1,
    val arrivedSpoken: Boolean = false,
)

/** New announcer state plus an optional line to speak this update. */
data class AnnouncerResult(val state: AnnouncerState, val utterance: String?)

/**
 * Decide the next thing to say, given current [progress]. At most one line per
 * call; the next location update picks up any remaining cue. Pure — no Android,
 * no TTS — so it is fully unit-testable.
 */
fun nextAnnouncement(
    steps: List<RouteStep>,
    progress: NavProgress,
    state: AnnouncerState,
    prepareMeters: Int = 300,
    nowMeters: Int = 40,
): AnnouncerResult {
    if (steps.isEmpty()) return AnnouncerResult(state, null)

    if (!state.startedSpoken) {
        return AnnouncerResult(state.copy(startedSpoken = true), steps[0].instruction)
    }

    if (progress.arrived && !state.arrivedSpoken) {
        val last = steps.size - 1
        return AnnouncerResult(
            state.copy(arrivedSpoken = true, nowStep = last, preparedStep = last),
            "You have arrived.",
        )
    }

    val i = progress.stepIndex

    // Safety net: if a fast/laggy GPS fix advanced us past a real turn whose
    // "now" cue never fired (the 30-40 m window was skipped), speak that turn's
    // confirmation the moment we register reaching it -- guarantees every real
    // maneuver is announced regardless of GPS spacing. `i >= 2` excludes the
    // departure step (index 0); `nowStep` only increases, so `nowStep < i - 1`
    // means the turn at i-1 was passed without being confirmed.
    if (i >= 2 && state.nowStep < i - 1) {
        return AnnouncerResult(state.copy(nowStep = i - 1), steps[i - 1].instruction)
    }

    val step = steps.getOrNull(i) ?: return AnnouncerResult(state, null)
    val dist = progress.distanceToTurnMeters

    if (dist < nowMeters && state.nowStep != i) {
        // Mark prepare done too, so a fast jump inside the now-radius never
        // back-fires a stale prepare cue for the same step.
        return AnnouncerResult(state.copy(nowStep = i, preparedStep = i), step.instruction)
    }

    if (dist < prepareMeters && state.preparedStep != i) {
        return AnnouncerResult(
            state.copy(preparedStep = i),
            "In ${formatDistance(dist)}, ${step.instruction}",
        )
    }

    return AnnouncerResult(state, null)
}

/** Speak-friendly distance: nearest 50 m below 1 km, else one-decimal km. */
fun formatDistance(meters: Int): String =
    if (meters >= 1000) {
        val km = Math.round(meters / 100.0) / 10.0
        "$km kilometers"
    } else {
        val rounded = ((meters + 25) / 50) * 50
        "$rounded meters"
    }
