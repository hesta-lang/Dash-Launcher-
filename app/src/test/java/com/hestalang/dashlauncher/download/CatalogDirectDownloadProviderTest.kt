package com.hestalang.dashlauncher.download

import com.hestalang.dashlauncher.data.VersionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDirectDownloadProviderTest {
    private val provider = CatalogDirectDownloadProvider()
    private val validHash = "a".repeat(64)

    private fun entry(
        url: String? = "https://downloads.example.test/geometry-dash.apk",
        size: Long? = 1024L,
        hash: String? = validHash,
        id: String = "2.2081"
    ) = VersionEntry(
        id = id,
        displayName = "Geometry Dash",
        version = "2.2081",
        channel = "archived",
        description = "Test fixture only",
        minAndroidApi = 23,
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        downloadUrl = url,
        sizeBytes = size,
        sha256 = hash
    )

    @Test
    fun acceptsCompleteHttpsArtifact() {
        val result = provider.resolve(entry())
        assertTrue(result is DownloadResolution.Available)
        val artifact = (result as DownloadResolution.Available).artifact
        assertEquals("geometry-dash-2-2081.apk", artifact.fileName)
        assertEquals(validHash, artifact.expectedSha256)
    }

    @Test
    fun rejectsMissingUrl() {
        val result = provider.resolve(entry(url = null))
        assertTrue(result is DownloadResolution.Unavailable)
    }

    @Test
    fun rejectsHttpUrl() {
        val result = provider.resolve(entry(url = "http://downloads.example.test/file.apk"))
        assertTrue(result is DownloadResolution.Unavailable)
    }

    @Test
    fun rejectsMissingFileSize() {
        val result = provider.resolve(entry(size = null))
        assertTrue(result is DownloadResolution.Unavailable)
    }

    @Test
    fun rejectsInvalidSha256() {
        val result = provider.resolve(entry(hash = "not-a-hash"))
        assertTrue(result is DownloadResolution.Unavailable)
    }

    @Test
    fun rejectsUrlWithEmbeddedCredentials() {
        val result = provider.resolve(
            entry(url = "https://user:password@downloads.example.test/file.apk")
        )
        assertTrue(result is DownloadResolution.Unavailable)
    }

    @Test
    fun sanitizesVersionIdForDestinationFileName() {
        val result = provider.resolve(entry(id = "../version/2.2081"))
        assertTrue(result is DownloadResolution.Available)
        val filename = (result as DownloadResolution.Available).artifact.fileName
        assertTrue(filename.matches(Regex("^geometry-dash-[a-z0-9_-]+\\.apk$")))
        assertTrue(!filename.contains('/'))
    }
}
