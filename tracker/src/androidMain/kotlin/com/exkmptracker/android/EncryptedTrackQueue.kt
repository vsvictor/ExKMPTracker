package com.exkmptracker.android

import android.content.ContentValues
import android.content.Context
import android.system.Os
import android.system.OsConstants
import net.zetetic.database.sqlcipher.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase as LegacyDatabase
import com.exkmptracker.model.TrackPoint
import com.exkmptracker.model.TrackingIdentity
import com.exkmptracker.ports.TrackQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.Closeable
import java.nio.ByteBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec

/** SQLite FIFO whose payload and identity partition key are both protected at rest. */
class EncryptedTrackQueue(context: Context) : TrackQueue, Closeable {
    private val helper = QueueDatabase(context.applicationContext)
    private val json = Json
    private val persistenceMutex = Mutex()

    override suspend fun append(identity: TrackingIdentity, point: TrackPoint) = persistenceMutex.withLock {
        withContext(Dispatchers.IO) {
        val partition = partition(identity)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, AndroidKeystoreKeys.queueEncryptionKey { helper.readableDatabase.rawQuery("SELECT 1 FROM points LIMIT 1", emptyArray<String>()).use { it.moveToFirst() } })
        val nonce = cipher.iv
        check(nonce.size == NONCE_SIZE) { "Unexpected Keystore queue IV size" }
        cipher.updateAAD(aad(identity, partition, point.id))
        val record = QueueRecord(identity.driverId, identity.brandId, point)
        val ciphertext = cipher.doFinal(json.encodeToString(record).encodeToByteArray())
        val values = ContentValues().apply {
            put("partition_key", partition)
            put("row_id", point.id)
            put("nonce", nonce)
            put("payload", ciphertext)
        }
        try {
            helper.writableDatabase.insertOrThrow("points", null, values)
        } catch (error: Exception) {
            throw QueueStorageException("Unable to commit encrypted track point", error)
        }
        Unit
        }
    }

    override suspend fun peek(identity: TrackingIdentity, limit: Int): List<TrackPoint> = persistenceMutex.withLock {
        withContext(Dispatchers.IO) {
        require(limit >= 0) { "limit must not be negative" }
        if (limit == 0) return@withContext emptyList()
        val partition = partition(identity)
        val result = mutableListOf<TrackPoint>()
        helper.readableDatabase.query("points", arrayOf("row_id", "nonce", "payload"), "partition_key=?",
            arrayOf(partition), null, null, "seq ASC", limit.toString()).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                result += readPoint(identity, partition, id, cursor.getBlob(1), cursor.getBlob(2))
            }
        }
        result
        }
    }

    override suspend fun acknowledge(identity: TrackingIdentity, ids: List<String>) = persistenceMutex.withLock {
        withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        require(ids.distinct().size == ids.size) { "Acknowledgement contains duplicate point IDs" }
        val db = helper.writableDatabase
        val partition = partition(identity)
        db.beginTransaction()
        try {
            val rows = mutableListOf<Pair<Long, String>>()
            db.query("points", arrayOf("seq", "row_id", "nonce", "payload"), "partition_key=?", arrayOf(partition),
                null, null, "seq ASC", ids.size.toString()).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(1)
                    check(id == ids[rows.size]) { "Acknowledgement is not the exact FIFO prefix" }
                    readPoint(identity, partition, id, cursor.getBlob(2), cursor.getBlob(3))
                    rows += cursor.getLong(0) to id
                }
            }
            check(rows.map { it.second } == ids) { "Acknowledgement is not the exact FIFO prefix" }
            val placeholders = rows.joinToString(",") { "?" }
            val args = arrayOf(partition) + rows.map { it.first.toString() }.toTypedArray()
            val deleted = db.delete("points", "partition_key=? AND seq IN ($placeholders)", args)
            check(deleted == ids.size) { "Queue changed while acknowledging prefix" }
            db.setTransactionSuccessful()
        } catch (error: Exception) {
            throw QueueStorageException("Unable to acknowledge the exact queue prefix", error)
        } finally {
            db.endTransaction()
        }
        }
    }

    override suspend fun size(identity: TrackingIdentity): Long = persistenceMutex.withLock {
        withContext(Dispatchers.IO) {
            helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM points WHERE partition_key=?", arrayOf(partition(identity))).use {
                check(it.moveToFirst()) { "Queue count query returned no result" }
                it.getLong(0)
            }
        }
    }

    override fun close() {
        helper.close()
    }

    private fun partition(identity: TrackingIdentity): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(AndroidKeystoreKeys.identityKey {
            helper.readableDatabase.rawQuery("SELECT 1 FROM points LIMIT 1", null).use { it.moveToFirst() }
        })
        val driver = identity.driverId.encodeToByteArray()
        val brand = identity.brandId.encodeToByteArray()
        val input = ByteBuffer.allocate(8 + driver.size + brand.size)
            .putInt(driver.size).put(driver).putInt(brand.size).put(brand).array()
        return mac.doFinal(input).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun aad(identity: TrackingIdentity, partition: String, id: String): ByteArray {
        val fields = listOf(
            "queue-v1".encodeToByteArray(),
            identity.driverId.encodeToByteArray(),
            identity.brandId.encodeToByteArray(),
            partition.encodeToByteArray(),
            id.encodeToByteArray(),
        )
        val size = fields.sumOf { 4 + it.size }
        return ByteBuffer.allocate(size).also { buffer ->
            fields.forEach { field -> buffer.putInt(field.size).put(field) }
        }.array()
    }
    private fun decrypt(payload: ByteArray, nonce: ByteArray, aad: ByteArray): QueueRecord {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, AndroidKeystoreKeys.queueEncryptionKey { helper.readableDatabase.rawQuery("SELECT 1 FROM points LIMIT 1", emptyArray<String>()).use { it.moveToFirst() } }, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return json.decodeFromString(cipher.doFinal(payload).decodeToString())
    }

    private fun readPoint(
        identity: TrackingIdentity,
        partition: String,
        id: String,
        nonce: ByteArray,
        payload: ByteArray,
    ): TrackPoint = try {
        val decoded = decrypt(payload, nonce, aad(identity, partition, id))
        check(decoded.driverId == identity.driverId && decoded.brandId == identity.brandId && decoded.point.id == id) {
            "Encrypted queue row identity does not match its partition"
        }
        decoded.point
    } catch (error: Exception) {
        throw QueueStorageException("Unable to decrypt queued point $id; queue data was preserved", error)
    }

    private class QueueDatabase(private val context: Context) : Closeable {
        private val databaseLazy = lazy { open() }
        private val db: SQLiteDatabase by databaseLazy
        val writableDatabase: SQLiteDatabase get() = db
        val readableDatabase: SQLiteDatabase get() = db
        private fun open(): SQLiteDatabase = synchronized(QueueDatabase::class.java) {
            System.loadLibrary("sqlcipher")
            val file = context.getDatabasePath("exkmp-tracker-queue-sqlcipher-v2.db")
            file.parentFile?.mkdirs()
            val key = SqlCipherKey.load(context, file)
            val database = try {
                SQLiteDatabase.openDatabase(file.path, key, null, SQLiteDatabase.CREATE_IF_NECESSARY,
                    { _, error -> throw QueueStorageException("SQLCipher queue is corrupt; database files were preserved", error) }, null)
            } catch (error: Exception) {
                throw QueueStorageException("Cannot open SQLCipher queue; preserve the database and restore its original key", error)
            } finally { key.fill(0) }
            try {
                database.rawQuery("PRAGMA cipher_version", emptyArray<String>()).use {
                    check(it.moveToFirst() && !it.getString(0).isNullOrEmpty()) { "SQLCipher encryption is unavailable" }
                }
                database.rawQuery("SELECT count(*) FROM sqlite_master", emptyArray<String>()).use { check(it.moveToFirst()) }
                database.rawQuery("PRAGMA quick_check", emptyArray<String>()).use {
                    check(it.moveToFirst() && it.getString(0) == "ok") { "SQLCipher integrity check failed" }
                }
                onConfigure(database)
                database.beginTransaction()
                try {
                    database.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY)")
                    database.execSQL("CREATE TABLE IF NOT EXISTS points (seq INTEGER PRIMARY KEY AUTOINCREMENT, partition_key TEXT NOT NULL, row_id TEXT NOT NULL, nonce BLOB NOT NULL, payload BLOB NOT NULL, UNIQUE(partition_key, row_id))")
                    database.execSQL("CREATE INDEX IF NOT EXISTS points_partition_seq ON points(partition_key, seq)")
                    val migrated = database.rawQuery("SELECT 1 FROM metadata WHERE name='legacy-v1'", emptyArray<String>()).use { it.moveToFirst() }
                    if (!migrated) {
                        val legacy = context.getDatabasePath("exkmp-tracker-queue-v1.db")
                        if (legacy.exists()) {
                            LegacyDatabase.openDatabase(legacy.path, null, LegacyDatabase.OPEN_READONLY,
                                android.database.DatabaseErrorHandler { throw QueueStorageException("Legacy SQLite queue is corrupt; all files were preserved") }).use { old ->
                                old.rawQuery("SELECT seq, partition_key, row_id, nonce, payload FROM points ORDER BY seq", null).use { rows ->
                                    while (rows.moveToNext()) {
                                        val values = ContentValues().apply {
                                            put("seq", rows.getLong(0)); put("partition_key", rows.getString(1))
                                            put("row_id", rows.getString(2)); put("nonce", rows.getBlob(3)); put("payload", rows.getBlob(4))
                                        }
                                        database.insertOrThrow("points", null, values)
                                    }
                                }
                            }
                        }
                        database.execSQL("INSERT INTO metadata(name) VALUES('legacy-v1')")
                    }
                    database.setTransactionSuccessful()
                } finally { database.endTransaction() }
                val dir = Os.open(file.parentFile!!.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(dir) } finally { Os.close(dir) }
                database
            } catch (error: Exception) {
                database.close()
                throw QueueStorageException("Cannot initialize or migrate SQLCipher queue; all source data was preserved", error)
            }
        }
        override fun close() { if (databaseLazy.isInitialized()) db.close() }

        private fun onConfigure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA synchronous=FULL")
        }

    }

    @Serializable
    private data class QueueRecord(val driverId: String, val brandId: String, val point: TrackPoint)

    private companion object { const val NONCE_SIZE = 12 }
}

class QueueStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)
