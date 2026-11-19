@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.exkmptracker.ios

import com.exkmptracker.model.LocationSource
import com.exkmptracker.model.TrackPoint
import com.exkmptracker.model.TrackingIdentity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlCipherQueueTests {
    private fun point(id: String) = TrackPoint(id, null, null, null, 1.0, 2.0, 3.0, null, null, 1, 1, LocationSource.gps, false, emptyList())
    private fun storage() = IosProtectedStorage("${NSHomeDirectory()}/Library/Application Support/queue-test-${NSUUID().UUIDString}")

    @Test fun migratesOnceAndPreservesExactPrefix() = runTest {
        val storage = storage()
        try {
            val identity = TrackingIdentity("driver", "brand")
            val other = TrackingIdentity("driver", "other")
            storage.transaction {
                storage.write("queue-647269766572-6272616e64.json", Json.encodeToString(listOf(point("one"), point("two"))))
            }
            val queue = IosTrackQueue(storage)
            assertEquals(listOf("one", "two"), queue.peek(identity, 10).map { it.id })
            queue.append(other, point("other"))
            assertFailsWith<IllegalArgumentException> { queue.acknowledge(identity, listOf("two")) }
            assertEquals(2L, queue.size(identity))
            queue.acknowledge(identity, listOf("one"))
            assertEquals(listOf("two"), IosTrackQueue(storage).peek(identity, 10).map { it.id })
            assertEquals(1L, queue.size(other))
        } finally { NSFileManager.defaultManager.removeItemAtPath(storage.directory, null) }
    }

    @Test fun corruptSnapshotIsNotDiscarded() = runTest {
        val storage = storage()
        try {
            val identity = TrackingIdentity("driver", "brand")
            storage.transaction { storage.write("queue-647269766572-6272616e64.json", "invalid") }
            assertFailsWith<IllegalStateException> { IosTrackQueue(storage).size(identity) }
            assertEquals("invalid", storage.transaction { storage.read("queue-647269766572-6272616e64.json") })
        } finally { NSFileManager.defaultManager.removeItemAtPath(storage.directory, null) }
    }
}
