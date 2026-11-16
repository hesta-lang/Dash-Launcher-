package com.hestalang.dashlauncher.download

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.database.Cursor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

enum class DownloadStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    UNKNOWN
}

data class DownloadSnapshot(
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reasonCode: Int?
)

sealed interface DownloadVerification {
    data class Verified(val uri: Uri) : DownloadVerification
    data class Rejected(val reason: String) : DownloadVerification
}

/**
 * Uses Android's DownloadManager for background transfer and verifies the APK
 * before it can be treated as a trusted artifact. It does not install the APK.
 */
class ApkDownloadCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun enqueue(artifact: DownloadArtifact): Long {
        val validationError = DownloadArtifactValidator.validationError(
            artifact.url,
            artifact.expectedSizeBytes,
            artifact.expectedSha256,
            artifact.expectedPackageName,
            artifact.expectedSigningCertificateSha256,
            artifact.supportedAbis
        )
        require(validationError == null) { validationError ?: "Invalid download artifact" }
        require(artifact.fileName.matches(Regex("^geometry-dash-[a-z0-9_-]+\\.apk$"))) {
            "El nombre de archivo no es válido."
        }

        val request = DownloadManager.Request(Uri.parse(artifact.url))
            .setTitle("Geometry Dash")
            .setDescription("Descargando " + artifact.versionLabel)
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                artifact.fileName
            )

        return downloadManager.enqueue(request)
    }

    fun query(downloadId: Long): DownloadSnapshot? {
        val cursor: Cursor = downloadManager.query(
            DownloadManager.Query().setFilterById(downloadId)
        ) ?: return null

        return cursor.use {
            if (!it.moveToFirst()) return@use null

            val statusValue = it.getInt(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            )
            val downloaded = it.getLong(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            )
            val total = it.getLong(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            )
            val reasonIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)
            val reason = if (reasonIndex >= 0) it.getInt(reasonIndex) else null

            val status = when (statusValue) {
                DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                else -> DownloadStatus.UNKNOWN
            }

            DownloadSnapshot(status, downloaded, total, reason)
        }
    }

    fun verifyCompletedDownload(
        downloadId: Long,
        artifact: DownloadArtifact
    ): DownloadVerification {
        val uri = downloadManager.getUriForDownloadedFile(downloadId)
            ?: return reject(downloadId, "Android no entregó el archivo descargado.")

        var temporaryApk: File? = null
        try {
            temporaryApk = File.createTempFile(
                "dash-launcher-verify-",
                ".apk",
                appContext.cacheDir
            )
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            val input = appContext.contentResolver.openInputStream(uri)
                ?: return reject(downloadId, "Dash Launcher no pudo abrir el archivo descargado.")

            input.use { source ->
                FileOutputStream(temporaryApk).use { target ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        target.write(buffer, 0, read)
                        byteCount += read
                    }
                    target.flush()
                }
            }

            if (byteCount != artifact.expectedSizeBytes) {
                return reject(
                    downloadId,
                    "El tamaño descargado no coincide con el catálogo. El archivo se eliminó."
                )
            }

            val actualHash = digest.digest().joinToString("") { byte ->
                "%02x".format(byte)
            }
            if (!actualHash.equals(artifact.expectedSha256, ignoreCase = true)) {
                return reject(
                    downloadId,
                    "El SHA-256 no coincide. El archivo se eliminó por seguridad."
                )
            }

            val archiveError = verifyApkIdentityAndAbi(temporaryApk, artifact)
            if (archiveError != null) {
                return reject(downloadId, archiveError)
            }

            return DownloadVerification.Verified(uri)
        } catch (error: Exception) {
            return reject(
                downloadId,
                error.message ?: "No se pudo verificar el APK descargado."
            )
        } finally {
            temporaryApk?.delete()
        }
    }

    private fun verifyApkIdentityAndAbi(
        apkFile: File,
        artifact: DownloadArtifact
    ): String? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = try {
            appContext.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } catch (error: Exception) {
            return "No se pudo leer la identidad interna del APK."
        } ?: return "El archivo descargado no es un APK Android válido."

        if (packageInfo.packageName != artifact.expectedPackageName) {
            return "El APK declara un paquete distinto de Geometry Dash; se eliminó por seguridad."
        }

        val signatures = currentSignatures(packageInfo)
        if (signatures.isEmpty()) {
            return "No se pudo leer el certificado de firma del APK."
        }
        val signerHashes = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
        if (signerHashes.none {
                it.equals(artifact.expectedSigningCertificateSha256, ignoreCase = true)
            }) {
            return "La firma del APK no coincide con el certificado verificado del catálogo."
        }

        val apkAbis = linkedSetOf<String>()
        try {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name.startsWith("lib/") && name.endsWith(".so")) {
                        val parts = name.split('/')
                        if (parts.size == 3 && parts[1].isNotBlank()) {
                            apkAbis.add(parts[1])
                        }
                    }
                }
            }
        } catch (error: Exception) {
            return "No se pudo inspeccionar la arquitectura incluida en el APK."
        }

        if (apkAbis.isEmpty()) {
            return "El APK no contiene bibliotecas nativas ABI verificables."
        }
        if (apkAbis != artifact.supportedAbis.toSet()) {
            return "Las arquitecturas internas del APK no coinciden con las ABI declaradas en el catálogo."
        }
        if (apkAbis.none { Build.SUPPORTED_ABIS.contains(it) }) {
            return "El APK no contiene una arquitectura compatible con este dispositivo."
        }

        return null
    }

    @Suppress("DEPRECATION")
    private fun currentSignatures(packageInfo: PackageInfo): List<Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            packageInfo.signatures?.toList().orEmpty()
        }
    }

    private fun reject(downloadId: Long, reason: String): DownloadVerification.Rejected {
        downloadManager.remove(downloadId)
        return DownloadVerification.Rejected(reason)
    }
}
