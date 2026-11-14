package dev.atomos.bridge.messaging

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional JNI handshake for the pinned OpenMLS native library.
 *
 * This bridge opens per-account encrypted OpenMLS SQLite storage and creates
 * device-bound KeyPackages. It does not yet create MLS groups or encrypt/decrypt
 * application messages; message composition remains disabled until those operations
 * and their persistence/interoperability tests are implemented and reviewed.
 */
data class DirectGroupResult(val welcomeHex: String?, val alreadyInitialized: Boolean)
data class IncomingMessageResult(val kind: String, val plaintext: ByteArray?)

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

    fun createKeyPackages(userId: String, deviceId: String, count: Int): List<DeviceEnrollmentBundle>? = synchronized(this) {
        if (!loaded || count !in 1..20) return null
        runCatching {
            val results = JSONArray(nativeCreateKeyPackages(userId, deviceId, count))
            List(results.length()) { index ->
                val result = results.getJSONObject(index)
                DeviceEnrollmentBundle(
                    credentialHex = result.getString("credential_hex"),
                    signaturePublicKeyHex = result.getString("signature_public_key_hex"),
                    keyPackageHex = result.getString("key_package_hex"),
                    cipherSuite = result.getString("cipher_suite")
                )
            }
        }.getOrNull()
    }

    fun createDirectGroup(
        conversationId: String,
        userId: String,
        deviceId: String,
        keyPackageHex: List<String>
    ): DirectGroupResult? = synchronized(this) {
        if (!loaded || keyPackageHex.isEmpty()) return null
        runCatching {
            val result = JSONObject(
                nativeCreateDirectGroup(
                    conversationId,
                    userId,
                    deviceId,
                    JSONArray(keyPackageHex).toString()
                )
            )
            DirectGroupResult(
                welcomeHex = result.optString("welcome_hex").takeIf { it.isNotBlank() },
                alreadyInitialized = result.optBoolean("already_initialized", false)
            )
        }.getOrNull()
    }

    fun hasGroup(conversationId: String): Boolean = synchronized(this) {
        if (!loaded) return false
        runCatching { nativeHasGroup(conversationId) }.getOrDefault(false)
    }

    fun markWelcomePublished(conversationId: String): Boolean = synchronized(this) {
        if (!loaded) return false
        runCatching { nativeMarkWelcomePublished(conversationId) }.getOrDefault(false)
    }

    fun processIncomingMessage(conversationId: String, messageId: String, messageHex: String): IncomingMessageResult? = synchronized(this) {
        if (!loaded) return null
        runCatching {
            val result = JSONObject(nativeProcessIncomingMessage(conversationId, messageId, messageHex))
            val plaintext = result.optString("plaintext_base64").takeIf { it.isNotBlank() }
                ?.let { Base64.decode(it, Base64.NO_WRAP) }
            IncomingMessageResult(result.getString("kind"), plaintext)
        }.getOrNull()
    }

    fun cachedPlaintext(conversationId: String, messageId: String): ByteArray? = synchronized(this) {
        if (!loaded) return null
        runCatching { nativeCachedPlaintext(conversationId, messageId) }.getOrNull()
    }

    fun createApplicationMessage(
        conversationId: String,
        deviceId: String,
        messageId: String,
        plaintext: ByteArray
    ): String? = synchronized(this) {
        if (!loaded) return null
        runCatching { nativeCreateApplicationMessage(conversationId, deviceId, messageId, plaintext) }.getOrNull()
    }

    fun markMessageSent(conversationId: String, messageId: String): Boolean = synchronized(this) {
        if (!loaded) return false
        runCatching { nativeMarkMessageSent(conversationId, messageId) }.getOrDefault(false)
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
    private external fun nativeCreateKeyPackages(userId: String, deviceId: String, count: Int): String
    private external fun nativeCreateDirectGroup(conversationId: String, userId: String, deviceId: String, keyPackagesJson: String): String
    private external fun nativeHasGroup(conversationId: String): Boolean
    private external fun nativeMarkWelcomePublished(conversationId: String): Boolean
    private external fun nativeProcessIncomingMessage(conversationId: String, messageId: String, messageHex: String): String
    private external fun nativeCreateApplicationMessage(conversationId: String, deviceId: String, messageId: String, plaintext: ByteArray): String
    private external fun nativeCachedPlaintext(conversationId: String, messageId: String): ByteArray
    private external fun nativeMarkMessageSent(conversationId: String, messageId: String): Boolean
    private external fun nativeCloseDatabase()
    private external fun nativeClearStorageKey()
}
