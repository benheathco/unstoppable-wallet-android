package io.horizontalsystems.walletkit.modules.qrscanner

import android.graphics.Bitmap
import io.horizontalsystems.walletkit.core.evidence.EvidenceHooks
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanEvidenceTest {
    private class Recording : EvidenceHooks {
        val calls = mutableListOf<String>()
        override fun onPaymentQrScanned(frame: Bitmap?, text: String) {
            calls += text
        }
    }

    @Test
    fun captures_only_when_opted_in_and_unlocked() {
        val hooks = Recording()
        dispatchScanEvidence(captureEvidence = false, locked = false, frame = null, text = "seed words", hooks = hooks)
        dispatchScanEvidence(captureEvidence = true, locked = true, frame = null, text = "locked", hooks = hooks)
        dispatchScanEvidence(captureEvidence = true, locked = false, frame = null, text = "bitcoin:bc1q", hooks = hooks)
        assertEquals(listOf("bitcoin:bc1q"), hooks.calls)
    }

    @Test
    fun a_throwing_hook_never_breaks_scanning() {
        val hooks = object : EvidenceHooks {
            override fun onPaymentQrScanned(frame: Bitmap?, text: String) = throw IllegalStateException("boom")
        }
        dispatchScanEvidence(captureEvidence = true, locked = false, frame = null, text = "x", hooks = hooks)
    }
}
