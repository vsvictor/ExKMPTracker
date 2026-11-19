@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.exkmptracker.ios

import com.exkmptracker.model.TrackPoint
import com.exkmptracker.model.TrackingIdentity
import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.SessionStore
import com.exkmptracker.ports.TrackQueue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open
import platform.posix.read
import platform.posix.rename
import platform.posix.unlink
import platform.posix.write

private val storageJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

/**
 * Injectable protected-file store shared by [IosTrackQueue] and [IosSessionStore].
 *
 * Every file operation of a dependent component runs inside one [transaction] guarded by this
 * instance's mutex; locks are never nested. Create exactly one instance per [directory] and
 * process and pass it to every component using that directory: separate instances on the same
 * directory are not coordinated with each other.
 */
class IosProtectedStorage(
    val directory: String = defaultDirectory(),
) {
    private val mutex = Mutex()

    init {
        require(directory.isNotBlank()) { "directory must not be blank" }
    }

    internal suspend fun <T> transaction(block: () -> T): T = mutex.withLock { block() }

    internal fun read(name: String): String? {
        val path = "$directory/$name"
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(path)) return null
        val bytes = ArrayList<Byte>()
        memScoped {
            val descriptor = open(path.cstr, O_RDONLY)
            check(descriptor >= 0) { "Could not read protected tracker file '$name'" }
            try {
                val chunk = ByteArray(8_192)
                chunk.usePinned { pinned ->
                    while (true) {
                        val count = read(descriptor, pinned.addressOf(0), chunk.size.convert())
                        check(count >= 0) { "Could not read protected tracker file '$name'" }
                        if (count == 0L) break
                        for (index in 0 until count.toInt()) bytes.add(pinned.get()[index])
                    }
                }
            } finally {
                close(descriptor)
            }
        }
        return try {
            bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Tracker file '$name' is not valid UTF-8", error)
        }
    }

    internal fun write(name: String, contents: String) {
        val fileManager = NSFileManager.defaultManager
        check(
            fileManager.createDirectoryAtPath(
                directory,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            ),
        ) { "Could not create tracker Application Support directory" }
        protect(directory)
        excludeFromBackup(directory)

        val path = "$directory/$name"
        val temporaryPath = "$directory/.$name.${NSUUID().UUIDString}.pending"
        val contentsBytes = contents.encodeToByteArray()
        memScoped {
            var renamed = false
            try {
                val descriptor = open(
                    temporaryPath.cstr,
                    O_CREAT or O_EXCL or O_WRONLY,
                    384,
                )
                check(descriptor >= 0) { "Could not create temporary tracker file '$name'" }
                try {
                    // Set inode protections before writing any serialized identity or track data.
                    protect(temporaryPath)
                    excludeFromBackup(temporaryPath)
                    writeFully(descriptor, contentsBytes, name)
                    check(fsync(descriptor) == 0) { "Could not synchronize tracker file '$name'" }
                } finally {
                    close(descriptor)
                }
                check(rename(temporaryPath.cstr, path.cstr) == 0) {
                    "Could not atomically replace tracker file '$name'"
                }
                renamed = true
                synchronize(directory, O_RDONLY)
            } finally {
                if (!renamed) unlink(temporaryPath.cstr)
            }
        }
    }

    internal fun delete(name: String) {
        val path = "$directory/$name"
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            check(NSFileManager.defaultManager.removeItemAtPath(path, error = null)) {
                "Could not remove protected tracker file '$name'"
            }
            synchronize(directory, O_RDONLY)
        }
    }

    internal fun synchronizeDirectory() = synchronize(directory, O_RDONLY)

    internal fun prepareDatabaseDirectory() {
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) { "Could not create tracker directory" }
        protect(directory)
        excludeFromBackup(directory)
    }

    private fun protect(path: String) {
        check(
            NSFileManager.defaultManager.setAttributes(
                mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
                ofItemAtPath = path,
                error = null,
            ),
        ) { "Could not set first-unlock file protection for '$path'" }
    }

    private fun excludeFromBackup(path: String) {
        check(
            NSURL.fileURLWithPath(path).setResourceValue(
                true,
                forKey = NSURLIsExcludedFromBackupKey,
                error = null,
            ),
        ) { "Could not exclude tracker data at '$path' from backup" }
    }

    private fun synchronize(path: String, flags: Int) {
        memScoped {
            val descriptor = open(path.cstr, flags)
            check(descriptor >= 0) { "Could not open '$path' for durable synchronization" }
            try {
                check(fsync(descriptor) == 0) { "Could not synchronize '$path' to durable storage" }
            } finally {
                close(descriptor)
            }
        }
    }

    private fun writeFully(descriptor: Int, bytes: ByteArray, name: String) {
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val written = write(
                    descriptor,
                    pinned.addressOf(offset),
                    (bytes.size - offset).convert(),
                )
                check(written > 0) { "Could not write protected tracker file '$name'" }
                offset += written.toInt()
            }
        }
    }

    companion object {
        fun defaultDirectory(): String = "${NSHomeDirectory()}/Library/Application Support/com.exkmptracker"
    }
}

/**
 * Session state is explicit and is never restored or started by the runtime automatically.
 * An explicit stop first writes a durable stop marker, so a failed session-file removal still
 * cannot make a later explicit restore resume a session the user stopped.
 */
class IosSessionStore(private val storage: IosProtectedStorage) : SessionStore {
    private val fileName = "confirmed-session.json"
    private val stoppedMarkerName = "confirmed-session.stopped"

    override suspend fun load(): TrackingSession? = storage.transaction {
        if (storage.read(stoppedMarkerName) != null) return@transaction null
        val contents = storage.read(fileName) ?: return@transaction null
        try {
            storageJson.decodeFromString<TrackingSession>(contents)
        } catch (error: SerializationException) {
            throw IllegalStateException("Corrupt saved tracker session; refusing to restore it", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Corrupt saved tracker session; refusing to restore it", error)
        }
    }

    override suspend fun save(session: TrackingSession) {
        storage.transaction {
            storage.write(fileName, storageJson.encodeToString(session))
            storage.delete(stoppedMarkerName)
        }
    }

    override suspend fun clear() {
        storage.transaction {
            var markerError: Throwable? = null
            try {
                storage.write(stoppedMarkerName, "stopped")
            } catch (error: Throwable) {
                markerError = error
            }
            try {
                storage.delete(fileName)
            } catch (error: Throwable) {
                markerError?.let(error::addSuppressed)
                throw IllegalStateException(
                    if (markerError == null) {
                        "Could not remove saved tracker session; stop marker prevents restoration"
                    } else {
                        "Could not remove saved tracker session or record stop; do not restore it"
                    },
                    error,
                )
            }
            storage.delete(stoppedMarkerName)
        }
    }
}

class IosTrackerLogger : com.exkmptracker.ports.TrackerLogger {
    override fun error(message: String, cause: Throwable?) {
        println(if (cause == null) message else "$message: ${cause.message}")
    }
}
