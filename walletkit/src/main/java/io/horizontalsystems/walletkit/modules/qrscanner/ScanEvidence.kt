package io.horizontalsystems.walletkit.modules.qrscanner

import android.graphics.Bitmap
import android.util.Log
import io.horizontalsystems.walletkit.core.evidence.EvidenceHooks
import io.horizontalsystems.walletkit.core.evidence.EvidenceHooksRegistry

// Only payment-address scans opt in; seed phrases, private keys and WalletConnect URIs never do
internal fun dispatchScanEvidence(
    captureEvidence: Boolean,
    locked: Boolean,
    frame: Bitmap?,
    text: String,
    hooks: EvidenceHooks = EvidenceHooksRegistry.hooks,
) {
    if (!captureEvidence || locked) return
    try {
        hooks.onPaymentQrScanned(frame, text)
    } catch (e: Exception) {
        // Logging must never throw either (android.util.Log is unmocked in JVM unit tests)
        runCatching { Log.w("ScanEvidence", "evidence hook failed", e) }
    }
}
