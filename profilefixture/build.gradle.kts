plugins {
    id("com.android.application")
}

android {
    namespace = "com.robtopx.geometryjump"
    compileSdk = 35

    defaultConfig {
        // Test-only fixture: it deliberately uses Geometry Dash's Android package ID
        // so CI can prove that a profile clone installs beside, rather than over, it.
        applicationId = "com.robtopx.geometryjump"
        minSdk = 26
        targetSdk = 29
        versionCode = 2081
        versionName = "2.2081-fixture"
    }
}
