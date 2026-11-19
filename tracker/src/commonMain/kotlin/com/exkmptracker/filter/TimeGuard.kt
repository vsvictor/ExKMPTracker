package com.exkmptracker.filter

import com.exkmptracker.model.GnssEvidence
import kotlin.math.abs

internal data class TimeReference(val serverTimeMs: Long, val atMonotonic: Double, val uncertaintyMs: Double)

internal class TimeGuard {
    var reference: TimeReference? = null
    var gnss: GnssEvidence? = null
    private var interference = false

    fun evidence(value: GnssEvidence): Boolean {
        if (!value.monotonicSeconds.isFinite() || !value.uncertaintyMs.isFinite() ||
            value.uncertaintyMs < 0 || value.utcTimeMs?.isFinite() == false ||
            value.meanAgcDb?.isFinite() == false || value.meanCn0DbHz?.isFinite() == false ||
            gnss?.let { value.monotonicSeconds <= it.monotonicSeconds } == true) return false
        val previous = gnss
        val agc = value.meanAgcDb
        val cn0 = value.meanCn0DbHz
        val previousAgc = previous?.meanAgcDb
        val previousCn0 = previous?.meanCn0DbHz
        if (agc != null && cn0 != null && previousAgc != null && previousCn0 != null &&
            agc - previousAgc < -6.0 && abs(cn0 - previousCn0) > 10.0) interference = true
        gnss = value
        return true
    }

    fun needsReference(now: Double): Boolean = reference?.let { now - it.atMonotonic >= 60 } ?: true

    fun flags(now: Double, wallTimeMs: Long, checkGnss: Boolean): Set<String> = buildSet {
        val ref = reference?.takeIf { now - it.atMonotonic in 0.0..300.0 }
        if (ref != null && abs(wallTimeMs - serverNow(ref, now)) > 2_000 + ref.uncertaintyMs) add("deviceTimeMismatch")
        val evidence = freshGnss(now)
        if (checkGnss && (ref == null || evidence == null)) add("gnssTimeUnverified")
        if (interference) {
            add("SUSPECTED_INTERFERENCE")
            add("SUSPECTED_SPOOFING")
            add("suspectedInterference")
            add("suspectedSpoofing")
        }
    }

    fun suspect(now: Double): Boolean {
        val ref = reference?.takeIf { now - it.atMonotonic in 0.0..300.0 } ?: return false
        val evidence = freshGnss(now) ?: return false
        val utc = evidence.utcTimeMs ?: return false
        return abs(utc + (now - evidence.monotonicSeconds) * 1000 - serverNow(ref, now)) >
            2_000 + ref.uncertaintyMs + evidence.uncertaintyMs
    }

    private fun freshGnss(now: Double) =
        gnss?.takeIf { now - it.monotonicSeconds in 0.0..5.0 && it.utcTimeMs?.isFinite() == true }

    private fun serverNow(ref: TimeReference, now: Double) = ref.serverTimeMs + (now - ref.atMonotonic) * 1000
}
