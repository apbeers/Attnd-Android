package com.arcu.attnd.data.attest

import com.arcu.attnd.data.api.ApiJson
import com.arcu.attnd.data.net.ApiException
import com.arcu.attnd.data.net.HttpClient
import com.arcu.attnd.data.net.HttpResponse
import com.arcu.attnd.data.net.UrlConnectionHttpClient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * The attestation endpoints. These requests are deliberately unauthenticated: they are the ones
 * that *produce* the bearer token. (They live under /api/v1/ for both API versions.)
 */
class AttestationApi(
    private val baseUrl: String = "https://${com.arcu.attnd.BuildConfig.ATTND_SERVER}/api/v1/attest/",
    private val http: HttpClient = UrlConnectionHttpClient(),
) {
    suspend fun nonce(): String =
        decode(http.execute("GET", baseUrl + "nonce/", ACCEPT_JSON, null), NonceResponse.serializer()).nonce

    suspend fun attestAndroid(request: AndroidAttestRequest): String {
        val body = ApiJson.encodeToString(AndroidAttestRequest.serializer(), request)
        return decode(http.execute("POST", baseUrl + "android/", ACCEPT_JSON, body), AttestTokenResponse.serializer()).token
    }

    private fun <T> decode(response: HttpResponse, serializer: KSerializer<T>): T {
        if (!response.isSuccessful) throw ApiException(response.code)
        val body = response.body ?: throw IOException("Empty response body")
        return ApiJson.decodeFromString(serializer, body)
    }

    private companion object {
        val ACCEPT_JSON = mapOf("Accept" to "application/json")
    }
}

@Serializable
data class NonceResponse(val nonce: String)

@Serializable
data class AndroidAttestRequest(
    @SerialName("device_id") val deviceId: String,
    val token: String,
    val nonce: String,
)

@Serializable
data class AttestTokenResponse(val token: String)
