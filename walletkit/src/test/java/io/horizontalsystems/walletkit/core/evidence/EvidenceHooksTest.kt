package io.horizontalsystems.walletkit.core.evidence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EvidenceHooksTest {
    @Test
    fun default_hooks_do_nothing() {
        val hooks = EvidenceHooksRegistry.hooks
        hooks.onPaymentQrScanned(null, "bitcoin:bc1q")
        assertFalse(hooks.needsRegistration.value)
        assertNull(hooks.registrationGate)
        assertNull(hooks.settingsEntry)
        assertFalse(hooks.copyBalanceOnLongPress)
    }
}
