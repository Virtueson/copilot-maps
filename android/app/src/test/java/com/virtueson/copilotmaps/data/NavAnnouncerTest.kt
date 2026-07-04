package com.virtueson.copilotmaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun step(instruction: String, dist: Int, lat: Double, lng: Double) =
    RouteStep(instruction = instruction, maneuver = "", distanceMeters = dist, location = GeoPoint(lat, lng))

private val steps = listOf(
    step("Head north on Main St", 100, 0.0, 0.000),
    step("Turn right onto Oak Ave", 200, 0.0, 0.010),
    step("Arrive at destination", 0, 0.0, 0.020),
)

private fun progress(index: Int, distToTurn: Int, arrived: Boolean = false) =
    NavProgress(stepIndex = index, distanceToTurnMeters = distToTurn, remainingDistanceMeters = distToTurn, arrived = arrived)

// Chaining fixtures: location is irrelevant here (chaining reads distanceMeters
// and we pass NavProgress directly), so a dummy point is fine.
private fun turn(instruction: String, gapToNext: Int) =
    RouteStep(instruction = instruction, maneuver = "TURN", distanceMeters = gapToNext, location = GeoPoint(0.0, 0.0))
private fun arrive() =
    RouteStep(instruction = "Arrive at destination", maneuver = "ARRIVE", distanceMeters = 0, location = GeoPoint(0.0, 0.0))

class NavAnnouncerTest {

    @Test
    fun `start cue speaks step zero instruction once`() {
        val r1 = nextAnnouncement(steps, progress(0, 0), AnnouncerState())
        assertEquals("Head north on Main St", r1.utterance)
        // same state, next call must not repeat the start cue
        val r2 = nextAnnouncement(steps, progress(1, 500), r1.state)
        assertNull(r2.utterance)
    }

    @Test
    fun `prepare cue fires once when within prepare distance`() {
        val started = AnnouncerState(startedSpoken = true)
        val r1 = nextAnnouncement(steps, progress(1, 250), started)
        assertEquals("In 250 meters, Turn right onto Oak Ave", r1.utterance)
        val r2 = nextAnnouncement(steps, progress(1, 120), r1.state)
        assertNull(r2.utterance) // already prepared for step 1
    }

    @Test
    fun `now cue fires once when within now distance`() {
        val prepared = AnnouncerState(startedSpoken = true, preparedStep = 1)
        val r1 = nextAnnouncement(steps, progress(1, 30), prepared)
        assertEquals("Turn right onto Oak Ave", r1.utterance)
        val r2 = nextAnnouncement(steps, progress(1, 20), r1.state)
        assertNull(r2.utterance)
    }

    @Test
    fun `jumping straight inside now distance skips a stale prepare cue`() {
        val started = AnnouncerState(startedSpoken = true)
        val r1 = nextAnnouncement(steps, progress(1, 25), started) // never saw prepare range
        assertEquals("Turn right onto Oak Ave", r1.utterance)
        // prepare for step 1 must NOT fire afterwards
        val r2 = nextAnnouncement(steps, progress(1, 200), r1.state)
        assertNull(r2.utterance)
    }

    @Test
    fun `arrived speaks once and suppresses a now cue for the final step`() {
        val s = AnnouncerState(startedSpoken = true, preparedStep = 2)
        val r1 = nextAnnouncement(steps, progress(2, 10, arrived = true), s)
        assertEquals("You have arrived.", r1.utterance)
        val r2 = nextAnnouncement(steps, progress(2, 5, arrived = true), r1.state)
        assertNull(r2.utterance)
    }

    @Test
    fun `empty steps produce no utterance`() {
        val r = nextAnnouncement(emptyList(), progress(0, 0), AnnouncerState())
        assertNull(r.utterance)
    }

    @Test
    fun `formatDistance rounds sensibly`() {
        assertEquals("300 meters", formatDistance(300))
        assertEquals("50 meters", formatDistance(47))
        assertEquals("1.2 kilometers", formatDistance(1234))
    }

    @Test
    fun `now cue fires on reaching the turn even if the 40m window was skipped`() {
        // Prepared for step 1, but a fast/laggy GPS fix advances the index to 2
        // without ever landing in the 30-40 m now-window for step 1.
        val s = AnnouncerState(startedSpoken = true, preparedStep = 1, nowStep = -1)
        val r1 = nextAnnouncement(steps, progress(2, 800), s)
        assertEquals("Turn right onto Oak Ave", r1.utterance) // step 1 confirmation, fired late
        val r2 = nextAnnouncement(steps, progress(2, 700), r1.state)
        assertNull(r2.utterance) // does not refire
    }

    @Test
    fun `advancing past the departure step does not speak a now cue`() {
        val s = AnnouncerState(startedSpoken = true)
        val r = nextAnnouncement(steps, progress(1, 280), s)
        // step index 1 with no prior cues -> normal prepare cue, NOT a stray safety-net now cue
        assertEquals("In 300 meters, Turn right onto Oak Ave", r.utterance)
    }

    @Test
    fun `arrived does not trigger a stale safety-net cue afterward`() {
        val s = AnnouncerState(startedSpoken = true, preparedStep = 2)
        val r1 = nextAnnouncement(steps, progress(2, 10, arrived = true), s)
        assertEquals("You have arrived.", r1.utterance)
        val r2 = nextAnnouncement(steps, progress(2, 5, arrived = true), r1.state)
        assertNull(r2.utterance) // arrival set nowStep to last; safety net must NOT fire step 1
    }

    @Test
    fun `now cue chains two turns spaced under 40m`() {
        val steps = listOf(turn("Depart", 10), turn("Turn left", 10), turn("Turn right", 500), arrive())
        val r = nextAnnouncement(steps, progress(1, 30), AnnouncerState(startedSpoken = true))
        assertEquals("Turn left, then Turn right", r.utterance)
    }

    @Test
    fun `now cue chains at most three turns`() {
        val steps = listOf(turn("Depart", 10), turn("A", 10), turn("B", 10), turn("C", 10), turn("D", 500), arrive())
        val r = nextAnnouncement(steps, progress(1, 30), AnnouncerState(startedSpoken = true))
        assertEquals("A, then B, then C", r.utterance) // capped at 3; D not included
    }

    @Test
    fun `now cue does not chain when the next turn is far`() {
        val steps = listOf(turn("Depart", 10), turn("A", 500), turn("B", 10), arrive())
        val r = nextAnnouncement(steps, progress(1, 30), AnnouncerState(startedSpoken = true))
        assertEquals("A", r.utterance)
    }

    @Test
    fun `chain stops before the arrive step`() {
        val steps = listOf(turn("Depart", 10), turn("A", 10), arrive())
        val r = nextAnnouncement(steps, progress(1, 30), AnnouncerState(startedSpoken = true))
        assertEquals("A", r.utterance) // arrive is not chained in
    }

    @Test
    fun `prepare cue chains the cluster`() {
        val steps = listOf(turn("Depart", 10), turn("A", 10), turn("B", 500), arrive())
        val r = nextAnnouncement(steps, progress(1, 280), AnnouncerState(startedSpoken = true))
        assertEquals("In 300 meters, A, then B", r.utterance)
    }

    @Test
    fun `chained now cue marks all folded turns announced`() {
        val steps = listOf(turn("Depart", 10), turn("A", 10), turn("B", 500), arrive())
        val r1 = nextAnnouncement(steps, progress(1, 30), AnnouncerState(startedSpoken = true))
        assertEquals("A, then B", r1.utterance)
        // advancing onto the folded step 2 must NOT re-announce it
        val r2 = nextAnnouncement(steps, progress(2, 30), r1.state)
        assertNull(r2.utterance)
    }
}
