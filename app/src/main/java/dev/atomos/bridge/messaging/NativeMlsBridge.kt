package dev.atomos.bridge.messaging

/**
 * Optional JNI handshake for the pinned OpenMLS native library.
 *
 * This only verifies that the native library is packaged and loadable. It does not
 * initialize an MLS identity, create a group, or encrypt/decrypt application messages.
 * Messaging must remain disabled until those protocol operations and persistence tests
 * are implemented and reviewed.
 */
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
    private external fun nativeCloseDatabase()
    private external fun nativeClearStorageKey()
}
