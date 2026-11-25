package com.arcu.attnd.data

import android.util.LruCache
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.PhotoAttribution
import com.arcu.attnd.domain.WebLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * API-031: the authors of a live Google place photo (`image_attribution_url`, our server's JSON). Kept
 * in memory only, like the photo itself (Google's terms: nothing on disk); failures return null and the
 * plain `image_credit` stays.
 */
object PhotoAttributionLoader {
    private val cache = LruCache<String, PhotoAttribution>(64)

    fun cached(url: String): PhotoAttribution? = cache.get(url)

    suspend fun load(url: String): PhotoAttribution? {
        val https = WebLinks.httpsUrl(url) ?: return null
        cache.get(https)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(https).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/json")
                try {
                    if (connection.responseCode !in 200..299) return@withContext null
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    PhotoAttribution.decode(text)?.also { cache.put(https, it) }
                } finally {
                    connection.disconnect()
                }
            } catch (e: java.io.IOException) {
                AppLog.caught(LogCategory.NET, "photo_attribution", e)
                null
            }
        }
    }
}
