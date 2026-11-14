package com.arcu.attnd.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/**
 * Tiny image loader: HttpURLConnection download, BitmapFactory decode (downsampled to
 * [maxDimension]), and an in-memory LruCache sized to 1/8 of the heap. Concurrent requests for
 * the same URL share one download. Images are public (Cloudinary/Google), so no auth header.
 */
object ImageLoader {

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }
    /** Downloads run here, not in the caller's scope, so one cancelled caller doesn't cancel the others. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightLock = Mutex()
    private val inFlight = HashMap<String, Deferred<Bitmap?>>()

    fun cached(url: String, maxDimension: Int): Bitmap? = cache.get(key(url, maxDimension))

    suspend fun load(url: String, maxDimension: Int = 1024): Bitmap? {
        val key = key(url, maxDimension)
        cache.get(key)?.let { return it }
        val deferred = inFlightLock.withLock {
            inFlight.getOrPut(key) {
                scope.async {
                    try {
                        download(url, maxDimension)?.also { cache.put(key, it) }
                    } finally {
                        inFlightLock.withLock { inFlight.remove(key) }
                    }
                }
            }
        }
        return deferred.await()
    }

    private fun key(url: String, maxDimension: Int) = "$maxDimension|$url"

    private suspend fun download(url: String, maxDimension: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext null
        val connection = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return@withContext null
        }
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return@withContext null
            val bytes = connection.inputStream.use { it.readBytes() }
            decode(bytes, maxDimension)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}

/**
 * Composable for a remote image. Shows [placeholder] while loading, on failure, or when [url] is null.
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxDimension: Int = 1024,
    placeholder: @Composable () -> Unit = { ImagePlaceholder(Modifier.fillMaxSize()) },
) {
    val bitmap: ImageBitmap? by produceState(
        initialValue = url?.let { ImageLoader.cached(it, maxDimension) }?.asImageBitmap(),
        url,
        maxDimension,
    ) {
        // produceState keeps its value across key changes, so reset it for a new URL first.
        value = url?.let { ImageLoader.cached(it, maxDimension) }?.asImageBitmap()
        if (url != null && value == null) value = ImageLoader.load(url, maxDimension)?.asImageBitmap()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            placeholder()
        }
    }
}

@Composable
fun ImagePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
