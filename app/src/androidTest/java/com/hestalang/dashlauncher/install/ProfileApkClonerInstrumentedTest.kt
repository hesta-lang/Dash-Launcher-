package com.hestalang.dashlauncher.install

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hestalang.dashlauncher.storage.GameProfileManager
import com.hestalang.dashlauncher.storage.VersionStorageManager
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real APK clone implementation on-device.
 *
 * The workflow installs a tiny CI-only fixture under com.robtopx.geometryjump.
 * This test reads that installed APK directly and never uninstalls or alters it.
 */
@RunWith(AndroidJUnit4::class)
class ProfileApkClonerInstrumentedTest {

    @Test
    fun clonedApkHasIndependentIdentityAndLeavesOriginalUntouched() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val originalInfo = packageManager.getApplicationInfo(
            GeometryDashInstallManager.GAME_PACKAGE,
            0
        )
        val sourceApk = File(originalInfo.sourceDir)
        assertTrue("El APK original fixture no está instalado o no se puede leer.", sourceApk.isFile && sourceApk.canRead())
        val originalSourceHash = sha256(sourceApk)

        // Keep the validation APK in internal app storage so CI can retrieve it
        // reliably with adb run-as, independent of external-storage mount behavior.
        val workDirectory = File(context.filesDir, VALIDATION_DIRECTORY)
        assertTrue(
            "No se pudo preparar la carpeta de validación.",
            workDirectory.isDirectory || workDirectory.mkdirs()
        )

        val profileManager = GameProfileManager(VersionStorageManager(context))
        val defaultProfile = profileManager.initialize(TEST_VERSION)
            .single { it.id == DEFAULT_PROFILE_ID }
        val profilePackageName = profileManager.packageNameForProfile(
            TEST_VERSION,
            defaultProfile.id
        )
        assertNotEquals(GeometryDashInstallManager.GAME_PACKAGE, profilePackageName)

        val outputApk = File(workDirectory, OUTPUT_APK_NAME)
        check(outputApk.delete() || !outputApk.exists()) {
            "No se pudo retirar el APK de prueba anterior."
        }

        val cloneResult = ProfileApkCloner().build(
            sourceApk = sourceApk,
            outputApk = outputApk,
            newPackageName = profilePackageName,
            displayLabel = "Geometry Dash $TEST_VERSION - CI profile"
        )

        assertEquals(GeometryDashInstallManager.GAME_PACKAGE, cloneResult.sourcePackageName)
        assertEquals(profilePackageName, cloneResult.installedPackageName)
        assertTrue("No se generó el APK de la instancia.", outputApk.isFile && outputApk.length() > 0L)

        @Suppress("DEPRECATION")
        val archiveInfo = packageManager.getPackageArchiveInfo(
            outputApk.absolutePath,
            android.content.pm.PackageManager.GET_ACTIVITIES
        )
        assertNotNull("Android no pudo analizar el APK clonado.", archiveInfo)
        assertEquals(profilePackageName, archiveInfo!!.packageName)
        assertTrue(
            "El APK clonado perdió la actividad de inicio original.",
            archiveInfo.activities.orEmpty().any {
                it.name == FIXTURE_ACTIVITY_NAME
            }
        )

        // The source APK installed as the original must remain byte-identical.
        assertEquals(originalSourceHash, sha256(sourceApk))
        assertEquals(
            GeometryDashInstallManager.GAME_PACKAGE,
            packageManager.getApplicationInfo(GeometryDashInstallManager.GAME_PACKAGE, 0).packageName
        )

        println("PROFILE_CLONE_APK_PATH=${outputApk.absolutePath}")
        println("PROFILE_CLONE_PACKAGE=$profilePackageName")
        println("PROFILE_SOURCE_SHA256=$originalSourceHash")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TEST_VERSION = "2.2081"
        private const val DEFAULT_PROFILE_ID = "default"
        private const val VALIDATION_DIRECTORY = "profile-clone-validation"
        private const val OUTPUT_APK_NAME = "instance.apk"
        private const val FIXTURE_ACTIVITY_NAME = "com.robtopx.geometryjump.FixtureActivity"
    }
}
