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

/**
 * Bakes the seal banner into the pixels and returns PNG bytes. The banner is appended below
 * [source] (never painted over it) so no evidence pixels are covered, and so the uploader can
 * re-seal the same source with the server capture id. Output depends only on [source] and
 * [meta]: fixed geometry, no clock reads.
 */
fun sealPng(source: Bitmap, meta: SealMeta): ByteArray {
    val lines = bannerLines(meta)
    val textSize = maxOf(12f, source.width / 40f)
    val lineHeight = textSize * 1.4f
    val padding = textSize / 2
    val bannerHeight = (lines.size * lineHeight + padding * 2).toInt()

    val sealed = Bitmap.createBitmap(source.width, source.height + bannerHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sealed)
    canvas.drawBitmap(source, 0f, 0f, null)

    val bannerTop = source.height.toFloat()
    canvas.drawRect(0f, bannerTop, source.width.toFloat(), sealed.height.toFloat(), Paint().apply { color = Color.BLACK })

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        this.textSize = textSize
        typeface = Typeface.MONOSPACE
    }
    lines.forEachIndexed { i, line ->
        canvas.drawText(line, padding, bannerTop + padding + (i + 1) * lineHeight - lineHeight * 0.3f, textPaint)
    }

    return ByteArrayOutputStream().use { out ->
        sealed.compress(Bitmap.CompressFormat.PNG, 100, out)
        sealed.recycle()
        out.toByteArray()
    }
}

private fun bannerLines(meta: SealMeta): List<String> = buildList {
    add("REQ ${meta.clientRequestId}")
    add("CAP ${meta.captureId ?: "-"}")
    add("${meta.network ?: "-"} ${meta.address?.let { truncateMiddle(it, 6, 4) } ?: "-"}")
    meta.amount?.let { add("AMT $it") }
    add("${meta.capturedAtIso} DEV ${meta.deviceId} v${meta.appVersion}")
}

internal fun truncateMiddle(value: String, head: Int, tail: Int): String =
    if (value.length <= head + tail + 1) value else value.take(head) + "…" + value.takeLast(tail)
