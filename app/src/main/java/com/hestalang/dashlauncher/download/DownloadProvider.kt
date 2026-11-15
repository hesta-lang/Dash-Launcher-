package com.hestalang.dashlauncher.download

import com.hestalang.dashlauncher.data.VersionEntry
import java.net.URI

data class DownloadArtifact(
    val url: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val fileName: String,
    val versionLabel: String
)

sealed interface DownloadResolution {
    data class Available(val artifact: DownloadArtifact) : DownloadResolution
    data class Unavailable(val reason: String) : DownloadResolution
}

interface DownloadProvider {
    fun resolve(version: VersionEntry): DownloadResolution
}

/**
 * Resolves only direct HTTPS artifacts described by the launcher's own catalog.
 * It deliberately does not scrape store pages or discover private CDN endpoints.
 */
class CatalogDirectDownloadProvider : DownloadProvider {
    override fun resolve(version: VersionEntry): DownloadResolution {
        val url = version.downloadUrl
            ?: return DownloadResolution.Unavailable(
                "Este registro todavía no incluye una URL directa HTTPS."
            )
        val size = version.sizeBytes
            ?: return DownloadResolution.Unavailable(
                "Falta el tamaño verificado del APK; la descarga permanece deshabilitada."
            )
        val hash = version.sha256
            ?: return DownloadResolution.Unavailable(
                "Falta el SHA-256 verificado del APK; la descarga permanece deshabilitada."
            )

        val validationError = DownloadArtifactValidator.validationError(url, size, hash)
        if (validationError != null) {
            return DownloadResolution.Unavailable(validationError)
        }

        val safeId = version.id
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "version" }

        return DownloadResolution.Available(
            DownloadArtifact(
                url = url,
                expectedSizeBytes = size,
                expectedSha256 = hash.lowercase(),
                fileName = "geometry-dash-$safeId.apk",
                versionLabel = version.version
            )
        )
    }
}

object DownloadArtifactValidator {
    private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")

    fun validationError(
        url: String,
        sizeBytes: Long,
        sha256: String
    ): String? {
        if (url.isBlank() || url != url.trim()) {
            return "La URL de descarga está vacía o contiene espacios no válidos."
        }

        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return "La URL de descarga no tiene un formato válido."
        }

        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return "Solo se permiten enlaces HTTPS."
        }
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.fragment != null) {
            return "La URL HTTPS no es válida o contiene credenciales/datos no permitidos."
        }
        if (sizeBytes <= 0L) {
            return "El tamaño del archivo debe estar verificado y ser mayor que cero."
        }
        if (!sha256Pattern.matches(sha256)) {
            return "El SHA-256 debe contener exactamente 64 caracteres hexadecimales."
        }

        return null
    }
}
