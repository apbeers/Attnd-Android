package com.arcu.attnd.util.integrity

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import kotlinx.coroutines.tasks.await

/** Thin coroutine wrapper around the Play Integrity "classic" token request. */
class IntegrityManager(context: Context) {

    private val integrityManager = IntegrityManagerFactory.create(context)

    /**
     * Requests an integrity token bound to [nonce] (issued by the backend to prevent replay).
     * The token is sent to the backend, which verifies it with Google and issues a JWT.
     */
    suspend fun fetchIntegrityToken(nonce: String): Result<String> {
        return try {
            val request = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build()

            val response: IntegrityTokenResponse = integrityManager.requestIntegrityToken(request).await()
            Result.success(response.token())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
