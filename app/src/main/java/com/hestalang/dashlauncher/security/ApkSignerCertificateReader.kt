package com.hestalang.dashlauncher.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Reads APK signer certificates using PackageManager first, then falls back
 * to the v1 JAR-signature certificate blocks (META-INF/*.RSA, *.DSA, *.EC).
 *
 * The fallback is useful on Android builds where getPackageArchiveInfo()
 * returns PackageInfo but leaves signingInfo/signatures empty.
 */
object ApkSignerCertificateReader {
    fun readCertificates(
        context: Context,
        apkFile: File,
        packageInfo: PackageInfo
    ): List<ByteArray> {
        val packageManager = context.applicationContext.packageManager
        val platformSignatures = signaturesFrom(packageInfo).ifEmpty {
            readLegacySignatures(packageManager, apkFile)
        }
        if (platformSignatures.isNotEmpty()) {
            return platformSignatures.map { it.toByteArray() }.distinctBy { it.sha256Key() }
        }

        return readV1Certificates(apkFile)
    }

    @Suppress("DEPRECATION")
    private fun signaturesFrom(info: PackageInfo): List<Signature> {
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else emptyList()
        return modern.ifEmpty { info.signatures?.toList().orEmpty() }
    }

    @Suppress("DEPRECATION")
    private fun readLegacySignatures(
        packageManager: PackageManager,
        apkFile: File
    ): List<Signature> {
        val info = runCatching {
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
        }.getOrNull() ?: return emptyList()
        return info.signatures?.toList().orEmpty()
    }

    private fun readV1Certificates(apkFile: File): List<ByteArray> {
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = LinkedHashMap<String, ByteArray>()
        ZipFile(apkFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.uppercase(Locale.ROOT)
                if (!name.startsWith("META-INF/") ||
                    !(name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")) ||
                    entry.isDirectory || entry.size > MAX_CERT_BLOCK_BYTES
                ) continue

                val bytes = zip.getInputStream(entry).use { input ->
                    input.readBytes()
                }
                val parsed = runCatching {
                    factory.generateCertificates(ByteArrayInputStream(bytes))
                        .filterIsInstance<X509Certificate>()
                        .map { it.encoded }
                }.getOrDefault(emptyList())

                parsed.forEach { encoded -> certificates.putIfAbsent(encoded.sha256Key(), encoded) }
            }
        }
        return certificates.values.toList()
    }

    private fun ByteArray.sha256Key(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val MAX_CERT_BLOCK_BYTES = 2L * 1024L * 1024L
}
