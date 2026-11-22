package com.exkmptracker.transport

import com.exkmptracker.model.TrackPoint
import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.*
import com.exkmptracker.cooperative.*

/** Swift-friendly host bridge; it never creates, owns or closes a network connection. */
class HostRpcTrackingTransport(client: TrackingRpcClient) : TrackingTransport, CooperativeTransport {
    private val delegate = RpcTrackingTransport(RpcClientTransport(client))

    override suspend fun serverTime(session: TrackingSession): ServerTime = delegate.serverTime(session)
    override suspend fun ingest(session: TrackingSession, points: List<TrackPoint>): List<String> =
        delegate.ingest(session, points)

    override suspend fun syncPeers(session: TrackingSession, captureTimestampMs: Long,
        state: CooperativeState, observations: List<VisualObservation>): List<MatchedDonor> =
        delegate.syncPeers(session, captureTimestampMs, state, observations)
}
