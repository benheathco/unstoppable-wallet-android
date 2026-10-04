package io.horizontalsystems.evidence

/** Enrollment-derived settings. Sub-project 2 supplies the real (StrongBox-backed) provider. */
data class EvidenceConfig(
    val serverUrl: String,
    val apiKey: String,
    val projectUuid: String,
    val deviceId: String,
    val appVersion: String,
)
