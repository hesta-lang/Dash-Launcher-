package com.hestalang.dashlauncher.private_storage

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Schedules verification even if Dash Launcher is no longer open. */
class PrivateApkDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id <= 0L) return
        val preferences = context.getSharedPreferences(PrivateApkDownloadManager.PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.contains(PrivateApkDownloadManager.VERSION_KEY_PREFIX + id)) return
        PrivateApkDownloadManager(context).scheduleVerification(id)
    }
}
