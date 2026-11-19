package com.exkmptracker.android

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import com.exkmptracker.ports.PointIdGenerator
import com.exkmptracker.ports.TrafficCounter
import com.exkmptracker.ports.TrackerClock
import java.security.SecureRandom

/** Monotonic time is based on Android elapsed realtime and is unaffected by wall-clock changes. */
class AndroidTrackerClock : TrackerClock {
    override fun wallTimeMs(): Long = System.currentTimeMillis()
    override fun monotonicSeconds(): Double = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
}

class SecurePointIdGenerator : PointIdGenerator {
    private val random = SecureRandom()
    override fun newId(): String = ByteArray(16).also(random::nextBytes).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }
}

/** App-UID byte totals measured relative to this counter instance's creation. */
class AndroidTrafficCounter : TrafficCounter {
    private val uid = Process.myUid()
    private val incomingBaseline = read(TrafficStats.getUidRxBytes(uid), "receive")
    private val outgoingBaseline = read(TrafficStats.getUidTxBytes(uid), "transmit")

    override fun totalIncomingBytes(): Long = current(TrafficStats.getUidRxBytes(uid), incomingBaseline, "receive")
    override fun totalOutgoingBytes(): Long = current(TrafficStats.getUidTxBytes(uid), outgoingBaseline, "transmit")

    private fun current(value: Long, baseline: Long, direction: String): Long =
        (read(value, direction) - baseline).coerceAtLeast(0L)

    private fun read(value: Long, direction: String): Long {
        if (value == TrafficStats.UNSUPPORTED.toLong()) {
            throw UnsupportedOperationException("Android UID $direction traffic statistics are unsupported")
        }
        return value
    }
}
