package com.hestalang.dashlauncher.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.database.Cursor
import java.security.MessageDigest

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
 * Uses Android's DownloadManager for background transfer and its system notification.
 * Files are stored in Dash Launcher's app-specific external downloads directory.
 */
class ApkDownloadCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun enqueue(artifact: DownloadArtifact): Long {
        val validationError = DownloadArtifactValidator.validationError(
            artifact.url,
            artifact.expectedSizeBytes,
            artifact.expectedSha256
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
            ?: return DownloadVerification.Rejected(
                "Android no entregó el archivo descargado."
            )

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            val input = appContext.contentResolver.openInputStream(uri)
                ?: return DownloadVerification.Rejected(
                    "Dash Launcher no pudo abrir el archivo descargado."
                )

            input.use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    byteCount += read
                }
            }

            if (byteCount != artifact.expectedSizeBytes) {
                downloadManager.remove(downloadId)
                return DownloadVerification.Rejected(
                    "El tamaño descargado no coincide con el catálogo. El archivo se eliminó."
                )
            }

            val actualHash = digest.digest().joinToString("") { byte ->
                "%02x".format(byte)
            }
            if (!actualHash.equals(artifact.expectedSha256, ignoreCase = true)) {
                downloadManager.remove(downloadId)
                return DownloadVerification.Rejected(
                    "El SHA-256 no coincide. El archivo se eliminó por seguridad."
                )
            }

            DownloadVerification.Verified(uri)
        } catch (error: Exception) {
            downloadManager.remove(downloadId)
            DownloadVerification.Rejected(
                error.message ?: "No se pudo verificar el APK descargado."
            )
        }
    }
}
