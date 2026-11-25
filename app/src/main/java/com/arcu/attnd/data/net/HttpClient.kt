package com.arcu.attnd.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * A completed HTTP exchange. [body] is null for 204/304 and when the server sent nothing.
 * [bytes] is the size of the response body as received (for diagnostics; -1 when unknown).
 */
class HttpResponse(
    val code: Int,
    val body: String?,
    private val headers: Map<String, List<String>>,
    val bytes: Long = body?.length?.toLong() ?: 0L,
) {
    val isSuccessful: Boolean get() = code in 200..299

    /** Case-insensitive header lookup (first value). */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/** A non-2xx response from the API (other than the 304 the bundle call handles itself). */
/**
 * An HTTP error answer. [error] / [serverMessage] are the body's `error` and `message` when it is JSON (e.g. API-025's
 * `409 {"error": "event_full_free", "message": …}`), else null.
 */
class ApiException(
    val code: Int,
    message: String = "HTTP $code",
    val error: String? = null,
    val serverMessage: String? = null,
) : Exception(message)

/** Seam for tests; the app uses [UrlConnectionHttpClient]. */
fun interface HttpClient {
    suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse

    /**
     * A binary request body (API-013: gzip-compressed diagnostics uploads). [headers] carry its
     * `Content-Type` / `Content-Encoding`. Test fakes that don't need it keep this default.
     */
    suspend fun executeBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray): HttpResponse =
        throw IOException("Binary request bodies are not supported by this client")
}

/**
 * Minimal blocking HttpURLConnection client run on [Dispatchers.IO].
 * Response caching is disabled so a conditional request's `304` reaches the caller untouched.
 */
class UrlConnectionHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpClient {

    override suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse = send(
        method, url,
        if (body == null) headers else mapOf("Content-Type" to "application/json; charset=utf-8") + headers,
        body?.toByteArray(Charsets.UTF_8),
    )

    override suspend fun executeBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray): HttpResponse =
        send(method, url, headers, body)

    private suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code < 0) throw IOException("Invalid HTTP response")
            val stream = when {
                code == HttpURLConnection.HTTP_NOT_MODIFIED || code == HttpURLConnection.HTTP_NO_CONTENT -> null
                code >= 400 -> connection.errorStream
                else -> connection.inputStream
            }
            val raw = stream?.use { it.readBytes() }
            val responseHeaders = connection.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapKeys { it.key!! }
            HttpResponse(code, raw?.toString(Charsets.UTF_8), responseHeaders, raw?.size?.toLong() ?: 0L)
        } finally {
            connection.disconnect()
        }
    }
}
