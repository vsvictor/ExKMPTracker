package com.exkmptracker.android

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import java.io.Closeable
import java.util.UUID

/** Activity recognition is advisory and optional; failures are reported without stopping tracking. */
class AndroidActivityRecognition(
    context: Context,
    private val report: (String, Throwable?) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val client by lazy { ActivityRecognition.getClient(appContext) }
    internal val deliveryToken: Uri = Uri.parse("exkmptracker://activity/${UUID.randomUUID()}")
    private var pendingIntent: PendingIntent? = null

    fun start() {
        if (pendingIntent != null) return
        if (Build.VERSION.SDK_INT >= 29 &&
            appContext.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            report("Activity transition recognition unavailable without ACTIVITY_RECOGNITION permission", null)
            return
        }
        val transitions = listOf(
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
            DetectedActivity.IN_VEHICLE,
        ).flatMap { type ->
            listOf(
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
            )
        }
        try {
            // Play services supplies the transition result as fill-in extras. Fix the destination
            // and token in the base intent so a fill-in cannot redirect it or change its owner.
            val delivery = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(appContext, TrackerActivityTransitionReceiver::class.java)
                    .setPackage(appContext.packageName)
                    .setAction(TrackerActivityTransitionReceiver.ACTION)
                    .setData(deliveryToken),
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
            )
            pendingIntent = delivery
            client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), delivery)
                .addOnSuccessListener {
                    if (pendingIntent != delivery) unregister(delivery)
                }
                .addOnFailureListener {
                    if (pendingIntent == delivery) pendingIntent = null
                    delivery.cancel()
                    report("Activity transition updates could not be registered", it)
                }
        } catch (error: Exception) {
            pendingIntent?.cancel()
            pendingIntent = null
            report("Activity recognition is unavailable", error)
        }
    }

    override fun close() {
        val delivery = pendingIntent ?: return
        pendingIntent = null
        unregister(delivery)
        delivery.cancel()
    }

    private fun unregister(delivery: PendingIntent) {
        try {
            client.removeActivityTransitionUpdates(delivery)
                .addOnFailureListener { report("Unable to unregister activity transition updates", it) }
        } catch (error: Exception) {
            report("Unable to unregister activity transition updates", error)
        }
    }

}
