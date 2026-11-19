@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.exkmptracker.ios

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import com.exkmptracker.model.TrackPoint
import com.exkmptracker.model.TrackingIdentity
import com.exkmptracker.ports.TrackQueue
import kotlinx.cinterop.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import sqlcipher.*
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL

/** One queue per protected storage instance. Operations and legacy migration share its mutex. */
class IosTrackQueue(private val storage: IosProtectedStorage) : TrackQueue {
    private var explicitKeyIdentifier: String? = null

    constructor(storage: IosProtectedStorage, keyIdentifier: String) : this(storage) {
        require(keyIdentifier.isNotBlank()) { "keyIdentifier must not be blank" }
        explicitKeyIdentifier = keyIdentifier
    }

    private fun keyIdentifier(): String {
        explicitKeyIdentifier?.let { return "custom:$it" }
        val directory = NSURL.fileURLWithPath(storage.directory).URLByStandardizingPath?.path
            ?: error("Cannot normalize tracker storage directory")
        val home = NSURL.fileURLWithPath(NSHomeDirectory()).URLByStandardizingPath?.path
            ?: error("Cannot normalize application home directory")
        return if (directory == home || directory.startsWith("$home/")) "app:${directory.removePrefix(home)}" else "external:$directory"
    }

    private var database: CPointer<sqlite3>? = null
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override suspend fun append(identity: TrackingIdentity, point: TrackPoint) = storage.transaction {
        val db = open()
        migrate(db, identity)
        statement(db, "INSERT INTO points(driver,brand,row_id,payload) VALUES(?,?,?,?)", identity.driverId, identity.brandId, point.id, json.encodeToString(point)) {
            check(sqlite3_step(it) == SQLITE_DONE) { "Could not append encrypted queue point" }
        }
        Unit
    }

    override suspend fun peek(identity: TrackingIdentity, limit: Int): List<TrackPoint> {
        require(limit >= 0) { "limit must not be negative" }
        return storage.transaction {
            val db = open()
            migrate(db, identity)
            readRows(db, identity, limit).map { it.third }
        }
    }

    override suspend fun acknowledge(identity: TrackingIdentity, ids: List<String>) = storage.transaction {
        val db = open()
        migrate(db, identity)
        if (ids.isNotEmpty()) transaction(db) {
            val rows = readRows(db, identity, ids.size)
            require(rows.map { it.second } == ids) { "Acknowledgement must exactly match the current FIFO prefix" }
            rows.forEach { row ->
                statement(db, "DELETE FROM points WHERE seq=?", row.first.toString()) {
                    check(sqlite3_step(it) == SQLITE_DONE && sqlite3_changes(db) == 1) { "Queue changed while acknowledging prefix" }
                }
            }
        }
    }

    override suspend fun size(identity: TrackingIdentity): Long = storage.transaction {
        val db = open()
        migrate(db, identity)
        statement(db, "SELECT count(*) FROM points WHERE driver=? AND brand=?", identity.driverId, identity.brandId) {
            check(sqlite3_step(it) == SQLITE_ROW) { "Could not count encrypted queue" }
            sqlite3_column_int64(it, 0)
        }
    }

    private fun open(): CPointer<sqlite3> {
        database?.let { return it }
        storage.prepareDatabaseDirectory()
        val db = memScoped {
            val out = alloc<CPointerVar<sqlite3>>()
            val rc = tracker_cipher_open("${storage.directory}/queue-sqlcipher-v2.db", keyIdentifier(), out.ptr)
            check(rc == SQLITE_OK && out.value != null) {
                "Cannot open SQLCipher queue (code $rc); preserve database and restore its original Keychain item"
            }
            out.value!!
        }
        try {
            statement(db, "PRAGMA quick_check") {
                check(sqlite3_step(it) == SQLITE_ROW && sqlite3_column_text(it, 0)?.reinterpret<ByteVar>()?.toKString() == "ok") {
                    "SQLCipher integrity check failed; database was preserved"
                }
            }
            execute(db, "PRAGMA synchronous=FULL")
            execute(db, "PRAGMA fullfsync=ON")
            execute(db, "CREATE TABLE IF NOT EXISTS points (seq INTEGER PRIMARY KEY AUTOINCREMENT, driver TEXT NOT NULL, brand TEXT NOT NULL, row_id TEXT NOT NULL, payload TEXT NOT NULL)")
            execute(db, "CREATE INDEX IF NOT EXISTS points_partition_seq ON points(driver, brand, seq)")
            execute(db, "CREATE TABLE IF NOT EXISTS migrations (driver TEXT NOT NULL, brand TEXT NOT NULL, PRIMARY KEY(driver,brand))")
            storage.synchronizeDirectory()
            database = db
            return db
        } catch (error: Throwable) {
            sqlite3_close(db)
            throw error
        }
    }

    private fun migrate(db: CPointer<sqlite3>, identity: TrackingIdentity) {
        val file = "queue-${identity.driverId.encodeToByteArray().hex()}-${identity.brandId.encodeToByteArray().hex()}.json"
        val contents = storage.read(file)
        val migrated = statement(db, "SELECT 1 FROM migrations WHERE driver=? AND brand=?", identity.driverId, identity.brandId) {
            when (sqlite3_step(it)) { SQLITE_ROW -> true; SQLITE_DONE -> false; else -> error("Cannot read queue migration state") }
        }
        if (!migrated) {
            val points = if (contents == null) emptyList() else try {
                json.decodeFromString<List<TrackPoint>>(contents)
            } catch (error: Exception) {
                throw IllegalStateException("Corrupt legacy queue '$file'; all files were preserved", error)
            }
            transaction(db) {
                points.forEach { point ->
                    statement(db, "INSERT INTO points(driver,brand,row_id,payload) VALUES(?,?,?,?)", identity.driverId, identity.brandId, point.id, json.encodeToString(point)) {
                        check(sqlite3_step(it) == SQLITE_DONE) { "Could not migrate legacy queue; source was preserved" }
                    }
                }
                statement(db, "INSERT INTO migrations(driver,brand) VALUES(?,?)", identity.driverId, identity.brandId) {
                    check(sqlite3_step(it) == SQLITE_DONE) { "Could not record queue migration" }
                }
            }
        }
        if (contents != null) storage.delete(file)
    }

    private fun readRows(db: CPointer<sqlite3>, identity: TrackingIdentity, limit: Int): List<Triple<Long, String, TrackPoint>> =
        statement(db, "SELECT seq,row_id,payload FROM points WHERE driver=? AND brand=? ORDER BY seq LIMIT ?", identity.driverId, identity.brandId, limit.toString()) { stmt ->
            val rows = mutableListOf<Triple<Long, String, TrackPoint>>()
            while (true) {
                when (sqlite3_step(stmt)) {
                    SQLITE_DONE -> break
                    SQLITE_ROW -> {
                        val id = columnText(stmt, 1)
                        val payload = columnText(stmt, 2)
                        val point = try { json.decodeFromString<TrackPoint>(payload) } catch (error: Exception) {
                            throw IllegalStateException("Corrupt encrypted queue row; data was preserved", error)
                        }
                        check(point.id == id) { "Encrypted queue row ID mismatch; data was preserved" }
                        rows += Triple(sqlite3_column_int64(stmt, 0), id, point)
                    }
                    else -> error("Could not read encrypted queue; data was preserved")
                }
            }
            rows
        }

    private fun columnText(stmt: CPointer<sqlite3_stmt>, index: Int): String {
        val text = sqlite3_column_text(stmt, index)?.reinterpret<ByteVar>() ?: error("Invalid queue text")
        return text.readBytes(sqlite3_column_bytes(stmt, index)).decodeToString(throwOnInvalidSequence = true)
    }

    private fun execute(db: CPointer<sqlite3>, sql: String) {
        check(sqlite3_exec(db, sql, null, null, null) == SQLITE_OK) { "SQLCipher queue operation failed; data was preserved" }
    }

    private inline fun <T> transaction(db: CPointer<sqlite3>, block: () -> T): T {
        execute(db, "BEGIN IMMEDIATE")
        try {
            val result = block()
            execute(db, "COMMIT")
            return result
        } catch (error: Throwable) {
            try { execute(db, "ROLLBACK") } catch (rollback: Throwable) { error.addSuppressed(rollback) }
            throw error
        }
    }

    private inline fun <T> statement(db: CPointer<sqlite3>, sql: String, vararg args: String, block: (CPointer<sqlite3_stmt>) -> T): T = memScoped {
        val out = alloc<CPointerVar<sqlite3_stmt>>()
        check(sqlite3_prepare_v2(db, sql, -1, out.ptr, null) == SQLITE_OK && out.value != null) { "Could not prepare SQLCipher queue query" }
        val stmt = out.value!!
        try {
            args.forEachIndexed { index, arg ->
                val bytes = arg.encodeToByteArray() + byteArrayOf(0)
                bytes.usePinned { pinned ->
                    check(tracker_bind_text(stmt, index + 1, pinned.addressOf(0), bytes.size - 1) == SQLITE_OK) { "Could not bind queue query" }
                }
            }
            block(stmt)
        } finally { sqlite3_finalize(stmt) }
    }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
