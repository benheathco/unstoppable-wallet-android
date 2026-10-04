package io.horizontalsystems.evidence.seal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream

@Serializable
data class SealMeta(
    val clientRequestId: String,
    val network: String?,
    val address: String?,
    val amount: String?,
    val capturedAtIso: String,
    val deviceId: String,
    val appVersion: String,
    // Server capture id — null until registration, then re-stamped by the uploader
    val captureId: String?,
)

private val BANNER_BG = Color.parseColor("#111827")
private val BANNER_FG = Color.parseColor("#f9fafb")

/**
 * Bakes a provenance banner at the TOP of the image, matching the No Your Chain browser
 * extension's format, and returns PNG bytes.
 *
 * The banner is PREPENDED above [source]: source pixels start at y=bannerHeight, so no
 * evidence pixels are covered. The uploader can re-seal the same source with the server
 * capture id without touching the evidence area. Output depends only on [source] and
 * [meta]: fixed geometry, no clock reads.
 */
fun sealPng(source: Bitmap, meta: SealMeta): ByteArray {
    // Scale banner height to match the extension's 56 CSS px × device scale.
    // We derive scale from image width: a 1080 px capture at 3× DPR gives scale=3.
    val scale = source.width / 360f
    val bannerH = Math.round(56f * scale)

    val sealed = Bitmap.createBitmap(source.width, bannerH + source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sealed)

    canvas.drawRect(0f, 0f, source.width.toFloat(), bannerH.toFloat(), Paint().apply { color = BANNER_BG })

    val (line1, line2) = bannerLines(meta)
    val padLeft = 12f * scale

    // Line 1: 13 × scale px, centred vertically at 32% of banner height (matches extension)
    val paint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BANNER_FG
        textSize = 13f * scale
        typeface = Typeface.MONOSPACE
    }
    val fm1 = paint1.fontMetrics
    canvas.drawText(line1, padLeft, bannerH * 0.32f - (fm1.ascent + fm1.descent) / 2f, paint1)

    // Line 2: 11 × scale px, centred vertically at 72% of banner height
    val paint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BANNER_FG
        textSize = 11f * scale
        typeface = Typeface.MONOSPACE
    }
    val fm2 = paint2.fontMetrics
    canvas.drawText(line2, padLeft, bannerH * 0.72f - (fm2.ascent + fm2.descent) / 2f, paint2)

    canvas.drawBitmap(source, 0f, bannerH.toFloat(), null)

    return ByteArrayOutputStream().use { out ->
        sealed.compress(Bitmap.CompressFormat.PNG, 100, out)
        sealed.recycle()
        out.toByteArray()
    }
}

internal fun bannerLines(meta: SealMeta): Pair<String, String> {
    val line1 = truncateMiddle(meta.address ?: "-", 160)
    val captureId = meta.captureId ?: "pending"
    val line2 = "Captured (UTC): ${meta.capturedAtIso}   Capture ID: $captureId"
    return line1 to line2
}

/**
 * Middle-truncate [value] to at most [max] characters, keeping both ends visible.
 * Matches the browser extension's truncateMiddle(str, max).
 */
internal fun truncateMiddle(value: String, max: Int): String {
    if (value.length <= max) return value
    val keep = max - 1
    val head = (keep + 1) / 2
    val tail = keep - head
    return "${value.take(head)}…${value.takeLast(tail)}"
}
