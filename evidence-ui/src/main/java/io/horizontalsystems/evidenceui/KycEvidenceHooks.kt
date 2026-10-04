package io.horizontalsystems.evidenceui

import android.content.Context
import android.graphics.Bitmap
import io.horizontalsystems.evidence.model.DecodedScan
import io.horizontalsystems.evidenceui.registration.RegistrationIntroPage
import io.horizontalsystems.evidenceui.scan.parsePaymentQr
import io.horizontalsystems.evidenceui.status.EvidenceStatusPage
import io.horizontalsystems.walletkit.core.evidence.EvidenceHooks
import io.horizontalsystems.walletkit.core.evidence.EvidenceSettingsEntry
import io.horizontalsystems.walletkit.modules.nav3.HSPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class KycEvidenceHooks(
    context: Context,
    private val registered: () -> Boolean = { EvidenceGraph.store.registration.value != null },
    private val record: (Bitmap, DecodedScan) -> Unit = { frame, scan -> EvidenceGraph.recorder.recordScan(frame, scan) },
) : EvidenceHooks {

    init {
        EvidenceGraph.init(context)
    }

    // A paste has no frame: without an image there is no evidence artifact to seal
    override fun onPaymentQrScanned(frame: Bitmap?, text: String) {
        try {
            if (frame == null || !registered()) return
            record(frame, parsePaymentQr(text))
        } catch (e: Exception) {
            // Evidence capture must never break scanning
        }
    }

    override val registrationGate: HSPage get() = RegistrationIntroPage()

    override val needsRegistration: StateFlow<Boolean> = EvidenceGraph.store.registration
        .map { it == null }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, !registered())

    override val copyBalanceOnLongPress: Boolean get() = true

    override val settingsEntry = EvidenceSettingsEntry(
        title = R.string.Evidence_SettingsTitle,
        icon = io.horizontalsystems.walletkit.R.drawable.file_24,
        page = EvidenceStatusPage,
    )
}
