package com.exkmptracker.ports

import com.exkmptracker.model.*

interface TrackerClock {
    fun wallTimeMs(): Long
    fun monotonicSeconds(): Double
}

interface PointIdGenerator {
    /** 16 cryptographically random bytes encoded as 32 lowercase hexadecimal characters. */
    fun newId(): String
}

interface TrackerLogger {
    fun error(message: String, cause: Throwable?)
}

interface TrackQueue : TrackQueueStorage

interface SessionStore {
    suspend fun load(): TrackingSession?
    suspend fun save(session: TrackingSession)
    suspend fun clear()
}

data class ServerTime(val serverTimeMs: Long)

interface TrackingTransport {
    suspend fun serverTime(session: TrackingSession): ServerTime
    suspend fun ingest(session: TrackingSession, points: List<TrackPoint>): List<String>
}

interface MapMatcher {
    suspend fun match(estimate: TrackEstimate): TrackEstimate
}

interface SensorController : PlatformSensorProvider

interface TrafficCounter {
    /** App UID totals since this counter instance was created; unsupported platforms throw. */
    fun totalIncomingBytes(): Long
    fun totalOutgoingBytes(): Long
}
