package com.hestalang.dashlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogJsonParserTest {
    private val manifestUrl =
        "https://raw.githubusercontent.com/hesta-lang/Dash-Launcher-/main/catalog/manifest.json"
    private val indexUrl =
        "https://raw.githubusercontent.com/hesta-lang/Dash-Launcher-/main/catalog/versions/stable.json"

    private val manifestJson = """
        {
          "catalogVersion": 1,
          "catalogId": "dash-launcher",
          "minLauncherVersion": "0.1.0",
          "versionFiles": {
            "stable": "versions/stable.json",
            "beta": "versions/beta.json",
            "archived": "versions/archived.json"
          }
        }
    """.trimIndent()

    private val indexJson = """
        [{
          "id": "gd-2.2081-arm64",
          "displayName": "Geometry Dash",
          "version": "2.2081",
          "channel": "stable",
          "platform": "android",
          "minAndroidApi": 23,
          "releasedAt": null,
          "changelog": null,
          "metadata": "../metadata/gd-2.2081-arm64.json"
        }]
    """.trimIndent()

    private val record = CatalogJsonParser.parseIndex(
        indexJson,
        "stable",
        indexUrl,
        manifestUrl
    ).single()

    @Test
    fun parsesManifestAndResolvesChannelIndex() {
        val manifest = CatalogJsonParser.parseManifest(manifestJson)
        assertEquals("dash-launcher", manifest.catalogId)
        assertEquals(
            "https://raw.githubusercontent.com/hesta-lang/Dash-Launcher-/main/catalog/versions/stable.json",
            CatalogJsonParser.resolveIndexUrl(manifestUrl, manifest.versionFiles.getValue("stable"))
        )
    }

    @Test
    fun parsesVersionIndexAndResolvesMetadataRelativeToIndex() {
        assertEquals("gd-2.2081-arm64", record.id)
        assertEquals(
            "https://raw.githubusercontent.com/hesta-lang/Dash-Launcher-/main/catalog/metadata/gd-2.2081-arm64.json",
            record.metadataUrl
        )
    }

    @Test
    fun rejectsMetadataPathOutsideCatalogRoot() {
        val unsafeIndex = indexJson.replace("../metadata/gd-2.2081-arm64.json", "../../outside.json")
        assertThrows(IllegalArgumentException::class.java) {
            CatalogJsonParser.parseIndex(unsafeIndex, "stable", indexUrl, manifestUrl)
        }
    }

    @Test
    fun rejectsIndexEntryWithWrongChannel() {
        val wrongChannel = indexJson.replace("\"stable\"", "\"beta\"")
        assertThrows(IllegalArgumentException::class.java) {
            CatalogJsonParser.parseIndex(wrongChannel, "stable", indexUrl, manifestUrl)
        }
    }

    @Test
    fun acceptsMetadataWithoutAnArtifactButKeepsItNonDownloadable() {
        val json = """
            {
              "id": "gd-2.2081-arm64",
              "description": "No authorized APK source has been configured.",
              "platform": "android",
              "minAndroidApi": 23,
              "supportedAbis": ["arm64-v8a"],
              "artifact": null
            }
        """.trimIndent()

        val metadata = CatalogJsonParser.parseMetadata(json, record)
        assertNull(metadata.artifact)
        assertTrue(metadata.supportedAbis.contains("arm64-v8a"))
    }

    @Test
    fun acceptsOnlyCompleteVerifiedDirectArtifacts() {
        val json = """
            {
              "id": "gd-2.2081-arm64",
              "description": "Test metadata.",
              "platform": "android",
              "minAndroidApi": 23,
              "supportedAbis": ["arm64-v8a"],
              "artifact": {
                "provider": "direct",
                "url": "https://downloads.example.test/gd.apk",
                "sizeBytes": 1024,
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              }
            }
        """.trimIndent()

        val metadata = CatalogJsonParser.parseMetadata(json, record)
        assertEquals("direct", metadata.artifact?.providerId)
        assertEquals(1024L, metadata.artifact?.sizeBytes)
    }

    @Test
    fun rejectsDirectArtifactWithoutIntegrityData() {
        val json = """
            {
              "id": "gd-2.2081-arm64",
              "description": "Incomplete metadata.",
              "platform": "android",
              "minAndroidApi": 23,
              "supportedAbis": ["arm64-v8a"],
              "artifact": {
                "provider": "direct",
                "url": "https://downloads.example.test/gd.apk"
              }
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            CatalogJsonParser.parseMetadata(json, record)
        }
    }

    @Test
    fun rejectsVersionMetadataForAnotherId() {
        val json = """
            {
              "id": "another-version",
              "description": "",
              "platform": "android",
              "minAndroidApi": 23,
              "supportedAbis": ["arm64-v8a"],
              "artifact": null
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            CatalogJsonParser.parseMetadata(json, record)
        }
    }

    @Test
    fun rejectsUnsupportedAbi() {
        val json = """
            {
              "id": "gd-2.2081-arm64",
              "description": "",
              "platform": "android",
              "minAndroidApi": 23,
              "supportedAbis": ["mips"],
              "artifact": null
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            CatalogJsonParser.parseMetadata(json, record)
        }
    }
}
