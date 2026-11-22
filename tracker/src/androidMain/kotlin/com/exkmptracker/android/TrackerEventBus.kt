package com.exkmptracker.android

import com.exkmptracker.bloc.TrackerBloc
import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.ports.SensorController
import com.exkmptracker.ports.TrackerLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Breaks the sensor/BLoC cycle. Events that arrive before [attach] are delivered in one pass,
 * matching the previous pending-platform-error buffer.
 */
internal class TrackerEventBus(private val logger: TrackerLogger) {
    private val pending = mutableListOf<TrackerEvent>()
    private var bloc: TrackerBloc? = null
    private val closed = AtomicBoolean(false)

    fun dispatch(event: TrackerEvent) {
        val target = synchronized(this) {
            if (closed.get()) return
            val current = bloc
            if (current == null) {
                pending += event
                null
            } else {
                current
            }
        }
        target?.dispatch(event)
    }

    fun report(message: String, cause: Throwable?) {
        logger.error(message, cause)
        dispatch(TrackerEvent.PlatformError(message))
    }

    fun attach(bloc: TrackerBloc) {
        val queued = synchronized(this) {
            this.bloc = bloc
            pending.toList().also { pending.clear() }
        }
        queued.forEach { bloc.dispatch(it) }
    }

    fun close() {
        closed.set(true)
    }
}

/** Stationary mode applies to location, GNSS and motion together, as the runtime did before Koin. */
internal fun androidStationaryControl(
    location: AndroidLocationSource,
    gnss: AndroidGnssMonitor,
    sensors: AndroidSensorController,
): SensorController = object : SensorController {
    override fun setStationary(stationary: Boolean) {
        location.setStationary(stationary)
        gnss.setStationary(stationary)
        sensors.setStationary(stationary)
    }
}
