package com.exkmptracker.android

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

internal object SqlCipherKey {
    @Synchronized
    fun load(context: Context, database: File): ByteArray {
        val file = File(context.noBackupFilesDir, "tracker-sqlcipher-key-v1")
        val atomic = AtomicFile(file)
        val exists = file.exists() || File(file.path + ".bak").exists()
        if (!exists && database.exists() && database.length() > 0) {
            throw QueueStorageException("SQLCipher key is missing; database was preserved. Restore the original app data and Keystore key.")
        }
        val wrappingKey = AndroidKeystoreKeys.databaseWrappingKey(!exists && !database.exists())
        try {
            if (exists) {
                val wrapped = atomic.readFully()
                check(wrapped.size == 12 + 32 + 16) { "Invalid wrapped SQLCipher key" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
                return cipher.doFinal(wrapped.copyOfRange(12, wrapped.size))
            }
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
                val wrapped = cipher.iv + cipher.doFinal(key)
                val stream = atomic.startWrite()
                try { stream.write(wrapped); atomic.finishWrite(stream) } catch (error: Throwable) {
                    atomic.failWrite(stream); throw error
                }
                val dir = Os.open(context.noBackupFilesDir.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(dir) } finally { Os.close(dir) }
                return key
            } catch (error: Throwable) { key.fill(0); throw error }
        } catch (error: Exception) {
            throw QueueStorageException("Cannot recover SQLCipher key; database was preserved", error)
        }
    }
}
