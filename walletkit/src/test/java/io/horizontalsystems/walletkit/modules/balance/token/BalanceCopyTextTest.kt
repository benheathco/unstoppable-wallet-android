package io.horizontalsystems.walletkit.modules.balance.token

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class BalanceCopyTextTest {
    @Test
    fun plain_number() {
        assertEquals("0.0712", balanceCopyText(BigDecimal("0.0712"), hidden = false))
    }

    @Test
    fun tiny_value_has_no_exponent() {
        assertEquals("0.00000001", balanceCopyText(BigDecimal("1E-8"), hidden = false))
    }

    @Test
    fun large_value_has_no_exponent_or_grouping() {
        assertEquals("1234000", balanceCopyText(BigDecimal("1.234E+6"), hidden = false))
    }

    @Test
    fun trailing_zeros_trimmed() {
        assertEquals("0.5", balanceCopyText(BigDecimal("0.50000000"), hidden = false))
        assertEquals("0", balanceCopyText(BigDecimal("0.000"), hidden = false))
    }

    @Test
    fun hidden_returns_null() {
        assertNull(balanceCopyText(BigDecimal("0.0712"), hidden = true))
    }

    @Test
    fun null_value_returns_null() {
        assertNull(balanceCopyText(null, hidden = false))
    }
}
