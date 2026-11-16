plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

val ciVersionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1

android {
    namespace = "com.hestalang.dashlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hestalang.dashlauncher"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".ci/dash-launcher-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

baselineProfile {
    automaticGenerationDuringBuild = true
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    baselineProfile(project(":baselineprofile"))
}
