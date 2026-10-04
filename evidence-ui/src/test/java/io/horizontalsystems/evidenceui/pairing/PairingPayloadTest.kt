package io.horizontalsystems.evidenceui.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {
    private val ok = """{"v":1,"kind":"opie-device","server":"http://localhost:8000","key":"ab.cd","team":"kyc","project":"p-1"}"""

    @Test
    fun parses_v1_payload() {
        assertEquals(PairingPayload("http://localhost:8000", "ab.cd", "kyc", "p-1"), PairingPayload.parse(ok).getOrThrow())
    }

    @Test
    fun project_is_optional() {
        val p = PairingPayload.parse(ok.replace(""","project":"p-1"""", "")).getOrThrow()
        assertNull(p.project)
    }

    @Test
    fun trims_whitespace_and_trailing_slash() {
        val p = PairingPayload.parse("  " + ok.replace("8000\"", "8000/\"") + "\n").getOrThrow()
        assertEquals("http://localhost:8000", p.server)
    }

    @Test
    fun rejects_unknown_version_kind_scheme_and_missing_fields() {
        listOf(
            ok.replace("\"v\":1", "\"v\":2"),
            ok.replace("opie-device", "other"),
            ok.replace("http://localhost:8000", "ftp://x"),
            ok.replace(""","key":"ab.cd"""", ""),
            "bitcoin:bc1qxyz",
            "",
        ).forEach { assertTrue(it, PairingPayload.parse(it).isFailure) }
    }

    @Test
    fun rejects_invalid_server_addresses() {
        listOf(
            ok.replace("http://localhost:8000", "http://"),
            ok.replace("http://localhost:8000", "https://?"),
            ok.replace("http://localhost:8000", "javascript:alert(1)"),
        ).forEach { assertTrue(it, PairingPayload.parse(it).isFailure) }
    }

    @Test
    fun rejects_blank_key() {
        assertTrue(ok.replace(""","key":"ab.cd"""", ""), PairingPayload.parse(ok.replace(""","key":"ab.cd"""", "")).isFailure)
    }

    @Test
    fun accepts_uppercase_scheme() {
        val p = PairingPayload.parse(ok.replace("http://", "HTTP://")).getOrThrow()
        assertEquals("HTTP://localhost:8000", p.server)
    }

    @Test
    fun accepts_https_uppercase() {
        val p = PairingPayload.parse(ok.replace("http://localhost:8000", "HTTPS://opie.example.com")).getOrThrow()
        assertEquals("HTTPS://opie.example.com", p.server)
    }
}
