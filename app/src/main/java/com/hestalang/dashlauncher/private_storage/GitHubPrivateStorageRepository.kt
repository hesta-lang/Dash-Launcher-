package com.hestalang.dashlauncher.private_storage

import com.hestalang.dashlauncher.security.ApkSignerCertificateReader

import android.content.Context
import android.util.Base64
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Read-only client for the owner's private GitHub repository.
 *
 * Fine-grained token permissions required:
 * - Repository access: only Dash-Launcher-Private-Storage
 * - Contents: Read-only
 *
 * The token is only sent to api.github.com. Signed release-asset redirects are followed
 * without forwarding the token to the redirected host.
 */
class GitHubPrivateStorageRepository(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(appContext.cacheDir, "private-storage")

    fun validateAccess(token: String) {
        validateTokenFormat(token)
        val response = requestApi(
            url = "$API_BASE/repos/$REPOSITORY",
            token = token,
            accept = "application/vnd.github+json"
        )
        val repository = JsonParser.parseString(response).asJsonObject
        require(repository.get("full_name")?.asString == REPOSITORY) {
            "El token no tiene acceso al repositorio privado configurado."
        }
        require(repository.get("private")?.asBoolean == true) {
            "El repositorio de almacenamiento debe permanecer privado."
        }
    }

    fun loadVersions(token: String): List<PrivateVersionInfo> {
        validateTokenFormat(token)
        val response = requestApi(
            url = "$API_BASE/repos/$REPOSITORY/contents/$CATALOG_PATH?ref=main",
            token = token,
            accept = "application/vnd.github+json"
        )
        val contentObject = JsonParser.parseString(response).asJsonObject
        require(contentObject.get("type")?.asString == "file") {
            "El manifiesto privado no es un archivo."
        }
        require(contentObject.get("encoding")?.asString == "base64") {
            "GitHub devolvió un formato de manifiesto no compatible."
        }
        val encoded = contentObject.get("content")?.asString
            ?: throw IOException("GitHub no devolvió el contenido del manifiesto privado.")
        val decoded = Base64.decode(encoded, Base64.DEFAULT)
        require(decoded.size <= MAX_CATALOG_BYTES) {
            "El manifiesto privado supera el tamaño máximo permitido."
        }
        return PrivateVersionCatalogParser.parse(String(decoded, Charsets.UTF_8))
    }

    /**
     * Resolves a named asset from the expected private Release, downloads it to cache,
     * and validates size, SHA-256, package, signing certificate, ABI and device support.
     * The returned temporary file must be deleted by the caller after it is stored.
     */
    fun downloadAndVerify(token: String, version: PrivateVersionInfo): File {
        validateTokenFormat(token)
        val encodedTag = URLEncoder.encode(version.tag, Charsets.UTF_8.name())
        val releaseJson = requestApi(
            url = "$API_BASE/repos/$REPOSITORY/releases/tags/$encodedTag",
            token = token,
            accept = "application/vnd.github+json"
        )
        val release = JsonParser.parseString(releaseJson).asJsonObject
        require(release.get("tag_name")?.asString == version.tag) {
            "La Release privada no coincide con la etiqueta declarada."
        }
        require(release.get("draft")?.asBoolean == false) {
            "La Release privada todavía está marcada como borrador."
        }

        val assets = release.getAsJsonArray("assets")
            ?: throw IOException("La Release privada no contiene archivos.")
        val matchingAsset = assets.firstOrNull { item ->
            item.isJsonObject && item.asJsonObject.get("name")?.asString == version.assetName
        }?.asJsonObject ?: throw IOException("No se encontró el APK esperado en la Release privada.")

        val assetId = matchingAsset.get("id")?.asLong
            ?: throw IOException("GitHub no devolvió el ID del asset.")
        val assetSize = matchingAsset.get("size")?.asLong
            ?: throw IOException("GitHub no devolvió el tamaño del asset.")
        require(assetSize == version.sizeBytes) {
            "El tamaño del asset de GitHub no coincide con el manifiesto privado."
        }

        val remoteDigest = matchingAsset.get("digest")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.removePrefix("sha256:")
        if (!remoteDigest.isNullOrBlank()) {
            require(remoteDigest.equals(version.sha256, ignoreCase = true)) {
                "El digest publicado por GitHub no coincide con el manifiesto privado."
            }
        }

        cacheDirectory.mkdirs()
        val temporaryFile = File.createTempFile("dash-private-", ".apk.part", cacheDirectory)
        try {
            val apiUrl = "$API_BASE/repos/$REPOSITORY/releases/assets/$assetId"
            val apiConnection = openApiConnection(
                url = apiUrl,
                token = token,
                accept = "application/octet-stream"
            )
            val responseCode = apiConnection.responseCode
            val input = when (responseCode) {
                HttpURLConnection.HTTP_OK -> apiConnection.inputStream
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308 -> {
                    val location = apiConnection.getHeaderField("Location")
                        ?: throw IOException("GitHub no proporcionó la URL temporal del APK.")
                    apiConnection.disconnect()
                    openSignedAsset(location)
                }
                else -> {
                    apiConnection.disconnect()
                    throw IOException(httpErrorMessage(responseCode))
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var copiedBytes = 0L
            input.use { source ->
                FileOutputStream(temporaryFile).use { target ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copiedBytes += count
                        require(copiedBytes <= version.sizeBytes) {
                            "La descarga supera el tamaño permitido."
                        }
                        digest.update(buffer, 0, count)
                        target.write(buffer, 0, count)
                    }
                    target.fd.sync()
                }
            }
            apiConnection.disconnect()

            require(copiedBytes == version.sizeBytes) {
                "El tamaño descargado no coincide con el manifiesto privado."
            }
            val actualHash = digest.digest().toHexString()
            require(actualHash.equals(version.sha256, ignoreCase = true)) {
                "El SHA-256 del APK no coincide con el manifiesto privado."
            }
            verifyApk(temporaryFile, version)
            return temporaryFile
        } catch (error: Exception) {
            temporaryFile.delete()
            throw error
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
            MessageDigest.getInstance("SHA-256")
                .digest(certificate)
                .toHexString()
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

    private fun requestApi(url: String, token: String, accept: String): String {
        val connection = openApiConnection(url, token, accept)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(httpErrorMessage(code))
            return connection.inputStream.use { readLimited(it, MAX_CATALOG_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    private fun openApiConnection(
        url: String,
        token: String,
        accept: String
    ): HttpURLConnection {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("api.github.com", ignoreCase = true) &&
            uri.userInfo == null) {
            "La solicitud de almacenamiento privado no apunta a la API HTTPS de GitHub."
        }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "DashLauncher-Android")
        }
    }

    private fun openSignedAsset(location: String): java.io.InputStream {
        val uri = URI(location)
        val allowedHosts = setOf(
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com"
        )
        require(uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in allowedHosts &&
            uri.userInfo == null) {
            "GitHub redirigió el APK a un dominio temporal no permitido."
        }
        val connection = (URL(location).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = ASSET_READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "DashLauncher-Android")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("El servidor temporal del APK respondió HTTP $code.")
        }
        return connection.inputStream
    }

    private fun validateTokenFormat(token: String) {
        require(token.startsWith(TOKEN_PREFIX) && token.length in 40..300) {
            "No hay un token fine-grained válido. Configúralo en Ajustes."
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
                throw IOException("La respuesta de GitHub supera el tamaño máximo permitido.")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun httpErrorMessage(code: Int): String = when (code) {
        401 -> "GitHub rechazó el token. Comprueba que siga vigente."
        403 -> "GitHub denegó el acceso. Comprueba que el token tenga Contents: Read-only."
        404 -> "No se encontró el recurso privado. Comprueba el nombre del repositorio y los permisos del token."
        429 -> "GitHub limitó temporalmente las solicitudes. Inténtalo más tarde."
        else -> "GitHub respondió HTTP $code al consultar el almacenamiento privado."
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val REPOSITORY = "hesta-lang/Dash-Launcher-Private-Storage"
        private const val API_BASE = "https://api.github.com"
        private const val CATALOG_PATH = "catalog/versions.json"
        private const val TOKEN_PREFIX = "github_pat_"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val ASSET_READ_TIMEOUT_MS = 90_000
        private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
    }
}
