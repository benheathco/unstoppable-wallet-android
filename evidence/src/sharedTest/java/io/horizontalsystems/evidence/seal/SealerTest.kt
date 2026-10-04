package io.horizontalsystems.evidence.seal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class SealerTest {
    private fun bmp() = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    private val base = SealMeta("req-1", "Ethereum", "0xabc", "100.0", "2026-10-04T00:00:00Z", "dev-1", "1.0", null)

    @Test
    fun sealing_with_the_same_meta_is_byte_identical() {
        assertArrayEquals(sealPng(bmp(), base), sealPng(bmp(), base))
    }

    @Test
    fun different_captureId_reseals_to_different_bytes() {
        assertFalse(sha256Hex(sealPng(bmp(), base)) == sha256Hex(sealPng(bmp(), base.copy(captureId = "srv-9"))))
    }

    @Test
    fun banner_is_appended_below_the_source_so_no_evidence_pixels_are_covered() {
        val sealed = sealPng(bmp(), base).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        assertEquals(200, sealed.width)
        assertTrue(sealed.height > 200)
        assertEquals(Color.RED, sealed.getPixel(0, 0))
        assertEquals(Color.RED, sealed.getPixel(199, 199))
    }

    @Test
    fun address_is_truncated_in_the_middle() {
        assertEquals("0x1234…cdef", truncateMiddle("0x1234567890abcdef", 6, 4))
        assertEquals("0xabc", truncateMiddle("0xabc", 6, 4))
    }
}
