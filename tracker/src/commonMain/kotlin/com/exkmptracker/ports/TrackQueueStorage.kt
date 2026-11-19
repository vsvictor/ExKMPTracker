package com.exkmptracker.ports

import com.exkmptracker.model.TrackPoint
import com.exkmptracker.model.TrackingIdentity

interface TrackQueueStorage {
    /** Return only after a durable commit, partitioned by both driver and brand. */
    suspend fun append(identity: TrackingIdentity, point: TrackPoint)
    suspend fun peek(identity: TrackingIdentity, limit: Int): List<TrackPoint>
    /** Remove exactly this FIFO prefix atomically, or throw without removing anything. */
    suspend fun acknowledge(identity: TrackingIdentity, ids: List<String>)
    suspend fun size(identity: TrackingIdentity): Long
}
