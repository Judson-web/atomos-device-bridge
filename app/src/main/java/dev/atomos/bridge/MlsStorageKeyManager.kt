package dev.atomos.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.atomos.bridge.messaging.NativeMlsBridge
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps a random MLS database key with a non-exportable Android Keystore AES-GCM key.
 *
 * The random database key is passed to the native process only for the lifetime of
 * initialization. It is never written in plaintext to preferences or disk.
 */
class MlsStorageKeyManager(
    context: Context,
    private val secureStore: SecureStore
) {
    private val appContext = context.applicationContext
    private val alias = "atomos_mls_storage_wrap_v1"
    private val secureStoreName = "mls_storage_key_wrapped"
    private val ivBytes = 12

    fun initializeNative(): Boolean {
        val storageKey = loadOrCreateStorageKey()
        return try {
            NativeMlsBridge.initializeStorageKey(storageKey)
        } finally {
            storageKey.fill(0)
        }
    }

    private fun loadOrCreateStorageKey(): ByteArray {
        val wrapped = secureStore.getStrict(secureStoreName)
        if (wrapped != null) {
            val packed = Base64.decode(wrapped, Base64.NO_WRAP)
            require(packed.size > ivBytes + 16) { "Wrapped MLS storage key is damaged." }
            val key = wrappingKey(createIfMissing = false)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packed.copyOfRange(0, ivBytes)))
            val unwrapped = cipher.doFinal(packed.copyOfRange(ivBytes, packed.size))
            require(unwrapped.size == 32) { "Wrapped MLS storage key has an invalid length." }
            return unwrapped
        }

        val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(createIfMissing = true))
            val packed = cipher.iv + cipher.doFinal(generated)
            secureStore.put(secureStoreName, Base64.encodeToString(packed, Base64.NO_WRAP))
            return generated.copyOf()
        } finally {
            generated.fill(0)
        }
    }

    private fun wrappingKey(createIfMissing: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        check(createIfMissing) {
            "The Android Keystore wrapping key is missing. Existing MLS state will not be reset."
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
