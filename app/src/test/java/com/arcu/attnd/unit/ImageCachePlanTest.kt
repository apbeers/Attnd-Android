package com.arcu.attnd.unit

import com.arcu.attnd.data.api.ApiMapper
import com.arcu.attnd.domain.ImageCachePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** API-007: offline image cache naming, which images a bundle needs, and what to delete. */
class ImageCachePlanTest {

    private val logo = "https://attnd.app/media/logo.png"
    private val flyer = "https://attnd.app/media/flyer.png"
    private val taco = "https://img/taco.jpg"

    @Test
    fun fileName_isStableLowerCaseSha256Hex() {
        // sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ImageCachePlan.fileName("abc"))
        val name = ImageCachePlan.fileName(taco)
        assertEquals(64, name.length)
        assertTrue(name.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(name, ImageCachePlan.fileName(taco))
        assertNotEquals(name, ImageCachePlan.fileName("$taco?v=2"))
    }

    @Test
    fun referencedUrls_logoFlyerAndLocationImages_withoutDuplicates() {
        val bundle = ApiMapper.parseBundle(SampleBundles.FULL)!!
        // display.logo_url is null, so the event logo is used; Main Stage and Info Booth have no image.
        assertEquals(listOf(logo, flyer, taco), ImageCachePlan.referencedUrls(bundle))
    }

    @Test
    fun referencedUrls_skipPdfFlyer() {
        val bundle = ApiMapper.parseBundle(SampleBundles.PARTIAL_DISPLAY)!!
        assertEquals(emptyList<String>(), ImageCachePlan.referencedUrls(bundle))
        assertEquals(emptyList<String>(), ImageCachePlan.referencedUrls(ApiMapper.parseBundle(SampleBundles.MINIMAL)!!))
    }

    @Test
    fun livePlacePhotos_areNeverPrefetched() {
        // API-031: Attnd's live Google photo redirect is loaded per view, never kept on disk.
        assertTrue(ImageCachePlan.isLivePlacePhoto("https://attnd.app/img/places/12/"))
        assertTrue(ImageCachePlan.isLivePlacePhoto("https://dev.attnd.app/img/places/12"))
        assertTrue(ImageCachePlan.isLivePlacePhoto("https://attnd.app/img/places/12/?format=json"))
        assertFalse(ImageCachePlan.isLivePlacePhoto("https://attnd.app/img/places/abc/"))
        assertFalse(ImageCachePlan.isLivePlacePhoto("https://attnd.app/img/places/"))
        assertFalse(ImageCachePlan.isLivePlacePhoto("https://attnd.app/media/img/places/12/"))
        assertFalse(ImageCachePlan.isLivePlacePhoto(taco))
        val bundle = ApiMapper.parseBundle(
            """{"event":{"id":1,"logo_url":"$logo"},"locations":[
              {"id":1,"sort_order":1,"image_url":"https://attnd.app/img/places/1/","image_credit":"Photo: Google Maps"},
              {"id":2,"sort_order":2,"image_url":"$taco"}]}""",
        )!!
        assertEquals(listOf(logo, taco), ImageCachePlan.referencedUrls(bundle))
    }

    @Test
    fun isPdf_ignoresQueryAndCase() {
        assertTrue(ImageCachePlan.isPdf("https://attnd.app/f.pdf"))
        assertTrue(ImageCachePlan.isPdf("https://cdn.example/flyer.PDF?sig=abc"))
        assertTrue(ImageCachePlan.isPdf("https://cdn.example/flyer.pdf#page=2"))
        assertFalse(ImageCachePlan.isPdf("https://cdn.example/flyer.png?name=a.pdf"))
    }

    @Test
    fun missing_keepsReferencedOrder() {
        val existing = setOf(ImageCachePlan.fileName(flyer))
        assertEquals(listOf(logo, taco), ImageCachePlan.missing(listOf(logo, flyer, taco), existing))
    }

    @Test
    fun stale_isEverythingUnreferenced_butNotInProgressDownloads() {
        val old = "https://img/old.jpg"
        val tmp = ImageCachePlan.fileName(old) + ".1234" + ImageCachePlan.TEMP_SUFFIX
        val existing = listOf(ImageCachePlan.fileName(logo), ImageCachePlan.fileName(old), tmp, "junk")
        assertEquals(
            setOf(ImageCachePlan.fileName(old), "junk"),
            ImageCachePlan.stale(existing, listOf(logo, taco)),
        )
        // Leaving an event: nothing is referenced, everything goes.
        assertEquals(setOf(ImageCachePlan.fileName(logo)), ImageCachePlan.stale(listOf(ImageCachePlan.fileName(logo)), emptyList()))
    }

    @Test
    fun fitsBudget_capsTotalAndSingleFiles() {
        val mb = 1024L * 1024
        assertTrue(ImageCachePlan.fitsBudget(0, 5 * mb))
        assertTrue(ImageCachePlan.fitsBudget(295 * mb, 5 * mb))
        assertFalse(ImageCachePlan.fitsBudget(296 * mb, 5 * mb))
        assertTrue(ImageCachePlan.fitsBudget(0, ImageCachePlan.MAX_FILE_BYTES))
        assertFalse(ImageCachePlan.fitsBudget(0, ImageCachePlan.MAX_FILE_BYTES + 1))
    }

    @Test
    fun contentType_rejectsPagesNotImages() {
        assertTrue(ImageCachePlan.isImageContentType("image/jpeg"))
        assertTrue(ImageCachePlan.isImageContentType("application/octet-stream"))
        assertTrue(ImageCachePlan.isImageContentType(null))
        assertFalse(ImageCachePlan.isImageContentType("text/html; charset=utf-8"))
        assertFalse(ImageCachePlan.isImageContentType("application/json"))
    }
}
