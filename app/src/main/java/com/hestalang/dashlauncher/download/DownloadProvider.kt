package com.hestalang.dashlauncher.download

import com.hestalang.dashlauncher.data.VersionEntry
import java.net.URI

data class DownloadArtifact(
    val url: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val expectedPackageName: String,
    val expectedSigningCertificateSha256: String,
    val supportedAbis: List<String>,
    val fileName: String,
    val versionLabel: String
)

sealed interface DownloadResolution {
    data class Available(val artifact: DownloadArtifact) : DownloadResolution
    data class OpenExternalPage(val url: String, val providerName: String) : DownloadResolution
    data class Unavailable(val reason: String) : DownloadResolution
}

interface DownloadProvider {
    val id: String
    fun resolve(version: VersionEntry): DownloadResolution
}

/**
 * Resolves direct APK artifacts explicitly declared by the version metadata.
 * Provider identifiers form an extension point for future documented APIs.
 */
class CatalogDirectDownloadProvider : DownloadProvider {
    override val id: String = "direct"

    override fun resolve(version: VersionEntry): DownloadResolution {
        if (version.providerId != id) {
            return DownloadResolution.Unavailable(
                "El proveedor '" + version.providerId + "' todavía no está integrado en Dash Launcher."
            )
        }

        val url = version.downloadUrl
            ?: return DownloadResolution.Unavailable(
                "Esta versión todavía no tiene un enlace directo de descarga autorizado."
            )
        val size = version.sizeBytes
            ?: return DownloadResolution.Unavailable(
                "Falta el tamaño verificado del APK; la descarga permanece deshabilitada."
            )
        val hash = version.sha256
            ?: return DownloadResolution.Unavailable(
                "Falta el SHA-256 verificado del APK; la descarga permanece deshabilitada."
            )
        val packageName = version.packageName
            ?: return DownloadResolution.Unavailable(
                "Falta el identificador oficial del paquete; la descarga permanece deshabilitada."
            )
        val signerHash = version.signingCertificateSha256
            ?: return DownloadResolution.Unavailable(
                "Falta la huella del certificado de firma; la descarga permanece deshabilitada."
            )

        val validationError = DownloadArtifactValidator.validationError(
            url, size, hash, packageName, signerHash, version.supportedAbis
        )
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
                expectedPackageName = packageName,
                expectedSigningCertificateSha256 = signerHash.lowercase(),
                supportedAbis = version.supportedAbis.distinct(),
                fileName = "geometry-dash-$safeId.apk",
                versionLabel = version.version
            )
        )
    }
}

/** Opens a provider's own product/version page; it never downloads an APK itself. */
class ExternalCatalogPageProvider : DownloadProvider {
    override val id: String = "external-page"

    override fun resolve(version: VersionEntry): DownloadResolution {
        if (version.providerId != id) {
            return DownloadResolution.Unavailable(
                "El proveedor '" + version.providerId + "' todavía no está integrado en Dash Launcher."
            )
        }
        val url = version.downloadUrl
            ?: return DownloadResolution.Unavailable("Esta versión no tiene una página externa configurada.")
        val providerName = version.providerName?.takeIf { it.isNotBlank() }
            ?: return DownloadResolution.Unavailable("Falta el nombre del proveedor externo.")
        val error = DownloadArtifactValidator.externalPageValidationError(url)
        if (error != null) return DownloadResolution.Unavailable(error)
        return DownloadResolution.OpenExternalPage(url, providerName)
    }
}

class DownloadProviderRegistry(providers: List<DownloadProvider>) {
    private val providersById = providers.associateBy { it.id }

    fun resolve(version: VersionEntry): DownloadResolution =
        providersById[version.providerId]?.resolve(version)
            ?: DownloadResolution.Unavailable(
                "El proveedor '" + version.providerId + "' no está disponible o todavía no está autorizado."
            )
}

object DownloadArtifactValidator {
    private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")

    fun externalPageValidationError(url: String): String? {
        if (url.isBlank() || url != url.trim()) {
            return "La página del proveedor está vacía o contiene espacios no válidos."
        }
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return "La página del proveedor no tiene un formato válido."
        }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.fragment != null) {
            return "La página del proveedor debe ser una URL HTTPS válida."
        }
        val allowedHosts = setOf(
            "play.google.com",
            "uoldown.com",
            "www.uoldown.com",
            "apkcombo.com",
            "www.apkcombo.com"
        )
        if (uri.host.lowercase() !in allowedHosts) {
            return "El dominio de este proveedor todavía no está en la lista permitida."
        }
        return null
    }

    fun validationError(
        url: String,
        sizeBytes: Long,
        sha256: String,
        packageName: String?,
        signingCertificateSha256: String?,
        supportedAbis: List<String>
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
        if (packageName != "com.robtopx.geometryjump") {
            return "El APK no declara el paquete oficial de Geometry Dash."
        }
        if (signingCertificateSha256.isNullOrBlank() || !sha256Pattern.matches(signingCertificateSha256)) {
            return "La huella SHA-256 del certificado de firma no está verificada."
        }
        val knownAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        if (supportedAbis.isEmpty() || supportedAbis.any { it !in knownAbis } || supportedAbis.distinct().size != supportedAbis.size) {
            return "El APK debe declarar una lista válida y no vacía de arquitecturas ABI."
        }

        return null
    }
}
