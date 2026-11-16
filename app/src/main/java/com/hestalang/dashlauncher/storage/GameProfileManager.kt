package com.hestalang.dashlauncher.storage

import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

data class GameProfileMetadata(
    val schemaVersion: Int = 2,
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val version: String? = null,
    val gameDataBackupFileName: String? = null,
    val gameDataBackupSizeBytes: Long? = null,
    val gameDataBackupSha256: String? = null
)

data class GameProfileRecord(
    val id: String,
    val version: String,
    val name: String,
    /** The instance's own directory under instances/. */
    val directory: File,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val hasGameDataBackup: Boolean,
    val backupSizeBytes: Long?,
    val backupSha256: String?
)

/**
 * Manages Prism-style, named Geometry Dash instances.
 *
 * Shared game APKs remain in versions/<version>/game.apk. Each version/profile pair
 * gets its own instances/<instance-id>/ directory, its own stable Android package
 * identity, and therefore its own Android-managed private app-data sandbox.
 *
 * The launcher-side directory stores instance metadata and exported backups only.
 * It cannot directly access another app's /data/user/0 sandbox. ZIP backups are
 * opaque until a supported in-game companion bridge is available.
 */
class GameProfileManager(
    private val versionStorageManager: VersionStorageManager,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun initialize(version: String, displayName: String = "Geometry Dash $version"): List<GameProfileRecord> {
        val localVersion = versionStorageManager.ensureVersion(version, displayName)
        ensureDirectory(versionStorageManager.instancesDirectory)
        migrateLegacyProfiles(version, localVersion.profileDirectory)

        var profiles = listProfilesFromRoot(version)
        if (profiles.isEmpty()) {
            val now = currentTimeMillis()
            writeMetadata(
                instanceDirectory(version, DEFAULT_PROFILE_ID),
                GameProfileMetadata(
                    id = DEFAULT_PROFILE_ID,
                    name = DEFAULT_PROFILE_NAME,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    version = version
                )
            )
            profiles = listProfilesFromRoot(version)
        }

        val activeFile = activeProfileFile(localVersion.directory)
        val legacyActiveFile = File(localVersion.profileDirectory, ACTIVE_PROFILE_FILE)
        val activeId = when {
            activeFile.isFile -> activeFile.readText(Charsets.UTF_8).trim()
            legacyActiveFile.isFile -> legacyActiveFile.readText(Charsets.UTF_8).trim()
            else -> ""
        }
        if (profiles.none { it.id == activeId }) {
            writeActiveProfile(localVersion.directory, profiles.first().id)
        } else if (!activeFile.isFile) {
            // Lift the old per-version pointer into the new instance-library layout.
            writeActiveProfile(localVersion.directory, activeId)
        }
        return profiles
    }

    /**
     * Stable Android package identity for one version/instance pair.
     * The editable instance name never affects the installed package identity.
     */
    fun packageNameForProfile(version: String, profileId: String): String {
        validateVersion(version)
        require(PROFILE_ID_PATTERN.matches(profileId)) {
            "El identificador de la instancia no es válido."
        }
        val versionToken = version.toByteArray(Charsets.UTF_8)
            .joinToString("") { byte -> "%02x".format(byte) }
        val profileToken = profileId.toByteArray(Charsets.UTF_8)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "com.hestalang.dashlauncher.gd.v" + versionToken + ".p" + profileToken
    }

    /** Unique, path-safe folder ID for the shared instance library. */
    fun instanceStorageId(version: String, profileId: String): String {
        validateVersion(version)
        require(PROFILE_ID_PATTERN.matches(profileId)) {
            "El identificador de la instancia no es válido."
        }
        val versionToken = version.toByteArray(Charsets.UTF_8)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "v" + versionToken + "--" + profileId
    }

    fun listProfiles(version: String): List<GameProfileRecord> {
        val localVersion = versionStorageManager.ensureVersion(version, "Geometry Dash $version")
        ensureDirectory(versionStorageManager.instancesDirectory)
        migrateLegacyProfiles(version, localVersion.profileDirectory)
        return listProfilesFromRoot(version)
    }

    fun activeProfileId(version: String): String {
        val profiles = initialize(version)
        val localVersion = versionStorageManager.ensureVersion(version, "Geometry Dash $version")
        val activeFile = activeProfileFile(localVersion.directory)
        val selected = if (activeFile.isFile) activeFile.readText(Charsets.UTF_8).trim() else ""
        return selected.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
    }

    fun createProfile(version: String, name: String): GameProfileRecord {
        val normalizedName = normalizeName(name)
        val existing = listProfiles(version)
        require(existing.none { it.name.equals(normalizedName, ignoreCase = true) }) {
            "Ya existe una instancia con ese nombre para Geometry Dash $version."
        }

        val id = UUID.randomUUID().toString()
        val now = currentTimeMillis()
        val directory = instanceDirectory(version, id)
        writeMetadata(
            directory,
            GameProfileMetadata(
                id = id,
                name = normalizedName,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                version = version
            )
        )
        if (existing.isEmpty()) {
            val localVersion = versionStorageManager.ensureVersion(version, "Geometry Dash $version")
            writeActiveProfile(localVersion.directory, id)
        }
        return toRecord(directory, readMetadata(directory)!!)
    }

    fun activateProfile(version: String, profileId: String): GameProfileRecord {
        val localVersion = versionStorageManager.ensureVersion(version, "Geometry Dash $version")
        val instance = findProfile(version, profileId)
        writeActiveProfile(localVersion.directory, instance.id)
        return instance
    }

    fun renameProfile(version: String, profileId: String, newName: String): GameProfileRecord {
        val normalizedName = normalizeName(newName)
        val existing = listProfiles(version)
        require(existing.none {
            it.id != profileId && it.name.equals(normalizedName, ignoreCase = true)
        }) {
            "Ya existe otra instancia con ese nombre."
        }
        val instance = findProfile(version, profileId)
        val metadata = readMetadata(instance.directory) ?: error("No se pudo leer la instancia.")
        val updated = metadata.copy(name = normalizedName, updatedAtEpochMillis = currentTimeMillis())
        writeMetadata(instance.directory, updated)
        return toRecord(instance.directory, updated)
    }

    fun deleteProfile(version: String, profileId: String) {
        val profiles = listProfiles(version)
        require(profiles.size > 1) { "No puedes eliminar la última instancia de una versión." }
        val instance = profiles.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("La instancia seleccionada ya no existe.")
        require(instance.directory.deleteRecursively()) { "No se pudo eliminar la instancia." }

        val localVersion = versionStorageManager.ensureVersion(version, "Geometry Dash $version")
        val activeFile = activeProfileFile(localVersion.directory)
        if (activeFile.isFile && activeFile.readText(Charsets.UTF_8).trim() == profileId) {
            val fallback = profiles.first { it.id != profileId }
            writeActiveProfile(localVersion.directory, fallback.id)
        }
    }

    /**
     * Stores an opaque ZIP backup in this instance's backups/ directory. It validates
     * archive paths and size limits but does not apply the archive to the running game.
     */
    fun importGameDataBackup(
        version: String,
        profileId: String,
        input: InputStream
    ): GameProfileRecord {
        val instance = findProfile(version, profileId)
        val backupsDirectory = File(instance.directory, BACKUPS_DIRECTORY)
        ensureDirectory(backupsDirectory)
        val temporary = File.createTempFile("game-data-", ".part", backupsDirectory)
        val target = File(backupsDirectory, BACKUP_FILE_NAME)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        require(copied <= MAX_BACKUP_BYTES) {
                            "El respaldo supera el límite de 512 MiB."
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(copied > 0L) { "El archivo de respaldo está vacío." }
            validateZip(temporary)
            moveReplacing(temporary, target)

            val metadata = readMetadata(instance.directory) ?: error("No se pudo leer la instancia.")
            val updated = metadata.copy(
                updatedAtEpochMillis = currentTimeMillis(),
                gameDataBackupFileName = BACKUP_RELATIVE_PATH,
                gameDataBackupSizeBytes = copied,
                gameDataBackupSha256 = digest.digest().toHexString()
            )
            writeMetadata(instance.directory, updated)
            return toRecord(instance.directory, updated)
        } finally {
            temporary.delete()
        }
    }

    fun exportGameDataBackup(version: String, profileId: String, output: OutputStream) {
        val instance = findProfile(version, profileId)
        val backup = File(instance.directory, BACKUP_RELATIVE_PATH)
        require(backup.isFile) { "Esta instancia todavía no contiene un archivo de respaldo." }
        FileInputStream(backup).use { input -> input.copyTo(output) }
        output.flush()
    }

    private fun findProfile(version: String, profileId: String): GameProfileRecord {
        require(PROFILE_ID_PATTERN.matches(profileId)) { "El ID de la instancia no es válido." }
        return listProfiles(version)
            .firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("La instancia seleccionada no existe.")
    }

    private fun listProfilesFromRoot(version: String): List<GameProfileRecord> {
        val root = versionStorageManager.instancesDirectory
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name.startsWith("v") }
            .mapNotNull { directory ->
                val metadata = readMetadata(directory) ?: return@mapNotNull null
                if (metadata.version != version ||
                    metadata.id != directory.name.substringAfter("--", missingDelimiterValue = "") ||
                    directory.name != instanceStorageId(version, metadata.id)
                ) return@mapNotNull null
                toRecord(directory, metadata)
            }
            .sortedWith(compareBy<GameProfileRecord> { it.createdAtEpochMillis }.thenBy { it.name })
            .toList()
    }

    private fun readMetadata(directory: File): GameProfileMetadata? {
        val file = File(directory, INSTANCE_METADATA_FILE)
        if (!file.isFile || file.length() > MAX_METADATA_BYTES) return null
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).use {
                gson.fromJson(it, GameProfileMetadata::class.java)
            }
        }.getOrNull()?.takeIf {
            it.schemaVersion == INSTANCE_SCHEMA_VERSION &&
                it.version != null &&
                VERSION_PATTERN.matches(it.version) &&
                PROFILE_ID_PATTERN.matches(it.id) &&
                it.name.isNotBlank() &&
                (it.gameDataBackupFileName == null ||
                    it.gameDataBackupFileName == BACKUP_RELATIVE_PATH)
        }
    }

    private fun readLegacyMetadata(directory: File): GameProfileMetadata? {
        val file = File(directory, LEGACY_METADATA_FILE)
        if (!file.isFile || file.length() > MAX_METADATA_BYTES) return null
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).use {
                gson.fromJson(it, GameProfileMetadata::class.java)
            }
        }.getOrNull()?.takeIf {
            it.schemaVersion == LEGACY_PROFILE_SCHEMA_VERSION &&
                PROFILE_ID_PATTERN.matches(it.id) &&
                it.id == directory.name &&
                it.name.isNotBlank() &&
                (it.gameDataBackupFileName == null || it.gameDataBackupFileName == LEGACY_BACKUP_FILE_NAME)
        }
    }

    /**
     * One-time, non-destructive migration from versions/<version>/profile/profiles.
     * The old files are retained until the user chooses to clean them up, so a failed
     * migration cannot erase an older profile or its backup.
     */
    private fun migrateLegacyProfiles(version: String, legacyProfileDirectory: File) {
        val legacyRoot = File(legacyProfileDirectory, LEGACY_PROFILES_DIRECTORY)
        if (!legacyRoot.isDirectory) return
        ensureDirectory(versionStorageManager.instancesDirectory)

        legacyRoot.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && PROFILE_ID_PATTERN.matches(it.name) }
            .forEach { legacyDirectory ->
                val legacyMetadata = readLegacyMetadata(legacyDirectory) ?: return@forEach
                val target = instanceDirectory(version, legacyMetadata.id)
                if (target.exists()) {
                    val current = readMetadata(target)
                    check(current?.version == version && current.id == legacyMetadata.id) {
                        "Existe una carpeta de instancia incompatible; no se modificó para proteger sus datos."
                    }
                    return@forEach
                }

                val temporary = File(
                    versionStorageManager.instancesDirectory,
                    ".migration-" + instanceStorageId(version, legacyMetadata.id)
                )
                if (temporary.exists()) temporary.deleteRecursively()
                ensureDirectory(temporary)

                try {
                    val legacyApk = File(legacyDirectory, INSTANCE_APK_FILE_NAME)
                    if (legacyApk.isFile) {
                        Files.copy(legacyApk.toPath(), File(temporary, INSTANCE_APK_FILE_NAME).toPath())
                    }

                    val legacyBackup = File(legacyDirectory, LEGACY_BACKUP_FILE_NAME)
                    val backupDirectory = File(temporary, BACKUPS_DIRECTORY)
                    if (legacyBackup.isFile) {
                        ensureDirectory(backupDirectory)
                        Files.copy(
                            legacyBackup.toPath(),
                            File(backupDirectory, BACKUP_FILE_NAME).toPath()
                        )
                    }

                    val migratedMetadata = legacyMetadata.copy(
                        schemaVersion = INSTANCE_SCHEMA_VERSION,
                        version = version,
                        gameDataBackupFileName = if (legacyBackup.isFile) BACKUP_RELATIVE_PATH else null
                    )
                    writeMetadata(temporary, migratedMetadata)
                    try {
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: Exception) {
                        Files.move(temporary.toPath(), target.toPath())
                    }
                } catch (error: Exception) {
                    temporary.deleteRecursively()
                    throw IllegalStateException(
                        "No se pudo migrar la instancia antigua ${legacyMetadata.name}; los archivos anteriores se conservaron.",
                        error
                    )
                }
            }
    }

    private fun writeMetadata(directory: File, metadata: GameProfileMetadata) {
        ensureDirectory(directory)
        require(metadata.version != null && VERSION_PATTERN.matches(metadata.version))
        val target = File(directory, INSTANCE_METADATA_FILE)
        val temporary = File(directory, "$INSTANCE_METADATA_FILE.tmp")
        temporary.writeText(gson.toJson(metadata), Charsets.UTF_8)
        moveReplacing(temporary, target)
    }

    private fun toRecord(directory: File, metadata: GameProfileMetadata): GameProfileRecord {
        val backup = File(directory, BACKUP_RELATIVE_PATH)
        return GameProfileRecord(
            id = metadata.id,
            version = metadata.version ?: "",
            name = metadata.name,
            directory = directory,
            createdAtEpochMillis = metadata.createdAtEpochMillis,
            updatedAtEpochMillis = metadata.updatedAtEpochMillis,
            hasGameDataBackup = backup.isFile,
            backupSizeBytes = if (backup.isFile) backup.length() else metadata.gameDataBackupSizeBytes,
            backupSha256 = metadata.gameDataBackupSha256
        )
    }

    private fun instanceDirectory(version: String, profileId: String): File =
        File(versionStorageManager.instancesDirectory, instanceStorageId(version, profileId))

    private fun activeProfileFile(versionDirectory: File) =
        File(versionDirectory, ACTIVE_PROFILE_FILE)

    private fun writeActiveProfile(versionDirectory: File, profileId: String) {
        require(PROFILE_ID_PATTERN.matches(profileId))
        val target = activeProfileFile(versionDirectory)
        val temporary = File(versionDirectory, "$ACTIVE_PROFILE_FILE.tmp")
        temporary.writeText(profileId, Charsets.UTF_8)
        moveReplacing(temporary, target)
    }

    private fun validateZip(file: File) {
        var entriesSeen = 0
        var uncompressedBytes = 0L
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entriesSeen++
                require(entriesSeen <= MAX_BACKUP_ENTRIES) {
                    "El respaldo contiene demasiados archivos."
                }
                val normalized = entry.name.replace('\\', '/')
                require(!normalized.startsWith("/") &&
                    normalized.split('/').none { it == ".." || it == "." }) {
                    "El respaldo contiene una ruta de archivo no permitida."
                }
                if (!entry.isDirectory) {
                    val size = entry.size
                    if (size >= 0L) {
                        uncompressedBytes += size
                        require(uncompressedBytes <= MAX_UNCOMPRESSED_BACKUP_BYTES) {
                            "El contenido descomprimido del respaldo es demasiado grande."
                        }
                    }
                }
            }
        }
    }

    private fun validateVersion(version: String) {
        require(VERSION_PATTERN.matches(version)) {
            "El número de versión no es válido. Se esperaba un valor como 2.2081."
        }
    }

    private fun normalizeName(value: String): String =
        value.trim().also {
            require(it.isNotEmpty() && it.length <= MAX_PROFILE_NAME_LENGTH) {
                "El nombre de la instancia debe tener entre 1 y $MAX_PROFILE_NAME_LENGTH caracteres."
            }
        }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists()) {
            check(directory.mkdirs()) { "No se pudo crear el directorio de la instancia." }
        }
        check(directory.isDirectory) { "La ruta de la instancia no es un directorio." }
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
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val INSTANCE_SCHEMA_VERSION = 2
        private const val LEGACY_PROFILE_SCHEMA_VERSION = 1
        private const val INSTANCE_METADATA_FILE = "instance.json"
        private const val LEGACY_METADATA_FILE = "profile.json"
        private const val LEGACY_PROFILES_DIRECTORY = "profiles"
        private const val ACTIVE_PROFILE_FILE = "active-profile.txt"
        private const val INSTANCE_APK_FILE_NAME = "instance.apk"
        private const val BACKUPS_DIRECTORY = "backups"
        private const val BACKUP_FILE_NAME = "game-data.zip"
        private const val BACKUP_RELATIVE_PATH = "backups/game-data.zip"
        private const val LEGACY_BACKUP_FILE_NAME = "game-data.zip"
        private const val MAX_METADATA_BYTES = 64 * 1024L
        private const val MAX_BACKUP_BYTES = 512L * 1024L * 1024L
        private const val MAX_UNCOMPRESSED_BACKUP_BYTES = 1024L * 1024L * 1024L
        private const val MAX_BACKUP_ENTRIES = 100_000
        private const val MAX_PROFILE_NAME_LENGTH = 32
        private val VERSION_PATTERN = Regex("^[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?$")
        private val PROFILE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
