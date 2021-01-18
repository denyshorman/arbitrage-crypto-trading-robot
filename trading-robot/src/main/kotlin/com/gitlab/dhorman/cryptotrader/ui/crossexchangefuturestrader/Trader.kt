package com.gitlab.dhorman.cryptotrader.ui.crossexchangefuturestrader

import com.gitlab.dhorman.cryptotrader.robots.crossexchangearbitrage.BinanceFuturesMarket
import com.gitlab.dhorman.cryptotrader.robots.crossexchangearbitrage.PoloniexFuturesMarket
import com.gitlab.dhorman.cryptotrader.robots.crossexchangearbitrage.cache.service.CacheableBinanceFuturesApi
import com.gitlab.dhorman.cryptotrader.robots.crossexchangearbitrage.cache.service.CacheablePoloniexFuturesApi
import com.gitlab.dhorman.cryptotrader.robots.crossexchangearbitrage.trader.CrossExchangeTrader
import com.gitlab.dhorman.cryptotrader.service.binance.BinanceFuturesApi
import com.gitlab.dhorman.cryptotrader.service.poloniexfutures.PoloniexFuturesApi
import com.gitlab.dhorman.cryptotrader.util.Secrets
import java.math.BigDecimal

fun createCrossExchangeTrader(): CrossExchangeTrader {
    val poloniexMarket = "BTCUSDTPERP"
    val binanceMarket = "btcusdt"

    val poloniexFuturesApi = PoloniexFuturesApi(
        apiKey = Secrets.get("POLONIEX_FUTURES_API_KEY")!!,
        apiSecret = Secrets.get("POLONIEX_FUTURES_API_SECRET")!!,
        apiPassphrase = Secrets.get("POLONIEX_FUTURES_API_PASSPHRASE")!!,
    )

    val binanceFuturesApi = BinanceFuturesApi(
        apiKey = Secrets.get("BINANCE_FUTURES_API_KEY")!!,
        apiSecret = Secrets.get("BINANCE_FUTURES_API_SECRET")!!,
        apiNet = BinanceFuturesApi.ApiNet.Main,
    )

    val poloniexFuturesApiCache = CacheablePoloniexFuturesApi(poloniexFuturesApi)
    val binanceFuturesApiCache = CacheableBinanceFuturesApi(binanceFuturesApi)

    val poloniexFuturesMarket = PoloniexFuturesMarket(
        cacheablePoloniexFuturesApi = poloniexFuturesApiCache,
        market = poloniexMarket,
    )

    val binanceFuturesMarket = BinanceFuturesMarket(
        cacheableBinanceFuturesApi = binanceFuturesApiCache,
        market = binanceMarket,
    )

    return CrossExchangeTrader(
        leftMarket = poloniexFuturesMarket,
        rightMarket = binanceFuturesMarket,
        maxOpenCost = BigDecimal("100"),
    )
}
