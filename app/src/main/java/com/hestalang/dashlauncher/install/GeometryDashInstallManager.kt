package com.hestalang.dashlauncher.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

data class InstalledGeometryDash(
    val versionName: String?,
    val versionCode: Long
)

enum class InstallRequestResult {
    PERMISSION_REQUIRED,
    INSTALLER_OPENED
}

/**
 * Bridges the launcher's private APK library with Android's package installer.
 *
 * Android still owns installation and confirmation. This class never silently
 * uninstalls an existing game or attempts to bypass signature/downgrade checks.
 */
class GeometryDashInstallManager(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun installedGameInfo(): InstalledGeometryDash? {
        val info = runCatching {
            getInstalledPackageInfo()
        }.getOrNull() ?: return null

        return InstalledGeometryDash(
            versionName = info.versionName,
            versionCode = info.toLongVersionCodeCompat()
        )
    }

    fun launchInstalledGame() {
        val launchIntent = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
            ?: throw IllegalStateException(
                "Geometry Dash está instalado, pero Android no encontró una actividad para iniciarlo."
            )
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)
    }

    fun openInstaller(apkFile: File): InstallRequestResult {
        require(apkFile.isFile && apkFile.canRead()) {
            "El APK de esta versión no existe o no se puede leer."
        }

        val archiveInfo = getArchivePackageInfo(apkFile)
            ?: throw IllegalArgumentException("Android no pudo leer el APK guardado.")
        require(archiveInfo.packageName == GAME_PACKAGE) {
            "El archivo no es Geometry Dash: paquete detectado ${archiveInfo.packageName}."
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(permissionIntent)
            return InstallRequestResult.PERMISSION_REQUIRED
        }

        val apkUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.files",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(installIntent)
        return InstallRequestResult.INSTALLER_OPENED
    }

    private fun getInstalledPackageInfo(): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                GAME_PACKAGE,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(GAME_PACKAGE, 0)
        }

    private fun getArchivePackageInfo(file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.toLongVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    companion object {
        const val GAME_PACKAGE = "com.robtopx.geometryjump"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
