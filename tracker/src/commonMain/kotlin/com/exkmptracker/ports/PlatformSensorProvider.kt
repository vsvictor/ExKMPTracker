package com.exkmptracker.ports

interface PlatformSensorProvider {
    /** Keep a low-power motion wake source even when precise sensors are paused. */
    fun setStationary(stationary: Boolean)
}
