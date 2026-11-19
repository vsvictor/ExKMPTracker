package com.exkmptracker.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** Service-owned and only accessed on the main thread. */
internal class TrackingWakeLock(
    context: Context,
    private val report: (String, Throwable) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private var lock: PowerManager.WakeLock? = null
    private val renewal = Runnable { renew() }

    fun start() {
        if (lock != null) return
        try {
            lock = checkNotNull(appContext.getSystemService(PowerManager::class.java))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ExKMPTracker:Location")
                .apply { setReferenceCounted(false) }
            renew()
        } catch (error: Exception) {
            stop()
            report("Unable to initialize the tracking wake lock", error)
        }
    }

    @SuppressLint("WakelockTimeout") // A bounded 60-second timeout is renewed only while tracking.
    private fun renew() {
        val active = lock ?: return
        try {
            active.acquire(LEASE_MS)
            handler.postDelayed(renewal, RENEWAL_MS)
        } catch (error: Exception) {
            stop()
            report("Unable to acquire the tracking wake lock", error)
        }
    }

    fun stop() {
        handler.removeCallbacks(renewal)
        val active = lock
        lock = null
        try {
            if (active?.isHeld == true) active.release()
        } catch (error: Exception) {
            report("Unable to release the tracking wake lock", error)
        }
    }

    private companion object {
        const val LEASE_MS = 60_000L
        const val RENEWAL_MS = 30_000L
    }
}
