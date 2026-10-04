package io.horizontalsystems.walletkit.modules.balance.token

import java.math.BigDecimal

// Plain number for the clipboard: no symbol, grouping or exponent. Null while hidden so a
// hidden balance is never copied.
fun balanceCopyText(value: BigDecimal?, hidden: Boolean): String? {
    if (hidden || value == null) return null
    return value.stripTrailingZeros().toPlainString()
}
