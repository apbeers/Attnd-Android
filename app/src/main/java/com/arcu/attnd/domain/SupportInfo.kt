package com.arcu.attnd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Build and support details shown in Options → About & support (API-004), and the plain-text
 * block "Copy support info" puts on the clipboard. Built by the ViewModel; pure Kotlin.
 */
data class SupportInfo(
    val appVersion: String,
    val versionCode: Int,
    val buildType: String,
    val serverHost: String,
    val apiVersion: Int,
    val osVersion: String,
    val device: String,
    val deviceId: String,
    val eventName: String? = null,
    val eventCode: String? = null,
    val eventId: Int? = null,
    val contentVersion: Int? = null,
    /** Epoch millis of the last successful bundle refresh. */
    val lastSyncedAt: Long? = null,
) {
    /** Not the production server: Options shows the "Internal test build" warning. */
    val isTestBuild: Boolean get() = serverHost != PRODUCTION_HOST

    val supportCode: String get() = supportCode(deviceId)

    /** Last refresh in [zone] (the device's), or "Never". */
    fun lastRefreshText(zone: ZoneId): String =
        lastSyncedAt?.let { LAST_REFRESH_FORMAT.withZone(zone).format(Instant.ofEpochMilli(it)) } ?: "Never"

    /** Everything above plus the full device id, for pasting into a support message. */
    fun plainText(zone: ZoneId): String = buildString {
        appendLine("Attnd support info")
        appendLine("Support code: $supportCode")
        appendLine("Device ID: $deviceId")
        appendLine("App version: $appVersion ($versionCode)")
        appendLine("Build type: $buildType")
        appendLine("Server: $serverHost")
        appendLine("API version: v$apiVersion")
        appendLine("Platform: Android $osVersion")
        appendLine("Device: $device")
        appendLine("Event: ${eventName ?: "None"}")
        appendLine("Event code: ${eventCode ?: "-"}")
        appendLine("Event ID: ${eventId ?: "-"}")
        appendLine("Content version: ${contentVersion ?: "-"}")
        append("Last refresh: ${lastRefreshText(zone)}")
    }

    companion object {
        const val PRODUCTION_HOST = "attnd.app"

        private val LAST_REFRESH_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)

        /** First 8 characters of the device id without dashes, upper case (e.g. `3F2A9C1B`). */
        fun supportCode(deviceId: String): String =
            deviceId.replace("-", "").take(SUPPORT_CODE_LENGTH).uppercase(Locale.ROOT)

        private const val SUPPORT_CODE_LENGTH = 8
    }
}
