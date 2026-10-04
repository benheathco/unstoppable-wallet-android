package io.horizontalsystems.evidenceui

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.evidence.model.DecodedScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KycEvidenceHooksTest {
    private val recorded = mutableListOf<DecodedScan>()
    private val hooks = KycEvidenceHooks(
        ApplicationProvider.getApplicationContext<Context>(),
        registered = { true },
        record = { _, scan -> recorded += scan },
    )

    @Test
    fun payment_scan_is_parsed_and_recorded() {
        hooks.onPaymentQrScanned(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), "bitcoin:bc1qabc")
        assertEquals(listOf(DecodedScan("bitcoin:bc1qabc", "bc1qabc", "bitcoin")), recorded)
    }

    @Test
    fun scan_without_frame_is_not_recorded() {
        hooks.onPaymentQrScanned(null, "bitcoin:bc1qabc")
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun exposes_gate_and_settings_entry() {
        assertNotNull(hooks.registrationGate)
        assertNotNull(hooks.settingsEntry)
    }

    @Test
    fun copies_balance_on_long_press() {
        assertTrue(hooks.copyBalanceOnLongPress)
    }

    @Test
    fun unregistered_scan_is_not_recorded() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val h = KycEvidenceHooks(ctx, registered = { false }, record = { _, scan -> recorded += scan })
        h.onPaymentQrScanned(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), "bitcoin:bc1qabc")
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun throwing_recorder_does_not_propagate() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val h = KycEvidenceHooks(ctx, registered = { true }, record = { _, _ -> error("boom") })
        h.onPaymentQrScanned(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), "bitcoin:bc1qabc")
    }
}
