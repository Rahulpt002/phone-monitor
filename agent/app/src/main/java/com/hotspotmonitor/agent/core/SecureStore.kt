package com.hotspotmonitor.agent.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Symmetric encryption used by [SecureStore]. Output = 1 byte IV length + IV + ciphertext/tag. */
interface Crypter {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key in the AndroidKeyStore (plan section 10: "device token kept
 * in Android Keystore-backed storage"). No user authentication: the phone runs headless.
 */
class KeystoreCrypter(private val alias: String) : Crypter {
    @Volatile
    private var cachedKey: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
        cachedKey = key
        return key
    }

    override fun encrypt(plain: ByteArray): ByteArray = AesGcm.encrypt(key(), plain)

    override fun decrypt(blob: ByteArray): ByteArray = AesGcm.decrypt(key(), blob)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** AES-GCM framing shared by [KeystoreCrypter] and test crypters. */
object AesGcm {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + sealed
    }

    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.isNotEmpty()) { "empty blob" }
        val ivLength = blob[0].toInt() and 0xff
        require(ivLength in 12..16 && blob.size > 1 + ivLength) { "malformed blob" }
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength)
    }
}

/**
 * Encrypted string values on top of a [KeyValueStore]. Values never appear in logs. A value that
 * can no longer be decrypted (key lost after a backup restore or keystore reset) reads as null.
 */
class SecureStore(
    private val store: KeyValueStore,
    private val crypter: Crypter,
    private val log: AgentLog,
) {
    /** Stores [value] (null removes it). Returns false when encryption failed. */
    fun putString(name: String, value: String?): Boolean {
        if (value == null) {
            store.remove(key(name))
            return true
        }
        return try {
            val blob = crypter.encrypt(value.toByteArray(Charsets.UTF_8))
            store.putString(key(name), Base64.getEncoder().encodeToString(blob))
            true
        } catch (e: Exception) {
            log.e(TAG, "Could not encrypt '$name'", e)
            false
        }
    }

    fun getString(name: String): String? {
        val stored = store.getString(key(name)) ?: return null
        return try {
            String(crypter.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8)
        } catch (e: Exception) {
            log.w(TAG, "Could not decrypt '$name'; treating it as missing", e)
            null
        }
    }

    fun contains(name: String): Boolean = store.contains(key(name))

    fun remove(name: String) = store.remove(key(name))

    private fun key(name: String) = "secure.$name"

    private companion object {
        const val TAG = "secure"
    }
}
