package com.hestalang.dashlauncher.private_storage

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.google.gson.Gson

data class PrivateDownloadSnapshot(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: Int,
    val reason: Int?
)

data class ActivePrivateDownload(val id: Long, val version: PrivateVersionInfo)

/** System DownloadManager owns the transfer and notification while the app is backgrounded. */
class PrivateApkDownloadManager(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val repository = GitHubPrivateStorageRepository(appContext)

    fun enqueue(session: String, version: PrivateVersionInfo): Long {
        val signedUrl = repository.resolveDownloadUrl(session, version)
        val filename = "dash-${version.id}-${System.currentTimeMillis()}.apk"
        val request = DownloadManager.Request(Uri.parse(signedUrl))
            .setTitle(version.displayName)
            .setDescription("Descargando Geometry Dash ${version.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, filename)
        val id = downloadManager.enqueue(request)
        val persisted = preferences.edit()
            .putString(VERSION_KEY_PREFIX + id, gson.toJson(version))
            .putLong(ACTIVE_ID_KEY, id)
            .putString(RESULT_KEY, "Descarga iniciada en el gestor de Android.")
            .commit()
        if (!persisted) {
            downloadManager.remove(id)
            throw IllegalStateException("No se pudo guardar el estado de la descarga.")
        }
        // Covers the rare race where DownloadManager finishes before the completion receiver
        // can find the metadata saved above.
        val initialSnapshot = query(id)
        if (initialSnapshot?.status == DownloadManager.STATUS_SUCCESSFUL ||
            initialSnapshot?.status == DownloadManager.STATUS_FAILED
        ) {
            scheduleVerification(id)
        }
        return id
    }

    fun activeDownload(): ActivePrivateDownload? {
        val id = preferences.getLong(ACTIVE_ID_KEY, NO_DOWNLOAD)
        if (id == NO_DOWNLOAD) return null
        val json = preferences.getString(VERSION_KEY_PREFIX + id, null) ?: return null
        return runCatching { ActivePrivateDownload(id, gson.fromJson(json, PrivateVersionInfo::class.java)) }.getOrNull()
    }

    fun query(id: Long): PrivateDownloadSnapshot? {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reasonColumn = it.getColumnIndex(DownloadManager.COLUMN_REASON)
            val reason = if (reasonColumn >= 0) it.getInt(reasonColumn) else null
            return PrivateDownloadSnapshot(downloaded, total, status, reason)
        }
    }

    fun scheduleVerification(id: Long) {
        if (!preferences.contains(VERSION_KEY_PREFIX + id)) return
        val request = androidx.work.OneTimeWorkRequestBuilder<PrivateApkDownloadWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putLong(PrivateApkDownloadWorker.KEY_DOWNLOAD_ID, id)
                    .build()
            )
            .build()
        androidx.work.WorkManager.getInstance(appContext).enqueueUniqueWork(
            verificationWorkName(id),
            androidx.work.ExistingWorkPolicy.KEEP,
            request
        )
        if (verificationStage(id) == null) {
            setVerificationStage(id, "Esperando al verificador de APK…")
        }
    }

    fun setVerificationStage(id: Long, stage: String) {
        preferences.edit().putString(VERIFICATION_STAGE_PREFIX + id, stage).apply()
    }

    fun verificationStage(id: Long): String? =
        preferences.getString(VERIFICATION_STAGE_PREFIX + id, null)

    fun clearVerificationStage(id: Long) {
        preferences.edit().remove(VERIFICATION_STAGE_PREFIX + id).apply()
    }

    fun verificationWorkName(id: Long): String = "private-apk-verification-$id"

    fun resultMessage(): String? = preferences.getString(RESULT_KEY, null)

    companion object {
        const val PREFERENCES = "private_apk_downloads"
        const val ACTIVE_ID_KEY = "active_download_id"
        const val RESULT_KEY = "last_result"
        const val NO_DOWNLOAD = -1L
        const val VERSION_KEY_PREFIX = "version_json_"
        const val VERIFICATION_STAGE_PREFIX = "verification_stage_"
    }
}
