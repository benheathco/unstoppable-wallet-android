package io.horizontalsystems.evidence.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordsTest {
    @Test
    fun `scan with non-address payload keeps raw payload and null address`() {
        val scan = DecodedScan(rawPayload = "hello world", address = null, network = null)
        assertEquals("hello world", scan.rawPayload)
        assertNull(scan.address)
    }

    @Test
    fun `send record carries token contract distinct from symbol`() {
        val r = SendRecord(
            address = "0xabc", network = "ethereum", assetSymbol = "USDT",
            tokenContract = "0xdac17...", amount = "100.0", fee = "0.5",
            memo = null, txHashIfKnown = null, capturedAtIso = "2026-10-04T00:00:00Z",
        )
        assertEquals("USDT", r.assetSymbol)
        assertEquals("0xdac17...", r.tokenContract)
    }
}
