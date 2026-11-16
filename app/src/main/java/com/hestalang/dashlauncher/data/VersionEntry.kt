package com.hestalang.dashlauncher.data

data class VersionEntry(
    val id: String,
    val displayName: String,
    val version: String,
    val channel: String,
    val description: String,
    val minAndroidApi: Int,
    val supportedAbis: List<String>,
    val downloadUrl: String?,
    val sizeBytes: Long?,
    val sha256: String?,
    val providerId: String = "direct",
    val providerName: String? = null
)
