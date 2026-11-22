package com.exkmptracker.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase as AndroidSqliteDatabase
import com.exkmptracker.android.EncryptedTrackQueue
import com.exkmptracker.ports.TrackQueue
import com.exkmptracker.ports.TrackQueueStorage
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.zetetic.database.sqlcipher.SQLiteDatabase as SqlCipherDatabase
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.definition.Kind

/**
 * Reads definitions only. It does not construct [EncryptedTrackQueue], so SQLCipher and the
 * keystore are not opened. Rejecting a non-Application context stays in [initTrackerKoin];
 * executing that check needs Robolectric, which this host test does not pull in.
 */
@OptIn(KoinInternalApi::class)
class AndroidQueueDefinitionTest {
    @Test
    fun queueIsOneSingletonSharedByBothPorts() {
        val factories = trackerPlatformModule.mappings.values.distinct()
        val queues = factories.filter { it.beanDefinition.primaryType == EncryptedTrackQueue::class }
        assertEquals(1, queues.size)
        val bean = queues.single().beanDefinition
        assertEquals(Kind.Singleton, bean.kind)
        assertTrue(TrackQueue::class in bean.secondaryTypes)
        assertTrue(TrackQueueStorage::class in bean.secondaryTypes)
        assertTrue(factories.none { it !== queues.single() && it.beanDefinition.primaryType == TrackQueue::class })
        assertTrue(factories.none { it !== queues.single() && it.beanDefinition.primaryType == TrackQueueStorage::class })
        assertTrue(factories.any { it.beanDefinition.primaryType == Context::class && it.beanDefinition.kind == Kind.Singleton })
        val forbidden = setOf(
            ByteArray::class,
            SecretKey::class,
            AndroidSqliteDatabase::class,
            SqlCipherDatabase::class,
        )
        assertTrue(factories.none { it.beanDefinition.primaryType in forbidden })
    }
}
