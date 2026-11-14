package com.arcu.attnd.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** A completed HTTP exchange. [body] is null for 204/304 and when the server sent nothing. */
class HttpResponse(
    val code: Int,
    val body: String?,
    private val headers: Map<String, List<String>>,
) {
    val isSuccessful: Boolean get() = code in 200..299

    /** Case-insensitive header lookup (first value). */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/** A non-2xx response from the API (other than the 304 the bundle call handles itself). */
class ApiException(val code: Int, message: String = "HTTP $code") : Exception(message)

/** Seam for tests; the app uses [UrlConnectionHttpClient]. */
fun interface HttpClient {
    suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
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
    ): HttpResponse = withContext(Dispatchers.IO) {
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
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code < 0) throw IOException("Invalid HTTP response")
            val stream = when {
                code == HttpURLConnection.HTTP_NOT_MODIFIED || code == HttpURLConnection.HTTP_NO_CONTENT -> null
                code >= 400 -> connection.errorStream
                else -> connection.inputStream
            }
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            val responseHeaders = connection.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapKeys { it.key!! }
            HttpResponse(code, text, responseHeaders)
        } finally {
            connection.disconnect()
        }
    }
}
