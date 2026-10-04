package io.horizontalsystems.walletkit.core.evidence

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.horizontalsystems.walletkit.modules.nav3.HSPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EvidenceSettingsEntry(@StringRes val title: Int, @DrawableRes val icon: Int, val page: HSPage)

/**
 * Seam for the private evidence build. Stock flavors keep the no-op default; the evidence
 * flavor's :evidence-ui registers a real implementation at startup. Walletkit never depends on
 * evidence code.
 */
interface EvidenceHooks {
    // Payment-address scans only (QRScannerActivity's captureEvidence flag). Must never throw.
    fun onPaymentQrScanned(frame: Bitmap?, text: String) {}

    val registrationGate: HSPage? get() = null
    val needsRegistration: StateFlow<Boolean> get() = NOT_NEEDED
    val settingsEntry: EvidenceSettingsEntry? get() = null

    companion object {
        private val NOT_NEEDED = MutableStateFlow(false)
    }
}

object EvidenceHooksRegistry {
    @Volatile
    var hooks: EvidenceHooks = object : EvidenceHooks {}
}
