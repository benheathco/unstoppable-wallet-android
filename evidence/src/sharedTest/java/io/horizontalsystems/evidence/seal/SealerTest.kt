package io.horizontalsystems.evidence.seal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class SealerTest {
    // 720 px → scale 2.0 → 26 px text, large enough for Robolectric's software renderer to
    // produce distinct pixels between different text strings (200 px → 7 px is below its threshold).
    private fun bmp() = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

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
    fun banner_is_at_the_top_so_source_pixels_start_below_it() {
        val source = bmp()
        val sealed = sealPng(source, base).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        assertEquals(source.width, sealed.width)
        assertTrue(sealed.height > source.height)
        // Top-left pixel is the dark banner background, not the red source
        assertNotEquals(Color.RED, sealed.getPixel(0, 0))
        // Bottom of the image is the last row of the source (red)
        assertEquals(Color.RED, sealed.getPixel(0, sealed.height - 1))
    }

    @Test
    fun banner_lines_match_extension_format() {
        val (line1, line2) = bannerLines(base)
        assertEquals("0xabc", line1)
        assertEquals("Captured (UTC): 2026-10-04T00:00:00Z   Capture ID: pending", line2)

        val (_, line2WithId) = bannerLines(base.copy(captureId = "srv-uuid-9"))
        assertEquals("Captured (UTC): 2026-10-04T00:00:00Z   Capture ID: srv-uuid-9", line2WithId)
    }

    @Test
    fun address_is_truncated_in_the_middle() {
        // 18 chars, max 11: keep=10, head=5, tail=5
        assertEquals("0x123…bcdef", truncateMiddle("0x1234567890abcdef", 11))
        assertEquals("0xabc", truncateMiddle("0xabc", 11))
        // Exactly max chars: no truncation
        assertEquals("12345", truncateMiddle("12345", 5))
        // max=1: keep=0, everything collapsed to ellipsis
        assertEquals("…", truncateMiddle("abcdef", 1))
    }
}
