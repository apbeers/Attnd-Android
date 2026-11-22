package com.arcu.attnd.domain

import java.security.MessageDigest

/**
 * API-007: pure rules for the offline image cache (`filesDir/images/`), used by
 * [com.arcu.attnd.data.ImageDiskCache] and unit tested on the JVM.
 *
 * Files are keyed by URL only: an image at a given URL is assumed not to change (Cloudinary and
 * the server's media URLs change when the image does), so an existing file is never re-downloaded.
 */
object ImageCachePlan {

    /** Total budget for one event's images; prefetch stops adding files beyond it. */
    const val MAX_TOTAL_BYTES = 300L * 1024 * 1024

    /** Larger images are never written to disk (they are still shown, from memory only). */
    const val MAX_FILE_BYTES = 15L * 1024 * 1024

    /** Downloads in flight at once while prefetching. */
    const val PREFETCH_CONCURRENCY = 4

    /** Suffix of in-progress downloads (`<name>.<random>.tmp`), renamed into place when complete. */
    const val TEMP_SUFFIX = ".tmp"

    /** `sha256(url)` as 64 lower-case hex characters: a safe, fixed-length file name. */
    fun fileName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isRemote(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    /** False for responses that are clearly not an image (an HTML login page, JSON); unknown types pass. */
    fun isImageContentType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return !type.startsWith("text/") && !type.contains("json") && !type.contains("xml")
    }

    /** A flyer that is a PDF opens in the system viewer instead of being shown (and cached) as an image. */
    fun isPdf(url: String): Boolean = url.substringBefore('?').substringBefore('#').endsWith(".pdf", ignoreCase = true)

    /**
     * Every image the bundle can show, most visible first (header logo, flyer, then locations in
     * list order), without duplicates: `display.logo_url`, `event.logo_url`, `event.flyer_url`
     * unless it is a PDF, and each location's `image_url`. API-031: live Google place photos
     * ([isLivePlacePhoto]) are never kept on disk (Google's terms), so they are left out.
     */
    fun referencedUrls(bundle: EventBundle): List<String> = buildList {
        bundle.display.logoUrl?.let(::add)
        bundle.event.logoUrl?.let(::add)
        bundle.event.flyerUrl?.takeUnless(::isPdf)?.let(::add)
        bundle.locations.sortedBy { it.sortOrder }.forEach { location -> location.imageUrl?.let(::add) }
    }.filter { isRemote(it) && !isLivePlacePhoto(it) }.distinct()

    /**
     * API-031: Attnd's live Google Places photo redirect, `/img/places/<id>/` (on any host, as on
     * iOS `MediaCache.isLivePlacePhoto`). Loaded per view into memory only, never written to the
     * image directory.
     */
    fun isLivePlacePhoto(url: String): Boolean {
        if (!isRemote(url)) return false
        val path = runCatching { java.net.URI(url.trim()).path }.getOrNull() ?: return false
        val parts = path.split('/').filter { it.isNotEmpty() }
        return parts.size == 3 && parts[0] == "img" && parts[1] == "places" && parts[2].all { it in '0'..'9' }
    }

    /** Referenced URLs that have no file yet, in [referenced] order. */
    fun missing(referenced: List<String>, existingNames: Set<String>): List<String> =
        referenced.filter { fileName(it) !in existingNames }

    /**
     * Files to delete once a full prefetch pass has been attempted: everything not referenced by
     * the current bundle. In-progress downloads ([TEMP_SUFFIX]) are left alone; they clean up after
     * themselves (and leftovers from a crash are removed at startup).
     */
    fun stale(existingNames: Collection<String>, referenced: Collection<String>): Set<String> {
        val keep = referenced.mapTo(HashSet()) { fileName(it) }
        return existingNames.filterTo(LinkedHashSet()) { it !in keep && !it.endsWith(TEMP_SUFFIX) }
    }

    /** Whether a file of [newBytes] may be added when [usedBytes] are already stored. */
    fun fitsBudget(usedBytes: Long, newBytes: Long, maxBytes: Long = MAX_TOTAL_BYTES): Boolean =
        newBytes in 0..MAX_FILE_BYTES && usedBytes + newBytes <= maxBytes
}
