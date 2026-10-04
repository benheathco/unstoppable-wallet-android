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
        val afterScheme = raw.substringAfter(':').removePrefix("//")

        // Try to extract address from query parameter first (for token transfers)
        val queryParams = afterScheme.substringAfter('?', "")
        val addressFromQuery = if (queryParams.isNotEmpty()) {
            queryParams.split('&').find { it.startsWith("address=") }
                ?.substringAfter("address=")
                ?.substringBefore('&')
        } else {
            null
        }

        if (addressFromQuery != null && addressFromQuery.isNotEmpty()) {
            return DecodedScan(raw, addressFromQuery, network)
        }

        // For ton scheme, extract the last non-empty path segment
        if (scheme == "ton") {
            val pathPart = afterScheme.substringBefore('?')
            val segments = pathPart.split('/').filter { it.isNotEmpty() }
            val address = segments.lastOrNull()
            return DecodedScan(raw, address, network)
        }

        // Standard parsing: extract address before @ or /
        var address = afterScheme
            .substringBefore('?')
            .substringBefore('@')
            .substringBefore('/')

        // Strip pay- prefix if present
        if (address.startsWith("pay-")) {
            address = address.removePrefix("pay-")
        }

        return DecodedScan(raw, address.ifEmpty { null }, network)
    }
    return DecodedScan(raw, raw.takeIf { addressLike.matches(it) }, null)
}
