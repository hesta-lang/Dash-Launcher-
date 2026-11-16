package com.hestalang.dashlauncher.storage

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class VersionStorageMetadata(
    val schemaVersion: Int = 1,
    val version: String,
    val displayName: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val apkFileName: String = "game.apk",
    val apkSizeBytes: Long? = null,
    val apkSha256: String? = null,
    val packageName: String? = null,
    val signingCertificateSha256: String? = null
)

data class LocalVersionRecord(
    val version: String,
    val displayName: String,
    val directory: File,
    val apkFile: File,
    val profileDirectory: File,
    val hasApk: Boolean,
    val apkSizeBytes: Long?,
    val apkSha256: String?
)

data class VersionStorageSnapshot(
    val rootDirectory: File,
    val versions: List<LocalVersionRecord>
)

/**
 * Owns the app-specific versions/ directory.
 *
 * On Android this resolves to:
 * /storage/emulated/0/Android/data/com.hestalang.dashlauncher/files/versions/
 *
 * The external app-specific directory is preferred for large APKs. If it is
 * unavailable, the manager falls back to the app's internal files directory.
 * No broad storage permission is needed.
 */
class VersionStorageManager internal constructor(
    private val rootDirectory: File,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(resolveRootDirectory(context))

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun initialize(): VersionStorageSnapshot {
        requireRootDirectory()
        return VersionStorageSnapshot(rootDirectory, listVersions())
    }

    fun ensureVersion(version: String, displayName: String): LocalVersionRecord {
        validateVersion(version)
        require(displayName.isNotBlank()) { "El nombre visible de la versión no puede estar vacío." }
        requireRootDirectory()

        val versionDirectory = File(rootDirectory, version)
        if (!versionDirectory.exists() && !versionDirectory.mkdirs()) {
            throw IllegalStateException("No se pudo crear el directorio de Geometry Dash $version.")
        }
        check(versionDirectory.isDirectory) {
            "La ruta de Geometry Dash $version no es un directorio."
        }

        val profileDirectory = File(versionDirectory, "profile")
        if (!profileDirectory.exists() && !profileDirectory.mkdirs()) {
            throw IllegalStateException("No se pudo crear el perfil de Geometry Dash $version.")
        }

        val existing = readMetadata(versionDirectory)
        val now = currentTimeMillis()
        val metadata = if (existing == null) {
            VersionStorageMetadata(
                version = version,
                displayName = displayName.trim(),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now
            )
        } else {
            existing.copy(
                displayName = displayName.trim(),
                updatedAtEpochMillis = now
            )
        }
        writeMetadata(versionDirectory, metadata)
        return toRecord(versionDirectory, metadata)
    }

    /**
     * Copies an already-verified APK into this version's private folder.
     * The copy is independently checked before replacing an existing game.apk,
     * so a partial or mismatched download cannot destroy a previously stored APK.
     * The input stream is consumed and closed by this method.
     */
    fun storeVerifiedApk(
        version: String,
        displayName: String,
        input: InputStream,
        expectedSizeBytes: Long,
        expectedSha256: String,
        packageName: String,
        signingCertificateSha256: String
    ): LocalVersionRecord {
        require(expectedSizeBytes > 0L) { "El tamaño esperado del APK no es válido." }
        require(SHA256_PATTERN.matches(expectedSha256)) {
            "El SHA-256 esperado debe contener 64 caracteres hexadecimales."
        }
        require(packageName == OFFICIAL_GAME_PACKAGE) {
            "El APK no declara el paquete oficial esperado de Geometry Dash."
        }
        require(SHA256_PATTERN.matches(signingCertificateSha256)) {
            "La huella del certificado de firma no es válida."
        }

        val record = ensureVersion(version, displayName)
        val temporaryFile = File.createTempFile("game-apk-", ".part", record.directory)
        var copiedBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            input.use { source ->
                FileOutputStream(temporaryFile).use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        target.write(buffer, 0, read)
                        copiedBytes += read
                    }
                    target.fd.sync()
                }
            }

            require(copiedBytes == expectedSizeBytes) {
                "El tamaño del APK copiado no coincide con el catálogo."
            }
            val actualSha256 = digest.digest().toHexString()
            require(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                "El SHA-256 del APK copiado no coincide con el catálogo."
            }

            moveReplacing(temporaryFile, record.apkFile)
            val previousMetadata = readMetadata(record.directory)
            val now = currentTimeMillis()
            val metadata = (previousMetadata ?: VersionStorageMetadata(
                version = version,
                displayName = displayName.trim(),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now
            )).copy(
                displayName = displayName.trim(),
                updatedAtEpochMillis = now,
                apkFileName = "game.apk",
                apkSizeBytes = copiedBytes,
                apkSha256 = actualSha256,
                packageName = packageName,
                signingCertificateSha256 = signingCertificateSha256.lowercase()
            )
            writeMetadata(record.directory, metadata)
            return toRecord(record.directory, metadata)
        } finally {
            temporaryFile.delete()
        }
    }

    fun listVersions(): List<LocalVersionRecord> {
        if (!rootDirectory.exists() || !rootDirectory.isDirectory) return emptyList()

        return rootDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && VERSION_PATTERN.matches(it.name) }
            .mapNotNull { directory ->
                val metadata = readMetadata(directory) ?: return@mapNotNull null
                if (metadata.version != directory.name) return@mapNotNull null
                toRecord(directory, metadata)
            }
            .sortedWith(compareBy<LocalVersionRecord> { it.version })
            .toList()
    }

    private fun requireRootDirectory() {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw IllegalStateException("No se pudo crear el almacenamiento local de versiones.")
        }
        check(rootDirectory.isDirectory) {
            "La ruta de almacenamiento de versiones existe, pero no es un directorio."
        }
    }

    private fun readMetadata(directory: File): VersionStorageMetadata? {
        val metadataFile = File(directory, METADATA_FILE_NAME)
        if (!metadataFile.isFile) return null
        return runCatching {
            FileInputStream(metadataFile).bufferedReader(Charsets.UTF_8).use { reader ->
                gson.fromJson(reader, VersionStorageMetadata::class.java)
            }
        }.getOrNull()?.takeIf {
            it.schemaVersion == METADATA_SCHEMA_VERSION &&
                VERSION_PATTERN.matches(it.version) &&
                it.apkFileName == "game.apk"
        }
    }

    private fun writeMetadata(directory: File, metadata: VersionStorageMetadata) {
        val target = File(directory, METADATA_FILE_NAME)
        val temporary = File(directory, "$METADATA_FILE_NAME.tmp")
        temporary.writeText(gson.toJson(metadata), Charsets.UTF_8)
        moveReplacing(temporary, target)
    }

    private fun toRecord(
        directory: File,
        metadata: VersionStorageMetadata
    ): LocalVersionRecord {
        val apkFile = File(directory, metadata.apkFileName)
        return LocalVersionRecord(
            version = metadata.version,
            displayName = metadata.displayName,
            directory = directory,
            apkFile = apkFile,
            profileDirectory = File(directory, "profile"),
            hasApk = apkFile.isFile,
            apkSizeBytes = if (apkFile.isFile) apkFile.length() else metadata.apkSizeBytes,
            apkSha256 = metadata.apkSha256
        )
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun validateVersion(version: String) {
        require(VERSION_PATTERN.matches(version)) {
            "El número de versión no es válido. Se esperaba un valor como 2.2081."
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val OFFICIAL_GAME_PACKAGE = "com.robtopx.geometryjump"
        private const val METADATA_SCHEMA_VERSION = 1
        private const val METADATA_FILE_NAME = "metadata.json"
        private val VERSION_PATTERN =
            Regex("^[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?$")
        private val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")

        private fun resolveRootDirectory(context: Context): File {
            val appContext = context.applicationContext
            val appSpecificExternalFiles = appContext.getExternalFilesDir(null)
            val baseDirectory = appSpecificExternalFiles ?: appContext.filesDir
            return File(baseDirectory, "versions")
        }
    }
}
