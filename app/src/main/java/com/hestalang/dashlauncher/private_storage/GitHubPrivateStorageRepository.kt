package com.hestalang.dashlauncher.private_storage

import android.content.Context
import android.os.Build
import android.util.Base64
import com.google.gson.JsonParser
import com.hestalang.dashlauncher.security.ApkSignerCertificateReader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipFile

/**
 * Android client for the Dash Launcher backend.
 *
 * GitHub App credentials, installation tokens, and GitHub OAuth tokens remain on
 * the server. This class sends only a short-lived Dash Launcher session to the
 * configured backend. APK verification remains local and independent.
 */
class GitHubPrivateStorageRepository(context: Context) {
    private val appContext = context.applicationContext
    private val backendBaseUrl = DashBackendConfiguration.BASE_URL.trimEnd('/')

    fun createLoginUrl(codeChallenge: String): String {
        require(codeChallenge.matches(Regex("^[A-Za-z0-9_-]{43}$"))) {
            "El desafío PKCE no es válido."
        }
        requireBackendConfigured()
        return backendBaseUrl + "/auth/login?code_challenge=" +
            URLEncoder.encode(codeChallenge, Charsets.UTF_8.name())
    }

    /** Exchanges the one-time browser callback code for a scoped launcher session. */
    fun exchangeLoginCode(code: String, codeVerifier: String): String {
        require(code.matches(Regex("^[A-Za-z0-9_-]{32,100}$"))) {
            "El código de inicio de sesión no es válido."
        }
        require(codeVerifier.matches(Regex("^[A-Za-z0-9._~-]{43,128}$"))) {
            "El verificador PKCE no es válido."
        }
        val body = com.google.gson.JsonObject().apply {
            addProperty("code", code)
            addProperty("codeVerifier", codeVerifier)
        }
        val response = request(
            path = "/auth/exchange",
            session = null,
            method = "POST",
            body = body.toString()
        )
        val json = JsonParser.parseString(response).asJsonObject
        val session = json.get("accessToken")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IOException("El backend no devolvió una sesión de Dash Launcher.")
        require(session.length in 60..2048 && session.contains(".")) {
            "El backend devolvió una sesión de Dash Launcher no válida."
        }
        return session
    }

    fun loadVersions(session: String): List<PrivateVersionInfo> {
        validateSessionFormat(session)
        val response = request("/api/catalog", session)
        return PrivateVersionCatalogParser.parse(response)
    }

    fun resolveDownloadUrl(session: String, version: PrivateVersionInfo): String {
        validateSessionFormat(session)
        val encodedId = URLEncoder.encode(version.id, Charsets.UTF_8.name())
        val response = request("/api/download-url?id=" + encodedId, session)
        val json = JsonParser.parseString(response).asJsonObject
        val location = json.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IOException("El backend no devolvió la URL temporal del APK.")
        val uri = URI(location)
        val allowedHosts = setOf(
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com"
        )
        require(uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in allowedHosts &&
            uri.userInfo == null) {
            "El backend devolvió una URL temporal de descarga no permitida."
        }
        require(json.get("versionId")?.asString == version.id) {
            "La URL temporal corresponde a otra versión del catálogo."
        }
        return location
    }

    /** Verifies a DownloadManager file before it can be stored or installed. */
    fun verifyDownloadedApk(file: File, version: PrivateVersionInfo) {
        require(file.isFile && file.length() == version.sizeBytes) {
            "El tamaño del APK descargado no coincide con el catálogo privado."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().toHexString().equals(version.sha256, ignoreCase = true)) {
            "El SHA-256 del APK no coincide con el manifiesto privado."
        }
        verifyApk(file, version)
    }

    private fun request(
        path: String,
        session: String?,
        method: String = "GET",
        body: String? = null
    ): String {
        requireBackendConfigured()
        require(path.startsWith("/") && !path.startsWith("//")) {
            "La ruta del backend no es válida."
        }
        val connection = (URL(backendBaseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DashLauncher-Android")
            setRequestProperty("Connection", "close")
            setRequestProperty("Cache-Control", "no-cache")
            if (!session.isNullOrBlank()) setRequestProperty("Authorization", "Bearer " + session)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { readLimited(it, MAX_RESPONSE_BYTES) }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    JsonParser.parseString(response).asJsonObject
                        .get("error")?.asString
                }.getOrNull()
                throw IOException(message ?: when (code) {
                    401 -> "La sesión de Dash Launcher caducó. Vuelve a conectar GitHub."
                    403 -> "Esta cuenta de GitHub no está autorizada para el almacenamiento privado."
                    404 -> "El recurso no existe en el almacenamiento privado."
                    429 -> "El servicio limitó temporalmente las solicitudes. Inténtalo más tarde."
                    else -> "El backend de Dash Launcher respondió HTTP $code."
                })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun requireBackendConfigured() {
        val uri = runCatching { URI(backendBaseUrl) }.getOrNull()
        require(uri != null &&
            uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.endsWith(".workers.dev", ignoreCase = true) == true &&
            !uri.host.contains("YOUR-SUBDOMAIN", ignoreCase = true) &&
            uri.userInfo == null) {
            "El servidor de Dash Launcher todavía no está configurado. Despliega el Worker y establece su URL en DashBackendConfiguration.kt."
        }
    }

    private fun validateSessionFormat(session: String) {
        require(session.length in 60..2048 && session.contains(".")) {
            "No hay una sesión válida de Dash Launcher. Conecta GitHub desde Configuración."
        }
    }

    private fun verifyApk(file: File, version: PrivateVersionInfo) {
        val packageInfo = appContext.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            0
        ) ?: throw IOException("El archivo descargado no es un APK Android válido.")
        require(packageInfo.packageName == version.packageName) {
            "El APK declara un paquete distinto al esperado."
        }

        val certificates = ApkSignerCertificateReader.readCertificates(
            appContext,
            file,
            packageInfo
        )
        require(certificates.isNotEmpty()) { "El APK no contiene un certificado de firma legible." }
        val signerHashes = certificates.map { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate).toHexString()
        }
        require(signerHashes.any {
            it.equals(version.signingCertificateSha256, ignoreCase = true)
        }) {
            "El certificado de firma no coincide con el manifiesto privado."
        }

        val apkAbis = linkedSetOf<String>()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    val parts = name.split('/')
                    if (parts.size == 3 && parts[1].isNotBlank()) apkAbis.add(parts[1])
                }
            }
        }
        require(apkAbis.isNotEmpty() && apkAbis == version.supportedAbis.toSet()) {
            "Las ABI internas del APK no coinciden con el manifiesto privado."
        }
        require(apkAbis.any { Build.SUPPORTED_ABIS.contains(it) }) {
            "El APK no contiene una arquitectura compatible con este dispositivo."
        }
        require(Build.VERSION.SDK_INT >= version.minAndroidApi) {
            "Esta versión requiere Android API ${version.minAndroidApi} o superior."
        }
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > maxBytes) {
                throw IOException("La respuesta del backend supera el tamaño máximo permitido.")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val REPOSITORY = "hesta-lang/Dash-Launcher-Private-Storage"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}

/** RFC 7636 PKCE helper for the browser-to-app authorization callback. */
object GitHubOAuthPkce {
    fun newVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
