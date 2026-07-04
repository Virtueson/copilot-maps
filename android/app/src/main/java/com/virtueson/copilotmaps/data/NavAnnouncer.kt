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

private data class Chain(val text: String, val lastIndex: Int)

/**
 * Fold a run of turns spaced < [chainMeters] apart (using each step's
 * `distanceMeters` = distance to the next maneuver) into one spoken line —
 * "Turn left, then turn right" — up to [maxChain] turns, stopping before an
 * ARRIVE step. Returns the joined text and the last folded step index.
 */
private fun chainFrom(steps: List<RouteStep>, i: Int, chainMeters: Int, maxChain: Int): Chain {
    val sb = StringBuilder(steps[i].instruction)
    var j = i
    var count = 1
    while (count < maxChain && j < steps.size - 1 && steps[j].distanceMeters < chainMeters) {
        val next = steps[j + 1]
        if (next.maneuver == "ARRIVE") break
        sb.append(", then ").append(next.instruction)
        j++
        count++
    }
    return Chain(sb.toString(), j)
}

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
    chainMeters: Int = 40,
    maxChain: Int = 3,
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
    // confirmation the moment we register reaching it. `i >= 2` excludes the
    // departure step (index 0); `nowStep` only increases, so `nowStep < i - 1`
    // means the turn at i-1 was passed without being confirmed.
    if (i >= 2 && state.nowStep < i - 1) {
        val skipped = steps.getOrNull(i - 1) ?: return AnnouncerResult(state, null)
        return AnnouncerResult(state.copy(nowStep = i - 1), skipped.instruction)
    }

    steps.getOrNull(i) ?: return AnnouncerResult(state, null)
    val dist = progress.distanceToTurnMeters

    if (dist < nowMeters && state.nowStep < i) {
        val chain = chainFrom(steps, i, chainMeters, maxChain)
        // Mark every folded turn announced so they don't re-fire individually
        // and the safety net won't re-announce them.
        return AnnouncerResult(state.copy(nowStep = chain.lastIndex, preparedStep = chain.lastIndex), chain.text)
    }

    if (dist < prepareMeters && state.preparedStep < i) {
        val chain = chainFrom(steps, i, chainMeters, maxChain)
        return AnnouncerResult(
            state.copy(preparedStep = chain.lastIndex),
            "In ${formatDistance(dist)}, ${chain.text}",
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
