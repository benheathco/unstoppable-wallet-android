package io.horizontalsystems.evidence

import android.graphics.Bitmap
import android.view.View
import io.horizontalsystems.evidence.model.DecodedScan
import io.horizontalsystems.evidence.model.EvidenceStatus
import io.horizontalsystems.evidence.model.SendRecord
import kotlinx.coroutines.flow.StateFlow

/**
 * The only surface the wallet talks to. Non-blocking and never throws into the wallet:
 * durability is the Room write, done synchronously before return, so a capture failure
 * can never break a scan or a send.
 */
interface EvidenceRecorder {
    fun recordScan(frame: Bitmap, decoded: DecodedScan)
    fun recordSend(send: SendRecord, confirmationView: View)

    // Operator-side only: enrolled? pending count
    val status: StateFlow<EvidenceStatus>
}
