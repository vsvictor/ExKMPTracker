@file:OptIn(org.koin.core.annotation.KoinExperimentalAPI::class)

package com.exkmptracker.vision.di

import com.exkmptracker.ports.TrackerClock
import com.exkmptracker.vision.YoloBackendOpener
import kotlin.test.Test
import org.koin.test.verify.verify

class VisionGraphVerifyTest {
    @Test
    fun visionModuleVerifiesWithExternalParameters() {
        visionCommonModule.verify(
            extraTypes = listOf(
                VisionRequest::class,
                YoloBackendOpener::class,
                TrackerClock::class,
            ),
        )
    }
}
