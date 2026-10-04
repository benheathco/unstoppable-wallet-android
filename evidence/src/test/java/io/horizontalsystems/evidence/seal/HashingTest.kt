package io.horizontalsystems.evidence.seal

import org.junit.Assert.assertEquals
import org.junit.Test

class HashingTest {
    @Test
    fun `sha256 is stable and lowercase hex`() {
        assertEquals(
            "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
            sha256Hex("foo".toByteArray()),
        )
    }
}
