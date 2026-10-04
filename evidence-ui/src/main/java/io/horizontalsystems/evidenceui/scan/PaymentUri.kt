package io.horizontalsystems.evidenceui.scan

import io.horizontalsystems.evidence.model.DecodedScan

// URI scheme → wallet blockchain uid (mapped to Opie keys later by the envelope's mapNetworkKey)
private val schemes = mapOf(
    "bitcoin" to "bitcoin",
    "bitcoincash" to "bitcoin-cash",
    "litecoin" to "litecoin",
    "dash" to "dash",
    "zcash" to "zcash",
    "ethereum" to "ethereum",
    "tron" to "tron",
    "solana" to "solana",
    "ton" to "the-open-network",
    "stellar" to "stellar",
)

private val addressLike = Regex("^[A-Za-z0-9:_-]{20,128}$")

/** Best-effort: network from a payment URI scheme, address from its path; else raw only. */
fun parsePaymentQr(text: String): DecodedScan {
    val raw = text.trim()
    val scheme = raw.substringBefore(':', "").lowercase()
    val network = schemes[scheme]
    if (network != null) {
        val address = raw.substringAfter(':').removePrefix("//")
            .substringBefore('?').substringBefore('@').substringBefore('/')
        return DecodedScan(raw, address.ifEmpty { null }, network)
    }
    return DecodedScan(raw, raw.takeIf { addressLike.matches(it) }, null)
}
