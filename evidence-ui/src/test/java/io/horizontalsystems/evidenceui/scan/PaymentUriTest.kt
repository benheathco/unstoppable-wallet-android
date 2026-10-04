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
        assertEquals("0x1", parsePaymentQr("ethereum:0xAbC@1/transfer?address=0x1").address)
        assertEquals("0xAbC", parsePaymentQr("ethereum:0xAbC@1").address)
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

    @Test
    fun strips_pay_prefix() {
        assertEquals("0xAbC", parsePaymentQr("ethereum:pay-0xAbC@1").address)
    }

    @Test
    fun extracts_last_path_segment_as_address() {
        assertEquals("EQAbc", parsePaymentQr("ton://transfer/EQAbc").address)
    }

    @Test
    fun extracts_query_parameter_address() {
        assertEquals("0xRecipient", parsePaymentQr("ethereum:0xToken@1/transfer?address=0xRecipient").address)
    }

    @Test
    fun uppercase_scheme_preserves_address_case() {
        assertEquals("BC1QABC", parsePaymentQr("BITCOIN:BC1QABC").address)
        assertEquals("bitcoin", parsePaymentQr("BITCOIN:BC1QABC").network)
    }

    @Test
    fun tron_uri() {
        assertEquals("THAddress", parsePaymentQr("tron:THAddress").address)
        assertEquals("tron", parsePaymentQr("tron:THAddress").network)
    }

    @Test
    fun solana_uri() {
        assertEquals("SolAddr", parsePaymentQr("solana:SolAddr").address)
        assertEquals("solana", parsePaymentQr("solana:SolAddr").network)
    }

    @Test
    fun empty_bitcoin_uri() {
        assertEquals(null, parsePaymentQr("bitcoin:").address)
    }

    @Test
    fun bip21_ignores_address_query_param() {
        assertEquals("bc1qreal1234567890abcdefgh", parsePaymentQr("bitcoin:bc1qreal1234567890abcdefgh?label=x&address=attacker").address)
    }

    @Test
    fun eip681_without_transfer_ignores_address_param() {
        assertEquals("0xAbC", parsePaymentQr("ethereum:0xAbC@1?address=0xEvil").address)
    }
}
