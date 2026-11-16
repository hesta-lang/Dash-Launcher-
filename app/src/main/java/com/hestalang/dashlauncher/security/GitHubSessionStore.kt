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

/** Stores short-lived Dash Launcher sessions and PKCE verifiers encrypted with Android Keystore. */
class GitHubSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    init {
        // Remove the encrypted PAT left by an earlier development build.
        context.applicationContext
            .getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(LEGACY_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_KEY_ALIAS)
            }
        }
    }

    @Synchronized
    fun saveSession(session: String) {
        require(session.length in 60..2048 && session.contains(".")) {
            "El servidor devolvió una sesión de Dash Launcher no válida."
        }
        saveEncrypted(SESSION_KEY, session)
    }

    @Synchronized
    fun readSession(): String? {
        val value = readEncrypted(SESSION_KEY) ?: return null
        return value.takeIf { it.length in 60..2048 && it.contains(".") }
            ?: throw IllegalStateException("La sesión guardada de Dash Launcher no es válida.")
    }

    @Synchronized
    fun hasSession(): Boolean = preferences.contains(SESSION_KEY)

    @Synchronized
    fun clearSession() {
        check(preferences.edit().remove(SESSION_KEY).commit()) {
            "No se pudo cerrar la sesión de Dash Launcher."
        }
    }

    @Synchronized
    fun savePendingVerifier(verifier: String) {
        require(verifier.matches(Regex("^[A-Za-z0-9._~-]{43,128}$"))) {
            "El verificador PKCE generado no es válido."
        }
        saveEncrypted(PENDING_VERIFIER_KEY, verifier)
    }

    @Synchronized
    fun readPendingVerifier(): String? = readEncrypted(PENDING_VERIFIER_KEY)

    @Synchronized
    fun clearPendingVerifier() {
        preferences.edit().remove(PENDING_VERIFIER_KEY).commit()
    }

    private fun saveEncrypted(preferenceKey: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, payload, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, payload, cipher.iv.size, encrypted.size)
        check(
            preferences.edit()
                .putString(preferenceKey, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit()
        ) { "No se pudo guardar la información cifrada de Dash Launcher." }
    }

    private fun readEncrypted(preferenceKey: String): String? {
        val encoded = preferences.getString(preferenceKey, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES) { "El dato cifrado está incompleto." }
            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException(
                "No se pudo descifrar la sesión de Dash Launcher. Desconecta y vuelve a iniciar sesión.",
                error
            )
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
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
        private const val KEY_ALIAS = "dash_launcher_backend_session_v1"
        private const val LEGACY_KEY_ALIAS = "dash_launcher_private_storage_v1"
        private const val LEGACY_PREFERENCES_NAME = "private_storage_credentials"
        private const val PREFERENCES_NAME = "dash_launcher_backend_session"
        private const val SESSION_KEY = "encrypted_session"
        private const val PENDING_VERIFIER_KEY = "encrypted_pending_pkce_verifier"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
