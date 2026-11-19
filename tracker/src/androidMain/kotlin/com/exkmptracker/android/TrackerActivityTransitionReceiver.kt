package com.exkmptracker.android

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult

/** Non-exported. Delivers only to an existing service and never starts or restores tracking. */
class TrackerActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val token = intent.data ?: return
        val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents.orEmpty()
        if (events.isEmpty()) return

        val app = context.applicationContext
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        val connection = object : ServiceConnection {
            private var finished = false
            private val timeout = Runnable { finish() }

            fun bind() {
                try {
                    // Even a failed bind must be unbound to release its connection bookkeeping.
                    if (app.bindService(Intent(app, TrackerForegroundService::class.java), this, 0)) {
                        handler.postDelayed(timeout, 2_000L)
                    } else {
                        finish()
                    }
                } catch (error: Exception) {
                    Log.w("ExKMPTracker", "Unable to deliver activity transition", error)
                    finish()
                }
            }

            private fun finish() {
                if (finished) return
                finished = true
                handler.removeCallbacks(timeout)
                try {
                    app.unbindService(this)
                } catch (_: IllegalArgumentException) {
                    // No binding was registered.
                } finally {
                    pending.finish()
                }
            }

            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (finished) return
                try {
                    (binder as? TrackerForegroundService.TrackerBinder)?.deliverTransitions(token, events)
                } finally {
                    finish()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = finish()
            override fun onNullBinding(name: ComponentName) = finish()
            override fun onBindingDied(name: ComponentName) = finish()
        }
        connection.bind()
    }

    internal companion object {
        const val ACTION = "com.exkmptracker.action.ACTIVITY_TRANSITION"
    }
}
