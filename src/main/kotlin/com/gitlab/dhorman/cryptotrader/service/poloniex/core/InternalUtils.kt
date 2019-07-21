package com.gitlab.dhorman.cryptotrader.service.poloniex.core

import java.math.BigDecimal
import java.math.RoundingMode

// USDT -> ETH
fun calcQuoteAmount(baseAmount: BigDecimal, price: BigDecimal): BigDecimal {
    return baseAmount.divide(price, 8, RoundingMode.DOWN)
}

// -USDT
fun buyBaseAmount(quoteAmount: BigDecimal, price: BigDecimal): BigDecimal {
    return (quoteAmount * price).setScale(8, RoundingMode.DOWN)
}

// +ETH
fun buyQuoteAmount(quoteAmount: BigDecimal, feeMultiplier: BigDecimal): BigDecimal {
    return (quoteAmount * feeMultiplier).setScale(8, RoundingMode.UP)
}

// -ETH
fun sellQuoteAmount(quoteAmount: BigDecimal): BigDecimal {
    return quoteAmount
}

// +USDT
fun sellBaseAmount(quoteAmount: BigDecimal, price: BigDecimal, feeMultiplier: BigDecimal): BigDecimal {
    return ((quoteAmount * price).setScale(8, RoundingMode.DOWN) * feeMultiplier).setScale(8, RoundingMode.UP)
}