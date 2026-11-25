package com.arcu.attnd.data

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.JoinLinks
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogLevel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the Play Store install referrer once per install. When the app was installed from the
 * QR-code → `/join/<code>/` → Play Store flow, the referrer is `event_code=<code>` and the app
 * joins that event directly instead of showing the home screen.
 */
object InstallReferrer {

    private const val TIMEOUT_MS = 5_000L

    suspend fun eventCode(context: Context): String? =
        JoinLinks.parseReferrerEventCode(withTimeoutOrNull(TIMEOUT_MS) { readReferrer(context) })

    private suspend fun readReferrer(context: Context): String? = suspendCancellableCoroutine { cont ->
        val client = InstallReferrerClient.newBuilder(context.applicationContext).build()
        cont.invokeOnCancellation { runCatching { client.endConnection() } }
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    val referrer = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        runCatching { client.installReferrer.installReferrer }.getOrNull()
                    } else {
                        null
                    }
                    runCatching { client.endConnection() }
                    if (cont.isActive) cont.resume(referrer)
                }

                override fun onInstallReferrerServiceDisconnected() {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (e: Exception) {
            AppLog.caught(LogCategory.LIFECYCLE, "install_referrer", e, LogLevel.WARNING)
            if (cont.isActive) cont.resume(null)
        }
    }
}
