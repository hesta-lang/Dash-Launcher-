package com.hestalang.dashlauncher.data

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI

data class CatalogManifest(
    val catalogVersion: Int,
    val catalogId: String,
    val minLauncherVersion: String,
    val versionFiles: Map<String, String>
)

data class CatalogVersionRecord(
    val id: String,
    val displayName: String,
    val version: String,
    val channel: String,
    val platform: String,
    val minAndroidApi: Int,
    val releasedAt: String?,
    val changelog: String?,
    val metadataUrl: String
)

data class CatalogArtifactMetadata(
    val providerId: String,
    val url: String?,
    val sizeBytes: Long?,
    val sha256: String?,
    val providerName: String?,
    val packageName: String?,
    val signingCertificateSha256: String?
)

data class CatalogVersionMetadata(
    val id: String,
    val description: String,
    val platform: String,
    val minAndroidApi: Int,
    val supportedAbis: List<String>,
    val artifact: CatalogArtifactMetadata?
)

object CatalogJsonParser {
    private val channels = setOf("stable", "beta", "archived")
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    private val idPattern = Regex("^[a-zA-Z0-9._-]{1,96}$")
    private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")

    fun parseManifest(json: String): CatalogManifest {
        val root = parseObject(json, "El manifiesto del catálogo")
        val version = requiredInt(root, "catalogVersion")
        require(version == 1) { "La versión del formato de catálogo no es compatible: $version." }
        val catalogId = requiredString(root, "catalogId")
        val minimumLauncherVersion = requiredString(root, "minLauncherVersion")
        val versionFilesObject = root.getAsJsonObject("versionFiles")
            ?: throw IllegalArgumentException("El manifiesto no contiene versionFiles.")

        val files = channels.associateWith { channel ->
            requiredString(versionFilesObject, channel)
        }
        return CatalogManifest(version, catalogId, minimumLauncherVersion, files)
    }

    fun resolveIndexUrl(manifestUrl: String, relativePath: String): String =
        resolveCatalogUrl(manifestUrl, relativePath, manifestUrl)

    fun parseIndex(
        json: String,
        channel: String,
        indexUrl: String,
        manifestUrl: String
    ): List<CatalogVersionRecord> {
        require(channel in channels) { "Canal no reconocido: $channel." }
        val root = JsonParser.parseString(json)
        require(root.isJsonArray) { "El índice de versiones debe ser un arreglo JSON." }
        val array = root.asJsonArray
        require(array.size() <= 5000) { "El índice contiene demasiadas versiones." }

        val seenIds = mutableSetOf<String>()
        return array.map { element ->
            require(element.isJsonObject) { "El índice contiene una entrada que no es un objeto." }
            val item = element.asJsonObject
            val id = requiredString(item, "id")
            require(idPattern.matches(id)) { "ID de versión no válido: $id." }
            require(seenIds.add(id)) { "El índice contiene un ID duplicado: $id." }

            val itemChannel = requiredString(item, "channel").lowercase()
            require(itemChannel == channel) {
                "La versión $id pertenece a $itemChannel y no a $channel."
            }
            val platform = requiredString(item, "platform").lowercase()
            require(platform == "android") { "La versión $id no es una versión Android." }
            val api = requiredInt(item, "minAndroidApi")
            require(api >= 23) { "La versión $id declara un Android API mínimo no válido." }

            val metadataPath = requiredString(item, "metadata")
            val metadataUrl = resolveCatalogUrl(indexUrl, metadataPath, manifestUrl)
            CatalogVersionRecord(
                id = id,
                displayName = requiredString(item, "displayName"),
                version = requiredString(item, "version"),
                channel = itemChannel,
                platform = platform,
                minAndroidApi = api,
                releasedAt = optionalString(item.get("releasedAt")),
                changelog = optionalString(item.get("changelog")),
                metadataUrl = metadataUrl
            )
        }.sortedWith(
            compareByDescending<CatalogVersionRecord> { it.releasedAt.orEmpty() }
                .thenByDescending { it.version }
        )
    }

    fun parseMetadata(json: String, expectedRecord: CatalogVersionRecord): CatalogVersionMetadata {
        val root = parseObject(json, "Los metadatos de versión")
        val id = requiredString(root, "id")
        require(id == expectedRecord.id) {
            "Los metadatos pertenecen a " + id + " y no a " + expectedRecord.id + "."
        }
        val platform = requiredString(root, "platform").lowercase()
        require(platform == "android") { "Los metadatos no describen un APK Android." }
        val api = requiredInt(root, "minAndroidApi")
        require(api >= 23) { "El Android API mínimo no es válido." }

        val abiArray = root.getAsJsonArray("supportedAbis")
            ?: throw IllegalArgumentException("Los metadatos no incluyen supportedAbis.")
        val abis = abiArray.map { value ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                "La lista de ABI contiene un valor no válido."
            }
            value.asString
        }.distinct()
        require(abis.all { it in supportedAbis }) {
            "Los metadatos contienen ABI no compatibles."
        }

        val artifactElement = root.get("artifact")
        val artifact = if (artifactElement == null || artifactElement.isJsonNull) {
            null
        } else {
            require(artifactElement.isJsonObject) { "artifact debe ser un objeto o null." }
            val value = artifactElement.asJsonObject
            val provider = requiredString(value, "provider")
            val providerName = optionalString(value.get("providerName"))
            val url = optionalString(value.get("url"))
            val size = optionalLong(value.get("sizeBytes"))
            val hash = optionalString(value.get("sha256"))
            val packageName = optionalString(value.get("packageName"))
            val signingCertificateSha256 = optionalString(value.get("signingCertificateSha256"))

            require(provider == "direct" || provider == "external-page") {
                "El proveedor de artefacto no está admitido."
            }
            if (provider == "direct") {
                require(abis.isNotEmpty()) {
                    "El proveedor direct requiere al menos una ABI verificada."
                }
                require(!url.isNullOrBlank() && isValidHttpsUrl(url)) {
                    "El proveedor direct requiere una URL HTTPS válida."
                }
                require(size != null && size > 0L) {
                    "El proveedor direct requiere un tamaño de archivo verificado."
                }
                require(!hash.isNullOrBlank() && sha256Pattern.matches(hash)) {
                    "El proveedor direct requiere un SHA-256 de 64 caracteres hexadecimales."
                }
                require(packageName == "com.robtopx.geometryjump") {
                    "El APK directo debe declarar el paquete oficial com.robtopx.geometryjump."
                }
                require(!signingCertificateSha256.isNullOrBlank() && sha256Pattern.matches(signingCertificateSha256)) {
                    "El APK directo requiere la huella SHA-256 verificada del certificado de firma."
                }
            }
            if (provider == "external-page") {
                require(!providerName.isNullOrBlank()) {
                    "El proveedor externo requiere un nombre visible."
                }
                require(!url.isNullOrBlank() && isValidExternalPageUrl(url)) {
                    "El proveedor externo requiere una página HTTPS de un dominio permitido."
                }
                require(abis.isEmpty()) {
                    "Una referencia externa no puede declarar ABI sin verificar."
                }
                require(packageName == null && signingCertificateSha256 == null) {
                    "Una referencia externa no puede declarar identidad de un APK no verificado."
                }
                require(size == null && hash == null) {
                    "Una referencia externa no puede declarar tamaño o hash de un APK."
                }
            }
            CatalogArtifactMetadata(provider, url, size, hash, providerName, packageName, signingCertificateSha256)
        }

        return CatalogVersionMetadata(
            id = id,
            description = optionalString(root.get("description")).orEmpty(),
            platform = platform,
            minAndroidApi = api,
            supportedAbis = abis,
            artifact = artifact
        )
    }

    private fun resolveCatalogUrl(
        baseUrl: String,
        relativePath: String,
        manifestUrl: String
    ): String {
        require(relativePath.isNotBlank() && relativePath == relativePath.trim()) {
            "La ruta del catálogo está vacía o contiene espacios inválidos."
        }
        val base = URI(baseUrl)
        val reference = URI(relativePath)
        require(!reference.isAbsolute && reference.rawAuthority == null &&
            reference.rawQuery == null && reference.rawFragment == null) {
            "Las rutas de índices y metadatos deben ser relativas al catálogo."
        }

        val resolved = base.resolve(reference).normalize()
        val root = URI(manifestUrl).resolve(".").normalize()
        require(resolved.scheme.equals("https", ignoreCase = true) &&
            resolved.host.equals(root.host, ignoreCase = true) &&
            resolved.port == root.port &&
            resolved.userInfo == null &&
            resolved.fragment == null &&
            resolved.path.startsWith(root.path)) {
            "La ruta intenta salir del catálogo o usar un servidor externo."
        }
        return resolved.toASCIIString()
    }

    private fun isValidExternalPageUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val allowedHosts = setOf(
            "play.google.com",
            "uoldown.com",
            "www.uoldown.com",
            "apkcombo.com",
            "www.apkcombo.com"
        )
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.host.lowercase() in allowedHosts &&
            uri.userInfo == null &&
            uri.fragment == null
    }.getOrDefault(false)

    private fun isValidHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null
    }.getOrDefault(false)

    private fun parseObject(json: String, description: String) =
        runCatching {
            JsonParser.parseString(json).asJsonObject
        }.getOrElse {
            throw IllegalArgumentException("$description no contienen JSON válido.", it)
        }

    private fun requiredString(
        obj: com.google.gson.JsonObject,
        key: String
    ): String {
        val value: JsonElement = obj.get(key)
            ?: throw IllegalArgumentException("Falta el campo obligatorio $key.")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "El campo $key debe ser texto."
        }
        return value.asString.trim().also {
            require(it.isNotEmpty()) { "El campo $key no puede estar vacío." }
        }
    }

    private fun requiredInt(obj: com.google.gson.JsonObject, key: String): Int {
        val value = obj.get(key)
            ?: throw IllegalArgumentException("Falta el campo obligatorio $key.")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "El campo $key debe ser un número entero."
        }
        val number = value.asInt
        require(value.asDouble == number.toDouble()) { "El campo $key debe ser un número entero." }
        return number
    }

    private fun optionalString(value: JsonElement?): String? {
        if (value == null || value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "Se esperaba un valor de texto o null."
        }
        return value.asString
    }

    private fun optionalLong(value: JsonElement?): Long? {
        if (value == null || value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "Se esperaba un número o null."
        }
        val number = value.asLong
        require(value.asDouble == number.toDouble()) { "El tamaño del archivo debe ser un entero." }
        return number
    }
}
