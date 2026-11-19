package com.exkmptracker.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal object AndroidKeystoreKeys {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val AES_ALIAS = "com.exkmptracker.storage.aes.v1"
    private const val DATABASE_ALIAS = "com.exkmptracker.storage.sqlcipher.v1"
    private const val HMAC_ALIAS = "com.exkmptracker.storage.identity.v1"

    @Synchronized
    fun encryptionKey(): SecretKey = key(AES_ALIAS) ?: generate(AES_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        KeyProperties.KEY_ALGORITHM_AES, "GCM", "NoPadding")

    @Synchronized
    fun queueEncryptionKey(queueHasRows: () -> Boolean): SecretKey {
        key(AES_ALIAS)?.let { return it }
        if (queueHasRows()) throw QueueStorageException("Queue payload key is missing; existing queue data was preserved. Restore the original Keystore key.")
        return encryptionKey()
    }

    @Synchronized
    fun databaseWrappingKey(allowCreate: Boolean): SecretKey {
        key(DATABASE_ALIAS)?.let { return it }
        if (!allowCreate) throw QueueStorageException("SQLCipher wrapping key is missing; database was preserved. Restore the original app data and Keystore key.")
        return generate(DATABASE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            KeyProperties.KEY_ALGORITHM_AES, "GCM", "NoPadding")
    }

    @Synchronized
    fun identityKey(queueHasRows: () -> Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (store.containsAlias(HMAC_ALIAS)) {
            return store.getKey(HMAC_ALIAS, null) as? SecretKey
                ?: throw QueueStorageException("Queue identity key is unusable; queue data was preserved")
        }
        if (queueHasRows()) {
            throw QueueStorageException("Queue identity key is missing; existing queue data was preserved")
        }
        return generate(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256, null, null)
    }

    private fun key(alias: String): SecretKey? = (KeyStore.getInstance(KEYSTORE).apply { load(null) }
        .getKey(alias, null) as? SecretKey)

    private fun generate(alias: String, purposes: Int, algorithm: String, blockMode: String?, padding: String?): SecretKey {
        val generator = KeyGenerator.getInstance(algorithm, KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(alias, purposes)
        if (blockMode != null) builder.setBlockModes(blockMode)
        if (padding != null) builder.setEncryptionPaddings(padding)
        if (algorithm == KeyProperties.KEY_ALGORITHM_AES) builder.setRandomizedEncryptionRequired(true)
        generator.init(builder.build())
        return generator.generateKey()
    }
}
