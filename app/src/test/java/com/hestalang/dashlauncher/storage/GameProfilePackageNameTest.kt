package com.hestalang.dashlauncher.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProfilePackageNameTest {

    private val manager = GameProfileManager(
        VersionStorageManager(File("build/tmp/profile-package-name-tests"))
    )

    @Test
    fun packageNameIsStableForSameVersionAndProfile() {
        val first = manager.packageNameForProfile("2.2081", "default")
        val second = manager.packageNameForProfile("2.2081", "default")

        assertEquals(first, second)
        assertTrue(first.startsWith("com.hestalang.dashlauncher.gd.v"))
    }

    @Test
    fun differentProfilesAndVersionsDoNotSharePackageName() {
        val defaultProfile = manager.packageNameForProfile("2.2081", "default")
        val secondProfile = manager.packageNameForProfile("2.2081", "profile-b")
        val otherVersion = manager.packageNameForProfile("2.2091", "default")

        assertNotEquals(defaultProfile, secondProfile)
        assertNotEquals(defaultProfile, otherVersion)
        assertNotEquals(secondProfile, otherVersion)
    }

    @Test
    fun punctuationVariantsCannotCollapseToSameVersionToken() {
        val dash = manager.packageNameForProfile("2.2-alpha", "default")
        val plus = manager.packageNameForProfile("2.2+alpha", "default")

        assertNotEquals(dash, plus)
    }
}
