package com.hestalang.dashlauncher.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores a fine-grained GitHub token encrypted with an AES key held by Android Keystore.
 * The token is never written to source code, logs, or an unencrypted preference.
 */
class GitHubAccessTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun saveToken(token: String) {
        val normalized = token.trim()
        require(normalized.startsWith(TOKEN_PREFIX) && normalized.length in 40..300) {
            "Introduce un token fine-grained de GitHub (debe comenzar con github_pat_)."
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, payload, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, payload, cipher.iv.size, encrypted.size)

        check(
            preferences.edit()
                .putString(TOKEN_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit()
        ) {
            "No se pudo guardar la credencial cifrada."
        }
    }

    @Synchronized
    fun readToken(): String? {
        val encoded = preferences.getString(TOKEN_KEY, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES) { "La credencial cifrada está incompleta." }

            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
                .takeIf { it.startsWith(TOKEN_PREFIX) }
                ?: throw IllegalStateException("La credencial guardada no es válida.")
        } catch (error: Exception) {
            throw IllegalStateException(
                "No se pudo descifrar la credencial guardada. Desconecta y vuelve a conectar el almacenamiento privado.",
                error
            )
        }
    }

    @Synchronized
    fun clearToken() {
        check(preferences.edit().remove(TOKEN_KEY).commit()) {
            "No se pudo eliminar la credencial guardada."
        }
    }

    fun hasToken(): Boolean = preferences.contains(TOKEN_KEY)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dash_launcher_private_storage_v1"
        private const val PREFERENCES_NAME = "private_storage_credentials"
        private const val TOKEN_KEY = "github_fine_grained_token"
        private const val TOKEN_PREFIX = "github_pat_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
