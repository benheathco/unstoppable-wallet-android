package io.horizontalsystems.evidence.net

/**
 * [key] is what goes in candidates[].network. [mapped] = false means Opie has no registry
 * entry for it: the raw key is still sent (never dropped) but flagged, so attribution misses
 * are visible rather than silent.
 */
data class NetworkKeyResult(val key: String, val mapped: Boolean)

// Opie validates candidates[].network by exact key against its chain registry
// (apps/crypto/chains.py by_key; mirrored in the extension's content/chains_bundled.js),
// so values here must be those PascalCase registry keys. Lookup keys are lowercase:
// the wallet's BlockchainType uids plus the registry keys themselves.
private val opieKeys: Map<String, String> = buildMap {
    fun chain(opieKey: String, vararg walletIds: String) {
        put(opieKey.lowercase(), opieKey)
        walletIds.forEach { put(it, opieKey) }
    }
    chain("Bitcoin")
    chain("BitcoinCash", "bitcoin-cash")
    chain("Litecoin")
    chain("Dash")
    chain("Dogecoin")
    chain("Zcash")
    chain("Ethereum")
    chain("Bsc", "binance-smart-chain")
    chain("Polygon", "polygon-pos")
    chain("AvalancheC", "avalanche")
    chain("Optimism", "optimistic-ethereum")
    chain("ArbitrumOne", "arbitrum-one")
    chain("Base")
    chain("ZkSyncEra", "zksync")
    chain("Tron")
    chain("Solana")
    chain("Ton", "the-open-network")
    chain("Stellar")
}

fun mapNetworkKey(walletChain: String): NetworkKeyResult {
    val normalized = walletChain.trim().lowercase()
    val opieKey = opieKeys[normalized]
    return if (opieKey != null) NetworkKeyResult(opieKey, true) else NetworkKeyResult(normalized, false)
}
