package io.horizontalsystems.evidence.net

import io.horizontalsystems.evidence.model.SendRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeTest {
    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun send(tokenContract: String? = "0xdac", network: String = "ethereum") = SendRecord(
        "0xabc", network, "USDT", tokenContract, "100.0", "0.5", "memo-1", "0xtxhash", null, "2026-10-04T00:00:00Z",
    )

    @Test
    fun `envelope sends mobile_app method and blank source_url`() {
        val o = parse(
            buildEnvelope(
                "proj-1", "2026-10-04T00:00:00Z", "req-1",
                listOf("scan_frame"), listOf(Candidate("0xabc", "Ethereum", Candidate.DEPOSIT_ADDRESS)), "scan", "1.0",
            )
        )
        assertEquals("mobile_app", o.str("capture_method"))
        assertEquals("", o.str("source_url"))
        assertEquals("req-1", o.str("client_request_id"))
        assertEquals("proj-1", o.str("project"))
        assertEquals("2026-10-04T00:00:00Z", o.str("client_captured_at"))
        assertEquals("1.0", o.str("extension_version"))
        assertEquals("scan", o.str("notes"))
        assertEquals(listOf("scan_frame"), o.getValue("expected_artifacts").jsonArray.map { it.jsonPrimitive.content })
        val candidate = o.getValue("candidates").jsonArray[0].jsonObject
        assertEquals("Ethereum", candidate.str("network"))
        assertEquals("DEPOSIT_ADDRESS", candidate.str("subject_type"))
        assertEquals("0xabc", candidate.str("identifier"))
        assertEquals("dom_detected", candidate.str("extraction_method"))
    }

    @Test
    fun `envelope omits candidates and notes when empty`() {
        val o = parse(buildEnvelope("proj-1", "2026-10-04T00:00:00Z", "req-1", listOf("scan_frame"), emptyList(), null, "1.0"))
        assertFalse(o.containsKey("candidates"))
        assertFalse(o.containsKey("notes"))
    }

    @Test
    fun `send yields a deposit address candidate with asset and memo, token contract in notes`() {
        val r = send()
        val cands = sendCandidates(r)
        assertEquals(1, cands.size)
        assertEquals(Candidate.DEPOSIT_ADDRESS, cands[0].subjectType)
        assertEquals("0xabc", cands[0].identifier)
        assertEquals("Ethereum", cands[0].network)
        assertEquals("USDT", cands[0].asset)
        assertEquals("memo-1", cands[0].memo)
        val notes = sendNotes(r)
        listOf("100.0", "USDT", "0xdac", "0.5", "memo-1", "0xtxhash").forEach { assertTrue(it, notes.contains(it)) }
    }

    @Test
    fun `native coin send has no token contract in notes`() {
        assertFalse(sendNotes(send(tokenContract = null)).contains("token contract"))
    }

    @Test
    fun `unmapped network is sent raw and flagged in notes`() {
        val r = send(network = "gnosis")
        assertEquals("gnosis", sendCandidates(r)[0].network)
        assertTrue(sendNotes(r).contains("gnosis"))
        assertTrue(sendNotes(r).contains("not in Opie chain registry"))
    }

    @Test
    fun `candidate fields are clamped to server limits with full values kept in notes`() {
        val longMemo = "m".repeat(300)
        val r = send().copy(memo = longMemo, assetSymbol = "S".repeat(50))
        val c = sendCandidates(r).single()
        assertEquals(128, c.memo.length)
        assertEquals(32, c.asset.length)
        assertTrue(sendNotes(r).contains(longMemo))
        assertEquals(255, Candidate("a".repeat(400), "n".repeat(100), Candidate.DEPOSIT_ADDRESS).clamped().identifier.length)
        assertEquals(64, Candidate("a", "n".repeat(100), Candidate.DEPOSIT_ADDRESS).clamped().network.length)
    }

    @Test
    fun `operator note is appended to send notes`() {
        assertTrue(sendNotes(send().copy(notes = "Shop on 5th")).contains("Shop on 5th"))
    }
}
