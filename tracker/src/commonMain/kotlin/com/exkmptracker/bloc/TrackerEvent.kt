package com.exkmptracker.bloc

import com.exkmptracker.model.*

sealed interface TrackerEvent {
    data class Start(val session: TrackingSession) : TrackerEvent
    data object Stop : TrackerEvent
    data object Tick : TrackerEvent
    data class Position(val fix: LocationFix) : TrackerEvent
    data class Motion(val sample: MotionSample) : TrackerEvent
    data class ActivityChanged(val activity: Activity) : TrackerEvent
    data class Steps(val count: Int, val monotonicSeconds: Double? = null) : TrackerEvent
    data class Gnss(val evidence: GnssEvidence) : TrackerEvent
    data class PlatformError(val message: String) : TrackerEvent
}
