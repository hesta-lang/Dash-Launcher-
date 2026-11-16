package com.hestalang.dashlauncher.storage

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProfileManagerTest {
    @Test
    fun createsActivatesRenamesAndDeletesNamedProfilesPerVersion() {
        val root = Files.createTempDirectory("dash-profile-test-").toFile()
        try {
            val storage = VersionStorageManager(root)
            val profiles = GameProfileManager(storage)
            val defaults = profiles.initialize("2.2091")
            assertEquals(1, defaults.size)
            assertEquals("Predeterminado", defaults.single().name)
            assertEquals("default", profiles.activeProfileId("2.2091"))

            val second = profiles.createProfile("2.2091", "Pruebas")
            profiles.activateProfile("2.2091", second.id)
            assertEquals(second.id, profiles.activeProfileId("2.2091"))

            profiles.renameProfile("2.2091", second.id, "Desafíos")
            assertEquals("Desafíos", profiles.listProfiles("2.2091").first { it.id == second.id }.name)

            profiles.deleteProfile("2.2091", second.id)
            assertEquals("default", profiles.activeProfileId("2.2091"))
            assertEquals(1, profiles.listProfiles("2.2091").size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preventsDuplicateNamesAndDeletingLastProfile() {
        val root = Files.createTempDirectory("dash-profile-test-").toFile()
        try {
            val profiles = GameProfileManager(VersionStorageManager(root))
            val default = profiles.initialize("2.2081").single()
            assertThrows(IllegalArgumentException::class.java) {
                profiles.createProfile("2.2081", "predeterminado")
            }
            assertThrows(IllegalArgumentException::class.java) {
                profiles.deleteProfile("2.2081", default.id)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsProfilesIndependentAcrossVersions() {
        val root = Files.createTempDirectory("dash-profile-test-").toFile()
        try {
            val profiles = GameProfileManager(VersionStorageManager(root))
            val first = profiles.initialize("2.2081").single()
            val second = profiles.initialize("2.2091").single()
            assertEquals(first.id, second.id)
            val extra = profiles.createProfile("2.2091", "Beta")
            assertEquals(1, profiles.listProfiles("2.2081").size)
            assertEquals(2, profiles.listProfiles("2.2091").size)
            assertFalse(extra.directory.absolutePath.contains("2.2081"))
        } finally {
            root.deleteRecursively()
        }
    }
}
