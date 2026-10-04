package io.horizontalsystems.evidence.net

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkKeyMapTest {
    @Test
    fun `known chain maps to opie key`() {
        assertEquals(NetworkKeyResult("Ethereum", true), mapNetworkKey("Ethereum"))
        assertEquals(NetworkKeyResult("Tron", true), mapNetworkKey("tron"))
    }

    @Test
    fun `wallet blockchain uid maps to opie registry key`() {
        assertEquals(NetworkKeyResult("Bsc", true), mapNetworkKey("binance-smart-chain"))
        assertEquals(NetworkKeyResult("Polygon", true), mapNetworkKey("polygon-pos"))
        assertEquals(NetworkKeyResult("Optimism", true), mapNetworkKey("optimistic-ethereum"))
        assertEquals(NetworkKeyResult("Ton", true), mapNetworkKey("the-open-network"))
    }

    @Test
    fun `unknown chain is passed through and flagged unmapped`() {
        assertEquals(NetworkKeyResult("some-l2", false), mapNetworkKey("some-l2"))
        assertEquals(NetworkKeyResult("gnosis", false), mapNetworkKey("gnosis"))
    }
}
