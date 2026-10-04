package io.horizontalsystems.evidenceui

import android.content.Context
import androidx.startup.Initializer
import io.horizontalsystems.walletkit.core.evidence.EvidenceHooksRegistry

class EvidenceUiInitializer : Initializer<KycEvidenceHooks> {
    override fun create(context: Context): KycEvidenceHooks =
        KycEvidenceHooks(context.applicationContext).also { EvidenceHooksRegistry.hooks = it }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
