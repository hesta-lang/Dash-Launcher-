package com.hestalang.dashlauncher.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProfileManagerTest {
    @Test
    fun createsActivatesRenamesAndDeletesNamedInstancesPerVersion() {
        val root = Files.createTempDirectory("dash-instance-test-").toFile()
        try {
            val storage = VersionStorageManager(root)
            val instances = GameProfileManager(storage)
            val defaults = instances.initialize("2.2091")
            assertEquals(1, defaults.size)
            assertEquals("Predeterminado", defaults.single().name)
            assertEquals("2.2091", defaults.single().version)
            assertEquals("default", instances.activeProfileId("2.2091"))
            assertTrue(defaults.single().directory.absolutePath.contains("/instances/"))
            assertTrue(File(defaults.single().directory, "instance.json").isFile)

            val second = instances.createProfile("2.2091", "Pruebas")
            instances.activateProfile("2.2091", second.id)
            assertEquals(second.id, instances.activeProfileId("2.2091"))

            instances.renameProfile("2.2091", second.id, "Desafíos")
            assertEquals("Desafíos", instances.listProfiles("2.2091").first { it.id == second.id }.name)

            instances.deleteProfile("2.2091", second.id)
            assertEquals("default", instances.activeProfileId("2.2091"))
            assertEquals(1, instances.listProfiles("2.2091").size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preventsDuplicateNamesAndDeletingLastInstance() {
        val root = Files.createTempDirectory("dash-instance-test-").toFile()
        try {
            val instances = GameProfileManager(VersionStorageManager(root))
            val default = instances.initialize("2.2081").single()
            assertThrows(IllegalArgumentException::class.java) {
                instances.createProfile("2.2081", "predeterminado")
            }
            assertThrows(IllegalArgumentException::class.java) {
                instances.deleteProfile("2.2081", default.id)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun instancesAreGloballyStoredButRemainScopedToTheirGameVersion() {
        val root = Files.createTempDirectory("dash-instance-test-").toFile()
        try {
            val instances = GameProfileManager(VersionStorageManager(root))
            val first = instances.initialize("2.2081").single()
            val second = instances.initialize("2.2091").single()
            assertEquals(first.id, second.id)
            assertFalse(first.directory == second.directory)
            assertFalse(first.directory.absolutePath == second.directory.absolutePath)

            val extra = instances.createProfile("2.2091", "Beta")
            assertEquals(1, instances.listProfiles("2.2081").size)
            assertEquals(2, instances.listProfiles("2.2091").size)
            assertTrue(extra.directory.parentFile == File(root, "instances"))
            assertEquals("2.2091", extra.version)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun migratesLegacyProfilesWithoutRemovingTheOldFiles() {
        val root = Files.createTempDirectory("dash-instance-migration-").toFile()
        try {
            val storage = VersionStorageManager(root)
            val version = storage.ensureVersion("2.2081", "Geometry Dash 2.2081")
            val legacyDirectory = File(version.profileDirectory, "profiles/default")
            assertTrue(legacyDirectory.mkdirs())
            File(legacyDirectory, "profile.json").writeText(
                """{"schemaVersion":1,"id":"default","name":"Predeterminado","createdAtEpochMillis":10,"updatedAtEpochMillis":20}"""
            )
            File(legacyDirectory, "instance.apk").writeText("cloned-apk")
            File(legacyDirectory, "game-data.zip").writeText("opaque-backup")
            File(version.profileDirectory, "active-profile.txt").writeText("default")

            val instances = GameProfileManager(storage)
            val migrated = instances.initialize("2.2081").single()

            assertEquals("default", migrated.id)
            assertEquals("2.2081", migrated.version)
            assertTrue(File(migrated.directory, "instance.json").isFile)
            assertEquals("cloned-apk", File(migrated.directory, "instance.apk").readText())
            assertEquals(
                "opaque-backup",
                File(migrated.directory, "backups/game-data.zip").readText()
            )
            assertTrue(legacyDirectory.isDirectory)
            assertTrue(File(legacyDirectory, "profile.json").isFile)
            assertEquals("default", instances.activeProfileId("2.2081"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun packageAndStorageIdsAreStableAndUniqueAcrossVersionsAndProfiles() {
        val instances = GameProfileManager(
            VersionStorageManager(Files.createTempDirectory("dash-instance-ids-").toFile())
        )
        val packageDefault = instances.packageNameForProfile("2.2081", "default")
        val packageSecond = instances.packageNameForProfile("2.2081", "profile-b")
        val packageOtherVersion = instances.packageNameForProfile("2.2091", "default")

        assertEquals(packageDefault, instances.packageNameForProfile("2.2081", "default"))
        assertFalse(packageDefault == packageSecond)
        assertFalse(packageDefault == packageOtherVersion)
        assertFalse(
            instances.instanceStorageId("2.2081", "default") ==
                instances.instanceStorageId("2.2091", "default")
        )
        assertTrue(packageDefault.startsWith("com.hestalang.dashlauncher.gd.v"))
    }
}
