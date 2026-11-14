package com.arcu.attnd.data

import com.arcu.attnd.BuildConfig
import com.arcu.attnd.data.api.AuthTokenProvider
import com.arcu.attnd.util.integrity.AttestationManager

/**
 * Bearer token for the API: the Play Integrity attestation JWT when the device can attest,
 * otherwise the static `BuildConfig.ATTND_API_KEY` (debug builds, emulators, CI).
 */
object AttestedTokenProvider : AuthTokenProvider {

    override suspend fun token(): String = AttestationManager.getToken() ?: BuildConfig.ATTND_API_KEY

    /** After a 401: drop the cached JWT and re-attest. Null (no Play Integrity) means don't retry. */
    override suspend fun reauthenticate(): String? {
        AttestationManager.invalidateToken()
        return AttestationManager.getToken()
    }
}
