package io.horizontalsystems.evidence.model

data class DecodedScan(
    val rawPayload: String,
    val address: String?,
    // Opie network key, e.g. "ethereum", "tron" (candidates[].network)
    val network: String?,
    // Operator's entity note
    val notes: String? = null,
)

/**
 * The wallet's hand-off for a send — not an uploaded file. Maps to capture candidates
 * (address, tokenContract) plus notes. Amount/fee are strings exactly as the wallet
 * displayed them, so evidence shows the figures the operator saw.
 */
data class SendRecord(
    val address: String,
    val network: String,
    val assetSymbol: String,
    // Contract address of a specific token; null = native coin
    val tokenContract: String?,
    val amount: String,
    val fee: String?,
    // On-chain memo/tag (distinct from notes)
    val memo: String?,
    val txHashIfKnown: String?,
    val notes: String? = null,
    val capturedAtIso: String,
)

data class EvidenceStatus(val enrolled: Boolean, val pendingBundles: Int)
