package com.gitlab.dhorman.cryptotrader.robots.crossexchangearbitrage

import com.gitlab.dhorman.cryptotrader.exchangesdk.binancefutures.BinanceFuturesApi
import com.gitlab.dhorman.cryptotrader.robots.crossexchangearbitrage.cache.service.CacheableBinanceFuturesApi
import com.gitlab.dhorman.cryptotrader.util.EventData
import com.gitlab.dhorman.cryptotrader.util.newPayload
import io.vavr.collection.TreeMap
import io.vavr.kotlin.tuple
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.math.BigDecimal

class BinanceFuturesMarket(
    private val cacheableBinanceFuturesApi: CacheableBinanceFuturesApi,
    private val market: String,
) : FuturesMarket, AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("BinanceFuturesMarket_$market"))

    override fun close() {
        scope.cancel()
    }

    // TODO: Correctly implement order book cache
    override val orderBook: Flow<EventData<OrderBook>> = run {
        cacheableBinanceFuturesApi.api.partialBookDepthStream(
            market,
            BinanceFuturesApi.PartialBookDepthEvent.Level.LEVEL_20,
            BinanceFuturesApi.BookUpdateSpeed.TIME_100_MS,
        )
            .map { it.newPayload(it.payload?.toOrderBook()) }
            .shareIn(scope, SharingStarted.Lazily, 1)
    }

    override val generalInfo: Flow<FuturesMarketGeneralInfo> = run {
        cacheableBinanceFuturesApi.exchangeInfo.combine(cacheableBinanceFuturesApi.getCommissionRate(market)) { exchangeInfo, commissionRate ->
            val marketInfo = exchangeInfo.symbolsIndexed[market.toUpperCase()]
                ?: throw RuntimeException("Futures market $market does not exist")

            val qtyFilter = marketInfo.filtersIndexed[BinanceFuturesApi.ExchangeInfo.ExchangeFilter.MarketLotSizeFilter::class]
                as? BinanceFuturesApi.ExchangeInfo.ExchangeFilter.MarketLotSizeFilter
                ?: throw RuntimeException("Futures market $market does not exist")

            FuturesMarketGeneralInfo(
                makerFee = commissionRate.makerCommissionRate,
                takerFee = commissionRate.makerCommissionRate,
                minQuoteAmount = qtyFilter.minQty,
                baseAssetPrecision = marketInfo.baseAssetPrecision,
                quotePrecision = marketInfo.quotePrecision,
            )
        }.shareIn(scope, SharingStarted.Lazily, 1)
    }

    override suspend fun createMarketPosition(quoteAmount: BigDecimal, positionSide: PositionSide): FuturesMarketPosition {
        val generalInfo = generalInfo.first()

        return BinanceFuturesMarketPosition(
            cacheableBinanceFuturesApi,
            market,
            quoteAmount,
            positionSide,
            generalInfo.baseAssetPrecision,
        )
    }

    companion object {
        private fun BinanceFuturesApi.PartialBookDepthEvent.toOrderBook(): OrderBook {
            val asks = TreeMap.ofAll(asks.stream()) { tuple(it.price, it.qty) }
            val bids = TreeMap.ofAll(compareByDescending { it }, bids.stream()) { tuple(it.price, it.qty) }
            return OrderBook(asks, bids)
        }
    }
}
