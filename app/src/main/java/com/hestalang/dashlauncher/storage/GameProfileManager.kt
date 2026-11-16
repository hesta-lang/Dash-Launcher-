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
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val gameDataBackupFileName: String? = null,
    val gameDataBackupSizeBytes: Long? = null,
    val gameDataBackupSha256: String? = null
)

data class GameProfileRecord(
    val id: String,
    val name: String,
    val directory: File,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val hasGameDataBackup: Boolean,
    val backupSizeBytes: Long?,
    val backupSha256: String?
)

/**
 * Manages named launcher-side profile containers for each stored game version.
 *
 * It deliberately does not read or write Geometry Dash's private sandbox. A future
 * Geode companion bridge must supply the actual game-data archive before a profile
 * can represent isolated in-game progress.
 */
class GameProfileManager(
    private val versionStorageManager: VersionStorageManager,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun initialize(version: String, displayName: String = "Geometry Dash $version"): List<GameProfileRecord> {
        val localVersion = versionStorageManager.ensureVersion(version, displayName)
        val profilesRoot = profilesRoot(localVersion.profileDirectory)
        ensureDirectory(profilesRoot)

        if (listProfilesFromRoot(profilesRoot).isEmpty()) {
            val now = currentTimeMillis()
            writeMetadata(
                File(profilesRoot, DEFAULT_PROFILE_ID),
                GameProfileMetadata(
                    id = DEFAULT_PROFILE_ID,
                    name = DEFAULT_PROFILE_NAME,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now
                )
            )
        }

        val profiles = listProfilesFromRoot(profilesRoot)
        val activeFile = activeProfileFile(localVersion.profileDirectory)
        val activeId = if (activeFile.isFile) activeFile.readText(Charsets.UTF_8).trim() else ""
        if (profiles.none { it.id == activeId }) {
            writeActiveProfile(localVersion.profileDirectory, profiles.first().id)
        }
        return profiles
    }

    /**
     * Stable Android package identity for one version/profile pair.
     *
     * The profile UUID, rather than its editable display name, keeps the installed app identity
     * stable when the user renames a profile. The version segment prevents different game
     * versions from replacing one another.
     */
    fun packageNameForProfile(version: String, profileId: String): String {
        validateVersion(version)
        require(PROFILE_ID_PATTERN.matches(profileId)) {
            "El identificador del perfil no es válido."
        }
        // Hex-encode UTF-8 bytes instead of replacing punctuation with underscores:
        // 2.2-alpha and 2.2+alpha must never collapse to the same installed package.
        val versionToken = version.toByteArray(Charsets.UTF_8)
            .joinToString("") { byte -> "%02x".format(byte) }
        val profileToken = profileId.toByteArray(Charsets.UTF_8)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "com.hestalang.dashlauncher.gd.v" + versionToken + ".p" + profileToken
    }

    fun listProfiles(version: String): List<GameProfileRecord> {
        val localVersion = versionStorageManager.ensureVersion(version, "Geometry Dash $version")
        ensureDirectory(profilesRoot(localVersion.profileDirectory))
        return listProfilesFromRoot(profilesRoot(localVersion.profileDirectory))
    }

    fun activeProfileId(version: String): String {
        val localVersion = versionStorageManager.ensureVersion(version, "Geometry Dash $version")
        val profiles = initialize(version)
        val activeFile = activeProfileFile(localVersion.profileDirectory)
        val selected = if (activeFile.isFile) activeFile.readText(Charsets.UTF_8).trim() else ""
        return selected.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
    }

    fun createProfile(version: String, name: String): GameProfileRecord {
        val normalizedName = normalizeName(name)
        val localVersion = versionStorageManager.ensureVersion(version, "Geometry Dash $version")
        val root = profilesRoot(localVersion.profileDirectory)
        ensureDirectory(root)
        val existing = listProfilesFromRoot(root)
        require(existing.none { it.name.equals(normalizedName, ignoreCase = true) }) {
            "Ya existe un perfil con ese nombre para Geometry Dash $version."
        }

        val id = UUID.randomUUID().toString()
        val now = currentTimeMillis()
        val directory = File(root, id)
        writeMetadata(
            directory,
            GameProfileMetadata(
                id = id,
                name = normalizedName,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now
            )
        )
        if (existing.isEmpty()) writeActiveProfile(localVersion.profileDirectory, id)
        return toRecord(directory, readMetadata(directory)!!)
    }

    fun activateProfile(version: String, profileId: String): GameProfileRecord {
        val localVersion = versionStorageManager.ensureVersion(version, "Geometry Dash $version")
        val profile = findProfile(localVersion.profileDirectory, profileId)
        writeActiveProfile(localVersion.profileDirectory, profile.id)
        return profile
    }

    fun renameProfile(version: String, profileId: String, newName: String): GameProfileRecord {
        val normalizedName = normalizeName(newName)
        val localVersion = versionStorageManager.ensureVersion(version, "Geometry Dash $version")
        val root = profilesRoot(localVersion.profileDirectory)
        val existing = listProfilesFromRoot(root)
        require(existing.none {
            it.id != profileId && it.name.equals(normalizedName, ignoreCase = true)
        }) {
            "Ya existe otro perfil con ese nombre."
        }
        val profile = findProfile(localVersion.profileDirectory, profileId)
        val directory = profile.directory
        val metadata = readMetadata(directory) ?: error("No se pudo leer el perfil.")
        val updated = metadata.copy(name = normalizedName, updatedAtEpochMillis = currentTimeMillis())
        writeMetadata(directory, updated)
        return toRecord(directory, updated)
    }

    fun deleteProfile(version: String, profileId: String) {
        val localVersion = versionStorageManager.ensureVersion(version, "Geometry Dash $version")
        val profiles = listProfilesFromRoot(profilesRoot(localVersion.profileDirectory))
        require(profiles.size > 1) { "No puedes eliminar el último perfil de una versión." }
        val profile = profiles.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("El perfil seleccionado ya no existe.")
        require(profile.directory.deleteRecursively()) { "No se pudo eliminar el perfil." }

        val activeFile = activeProfileFile(localVersion.profileDirectory)
        if (activeFile.isFile && activeFile.readText(Charsets.UTF_8).trim() == profileId) {
            val fallback = profiles.first { it.id != profileId }
            writeActiveProfile(localVersion.profileDirectory, fallback.id)
        }
    }

    /**
     * Stores an opaque ZIP backup in the selected profile. It validates the archive
     * structure and hashes the bytes, but does not apply them to the running game.
     */
    fun importGameDataBackup(
        version: String,
        profileId: String,
        input: InputStream
    ): GameProfileRecord {
        val profile = findProfile(
            versionStorageManager.ensureVersion(version, "Geometry Dash $version").profileDirectory,
            profileId
        )
        val temporary = File.createTempFile("game-data-", ".part", profile.directory)
        val target = File(profile.directory, BACKUP_FILE_NAME)
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

            val metadata = readMetadata(profile.directory) ?: error("No se pudo leer el perfil.")
            val updated = metadata.copy(
                updatedAtEpochMillis = currentTimeMillis(),
                gameDataBackupFileName = BACKUP_FILE_NAME,
                gameDataBackupSizeBytes = copied,
                gameDataBackupSha256 = digest.digest().toHexString()
            )
            writeMetadata(profile.directory, updated)
            return toRecord(profile.directory, updated)
        } finally {
            temporary.delete()
        }
    }

    fun exportGameDataBackup(version: String, profileId: String, output: OutputStream) {
        val profile = findProfile(
            versionStorageManager.ensureVersion(version, "Geometry Dash $version").profileDirectory,
            profileId
        )
        val backup = File(profile.directory, BACKUP_FILE_NAME)
        require(backup.isFile) { "Este perfil todavía no contiene un archivo de respaldo." }
        FileInputStream(backup).use { input -> input.copyTo(output) }
        output.flush()
    }

    private fun findProfile(profileDirectory: File, profileId: String): GameProfileRecord {
        require(PROFILE_ID_PATTERN.matches(profileId)) { "El ID del perfil no es válido." }
        return listProfilesFromRoot(profilesRoot(profileDirectory))
            .firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("El perfil seleccionado no existe.")
    }

    private fun listProfilesFromRoot(root: File): List<GameProfileRecord> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && PROFILE_ID_PATTERN.matches(it.name) }
            .mapNotNull { directory ->
                val metadata = readMetadata(directory) ?: return@mapNotNull null
                if (metadata.id != directory.name) return@mapNotNull null
                toRecord(directory, metadata)
            }
            .sortedWith(compareBy<GameProfileRecord> { it.createdAtEpochMillis }.thenBy { it.name })
            .toList()
    }

    private fun readMetadata(directory: File): GameProfileMetadata? {
        val file = File(directory, PROFILE_METADATA_FILE)
        if (!file.isFile || file.length() > MAX_METADATA_BYTES) return null
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).use {
                gson.fromJson(it, GameProfileMetadata::class.java)
            }
        }.getOrNull()?.takeIf {
            it.schemaVersion == PROFILE_SCHEMA_VERSION &&
                PROFILE_ID_PATTERN.matches(it.id) &&
                it.name.isNotBlank() &&
                (it.gameDataBackupFileName == null || it.gameDataBackupFileName == BACKUP_FILE_NAME)
        }
    }

    private fun writeMetadata(directory: File, metadata: GameProfileMetadata) {
        ensureDirectory(directory)
        val target = File(directory, PROFILE_METADATA_FILE)
        val temporary = File(directory, "$PROFILE_METADATA_FILE.tmp")
        temporary.writeText(gson.toJson(metadata), Charsets.UTF_8)
        moveReplacing(temporary, target)
    }

    private fun toRecord(directory: File, metadata: GameProfileMetadata): GameProfileRecord {
        val backup = File(directory, BACKUP_FILE_NAME)
        return GameProfileRecord(
            id = metadata.id,
            name = metadata.name,
            directory = directory,
            createdAtEpochMillis = metadata.createdAtEpochMillis,
            updatedAtEpochMillis = metadata.updatedAtEpochMillis,
            hasGameDataBackup = backup.isFile,
            backupSizeBytes = if (backup.isFile) backup.length() else metadata.gameDataBackupSizeBytes,
            backupSha256 = metadata.gameDataBackupSha256
        )
    }

    private fun profilesRoot(profileDirectory: File) = File(profileDirectory, "profiles")
    private fun activeProfileFile(profileDirectory: File) = File(profileDirectory, "active-profile.txt")

    private fun writeActiveProfile(profileDirectory: File, profileId: String) {
        require(PROFILE_ID_PATTERN.matches(profileId))
        val target = activeProfileFile(profileDirectory)
        val temporary = File(profileDirectory, "active-profile.tmp")
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
                "El nombre del perfil debe tener entre 1 y $MAX_PROFILE_NAME_LENGTH caracteres."
            }
        }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists()) {
            check(directory.mkdirs()) { "No se pudo crear el directorio del perfil." }
        }
        check(directory.isDirectory) { "La ruta del perfil no es un directorio." }
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
        private const val PROFILE_SCHEMA_VERSION = 1
        private const val PROFILE_METADATA_FILE = "profile.json"
        private const val DEFAULT_PROFILE_ID = "default"
        private const val DEFAULT_PROFILE_NAME = "Predeterminado"
        private const val BACKUP_FILE_NAME = "game-data.zip"
        private const val MAX_METADATA_BYTES = 64 * 1024L
        private const val MAX_BACKUP_BYTES = 512L * 1024L * 1024L
        private const val MAX_UNCOMPRESSED_BACKUP_BYTES = 1024L * 1024L * 1024L
        private const val MAX_BACKUP_ENTRIES = 100_000
        private const val MAX_PROFILE_NAME_LENGTH = 32
        private val VERSION_PATTERN = Regex("^[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?$")
        private val PROFILE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
