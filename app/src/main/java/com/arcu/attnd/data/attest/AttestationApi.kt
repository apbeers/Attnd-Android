package com.arcu.attnd.data.attest

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit interface for the attestation endpoints.
 *
 * These calls intentionally bypass the main Authorization interceptor —
 * they are the ones that *produce* the token, so they cannot require one.
 * A separate, unauthenticated Retrofit instance is used for this interface.
 */
interface AttestationApi {

    /** GET /api/v1/attest/nonce/ */
    @GET("api/v1/attest/nonce/")
    suspend fun getNonce(): NonceResponse

    /** POST /api/v1/attest/android/ */
    @POST("api/v1/attest/android/")
    suspend fun attestAndroid(@Body request: AndroidAttestRequest): AttestTokenResponse
}

data class NonceResponse(val nonce: String)

data class AndroidAttestRequest(
    val device_id: String,
    val token: String,
    val nonce: String,
)

data class AttestTokenResponse(val token: String)
