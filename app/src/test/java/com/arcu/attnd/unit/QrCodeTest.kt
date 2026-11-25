package com.arcu.attnd.unit

import com.arcu.attnd.domain.PlanShare
import com.arcu.attnd.domain.QrCode
import com.arcu.attnd.domain.QrEcc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The share block's QR encoder. Expected symbols come from the reference encoder python-qrcode 8
 * (byte mode, same version / error correction / mask, no border), compared by the SHA-256 of the
 * rows ("#" dark, "." light, joined by "\n").
 */
class QrCodeTest {

    private fun text(n: Int): String {
        val sb = StringBuilder("https://attnd.app/p/DEMO/eyJ2IjoxLCJpZHMiOlsxMDEsMTE0XSwiZCI6IjIwMjYtMTEtMTQifQ")
        var i = 0
        while (sb.length < n) sb.append('a' + i++ % 26)
        return sb.substring(0, n)
    }

    private fun sha(code: QrCode): String =
        MessageDigest.getInstance("SHA-256").digest(code.rows().joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }

    private fun check(text: String, ecc: QrEcc, mask: Int, version: Int, hash: String) {
        val code = QrCode.encode(text, ecc, forceMask = mask)
        assertNotNull(code)
        assertEquals("version for ${text.length} chars", version, code!!.version)
        assertEquals(version * 4 + 17, code.size)
        assertEquals("symbol for ${text.length} chars $ecc mask $mask", hash, sha(code))
    }

    @Test
    fun matchesReferenceEncoder() {
        check("HELLO", QrEcc.MEDIUM, 0, 1, "861b752eb1990bc869ad395f61821f6aea204eaf74df46123664707c9761bab1")
        check("https://attnd.app/join/DEMO/", QrEcc.MEDIUM, 3, 3, "46b1b51310b6edd5a6475df68490ccfcc7f28bac73c7b6bbf3cf2555277993c5")
        check(text(120), QrEcc.LOW, 5, 6, "cd288bdb5a9eea388f87aadacf8c0d540512e4c52ab4fbc0a372b0d606af87a7")
        check(text(300), QrEcc.MEDIUM, 2, 13, "dcd6525d1cefe0028796f1362824bd7d6007a5014592d31dfce5ae430f9f42f6")
        check(text(600), QrEcc.QUARTILE, 7, 23, "2ba8b47147219b99c9d8d6476d331fa2ab037721b8515ccfabda65cc6481371f")
        check(text(1200), QrEcc.MEDIUM, 1, 29, "4f1b2ca696771ecb9453c37b0f618973c22a9bb05787219916be6b20889f9f24")
        check(text(1530), QrEcc.MEDIUM, 4, 32, "f5eb929e3716d93383895dca5df3864c34ca6109306a1fdd2111a62c614db010")
        check(text(20), QrEcc.HIGH, 6, 3, "c845cae2d1b3e9170ee57c91a7d1aece869be95aa37a1ca2de76871a175b61ea")
    }

    @Test
    fun picksAMaskAndFitsTheLongestShareLink() {
        val ids = (1..400).toList()
        val link = PlanShare.link("DEMO", ids)
        val code = QrCode.encode(link)
        assertNotNull(code)
        assertTrue(code!!.mask in 0..7)
        assertTrue(code.version <= 40)
        // Finder pattern corners are dark, the separator next to them light.
        assertTrue(code[0, 0] && code[code.size - 1, 0] && code[0, code.size - 1])
        assertTrue(!code[7, 0] && !code[0, 7])
    }

    @Test
    fun tooLongIsNull() {
        assertNull(QrCode.encode("x".repeat(3000), QrEcc.MEDIUM))
    }
}
