package com.hestalang.dashlauncher.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashLauncherBaselineProfile {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndNavigation() = baselineProfileRule.collect(
        packageName = "com.hestalang.dashlauncher",
        includeInStartupProfile = true
    ) {
        startActivityAndWait()

        device.wait(Until.hasObject(By.text("Catálogo de versiones")), 10_000)

        device.findObject(By.text("Configuración")).click()
        device.wait(Until.hasObject(By.text("Tema de la aplicación")), 5_000)

        device.findObject(By.text("Claro")).click()
        device.findObject(By.text("Sistema")).click()

        device.findObject(By.text("Versiones")).click()
        device.wait(Until.hasObject(By.text("Catálogo de versiones")), 5_000)

        device.findObject(By.text("Beta")).click()
        device.findObject(By.text("Archived")).click()
        device.findObject(By.text("Stable")).click()
    }
}
