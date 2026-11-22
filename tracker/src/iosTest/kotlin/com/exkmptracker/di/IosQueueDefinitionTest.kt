package com.exkmptracker.di

import com.exkmptracker.ios.IosProtectedStorage
import com.exkmptracker.ios.IosSessionStore
import com.exkmptracker.ios.IosTrackQueue
import com.exkmptracker.ios.iosQueueFor
import com.exkmptracker.ios.iosSessionFor
import com.exkmptracker.ports.SessionStore
import com.exkmptracker.ports.TrackQueue
import com.exkmptracker.ports.TrackQueueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.definition.Kind
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/** Does not call `tracker_cipher_open`. The reuse test only constructs [IosTrackQueue]. */
@OptIn(KoinInternalApi::class)
class IosQueueDefinitionTest {
    @AfterTest
    fun tearDown() {
        if (KoinPlatform.getKoinOrNull() != null) stopKoin()
    }

    @Test
    fun storageQueueAndSessionAreSingleDefinitions() {
        val factories = trackerPlatformModule.mappings.values.distinct()
        val queues = factories.filter { it.beanDefinition.primaryType == IosTrackQueue::class }
        assertEquals(1, queues.size)
        val queue = queues.single().beanDefinition
        assertEquals(Kind.Singleton, queue.kind)
        assertTrue(TrackQueue::class in queue.secondaryTypes)
        assertTrue(TrackQueueStorage::class in queue.secondaryTypes)
        val storages = factories.filter { it.beanDefinition.primaryType == IosProtectedStorage::class }
        assertEquals(1, storages.size)
        assertEquals(Kind.Singleton, storages.single().beanDefinition.kind)
        val sessions = factories.filter {
            it.beanDefinition.primaryType == IosSessionStore::class || SessionStore::class in it.beanDefinition.secondaryTypes
        }
        assertEquals(1, sessions.size)
        val session = sessions.single().beanDefinition
        assertEquals(IosSessionStore::class, session.primaryType)
        assertEquals(Kind.Singleton, session.kind)
        assertTrue(SessionStore::class in session.secondaryTypes)
    }

    @Test
    fun sameStandardizedDirectoryReusesTheGraphQueue() {
        val directory = "/tmp/exkmptracker-koin-same-directory"
        val shared = IosProtectedStorage(directory)
        startKoin {
            modules(module {
                single { shared }
                single<IosTrackQueue> { IosTrackQueue(get()) } binds arrayOf(TrackQueue::class, TrackQueueStorage::class)
                single { IosSessionStore(get<IosProtectedStorage>()) } binds arrayOf(SessionStore::class)
            })
        }
        val koin = KoinPlatform.getKoin()
        val requested = IosProtectedStorage("/tmp/exkmptracker-koin-parent/../exkmptracker-koin-same-directory")
        assertSame(koin.get<IosTrackQueue>(), iosQueueFor(requested))
        assertSame(koin.get<SessionStore>(), iosSessionFor(requested))
        val other = IosProtectedStorage("$directory-other")
        assertTrue(iosQueueFor(other) !== koin.get<TrackQueue>())
        assertTrue(iosSessionFor(other) !== koin.get<SessionStore>())
    }
}
