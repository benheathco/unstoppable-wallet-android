package io.horizontalsystems.evidenceui.scan

import io.horizontalsystems.evidence.model.DecodedScan
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentUriTest {
    @Test
    fun bip21_gives_bitcoin_and_address() {
        assertEquals(
            DecodedScan("bitcoin:bc1qabc?amount=0.1", "bc1qabc", "bitcoin"),
            parsePaymentQr("bitcoin:bc1qabc?amount=0.1"),
        )
    }

    @Test
    fun eip681_strips_chain_and_function_suffixes() {
        assertEquals("0xAbC", parsePaymentQr("ethereum:0xAbC@1/transfer?address=0x1").address)
        assertEquals("ethereum", parsePaymentQr("ethereum:0xAbC").network)
    }

    @Test
    fun bare_text_is_the_address_with_unknown_network() {
        assertEquals(
            DecodedScan("TXyz1234567890abcdefghij", "TXyz1234567890abcdefghij", null),
            parsePaymentQr("  TXyz1234567890abcdefghij "),
        )
    }

    @Test
    fun garbage_keeps_raw_payload_and_null_address() {
        assertEquals(DecodedScan("hello world", null, null), parsePaymentQr("hello world"))
    }
}
