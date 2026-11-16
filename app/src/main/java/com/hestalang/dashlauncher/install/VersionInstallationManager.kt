package com.hestalang.dashlauncher.install

import java.io.File
import java.util.Locale

data class PreparedVersionInstallation(
    val apkFile: File,
    val packageName: String,
    val warnings: List<String>
)

/**
 * Creates one independently installable package for each catalog game version.
 * The verified source APK remains untouched in versions/<version>/game.apk.
 */
class VersionInstallationManager(
    private val cloner: ProfileApkCloner = ProfileApkCloner()
) {
    fun prepare(
        sourceApk: File,
        version: String,
        outputDirectory: File
    ): PreparedVersionInstallation {
        require(sourceApk.isFile && sourceApk.canRead()) {
            "No se encontró el APK original verificado de Geometry Dash $version."
        }
        require(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "No se pudo crear la carpeta de instalación de Geometry Dash $version."
        }

        val packageName = packageNameForVersion(version)
        val token = versionToken(version)
        val outputApk = File(outputDirectory, "geometry-dash-$token.apk")
        val result = cloner.build(
            sourceApk = sourceApk,
            outputApk = outputApk,
            newPackageName = packageName,
            displayLabel = "Geometry Dash $version"
        )

        return PreparedVersionInstallation(
            apkFile = result.outputApk,
            packageName = result.installedPackageName,
            warnings = result.warnings
        )
    }

    companion object {
        private val VERSION_PATTERN =
            Regex("^[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?$")

        fun packageNameForVersion(version: String): String {
            require(version.length <= 32 && VERSION_PATTERN.matches(version)) {
                "El número de versión no es válido para crear una instalación independiente."
            }
            return "com.hestalang.dashlauncher.gd.v" + versionToken(version)
        }

        private fun versionToken(version: String): String =
            version.lowercase(Locale.ROOT).map { char ->
                if (char in 'a'..'z' || char in '0'..'9') char else '_'
            }.joinToString("")
    }
}
