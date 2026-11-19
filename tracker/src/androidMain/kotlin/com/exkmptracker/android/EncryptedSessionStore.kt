package com.exkmptracker.android

import android.content.Context
import android.util.AtomicFile
import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Authenticated, atomic session persistence. Corrupt or undecryptable state is never discarded. */
class EncryptedSessionStore(context: Context) : SessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "tracker-session-v1.bin"))
    private val json = Json

    override suspend fun load(): TrackingSession? = withContext(Dispatchers.IO) {
        val bytes = try {
            file.openRead().use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return@withContext null
        }
        try {
            require(bytes.size > HEADER_SIZE && bytes[0] == VERSION) { "Invalid encrypted session record" }
            val nonce = bytes.copyOfRange(1, 1 + NONCE_SIZE)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, AndroidKeystoreKeys.encryptionKey(), GCMParameterSpec(128, nonce))
            cipher.updateAAD(AAD)
            json.decodeFromString<TrackingSession>(cipher.doFinal(bytes.copyOfRange(1 + NONCE_SIZE, bytes.size)).decodeToString())
        } catch (error: Exception) {
            throw EncryptedStorageException("Unable to decrypt persisted tracker session", error)
        }
    }

    override suspend fun save(session: TrackingSession) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, AndroidKeystoreKeys.encryptionKey())
        val nonce = cipher.iv
        check(nonce.size == NONCE_SIZE) { "Unexpected Keystore session IV size" }
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(json.encodeToString(session).encodeToByteArray())
        val output = file.startWrite()
        try {
            output.write(VERSION.toInt())
            output.write(nonce)
            output.write(encrypted)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw EncryptedStorageException("Unable to persist tracker session", error)
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            file.delete()
            val base = file.baseFile
            if (base.exists() || File("${base.path}.bak").exists() || File("${base.path}.new").exists()) {
                throw IOException("Unable to remove all encrypted session files")
            }
        } catch (error: Exception) {
            throw EncryptedStorageException("Unable to clear persisted tracker session", error)
        }
    }

    private companion object {
        const val VERSION: Byte = 1
        const val NONCE_SIZE = 12
        const val HEADER_SIZE = 1 + NONCE_SIZE
        val AAD = "com.exkmptracker.session.v1".encodeToByteArray()
    }
}

class EncryptedStorageException(message: String, cause: Throwable) : IOException(message, cause)
