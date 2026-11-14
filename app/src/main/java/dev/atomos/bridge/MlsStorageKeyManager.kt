package dev.atomos.bridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.atomos.bridge.messaging.NativeMlsBridge
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps a per-account random MLS database key with a non-exportable Android Keystore key.
 * The database key is passed to the native process only for engine initialization.
 */
class MlsStorageKeyManager(
    private val secureStore: SecureStore,
    accountId: String
) {
    private val accountSuffix = accountId.replace("-", "").lowercase().take(16).also {
        require(it.length == 16 && it.all { char -> char.isLetterOrDigit() }) { "Invalid account identifier." }
    }
    private val alias = "atomos_mls_storage_wrap_$accountSuffix"
    private val secureStoreName = "mls_storage_key_wrapped_$accountSuffix"
    private val deviceIdKey = "mls_device_id_$accountSuffix"
    private val ivBytes = 12

    fun initializeNative(databaseFile: File): Boolean {
        NativeMlsBridge.closeDatabase()
        val storageKey = loadOrCreateStorageKey(databaseFile)
        return try {
            if (!NativeMlsBridge.initializeStorageKey(storageKey)) return false
            val opened = NativeMlsBridge.openDatabase(databaseFile.absolutePath)
            if (!opened) NativeMlsBridge.clearStorageKey()
            opened
        } finally {
            storageKey.fill(0)
        }
    }

    fun closeNative() {
        NativeMlsBridge.closeDatabase()
        NativeMlsBridge.clearStorageKey()
    }

    fun rotateDeviceId(): String {
        val created = java.util.UUID.randomUUID().toString()
        secureStore.put(deviceIdKey, created)
        return created
    }

    fun deviceIdOrCreate(): String {
        val existing = secureStore.getStrict(deviceIdKey)
        if (existing != null) {
            return java.util.UUID.fromString(existing).toString()
        }
        val created = java.util.UUID.randomUUID().toString()
        secureStore.put(deviceIdKey, created)
        return created
    }

    fun createDeviceKeyPackage(userId: String, deviceId: String) =
        NativeMlsBridge.createKeyPackage(userId, deviceId)

    private fun loadOrCreateStorageKey(databaseFile: File): ByteArray {
        val wrapped = secureStore.getStrict(secureStoreName)
        if (wrapped != null) {
            check(databaseFile.exists() && databaseFile.length() > 0L) {
                "The MLS database is missing while its wrapped key remains. Refusing to create an empty replacement."
            }
            val packed = Base64.decode(wrapped, Base64.NO_WRAP)
            require(packed.size > ivBytes + 16) { "Wrapped MLS storage key is damaged." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                wrappingKey(createIfMissing = false),
                GCMParameterSpec(128, packed.copyOfRange(0, ivBytes))
            )
            val unwrapped = cipher.doFinal(packed.copyOfRange(ivBytes, packed.size))
            require(unwrapped.size == 32) { "Wrapped MLS storage key has an invalid length." }
            return unwrapped
        }

        check(!databaseFile.exists() || databaseFile.length() == 0L) {
            "The MLS database exists but its wrapped storage key is missing. Refusing to generate a replacement key."
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
