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
    val packageName: String,
    val versionName: String?,
    val versionCode: Long
)

enum class InstallRequestResult {
    PERMISSION_REQUIRED,
    INSTALLER_OPENED
}

/**
 * Creates profile-specific Geometry Dash APKs and asks Android to install them.
 * Android still owns the final installation confirmation; this class never uninstalls apps.
 */
class GeometryDashInstallManager(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    // Keep APK parsing/signing libraries out of the cold-start path. They are only needed
    // after the user explicitly creates or updates a profile installation.
    private val cloner by lazy(LazyThreadSafetyMode.NONE) { ProfileApkCloner() }

    fun installedGameInfo(): InstalledGeometryDash? =
        installedPackageInfo(GAME_PACKAGE)

    fun installedPackageInfo(packageName: String): InstalledGeometryDash? {
        val info = runCatching {
            getInstalledPackageInfo(packageName)
        }.getOrNull() ?: return null

        return InstalledGeometryDash(
            packageName = packageName,
            versionName = info.versionName,
            versionCode = info.toLongVersionCodeCompat()
        )
    }

    fun isPackageInstalled(packageName: String): Boolean =
        installedPackageInfo(packageName) != null

    fun launchInstalledGame() = launchGame(GAME_PACKAGE)

    fun launchGame(packageName: String) {
        require(PACKAGE_PATTERN.matches(packageName)) {
            "El identificador de la instancia no es válido."
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException(
                "Android no encontró una actividad para iniciar la instancia $packageName."
            )
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)
    }

    /**
     * Rewrites and signs a source APK for the supplied profile package identity.
     * The source is left untouched and the output is kept inside the profile directory.
     */
    fun buildProfileInstance(
        sourceApk: File,
        outputApk: File,
        packageName: String,
        displayLabel: String
    ): ProfileApkCloneResult = cloner.build(
        sourceApk = sourceApk,
        outputApk = outputApk,
        newPackageName = packageName,
        displayLabel = displayLabel
    )

    fun openInstaller(apkFile: File): InstallRequestResult =
        openInstallerForPackage(apkFile, GAME_PACKAGE)

    fun openInstallerForPackage(
        apkFile: File,
        expectedPackageName: String
    ): InstallRequestResult {
        require(apkFile.isFile && apkFile.canRead()) {
            "El APK de esta instancia no existe o no se puede leer."
        }
        require(PACKAGE_PATTERN.matches(expectedPackageName)) {
            "El identificador esperado no es válido."
        }

        val archiveInfo = getArchivePackageInfo(apkFile)
            ?: throw IllegalArgumentException("Android no pudo leer el APK generado.")
        require(archiveInfo.packageName == expectedPackageName) {
            "El paquete del APK generado no coincide. Esperado: " +
                expectedPackageName + "; detectado: " + archiveInfo.packageName + "."
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + appContext.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(permissionIntent)
            return InstallRequestResult.PERMISSION_REQUIRED
        }

        val apkUri = FileProvider.getUriForFile(
            appContext,
            appContext.packageName + ".files",
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

    private fun getInstalledPackageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
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
        private val PACKAGE_PATTERN =
            Regex("^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }
}
