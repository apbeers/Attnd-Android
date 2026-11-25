package com.arcu.attnd.data

import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.ImageCachePlan
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Outcome of [ImageDiskCache.fetch]. */
sealed interface ImageFetch {
    data class Stored(val file: File) : ImageFetch
    /** Over [ImageCachePlan.MAX_FILE_BYTES]: not written to disk; the caller may download it into memory. */
    data object TooLarge : ImageFetch
    data object Failed : ImageFetch
}

/**
 * API-007: the joined event's images on disk, in [dir] (`filesDir/images/`, not the cache dir, so
 * the OS doesn't purge them mid-event), one file per URL named by [ImageCachePlan.fileName].
 *
 * - [fetch] is the network step of `ImageLoader` (memory → disk → network). Downloads go to a
 *   temp file and are renamed into place, so a file is either complete or absent.
 * - [prefetch] runs after every applied bundle (and after a restore): it downloads every referenced
 *   image that is missing ([ImageCachePlan.PREFETCH_CONCURRENCY] at a time, within
 *   [ImageCachePlan.MAX_TOTAL_BYTES]), then deletes files the bundle no longer references. New
 *   images are fetched before old ones go, and a failed download keeps the existing file.
 * - [clear] deletes everything (leaving or switching events).
 *
 * Images are public (Cloudinary/Google), so no auth header.
 */
class ImageDiskCache(
    private val dir: File,
    private val scope: CoroutineScope,
) {
    private val inFlightLock = Mutex()
    private val inFlight = HashMap<String, Deferred<ImageFetch>>()

    private val prefetchLock = Any()
    private var prefetchJob: Job? = null
    private var prefetchUrls: List<String>? = null
    /** The URL set of the last pass that ended with every image on disk; repeating it is a no-op. */
    @Volatile
    private var completeUrls: List<String>? = null

    /** Temp files carry this process's id, so the start-up clean-up never touches a live download. */
    private val session = UUID.randomUUID().toString()

    init {
        // Leftover temp files from a process that died mid-download.
        scope.launch(Dispatchers.IO) {
            dir.listFiles()
                ?.filter { it.name.endsWith(ImageCachePlan.TEMP_SUFFIX) && session !in it.name }
                ?.forEach { it.delete() }
        }
    }

    /** The stored file for [url], if it has been downloaded. */
    fun file(url: String): File? = File(dir, ImageCachePlan.fileName(url)).takeIf { it.isFile }

    /** Deletes the stored file for [url] (e.g. it would not decode); the next load downloads it again. */
    fun evict(url: String) {
        File(dir, ImageCachePlan.fileName(url)).delete()
    }

    /** Downloads [url] to disk, sharing one download between concurrent callers. */
    suspend fun fetch(url: String): ImageFetch {
        file(url)?.let { return ImageFetch.Stored(it) }
        val deferred = inFlightLock.withLock {
            inFlight.getOrPut(url) {
                // On [scope], not the caller's, so one cancelled caller doesn't cancel the others.
                scope.async(Dispatchers.IO) {
                    try {
                        download(url)
                    } finally {
                        inFlightLock.withLock { inFlight.remove(url) }
                    }
                }
            }
        }
        return deferred.await()
    }

    /**
     * Makes [urls] (see [ImageCachePlan.referencedUrls]) available offline in the background,
     * replacing any pass still running for an older bundle. Returns immediately.
     */
    fun prefetch(urls: List<String>) {
        synchronized(prefetchLock) {
            if (urls == completeUrls) return
            if (urls == prefetchUrls && prefetchJob?.isActive == true) return
            val previous = prefetchJob
            // Cancel now, so the old pass can't reach its clean-up step with the old URL set.
            previous?.cancel()
            prefetchUrls = urls
            prefetchJob = scope.launch(Dispatchers.IO) {
                previous?.join()
                if (runPrefetch(urls)) completeUrls = urls
            }
        }
    }

    /** Deletes every stored image and stops any prefetch. Called when the event's bundle is deleted. */
    fun clear() {
        synchronized(prefetchLock) {
            prefetchJob?.cancel()
            prefetchJob = null
            prefetchUrls = null
            completeUrls = null
        }
        // A download still finishing may add one file after this; the next completed prefetch removes it.
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * One pass: download what's missing, then remove unreferenced files. True when nothing failed
     * (images skipped for size or budget count as done, so they aren't retried on every sync).
     */
    private suspend fun runPrefetch(urls: List<String>): Boolean {
        val started = System.nanoTime()
        val fetched = AtomicInteger(0)
        val failures = AtomicInteger(0)
        dir.mkdirs()
        val existing = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val referencedNames = urls.mapTo(HashSet()) { ImageCachePlan.fileName(it) }
        val used = AtomicLong(existing.filter { it.name in referencedNames }.sumOf { it.length() })
        val missing = ImageCachePlan.missing(urls, existing.mapTo(HashSet()) { it.name })
        val semaphore = Semaphore(ImageCachePlan.PREFETCH_CONCURRENCY)
        val failed = AtomicBoolean(false)
        coroutineScope {
            missing.forEach { url ->
                launch {
                    semaphore.withPermit<Unit> {
                        if (used.get() >= ImageCachePlan.MAX_TOTAL_BYTES) return@withPermit
                        when (val result = fetch(url)) {
                            is ImageFetch.Stored -> {
                                val size = result.file.length()
                                // Over budget (several downloads finished together): drop this one.
                                if (!ImageCachePlan.fitsBudget(used.get(), size)) result.file.delete()
                                else used.addAndGet(size)
                                fetched.incrementAndGet()
                            }
                            ImageFetch.TooLarge -> Unit
                            ImageFetch.Failed -> {
                                failed.set(true) // existing file (if any) is kept
                                failures.incrementAndGet()
                            }
                        }
                    }
                }
            }
        }
        // Every referenced image has been attempted: now drop what the bundle no longer uses.
        val names = dir.list()?.toList().orEmpty()
        val stale = ImageCachePlan.stale(names, urls)
        if (stale.isNotEmpty()) {
            var bytes = 0L
            val deleted = stale.count { name -> File(dir, name).let { file -> val size = file.length(); file.delete().also { if (it) bytes += size } } }
            AppLog.debug(LogCategory.STORAGE, LogEvent.IMAGES_PRUNED, "Deleted $deleted unreferenced images", mapOf("files" to deleted, "bytes" to bytes))
        }
        if (missing.isNotEmpty()) {
            AppLog.debug(
                LogCategory.STORAGE, LogEvent.IMAGES_PREFETCH, "Images prefetched: ${fetched.get()} of ${missing.size}",
                mapOf("total" to urls.size, "fetched" to fetched.get(), "failed" to failures.get(), "ms" to (System.nanoTime() - started) / 1_000_000),
            )
        }
        return !failed.get()
    }

    private suspend fun download(url: String): ImageFetch = withContext(Dispatchers.IO) {
        if (!ImageCachePlan.isRemote(url)) return@withContext ImageFetch.Failed
        val connection = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return@withContext ImageFetch.Failed
        }
        val started = System.nanoTime()
        val target = File(dir, ImageCachePlan.fileName(url))
        val tmp = File(dir, "${target.name}.$session-${UUID.randomUUID()}${ImageCachePlan.TEMP_SUFFIX}")
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) {
                AppLog.warning(LogCategory.NET, LogEvent.IMAGE_FAILED, "Image download ${connection.responseCode}", mapOf("error" to "http", "status" to connection.responseCode))
                return@withContext ImageFetch.Failed
            }
            // A captive portal or error page answering 200 must not be stored as the image.
            if (!ImageCachePlan.isImageContentType(connection.contentType)) return@withContext ImageFetch.Failed
            if (connection.contentLengthLong > ImageCachePlan.MAX_FILE_BYTES) return@withContext ImageFetch.TooLarge
            dir.mkdirs()
            val written = connection.inputStream.use { input ->
                tmp.outputStream().use { output -> copyAtMost(input, output, ImageCachePlan.MAX_FILE_BYTES) }
            }
            AppLog.debug(
                LogCategory.NET, LogEvent.IMAGE_FETCHED, "Image downloaded",
                mapOf("ms" to (System.nanoTime() - started) / 1_000_000, "bytes" to written, "status" to connection.responseCode),
            )
            when {
                written < 0 -> ImageFetch.TooLarge
                written == 0L -> ImageFetch.Failed
                tmp.renameTo(target) -> ImageFetch.Stored(target)
                else -> ImageFetch.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warning(LogCategory.NET, LogEvent.IMAGE_FAILED, "Image download failed (${AppLog.errorKind(e)})", mapOf("error" to AppLog.errorKind(e), "domain" to e.javaClass.simpleName))
            ImageFetch.Failed
        } finally {
            tmp.delete() // no-op after a successful rename
            connection.disconnect()
        }
    }

    /** Copies up to [limit] bytes; returns the count, or -1 when the stream is longer than [limit]. */
    private fun copyAtMost(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > limit) return -1
            output.write(buffer, 0, read)
        }
    }
}
