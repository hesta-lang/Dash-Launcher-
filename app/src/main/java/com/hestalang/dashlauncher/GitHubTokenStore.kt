package com.hestalang.dashlauncher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the GitHub token encrypted with an AES key held by Android Keystore.
 * The plaintext token is never written to preferences or logs.
 */
class GitHubTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun hasToken(): Boolean = preferences.contains(TOKEN_KEY)

    fun save(token: String) {
        require(token.startsWith("github_pat_") || token.startsWith("ghp_")) {
            "El formato del token no parece ser un token personal de GitHub."
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(cipher.iv.size + encrypted.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        preferences.edit()
            .putString(TOKEN_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    fun read(): String? {
        val encoded = preferences.getString(TOKEN_KEY, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "El token guardado está dañado." }
        val buffer = ByteBuffer.wrap(payload)
        val iv = ByteArray(GCM_IV_BYTES)
        buffer.get(iv)
        val encrypted = ByteArray(buffer.remaining())
        buffer.get(encrypted)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun clear() {
        preferences.edit().remove(TOKEN_KEY).apply()
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
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "dash_launcher_secure_settings"
        private const val TOKEN_KEY = "github_private_storage_token"
        private const val KEY_ALIAS = "dash_launcher_github_token_key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}

object GitHubPrivateStorageAccess {
    fun check(token: String): String {
        if (token.isBlank()) return "No hay un token guardado."
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(REPOSITORY_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "DashLauncher-Android")
            }
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    "Conexión con Dash-Launcher-Private-Storage establecida correctamente."
                HttpURLConnection.HTTP_UNAUTHORIZED ->
                    "GitHub rechazó el token. Revisa que no esté caducado o revocado."
                HttpURLConnection.HTTP_FORBIDDEN ->
                    "GitHub rechazó la solicitud. Comprueba el permiso Contents: Read-only y los límites de API."
                HttpURLConnection.HTTP_NOT_FOUND ->
                    "No se encontró el repositorio o el token no tiene acceso a él."
                else ->
                    "GitHub respondió HTTP ${connection.responseCode}. Revisa los permisos del token."
            }
        } catch (_: Exception) {
            "No se pudo conectar con GitHub. Comprueba tu conexión a Internet."
        } finally {
            connection?.disconnect()
        }
    }

    private const val REPOSITORY_API_URL =
        "https://api.github.com/repos/hesta-lang/Dash-Launcher-Private-Storage"
}
