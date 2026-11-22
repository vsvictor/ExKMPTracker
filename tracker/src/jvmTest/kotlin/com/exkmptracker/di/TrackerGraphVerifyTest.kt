@file:OptIn(org.koin.core.annotation.KoinExperimentalAPI::class)

package com.exkmptracker.di

import com.exkmptracker.cooperative.EdgeVisionController
import com.exkmptracker.ports.MapMatcher
import com.exkmptracker.ports.TrackingRpcTransport
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import org.koin.test.verify.verify

class TrackerGraphVerifyTest {
    @Test
    fun graphVerifiesWithoutTheJvmPlatformModule() {
        // verify() reads the CoopNavTracker constructor. The factory builds that scope from the
        // compute dispatcher; the test graph does not register a root CoroutineScope.
        trackerGraphUnderTest().verify(
            extraTypes = listOf(
                TrackingRpcTransport::class,
                MapMatcher::class,
                EdgeVisionController::class,
                CoroutineScope::class,
            ),
        )
    }
}
