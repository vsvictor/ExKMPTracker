@file:OptIn(
    kotlin.experimental.ExperimentalObjCRefinement::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package com.exkmptracker.ios

import com.exkmptracker.di.TrackerQualifiers
import com.exkmptracker.di.declares
import com.exkmptracker.ports.SessionStore
import com.exkmptracker.ports.TrackQueue
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.qualifier.Qualifier
import org.koin.mp.KoinPlatform
import platform.Foundation.NSURL

/**
 * Reuses the process queue when [storage] names the same standardized directory as the Koin
 * single. A different directory keeps its own queue. Resolving the single constructs
 * [IosTrackQueue] and does not call `tracker_cipher_open`.
 */
@HiddenFromObjC
internal fun iosStorageFromGraphOrNew(requested: IosProtectedStorage? = null): IosProtectedStorage {
    val shared = sharedStorageOrNull() ?: return requested ?: IosProtectedStorage()
    return if (requested == null || iosSameDirectory(requested, shared)) shared else requested
}

@HiddenFromObjC
internal fun iosQueueFor(storage: IosProtectedStorage): TrackQueue {
    val koin = KoinPlatform.getKoinOrNull()
    if (koin == null || !koin.declares(IosTrackQueue::class)) return IosTrackQueue(storage)
    val shared = koin.getOrNull<IosProtectedStorage>()
    return if (shared != null && iosSameDirectory(shared, storage)) koin.get() else IosTrackQueue(storage)
}

@HiddenFromObjC
internal fun iosSessionFor(storage: IosProtectedStorage): SessionStore {
    val koin = KoinPlatform.getKoinOrNull()
    if (koin == null || !koin.declares(SessionStore::class)) return IosSessionStore(storage)
    val shared = koin.getOrNull<IosProtectedStorage>()
    return if (shared != null && iosSameDirectory(shared, storage)) koin.get() else IosSessionStore(storage)
}

/** Koin main dispatcher, or [Dispatchers.Main] when the graph has not registered one. */
@HiddenFromObjC
internal fun iosMainDispatcher(): CoroutineDispatcher =
    iosDispatcher(TrackerQualifiers.main, Dispatchers.Main)

/** Koin compute dispatcher, or [Dispatchers.Default] when the graph has not registered one. */
@HiddenFromObjC
internal fun iosComputeDispatcher(): CoroutineDispatcher =
    iosDispatcher(TrackerQualifiers.compute, Dispatchers.Default)

@HiddenFromObjC
private fun sharedStorageOrNull(): IosProtectedStorage? {
    val koin = KoinPlatform.getKoinOrNull() ?: return null
    if (!koin.declares(IosProtectedStorage::class)) return null
    return koin.get()
}

@HiddenFromObjC
private fun iosDispatcher(qualifier: Qualifier, fallback: CoroutineDispatcher): CoroutineDispatcher {
    val koin = KoinPlatform.getKoinOrNull() ?: return fallback
    return koin.getOrNull(qualifier) ?: fallback
}

/** Same normalization as [IosTrackQueue]'s key id: `NSURL` standardized path, without opening the db. */
@HiddenFromObjC
private fun iosSameDirectory(left: IosProtectedStorage, right: IosProtectedStorage): Boolean {
    if (left === right) return true
    val leftPath = NSURL.fileURLWithPath(left.directory).URLByStandardizingPath?.path ?: return false
    val rightPath = NSURL.fileURLWithPath(right.directory).URLByStandardizingPath?.path ?: return false
    return leftPath == rightPath
}
