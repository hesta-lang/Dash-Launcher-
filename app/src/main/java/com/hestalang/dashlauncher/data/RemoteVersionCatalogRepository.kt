package com.hestalang.dashlauncher.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class CatalogChannelSnapshot(
    val channel: String,
    val versions: List<CatalogVersionRecord>,
    val isUsingCache: Boolean,
    val warning: String?
)

data class CatalogMetadataSnapshot(
    val metadata: CatalogVersionMetadata,
    val isUsingCache: Boolean,
    val warning: String?
)

/**
 * Loads channel indexes first, then fetches one version's metadata only when
 * the user opens it. Documents are cached with HTTP validators and can be used
 * as a read-only fallback while the device is offline.
 */
class RemoteVersionCatalogRepository(
    context: Context,
    private val manifestUrl: String = DEFAULT_MANIFEST_URL
) {
    private val cacheDirectory = File(context.applicationContext.cacheDir, "version-catalog")

    fun loadChannel(channel: String, forceRefresh: Boolean = false): CatalogChannelSnapshot {
        require(channel in CHANNELS) { "Canal no reconocido." }

        val manifest = loadDocument(
            url = manifestUrl,
            forceRefresh = forceRefresh,
            parser = CatalogJsonParser::parseManifest
        )
        val indexPath = manifest.value.versionFiles[channel]
            ?: throw IllegalStateException("El manifiesto no publica el canal $channel.")
        val indexUrl = CatalogJsonParser.resolveIndexUrl(manifestUrl, indexPath)
        val index = loadDocument(
            url = indexUrl,
            forceRefresh = forceRefresh
        ) { body ->
            CatalogJsonParser.parseIndex(body, channel, indexUrl, manifestUrl)
        }

        val warnings = listOfNotNull(manifest.warning, index.warning)
            .distinct()
            .joinToString(" ")
            .ifBlank { null }

        return CatalogChannelSnapshot(
            channel = channel,
            versions = index.value,
            isUsingCache = manifest.isUsingCache || index.isUsingCache,
            warning = warnings
        )
    }

    fun loadMetadata(
        record: CatalogVersionRecord,
        forceRefresh: Boolean = false
    ): CatalogMetadataSnapshot {
        val result = loadDocument(
            url = record.metadataUrl,
            forceRefresh = forceRefresh
        ) { body ->
            CatalogJsonParser.parseMetadata(body, record)
        }
        return CatalogMetadataSnapshot(result.value, result.isUsingCache, result.warning)
    }

    private fun <T> loadDocument(
        url: String,
        forceRefresh: Boolean,
        parser: (String) -> T
    ): CachedValue<T> {
        val cacheKey = sha256(url)
        val bodyFile = File(cacheDirectory, "$cacheKey.json")
        val etagFile = File(cacheDirectory, "$cacheKey.etag")
        val modifiedFile = File(cacheDirectory, "$cacheKey.modified")
        val cachedBody = readCachedBody(bodyFile)

        var connection: HttpURLConnection? = null
        try {
            require(url.startsWith("https://", ignoreCase = true)) {
                "El catálogo solo admite conexiones HTTPS."
            }
            cacheDirectory.mkdirs()
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DashLauncher-Android")
                if (!forceRefresh) {
                    if (etagFile.isFile) {
                        etagFile.readText().takeIf { it.isNotBlank() }?.let {
                            setRequestProperty("If-None-Match", it)
                        }
                    }
                    if (modifiedFile.isFile) {
                        modifiedFile.readText().takeIf { it.isNotBlank() }?.let {
                            setRequestProperty("If-Modified-Since", it)
                        }
                    }
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                val body = cachedBody
                    ?: throw IOException("El servidor respondió 304, pero no existe una copia local.")
                return CachedValue(parser(body), true, null)
            }
            if (responseCode !in 200..299) {
                throw IOException("El servidor del catálogo respondió HTTP $responseCode.")
            }

            val body = connection.inputStream.use { readLimited(it) }
            val parsed = parser(body)

            val temporaryFile = File(cacheDirectory, "$cacheKey.tmp")
            temporaryFile.writeText(body, Charsets.UTF_8)
            if (!temporaryFile.renameTo(bodyFile)) {
                bodyFile.writeText(body, Charsets.UTF_8)
                temporaryFile.delete()
            }

            connection.getHeaderField("ETag")?.let { etagFile.writeText(it, Charsets.UTF_8) }
                ?: etagFile.delete()
            connection.getHeaderField("Last-Modified")?.let {
                modifiedFile.writeText(it, Charsets.UTF_8)
            } ?: modifiedFile.delete()

            return CachedValue(parsed, false, null)
        } catch (error: Exception) {
            if (cachedBody != null) {
                val cachedValue = runCatching { parser(cachedBody) }.getOrNull()
                if (cachedValue != null) {
                    return CachedValue(
                        cachedValue,
                        true,
                        "No se pudo actualizar el catálogo; se está usando la copia guardada en este dispositivo."
                    )
                }
            }
            throw IllegalStateException(
                error.message ?: "No se pudo obtener el catálogo de versiones.",
                error
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun readCachedBody(file: File): String? {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_DOCUMENT_BYTES) {
            return null
        }
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    private fun readLimited(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > MAX_DOCUMENT_BYTES) {
                throw IOException("El documento del catálogo supera el límite permitido.")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private data class CachedValue<T>(
        val value: T,
        val isUsingCache: Boolean,
        val warning: String?
    )

    companion object {
        const val DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/hesta-lang/Dash-Launcher-/main/catalog/manifest.json"
        private val CHANNELS = setOf("stable", "beta", "archived")
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
    }
}
