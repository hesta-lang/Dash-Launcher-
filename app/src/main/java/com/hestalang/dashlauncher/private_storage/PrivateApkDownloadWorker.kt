package com.hestalang.dashlauncher.private_storage

import android.app.DownloadManager
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.hestalang.dashlauncher.storage.VersionStorageManager
import java.io.File
import java.io.FileOutputStream

/** Verifies and stores a completed system-managed private APK download. */
class PrivateApkDownloadWorker(appContext: Context, parameters: WorkerParameters) : Worker(appContext, parameters) {
    override fun doWork(): androidx.work.ListenableWorker.Result {
        val id = inputData.getLong(KEY_DOWNLOAD_ID, PrivateApkDownloadManager.NO_DOWNLOAD)
        if (id == PrivateApkDownloadManager.NO_DOWNLOAD) return androidx.work.ListenableWorker.Result.failure()
        val preferences = applicationContext.getSharedPreferences(PrivateApkDownloadManager.PREFERENCES, Context.MODE_PRIVATE)
        val json = preferences.getString(PrivateApkDownloadManager.VERSION_KEY_PREFIX + id, null)
            ?: return fail(preferences, id, "No se encontró la información de la versión descargada.")
        val version = runCatching { Gson().fromJson(json, PrivateVersionInfo::class.java) }
            .getOrElse { return fail(preferences, id, "No se pudo leer la información de la versión descargada.") }
        val downloadManager = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val snapshot = queryStatus(downloadManager, id)
            ?: return fail(preferences, id, "Android ya no encuentra esta descarga.")
        if (snapshot.first != DownloadManager.STATUS_SUCCESSFUL) {
            downloadManager.remove(id)
            return fail(preferences, id, "Android no pudo completar la descarga (código ${snapshot.second}).")
        }

        val temporary = File.createTempFile("dash-private-verify-", ".apk", applicationContext.cacheDir)
        try {
            val descriptor = downloadManager.openDownloadedFile(id)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            GitHubPrivateStorageRepository(applicationContext).verifyDownloadedApk(temporary, version)
            val stored = VersionStorageManager(applicationContext).storeVerifiedApk(
                version = version.version,
                displayName = version.displayName,
                input = temporary.inputStream(),
                expectedSizeBytes = version.sizeBytes,
                expectedSha256 = version.sha256,
                packageName = version.packageName,
                signingCertificateSha256 = version.signingCertificateSha256
            )
            preferences.edit()
                .remove(PrivateApkDownloadManager.VERSION_KEY_PREFIX + id)
                .remove(PrivateApkDownloadManager.ACTIVE_ID_KEY)
                .putString(PrivateApkDownloadManager.RESULT_KEY, "APK verificado y guardado en ${stored.apkFile.absolutePath}.")
                .commit()
            downloadManager.remove(id)
            return androidx.work.ListenableWorker.Result.success()
        } catch (error: Exception) {
            return fail(preferences, id, error.message ?: "No se pudo verificar ni guardar el APK descargado.")
        } finally {
            temporary.delete()
        }
    }

    private fun queryStatus(manager: DownloadManager, id: Long): Pair<Int, Int>? {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to
                it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        }
    }

    private fun fail(preferences: android.content.SharedPreferences, id: Long, message: String): androidx.work.ListenableWorker.Result {
        preferences.edit()
            .remove(PrivateApkDownloadManager.VERSION_KEY_PREFIX + id)
            .remove(PrivateApkDownloadManager.ACTIVE_ID_KEY)
            .putString(PrivateApkDownloadManager.RESULT_KEY, message)
            .commit()
        return androidx.work.ListenableWorker.Result.failure()
    }

    companion object { const val KEY_DOWNLOAD_ID = "private_download_id" }
}
