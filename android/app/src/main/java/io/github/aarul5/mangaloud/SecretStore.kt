package io.github.aarul5.mangaloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys kept encrypted with a key that never leaves the phone's Android Keystore. A backup
 * or a copied settings file holds only the encrypted text, which no other phone can read.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun get(name: String): String = cache.getOrPut(name) { read(name) }

    private fun read(name: String): String {
        val saved = prefs.getString(name, null) ?: return ""
        return try {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            // The Keystore key is gone (phone restored from a backup): the key must be entered again.
            Log.w(TAG, "Could not read the saved $name: ${e.javaClass.simpleName}")
            ""
        }
    }

    fun set(name: String, value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) {
            prefs.edit().remove(name).apply()
            cache[name] = ""
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        cache[name] = clean
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /** Decrypted values, so a key read on every request does not go to the Keystore each time. */
        private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
        private const val TAG = "SecretStore"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "mangaloud-secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
