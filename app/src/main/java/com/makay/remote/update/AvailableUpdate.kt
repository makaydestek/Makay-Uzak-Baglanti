package com.makay.remote.update

data class AvailableUpdate(
    val tag: String,
    val versionName: String,
    val apkUrl: String,
    val apkName: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val expectedSha256: String? = null,
    val expectedSizeBytes: Long? = null
)
