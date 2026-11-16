package com.hestalang.dashlauncher.private_storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivateVersionCatalogParserTest {
    private val validCatalog = """
        {
          "schemaVersion": 1,
          "repository": "hesta-lang/Dash-Launcher-Private-Storage",
          "versions": [{
            "id": "gd-2.2091-import",
            "tag": "gd-2.2091-import",
            "version": "2.2091",
            "displayName": "Geometry Dash 2.2091",
            "assetName": "Geometry.Dash.2.2091.Android.apk",
            "sizeBytes": 174650508,
            "sha256": "780eb29421c9eb4eee068ca195b132b8fe96a7473342fe67e23e7f4a6e245fb0",
            "packageName": "com.robtopx.geometryjump",
            "signingCertificateSha256": "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc",
            "supportedAbis": ["arm64-v8a"],
            "minAndroidApi": 23,
            "officialSignatureVerified": false,
            "provenance": "Private import",
            "warning": "Official signer not verified"
          }]
        }
    """.trimIndent()

    @Test
    fun parsesValidatedPrivateVersion() {
        val versions = PrivateVersionCatalogParser.parse(validCatalog)
        assertEquals(1, versions.size)
        assertEquals("2.2091", versions.single().version)
        assertEquals("Geometry.Dash.2.2091.Android.apk", versions.single().assetName)
        assertFalse(versions.single().officialSignatureVerified)
    }

    @Test
    fun rejectsAnotherRepository() {
        val invalid = validCatalog.replace(
            "hesta-lang/Dash-Launcher-Private-Storage",
            "someone/other-repository"
        )
        assertThrows(IllegalArgumentException::class.java) {
            PrivateVersionCatalogParser.parse(invalid)
        }
    }

    @Test
    fun rejectsMalformedSha256() {
        val invalid = validCatalog.replace(
            "780eb29421c9eb4eee068ca195b132b8fe96a7473342fe67e23e7f4a6e245fb0",
            "bad-hash"
        )
        assertThrows(IllegalArgumentException::class.java) {
            PrivateVersionCatalogParser.parse(invalid)
        }
    }
}
