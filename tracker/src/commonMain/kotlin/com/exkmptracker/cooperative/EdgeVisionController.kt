package com.exkmptracker.cooperative

/** Injected native vision pipeline. Stop releases camera subscriptions and inference resources. */
interface EdgeVisionController {
    fun start(onObservations: (ObservedPeers) -> Unit, onError: (String) -> Unit)
    fun stop()
}
