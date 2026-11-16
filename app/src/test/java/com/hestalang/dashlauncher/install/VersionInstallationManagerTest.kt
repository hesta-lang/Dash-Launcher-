package com.hestalang.dashlauncher.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VersionInstallationManagerTest {
    @Test
    fun eachVersionGetsAStableDistinctAndroidPackage() {
        assertEquals(
            "com.hestalang.dashlauncher.gd.v2_2081",
            VersionInstallationManager.packageNameForVersion("2.2081")
        )
        assertEquals(
            "com.hestalang.dashlauncher.gd.v2_2091",
            VersionInstallationManager.packageNameForVersion("2.2091")
        )
        assertNotEquals(
            VersionInstallationManager.packageNameForVersion("2.2081"),
            VersionInstallationManager.packageNameForVersion("2.2091")
        )
    }

    @Test
    fun rejectsInvalidVersionStrings() {
        assertThrows(IllegalArgumentException::class.java) {
            VersionInstallationManager.packageNameForVersion("../2.2081")
        }
    }
}
