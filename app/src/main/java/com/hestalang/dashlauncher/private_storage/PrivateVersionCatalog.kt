package com.hestalang.dashlauncher.private_storage

import com.google.gson.JsonParser

data class PrivateVersionInfo(
    val id: String,
    val tag: String,
    val version: String,
    val displayName: String,
    val assetName: String,
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String,
    val signingCertificateSha256: String,
    val supportedAbis: List<String>,
    val minAndroidApi: Int,
    val officialSignatureVerified: Boolean,
    val provenance: String,
    val warning: String
)

object PrivateVersionCatalogParser {
    const val EXPECTED_REPOSITORY = "hesta-lang/Dash-Launcher-Private-Storage"
    private val versionPattern = Regex("^[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?$")
    private val tagPattern = Regex("^[A-Za-z0-9._-]{1,128}$")
    private val assetPattern = Regex("^[A-Za-z0-9._-]+\\.apk$")
    private val hashPattern = Regex("^[a-fA-F0-9]{64}$")
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    fun parse(json: String): List<PrivateVersionInfo> {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("schemaVersion")?.asInt == 1) {
            "La versión del manifiesto privado no es compatible."
        }
        require(root.get("repository")?.asString == EXPECTED_REPOSITORY) {
            "El manifiesto apunta a un repositorio privado distinto del configurado."
        }
        val items = root.getAsJsonArray("versions")
            ?: throw IllegalArgumentException("El manifiesto privado no contiene versiones.")
        require(items.size() <= 500) { "El manifiesto privado contiene demasiadas versiones." }

        val seenIds = mutableSetOf<String>()
        val parsed = items.map { element ->
            require(element.isJsonObject) { "La entrada de versión no es un objeto." }
            val item = element.asJsonObject
            val id = requiredString(item, "id")
            require(ID_PATTERN.matches(id) && seenIds.add(id)) {
                "El manifiesto contiene un ID inválido o duplicado."
            }
            val tag = requiredString(item, "tag")
            require(tagPattern.matches(tag)) { "La etiqueta de Release no es válida." }
            val version = requiredString(item, "version")
            require(versionPattern.matches(version)) { "El número de versión no es válido." }
            val displayName = requiredString(item, "displayName")
            val assetName = requiredString(item, "assetName")
            require(assetPattern.matches(assetName)) { "El nombre del APK no es válido." }
            val size = requiredLong(item, "sizeBytes")
            require(size in 1L..MAX_APK_SIZE_BYTES) { "El tamaño declarado del APK no es válido." }
            val sha256 = requiredString(item, "sha256")
            require(hashPattern.matches(sha256)) { "El SHA-256 del APK no es válido." }
            val packageName = requiredString(item, "packageName")
            require(packageName == GAME_PACKAGE) {
                "El paquete declarado no corresponde a Geometry Dash."
            }
            val signer = requiredString(item, "signingCertificateSha256")
            require(hashPattern.matches(signer)) { "La huella del certificado no es válida." }
            val abisJson = item.getAsJsonArray("supportedAbis")
                ?: throw IllegalArgumentException("Falta la lista de arquitecturas ABI.")
            val abis = abisJson.map { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    "La lista ABI contiene un valor no válido."
                }
                value.asString
            }.distinct()
            require(abis.isNotEmpty() && abis.all { it in supportedAbis }) {
                "La lista ABI está vacía o contiene arquitecturas no admitidas."
            }
            val minAndroidApi = requiredLong(item, "minAndroidApi").toInt()
            require(minAndroidApi in 23..100) { "El Android API mínimo no es válido." }

            PrivateVersionInfo(
                id = id,
                tag = tag,
                version = version,
                displayName = displayName,
                assetName = assetName,
                sizeBytes = size,
                sha256 = sha256.lowercase(),
                packageName = packageName,
                signingCertificateSha256 = signer.lowercase(),
                supportedAbis = abis,
                minAndroidApi = minAndroidApi,
                officialSignatureVerified = requiredBoolean(item, "officialSignatureVerified"),
                provenance = requiredString(item, "provenance"),
                warning = requiredString(item, "warning")
            )
        }
        return parsed.sortedWith(compareByDescending<PrivateVersionInfo> { it.version })
    }

    private fun requiredString(
        item: com.google.gson.JsonObject,
        key: String
    ): String {
        val value = item.get(key)
            ?: throw IllegalArgumentException("Falta el campo $key.")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "El campo $key debe ser texto."
        }
        return value.asString.trim().also {
            require(it.isNotEmpty()) { "El campo $key no puede estar vacío." }
        }
    }

    private fun requiredLong(item: com.google.gson.JsonObject, key: String): Long {
        val value = item.get(key)
            ?: throw IllegalArgumentException("Falta el campo $key.")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "El campo $key debe ser numérico."
        }
        return value.asLong.also {
            require(value.asDouble == it.toDouble()) { "El campo $key debe ser entero." }
        }
    }

    private fun requiredBoolean(item: com.google.gson.JsonObject, key: String): Boolean {
        val value = item.get(key)
            ?: throw IllegalArgumentException("Falta el campo $key.")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
            "El campo $key debe ser booleano."
        }
        return value.asBoolean
    }

    private const val GAME_PACKAGE = "com.robtopx.geometryjump"
    private const val MAX_APK_SIZE_BYTES = 1024L * 1024L * 1024L
    private val ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,96}$")
}
