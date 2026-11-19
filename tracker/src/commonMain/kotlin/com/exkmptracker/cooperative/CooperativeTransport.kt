package com.exkmptracker.cooperative

import com.exkmptracker.model.TrackingSession

interface CooperativeTransport {
    suspend fun syncPeers(
        session: TrackingSession,
        captureTimestampMs: Long,
        state: CooperativeState,
        observations: List<VisualObservation>,
    ): List<MatchedDonor>
}
