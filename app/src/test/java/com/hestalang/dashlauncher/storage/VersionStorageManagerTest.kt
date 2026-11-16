package com.hestalang.dashlauncher.storage

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionStorageManagerTest {
    @Test
    fun createsSharedVersionLibraryAndSiblingInstanceRoot() {
        val root = Files.createTempDirectory("dash-storage-").toFile()
        try {
            val manager = VersionStorageManager(root)

            val first = manager.ensureVersion("2.2081", "Geometry Dash 2.2081")
            val second = manager.ensureVersion("2.2091", "Geometry Dash 2.2091")

            assertTrue(root.isDirectory)
            assertEquals(File(root, "versions"), manager.rootDirectory)
            assertTrue(manager.rootDirectory.isDirectory)
            assertEquals(File(root, "instances"), manager.instancesDirectory)
            assertTrue(manager.instancesDirectory.isDirectory)
            assertEquals(File(root, "cache"), manager.cacheDirectory)
            assertTrue(manager.cacheDirectory.isDirectory)
            assertTrue(first.directory.isDirectory)
            assertFalse(first.profileDirectory.exists())
            assertTrue(second.directory.isDirectory)
            assertTrue(File(first.directory, "metadata.json").isFile)
            assertTrue(File(second.directory, "metadata.json").isFile)
            assertFalse(first.directory == second.directory)
            assertEquals(listOf("2.2081", "2.2091"), manager.initialize().versions.map { it.version })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathTraversalAndInvalidVersionNames() {
        val root = Files.createTempDirectory("dash-versions-").toFile()
        try {
            val manager = VersionStorageManager(root)

            assertThrows(IllegalArgumentException::class.java) {
                manager.ensureVersion("../outside", "Invalid version")
            }
            assertThrows(IllegalArgumentException::class.java) {
                manager.ensureVersion("2.2081/other", "Invalid version")
            }
            assertFalse(File(root, "versions/../outside").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storesOnlyApkBytesMatchingExpectedSizeAndSha256() {
        val root = Files.createTempDirectory("dash-versions-").toFile()
        try {
            val manager = VersionStorageManager(root)
            val apkBytes = "verified-apk-content".toByteArray()
            val sha256 = sha256(apkBytes)

            val stored = manager.storeVerifiedApk(
                version = "2.2081",
                displayName = "Geometry Dash 2.2081",
                input = ByteArrayInputStream(apkBytes),
                expectedSizeBytes = apkBytes.size.toLong(),
                expectedSha256 = sha256,
                packageName = VersionStorageManager.OFFICIAL_GAME_PACKAGE,
                signingCertificateSha256 = "b".repeat(64)
            )

            assertTrue(stored.hasApk)
            assertEquals(apkBytes.size.toLong(), stored.apkFile.length())
            assertEquals(sha256, stored.apkSha256)
            assertEquals(
                apkBytes.toList(),
                stored.apkFile.readBytes().toList()
            )
            assertEquals("game.apk", stored.apkFile.name)
            assertTrue(stored.apkFile.absolutePath.contains("/versions/2.2081/"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedCopyDoesNotReplaceAnExistingVerifiedApk() {
        val root = Files.createTempDirectory("dash-versions-").toFile()
        try {
            val manager = VersionStorageManager(root)
            val original = "original-apk".toByteArray()
            val originalSha = sha256(original)
            val initial = manager.storeVerifiedApk(
                version = "2.2081",
                displayName = "Geometry Dash 2.2081",
                input = ByteArrayInputStream(original),
                expectedSizeBytes = original.size.toLong(),
                expectedSha256 = originalSha,
                packageName = VersionStorageManager.OFFICIAL_GAME_PACKAGE,
                signingCertificateSha256 = "c".repeat(64)
            )

            val changed = "unverified-apk".toByteArray()
            assertThrows(IllegalArgumentException::class.java) {
                manager.storeVerifiedApk(
                    version = "2.2081",
                    displayName = "Geometry Dash 2.2081",
                    input = ByteArrayInputStream(changed),
                    expectedSizeBytes = changed.size.toLong(),
                    expectedSha256 = "0".repeat(64),
                    packageName = VersionStorageManager.OFFICIAL_GAME_PACKAGE,
                    signingCertificateSha256 = "c".repeat(64)
                )
            }

            assertEquals(original.toList(), initial.apkFile.readBytes().toList())
            assertEquals(originalSha, manager.listVersions().single().apkSha256)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
