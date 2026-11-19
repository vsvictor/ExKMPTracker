@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.exkmptracker.ios

import com.exkmptracker.ports.PointIdGenerator
import com.exkmptracker.ports.TrackerClock
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreFoundation.kCFAbsoluteTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSDate
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime
import platform.posix.timespec

class IosTrackerClock : TrackerClock {
    override fun wallTimeMs(): Long =
        ((CFAbsoluteTimeGetCurrent() + kCFAbsoluteTimeIntervalSince1970) * 1_000.0).toLong()

    override fun monotonicSeconds(): Double = memScoped {
        // Darwin's monotonic clock includes sleep; its uptime clock does not.
        val value = alloc<timespec>()
        check(clock_gettime(CLOCK_MONOTONIC_RAW.toUInt(), value.ptr) == 0) { "Darwin monotonic clock unavailable" }
        value.tv_sec.toDouble() + value.tv_nsec.toDouble() / 1_000_000_000.0
    }
}

internal fun NSDate.unixTimeSeconds(): Double =
    timeIntervalSince1970

class IosPointIdGenerator : PointIdGenerator {
    override fun newId(): String {
        val bytes = ByteArray(16)
        val status = bytes.usePinned {
            SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), it.addressOf(0))
        }
        check(status == errSecSuccess) { "SecRandomCopyBytes failed with status $status" }
        val alphabet = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value shr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }
}
