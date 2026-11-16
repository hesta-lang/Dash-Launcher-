package com.hestalang.dashlauncher.data

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PrivateStorageVersion(
    val id: String,
    val version: String,
    val displayName: String,
    val tag: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String,
    val signingCertificateSha256: String,
    val supportedAbis: List<String>,
    val minAndroidApi: Int,
    val officialSignatureVerified: Boolean,
    val warning: String?
)

class PrivateStorageCatalogRepository {
    fun load(token: String): List<PrivateStorageVersion> {
        require(token.isNotBlank()) { "Primero configura el token de GitHub en Ajustes." }
        val json = requestCatalog(token)
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("schemaVersion")?.asInt == 1) {
            "El formato del catálogo privado no es compatible."
        }
        require(root.get("repository")?.asString == REPOSITORY) {
            "El catálogo privado pertenece a otro repositorio."
        }
        val versions = root.getAsJsonArray("versions")
            ?: throw IllegalStateException("El catálogo privado no contiene versiones.")
        require(versions.size() <= 100) { "El catálogo privado contiene demasiadas entradas." }
        val seen = mutableSetOf<String>()
        val parsedVersions = versions.map { element ->
            val item = element.asJsonObject
            fun required(name: String): String =
                item.get(name)?.takeIf { !it.isJsonNull }?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Falta el campo $name en el catálogo privado.")

            val id = required("id")
            require(id.matches(Regex("^[a-zA-Z0-9._-]{1,96}$")) && seen.add(id)) {
                "El catálogo privado contiene un ID inválido o duplicado."
            }
            val tag = required("tag")
            val assetName = required("assetName")
            require(tag.matches(Regex("^[a-zA-Z0-9._-]{1,100}$"))) {
                "La etiqueta de la versión privada no es válida."
            }
            require(!assetName.contains("/") && !assetName.contains("\\") && assetName.endsWith(".apk")) {
                "El nombre del APK privado no es válido."
            }
            val size = item.get("sizeBytes")?.asLong
                ?: throw IllegalArgumentException("Falta el tamaño del APK privado.")
            require(size > 0L) { "El tamaño del APK privado no es válido." }
            val hash = required("sha256")
            require(hash.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                "El SHA-256 del APK privado no es válido."
            }
            val packageName = required("packageName")
            require(packageName == "com.robtopx.geometryjump") {
                "El APK privado no declara el paquete esperado de Geometry Dash."
            }
            val signer = required("signingCertificateSha256")
            require(signer.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                "La huella del certificado del APK privado no es válida."
            }
            val abis = item.getAsJsonArray("supportedAbis")?.map { it.asString }.orEmpty()
            require(abis.isNotEmpty() && abis.all {
                it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }) { "El catálogo privado no declara arquitecturas Android válidas." }

            PrivateStorageVersion(
                id = id,
                version = required("version"),
                displayName = required("displayName"),
                tag = tag,
                assetName = assetName,
                downloadUrl = "",
                sizeBytes = size,
                sha256 = hash.lowercase(),
                packageName = packageName,
                signingCertificateSha256 = signer.lowercase(),
                supportedAbis = abis.distinct(),
                minAndroidApi = item.get("minAndroidApi")?.asInt ?: 23,
                officialSignatureVerified = item.get("officialSignatureVerified")?.asBoolean ?: false,
                warning = item.get("warning")?.takeIf { !it.isJsonNull }?.asString
            )
        }.sortedByDescending { it.version }
        return parsedVersions.map { version ->
            version.copy(downloadUrl = resolveAssetDownloadUrl(token, version))
        }
    }

    private fun resolveAssetDownloadUrl(token: String, version: PrivateStorageVersion): String {
        val encodedTag = URLEncoder.encode(version.tag, Charsets.UTF_8.name()).replace("+", "%20")
        val releaseUrl = "https://api.github.com/repos/hesta-lang/Dash-Launcher-Private-Storage/releases/tags/$encodedTag"
        val release = requestJson(releaseUrl, token)
        val root = JsonParser.parseString(release).asJsonObject
        val assets = root.getAsJsonArray("assets")
            ?: throw IllegalStateException("El release privado no contiene archivos descargables.")
        val asset = assets.firstOrNull { item ->
            item.asJsonObject.get("name")?.asString == version.assetName
        }?.asJsonObject ?: throw IllegalStateException(
            "No se encontró el APK ${version.assetName} en el release ${version.tag}."
        )
        val assetSize = asset.get("size")?.asLong
        require(assetSize == version.sizeBytes) {
            "El tamaño del APK publicado no coincide con el catálogo privado."
        }
        val assetId = asset.get("id")?.asLong
            ?: throw IllegalStateException("GitHub no devolvió el ID del APK privado.")
        val apiAssetUrl = "https://api.github.com/repos/hesta-lang/Dash-Launcher-Private-Storage/releases/assets/$assetId"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(apiAssetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "DashLauncher-Android")
            }
            val code = connection.responseCode
            require(code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_SEE_OTHER) {
                "GitHub no devolvió un enlace temporal seguro para descargar el APK (HTTP $code)."
            }
            val location = connection.getHeaderField("Location")
                ?: throw IllegalStateException("GitHub no devolvió la URL temporal del APK.")
            val uri = URL(location).toURI()
            require(uri.scheme.equals("https", true) &&
                uri.host.lowercase() in setOf(
                    "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com",
                    "github-releases.githubusercontent.com"
                ) && uri.userInfo == null && uri.fragment == null) {
                "GitHub devolvió un destino de descarga no permitido."
            }
            return uri.toASCIIString()
        } finally {
            connection?.disconnect()
        }
    }

    private fun requestJson(url: String, token: String): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "DashLauncher-Android")
            }
            val code = connection.responseCode
            require(code in 200..299) { "GitHub respondió HTTP $code al consultar el release privado." }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }

    private fun requestCatalog(token: String): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(CATALOG_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github.raw+json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "DashLauncher-Android")
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw IllegalStateException("GitHub rechazó el token. Vuelve a guardarlo en Configuración.")
            }
            if (code == HttpURLConnection.HTTP_FORBIDDEN || code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw IllegalStateException(
                    "No hay acceso al catálogo privado. Comprueba Contents: Read-only y la autorización del repositorio."
                )
            }
            if (code !in 200..299) {
                throw IllegalStateException("GitHub respondió HTTP $code al consultar el catálogo privado.")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() + count <= MAX_BYTES) {
                        "El catálogo privado supera el tamaño permitido."
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val REPOSITORY = "hesta-lang/Dash-Launcher-Private-Storage"
        private const val CATALOG_API_URL =
            "https://api.github.com/repos/hesta-lang/Dash-Launcher-Private-Storage/contents/catalog/versions.json?ref=main"
        private const val MAX_BYTES = 512 * 1024
    }
}
