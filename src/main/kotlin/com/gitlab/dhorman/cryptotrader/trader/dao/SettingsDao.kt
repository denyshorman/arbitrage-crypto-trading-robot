package com.gitlab.dhorman.cryptotrader.trader.dao

import com.gitlab.dhorman.cryptotrader.service.poloniex.model.Amount
import com.gitlab.dhorman.cryptotrader.service.poloniex.model.Currency
import io.vavr.collection.List
import io.vavr.collection.Map
import io.vavr.kotlin.hashMap
import io.vavr.kotlin.list
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
class SettingsDao {
    @Volatile
    var primaryCurrencies: List<Currency> = list("USDT", "USDC", "USDJ", "PAX")

    @Volatile
    var fixedAmount: Map<Currency, Amount> = hashMap(
        "USDT" to BigDecimal(104),
        "USDC" to BigDecimal(0),
        "USDJ" to BigDecimal(0),
        "PAX" to BigDecimal(0)
    )

    @Volatile
    var minTradeAmount = BigDecimal(2)
}
