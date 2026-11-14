package dev.atomos.bridge.messaging

import org.json.JSONObject

/**
 * Optional JNI handshake for the pinned OpenMLS native library.
 *
 * This bridge opens per-account encrypted OpenMLS SQLite storage and creates
 * device-bound KeyPackages. It does not yet create MLS groups or encrypt/decrypt
 * application messages; message composition remains disabled until those operations
 * and their persistence/interoperability tests are implemented and reviewed.
 */
data class DeviceEnrollmentBundle(
    val credentialHex: String,
    val signaturePublicKeyHex: String,
    val keyPackageHex: String,
    val cipherSuite: String
)

object NativeMlsBridge {
    @Volatile
    private var loadAttempted = false

    @Volatile
    private var loaded = false

    fun initializeStorageKey(key: ByteArray): Boolean = synchronized(this) {
        if (key.size != 32) return false
        if (!loadAttempted) {
            loadAttempted = true
            loaded = runCatching { System.loadLibrary("atomos_mls_bridge") }.isSuccess
        }
        if (!loaded) return false
        runCatching { nativeInitializeStorageKey(key) }.getOrDefault(false)
    }

    fun createKeyPackage(userId: String, deviceId: String): DeviceEnrollmentBundle? = synchronized(this) {
        if (!loaded) return null
        runCatching {
            val result = JSONObject(nativeCreateKeyPackage(userId, deviceId))
            DeviceEnrollmentBundle(
                credentialHex = result.getString("credential_hex"),
                signaturePublicKeyHex = result.getString("signature_public_key_hex"),
                keyPackageHex = result.getString("key_package_hex"),
                cipherSuite = result.getString("cipher_suite")
            )
        }.getOrNull()
    }

    fun openDatabase(path: String): Boolean = synchronized(this) {
        if (!loaded) return false
        runCatching { nativeOpenDatabase(path) }.getOrDefault(false)
    }

    fun closeDatabase() = synchronized(this) {
        if (loaded) runCatching { nativeCloseDatabase() }
    }

    fun clearStorageKey() = synchronized(this) {
        if (loaded) runCatching { nativeClearStorageKey() }
    }

    fun protocolVersionOrNull(): String? = synchronized(this) {
        if (!loadAttempted) {
            loadAttempted = true
            loaded = runCatching { System.loadLibrary("atomos_mls_bridge") }.isSuccess
        }
        if (!loaded) return null
        runCatching { nativeProtocolVersion() }.getOrNull()
    }

    private external fun nativeProtocolVersion(): String
    private external fun nativeInitializeStorageKey(key: ByteArray): Boolean
    private external fun nativeOpenDatabase(path: String): Boolean
    private external fun nativeCreateKeyPackage(userId: String, deviceId: String): String
    private external fun nativeCloseDatabase()
    private external fun nativeClearStorageKey()
}
