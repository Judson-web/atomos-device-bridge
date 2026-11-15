package dev.atomos.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small AES-GCM encrypted preference store for local app preferences. */
class SecureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("atomos_secure_v2", Context.MODE_PRIVATE)
    private val alias = "atomos_device_bridge_v2"
    private val ivLength = 12
    private val aadPrefix = "dev.atomos.bridge.secure-store.v2:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String) {
        require(name.matches(Regex("[a-z0-9_]{1,64}"))) { "Invalid secure-storage key." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD((aadPrefix + name).toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "Could not save encrypted app data."
        }
    }

    @Synchronized
    fun get(name: String): String? {
        require(name.matches(Regex("[a-z0-9_]{1,64}"))) { "Invalid secure-storage key." }
        val encoded = prefs.getString(name, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > ivLength + 16) { "Stored value is truncated." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, ivLength)))
            cipher.updateAAD((aadPrefix + name).toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(packed.copyOfRange(ivLength, packed.size)), Charsets.UTF_8)
        } catch (_: AEADBadTagException) {
            prefs.edit().remove(name).commit()
            null
        } catch (_: Exception) {
            prefs.edit().remove(name).commit()
            null
        }
    }

    /**
     * Strict read for cryptographic key wrappers. Unlike get(), this never deletes
     * a corrupt value or silently converts a decryption failure into a missing key.
     */
    @Synchronized
    fun getStrict(name: String): String? {
        require(name.matches(Regex("[a-z0-9_]{1,64}"))) { "Invalid secure-storage key." }
        val encoded = prefs.getString(name, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > ivLength + 16) { "Stored value is truncated." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, ivLength)))
        cipher.updateAAD((aadPrefix + name).toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(packed.copyOfRange(ivLength, packed.size)), Charsets.UTF_8)
    }

    @Synchronized
    fun remove(vararg names: String) {
        prefs.edit().apply { names.forEach { remove(it) } }.commit()
    }
}
