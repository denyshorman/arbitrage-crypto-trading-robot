package com.gitlab.dhorman.cryptotrader.trader

import com.gitlab.dhorman.cryptotrader.core.*
import com.gitlab.dhorman.cryptotrader.service.poloniex.PoloniexApi
import com.gitlab.dhorman.cryptotrader.service.poloniex.core.*
import com.gitlab.dhorman.cryptotrader.service.poloniex.exception.*
import com.gitlab.dhorman.cryptotrader.service.poloniex.model.*
import com.gitlab.dhorman.cryptotrader.service.poloniex.model.Currency
import com.gitlab.dhorman.cryptotrader.trader.dao.TransactionsDao
import com.gitlab.dhorman.cryptotrader.trader.dao.UnfilledMarketsDao
import com.gitlab.dhorman.cryptotrader.trader.model.TranIntentMarket
import com.gitlab.dhorman.cryptotrader.trader.model.TranIntentMarketCompleted
import com.gitlab.dhorman.cryptotrader.trader.model.TranIntentMarketPartiallyCompleted
import com.gitlab.dhorman.cryptotrader.trader.model.TranIntentMarketPredicted
import com.gitlab.dhorman.cryptotrader.util.*
import io.vavr.Tuple2
import io.vavr.Tuple3
import io.vavr.collection.Array
import io.vavr.collection.List
import io.vavr.collection.Map
import io.vavr.kotlin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.collect
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.Comparator

typealias PathId = UUID

@Service
class PoloniexTrader(
    private val poloniexApi: PoloniexApi,
    val data: DataStreams,
    val indicators: IndicatorStreams,
    private val transactionsDao: TransactionsDao,
    private val unfilledMarketsDao: UnfilledMarketsDao,
    @Qualifier("pg_tran_manager") private val tranManager: ReactiveTransactionManager
) {
    private val logger = KotlinLogging.logger {}
    private val soundSignalEnabled = System.getenv("ENABLE_SOUND_SIGNAL") != null
    private lateinit var tranIntentScope: CoroutineScope
    private lateinit var delayedTradeManager: DelayedTradeManager
    private val pathManager = PathManager()

    @Volatile
    var primaryCurrencies: List<Currency> = list("USDT", "USDC")

    @Volatile
    var fixedAmount: Map<Currency, Amount> = hashMap(
        "USDT" to BigDecimal(109),
        "USDC" to BigDecimal(0)
    )

    val tranRequests = Channel<ExhaustivePath>(Channel.RENDEZVOUS)

    fun start(scope: CoroutineScope) = scope.launch {
        logger.info("Start trading on Poloniex")

        subscribeToRequiredTopicsBeforeTrading()

        tranIntentScope = CoroutineScope(Dispatchers.Default + SupervisorJob(coroutineContext[Job]))
        delayedTradeManager = DelayedTradeManager(tranIntentScope)

        startMonitoringForTranRequests(tranIntentScope)
        resumeSleepingTransactions(tranIntentScope)

        try {
            val tickerFlow = Flux.interval(Duration.ofSeconds(30))
                .startWith(0)
                .onBackpressureDrop()

            tickerFlow.collect {
                logger.debug { "Trying to find new transaction..." }

                val (startCurrency, requestedAmount) = requestBalanceForTransaction() ?: return@collect

                logger.debug { "Requested currency $startCurrency and amount $requestedAmount for transaction" }

                val bestPath = selectBestPath(requestedAmount, startCurrency, requestedAmount, primaryCurrencies) ?: return@collect

                logger.debug {
                    val startAmount = bestPath.chain.head().fromAmount
                    val endAmount = bestPath.chain.last().toAmount
                    val longPath = bestPath.longPathString()

                    "Found an optimal path: $longPath, using amount $startAmount with potential profit ${endAmount - startAmount}"
                }

                startPathTransaction(bestPath, tranIntentScope)
            }
        } finally {
            logger.debug { "Trying to cancel all Poloniex transactions..." }

            tranIntentScope.cancel()
        }
    }

    private fun CoroutineScope.startMonitoringForTranRequests(scope: CoroutineScope): Job = this.launch {
        tranRequests.consumeEach {
            startPathTransaction(it, scope)
        }
    }

    private suspend fun subscribeToRequiredTopicsBeforeTrading() {
        coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                data.balances.first()
            }

            launch(start = CoroutineStart.UNDISPATCHED) {
                data.openOrders.first()
            }
        }
    }

    private suspend fun resumeSleepingTransactions(scope: CoroutineScope) {
        logger.debug { "Trying to resume sleeping transactions..." }

        val sleepingTransactions = transactionsDao.getActive()

        for ((id, markets) in sleepingTransactions) {
            val startMarketIdx = partiallyCompletedMarketIndex(markets)!!
            val initAmount = markets.first().fromAmount(markets, 0)
            val targetAmount = markets.last().targetAmount(markets, markets.length() - 1)

            if (initAmount > targetAmount) {
                logger.debug { "Restored path $id is not profitable (${targetAmount - initAmount}). Trying to find new path..." }

                val currMarket = markets[startMarketIdx] as TranIntentMarketPartiallyCompleted
                val fromCurrency = currMarket.fromCurrency
                val fromCurrencyAmount = currMarket.fromAmount
                scope.launch {
                    var bestPath: Array<TranIntentMarket>?

                    while (true) {
                        bestPath = findNewPath(initAmount, fromCurrency, fromCurrencyAmount, primaryCurrencies)

                        if (bestPath != null) {
                            logger.debug { "Found optimal path ${bestPath.pathString()} for $id" }

                            break
                        } else {
                            logger.debug { "Optimal path not found for $id. Retrying..." }
                        }

                        delay(60000)
                    }

                    val changedMarkets = updateMarketsWithBestPath(markets, startMarketIdx, bestPath!!)
                    val newId = UUID.randomUUID()

                    withContext(NonCancellable) {
                        TransactionalOperator.create(tranManager).transactional(mono(Dispatchers.Unconfined) {
                            transactionsDao.addActive(newId, changedMarkets, startMarketIdx)
                            transactionsDao.deleteActive(id)
                        }).retry().awaitFirstOrNull()
                    }

                    TransactionIntent(newId, changedMarkets, startMarketIdx, scope).start()
                }
            } else {
                TransactionIntent(id, markets, startMarketIdx, scope).start()
            }
        }

        logger.debug { "Sleeping transactions restored: ${sleepingTransactions.size}" }
    }

    private suspend fun selectBestPath(
        initAmount: Amount,
        fromCurrency: Currency,
        fromAmount: Amount,
        endCurrencies: List<Currency>,
        recommendedChainCount: Int? = null
    ): ExhaustivePath? {
        val activeTransactionIds = transactionsDao.getActive().map { it._2.id() }

        val allPaths = indicators.getPaths(fromCurrency, fromAmount, endCurrencies, fun(p): Boolean {
            val targetMarket = p.chain.lastOrNull() ?: return false
            return initAmount < targetMarket.toAmount && !activeTransactionIds.contains(p.id)
        }, Comparator { p0, p1 ->
            if (p0.id == p1.id) {
                0
            } else {
                if (recommendedChainCount == null) {
                    p1.profitability.compareTo(p0.profitability)
                } else {
                    val c0 = p0.chain.length()
                    val c1 = p1.chain.length()

                    if (c0 <= recommendedChainCount && c1 <= recommendedChainCount) {
                        p1.profitability.compareTo(p0.profitability)
                    } else if (c0 <= recommendedChainCount) {
                        -1
                    } else if (c1 <= recommendedChainCount) {
                        1
                    } else {
                        val c = c0.compareTo(c1)

                        if (c == 0) {
                            p1.profitability.compareTo(p0.profitability)
                        } else {
                            c
                        }
                    }
                }
            }
        })

        return allPaths.headOption().orNull
    }

    private suspend fun startPathTransaction(bestPath: ExhaustivePath, scope: CoroutineScope) {
        val id = UUID.randomUUID()
        val markets = prepareMarketsForIntent(bestPath)
        val marketIdx = 0
        val fromCurrency = markets[marketIdx].fromCurrency
        val requestedAmount = (markets[marketIdx] as TranIntentMarketPartiallyCompleted).fromAmount

        val canStartTransaction = TransactionalOperator.create(tranManager, object : TransactionDefinition {
            override fun getIsolationLevel() = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }).transactional(mono(Dispatchers.Unconfined) {
            val (available, onOrders) = data.balances.first().getOrNull(fromCurrency) ?: return@mono false
            val (_, amountInUse) = transactionsDao.balanceInUse(fromCurrency) ?: tuple(fromCurrency, BigDecimal.ZERO)
            val reservedAmount = onOrders - amountInUse
            val availableAmount = available + if (reservedAmount >= BigDecimal.ZERO) BigDecimal.ZERO else reservedAmount

            if (availableAmount >= requestedAmount) {
                transactionsDao.addActive(id, markets, marketIdx)
                true
            } else {
                false
            }
        }).retry().awaitFirstOrNull() ?: return

        if (canStartTransaction) {
            TransactionIntent(id, markets, marketIdx, scope).start()
        }
    }

    suspend fun startPathTranFromUnfilledTrans(id: Long) {
        val (initCurrency, initCurrencyAmount, currentCurrency, currentCurrencyAmount) = unfilledMarketsDao.get(id)
            ?: run {
                logger.warn("Unfilled amount not found for id $id")
                return
            }

        val updatedMarkets = Array.of<TranIntentMarket>(
            TranIntentMarketCompleted(
                Market(initCurrency, currentCurrency),
                OrderSpeed.Instant,
                CurrencyType.Base,
                Array.of(
                    adjustFromAmount(initCurrencyAmount),
                    adjustTargetAmount(currentCurrencyAmount, OrderType.Buy)
                )
            ),
            TranIntentMarketPartiallyCompleted(
                Market(initCurrency, currentCurrency),
                OrderSpeed.Instant,
                CurrencyType.Quote,
                currentCurrencyAmount
            )
        )

        val activeMarketId = updatedMarkets.length() - 1

        val bestPath = findNewPath(
            initCurrencyAmount,
            currentCurrency,
            currentCurrencyAmount,
            primaryCurrencies
        )

        if (bestPath == null) {
            logger.debug { "Path not found for ${updatedMarkets.pathString()}" }
            return
        }

        val changedMarkets = updateMarketsWithBestPath(updatedMarkets, activeMarketId, bestPath)
        val tranId = UUID.randomUUID()

        withContext(NonCancellable) {
            TransactionalOperator.create(tranManager, object : TransactionDefinition {
                override fun getIsolationLevel() = TransactionDefinition.ISOLATION_REPEATABLE_READ
            }).transactional(mono(Dispatchers.Unconfined) {
                unfilledMarketsDao.remove(id)
                transactionsDao.addActive(tranId, changedMarkets, activeMarketId)
            }).retry().awaitFirst()
        }

        TransactionIntent(tranId, changedMarkets, activeMarketId, tranIntentScope).start()
    }

    private suspend fun findNewPath(
        initAmount: Amount,
        fromCurrency: Currency,
        fromAmount: Amount,
        endCurrencies: List<Currency>,
        recommendedChainCount: Int? = null
    ): Array<TranIntentMarket>? {
        val bestPath = selectBestPath(initAmount, fromCurrency, fromAmount, endCurrencies/*, recommendedChainCount*/) ?: return null
        return prepareMarketsForIntent(bestPath)
    }

    private suspend fun predictedFromAmount(markets: Array<TranIntentMarket>, idx: Int): Amount {
        val prevIdx = idx - 1
        return when (val prevTran = markets[prevIdx]) {
            is TranIntentMarketCompleted -> prevTran.targetAmount
            is TranIntentMarketPartiallyCompleted -> prevTran.predictedTargetAmount()
            is TranIntentMarketPredicted -> prevTran.predictedTargetAmount(markets, prevIdx)
        }
    }

    private suspend fun TranIntentMarketPredicted.predictedTargetAmount(markets: Array<TranIntentMarket>, idx: Int): Amount {
        val fee = data.fee.first()
        val orderBook = data.getOrderBookFlowBy(market).first()
        val fromAmount = predictedFromAmount(markets, idx)

        return if (orderSpeed == OrderSpeed.Instant) {
            getInstantOrderTargetAmount(orderType, fromAmount, fee.taker, orderBook)
        } else {
            getDelayedOrderTargetAmount(orderType, fromAmount, fee.maker, orderBook)
        }
    }

    private suspend fun TranIntentMarketPartiallyCompleted.predictedTargetAmount(): Amount {
        val fee = data.fee.first()
        val orderBook = data.getOrderBookFlowBy(market).first()

        return if (orderSpeed == OrderSpeed.Instant) {
            getInstantOrderTargetAmount(orderType, fromAmount, fee.taker, orderBook)
        } else {
            getDelayedOrderTargetAmount(orderType, fromAmount, fee.maker, orderBook)
        }
    }

    private suspend fun TranIntentMarket.fromAmount(markets: Array<TranIntentMarket>, idx: Int): Amount {
        return when (this) {
            is TranIntentMarketCompleted -> fromAmount
            is TranIntentMarketPartiallyCompleted -> fromAmount
            is TranIntentMarketPredicted -> predictedFromAmount(markets, idx)
        }
    }

    private suspend fun TranIntentMarket.targetAmount(markets: Array<TranIntentMarket>, idx: Int): Amount {
        return when (this) {
            is TranIntentMarketCompleted -> targetAmount
            is TranIntentMarketPartiallyCompleted -> predictedTargetAmount()
            is TranIntentMarketPredicted -> predictedTargetAmount(markets, idx)
        }
    }

    private suspend fun requestBalanceForTransaction(): Tuple2<Currency, Amount>? {
        // TODO: Review requestBalanceForTransaction algorithm
        val usedBalances = transactionsDao.balancesInUse(primaryCurrencies)
            .groupBy({ it._1 }, { it._2 })
            .mapValues { it.value.reduce { a, b -> a + b } }

        val allBalances = data.balances.first()

        val availableBalance = allBalances.toVavrStream()
            .filter { primaryCurrencies.contains(it._1) }
            .map { currencyAvailableAndOnOrders ->
                val (currency, availableAndOnOrders) = currencyAvailableAndOnOrders
                val (available, onOrders) = availableAndOnOrders
                val balanceInUse = usedBalances.getOrDefault(currency, BigDecimal.ZERO)
                val reservedAmount = onOrders - balanceInUse
                var availableAmount = available - fixedAmount.getOrElse(
                    currency,
                    BigDecimal.ZERO
                ) + if (reservedAmount >= BigDecimal.ZERO) BigDecimal.ZERO else reservedAmount
                if (availableAmount < BigDecimal.ZERO) availableAmount = BigDecimal.ZERO
                tuple(currency, availableAmount)
            }
            .filter { it._2 > BigDecimal(2) }
            .firstOrNull() ?: return null

        val (currency, amount) = availableBalance

        return if (amount > BigDecimal(5)) {
            tuple(currency, BigDecimal(5))
        } else {
            availableBalance
        }
    }

    companion object {
        private fun partiallyCompletedMarketIndex(markets: Array<TranIntentMarket>): Int? {
            var i = 0
            for (market in markets) {
                if (market is TranIntentMarketPartiallyCompleted) break
                i++
            }
            return if (i == markets.length()) null else i
        }

        private fun Array<TranIntentMarket>.pathString(): String {
            return this.iterator()
                .map { "${it.market}${if (it.orderSpeed == OrderSpeed.Instant) "0" else "1"}" }
                .mkString("->")
        }

        private fun Array<TranIntentMarket>.id(): String {
            return this.iterator().map {
                val speed = if (it.orderSpeed == OrderSpeed.Instant) "0" else "1"
                "${it.market.baseCurrency}${it.market.quoteCurrency}$speed"
            }.mkString("")
        }

        private fun prepareMarketsForIntent(bestPath: ExhaustivePath): Array<TranIntentMarket> {
            val markets = bestPath.chain.mapIndexed { index, order ->
                when (order) {
                    is InstantOrder -> run {
                        if (index == 0) {
                            TranIntentMarketPartiallyCompleted(
                                order.market,
                                OrderSpeed.Instant,
                                order.market.tpe(order.fromCurrency)!!,
                                order.fromAmount
                            )
                        } else {
                            TranIntentMarketPredicted(
                                order.market,
                                OrderSpeed.Instant,
                                order.market.tpe(order.fromCurrency)!!
                            )
                        }
                    }
                    is DelayedOrder -> run {
                        if (index == 0) {
                            TranIntentMarketPartiallyCompleted(
                                order.market,
                                OrderSpeed.Delayed,
                                order.market.tpe(order.fromCurrency)!!,
                                order.fromAmount
                            )
                        } else {
                            TranIntentMarketPredicted(
                                order.market,
                                OrderSpeed.Delayed,
                                order.market.tpe(order.fromCurrency)!!
                            )
                        }
                    }
                }
            }

            return Array.ofAll(markets)
        }

        private fun updateMarketsWithBestPath(markets: Array<TranIntentMarket>, marketIdx: Int, bestPath: Array<TranIntentMarket>): Array<TranIntentMarket> {
            return markets.dropRight(markets.length() - marketIdx).appendAll(bestPath)
        }

        private fun getInstantOrderTargetAmount(
            orderType: OrderType,
            fromAmount: Amount,
            takerFeeMultiplier: BigDecimal,
            orderBook: OrderBookAbstract
        ): Amount {
            var unusedFromAmount: Amount = fromAmount
            var toAmount = BigDecimal.ZERO

            if (orderType == OrderType.Buy) {
                if (orderBook.asks.length() == 0) return BigDecimal.ZERO

                for ((basePrice, quoteAmount) in orderBook.asks) {
                    val availableFromAmount = buyBaseAmount(quoteAmount, basePrice)

                    if (unusedFromAmount <= availableFromAmount) {
                        toAmount += buyQuoteAmount(calcQuoteAmount(unusedFromAmount, basePrice), takerFeeMultiplier)
                        break
                    } else {
                        unusedFromAmount -= buyBaseAmount(quoteAmount, basePrice)
                        toAmount += buyQuoteAmount(quoteAmount, takerFeeMultiplier)
                    }
                }
            } else {
                if (orderBook.bids.length() == 0) return BigDecimal.ZERO

                for ((basePrice, quoteAmount) in orderBook.bids) {
                    if (unusedFromAmount <= quoteAmount) {
                        toAmount += sellBaseAmount(unusedFromAmount, basePrice, takerFeeMultiplier)
                        break
                    } else {
                        unusedFromAmount -= sellQuoteAmount(quoteAmount)
                        toAmount += sellBaseAmount(quoteAmount, basePrice, takerFeeMultiplier)
                    }
                }
            }

            return toAmount
        }

        private fun getDelayedOrderTargetAmount(
            orderType: OrderType,
            fromAmount: Amount,
            makerFeeMultiplier: BigDecimal,
            orderBook: OrderBookAbstract
        ): Amount {
            if (orderType == OrderType.Buy) {
                if (orderBook.bids.length() == 0) return BigDecimal.ZERO

                val basePrice = orderBook.bids.head()._1
                val quoteAmount = calcQuoteAmount(fromAmount, basePrice)

                return buyQuoteAmount(quoteAmount, makerFeeMultiplier)
            } else {
                if (orderBook.asks.length() == 0) return BigDecimal.ZERO

                val basePrice = orderBook.asks.head()._1

                return sellBaseAmount(fromAmount, basePrice, makerFeeMultiplier)
            }
        }

        private fun adjustFromAmount(amount: Amount): BareTrade {
            return BareTrade(amount, BigDecimal.ONE, BigDecimal.ZERO)
        }

        private fun adjustTargetAmount(amount: Amount, orderType: OrderType): BareTrade {
            return if (orderType == OrderType.Buy) {
                BareTrade(amount, BigDecimal.ZERO, BigDecimal.ONE)
            } else {
                BareTrade(amount, BigDecimal.ZERO, BigDecimal.ZERO)
            }
        }

        private fun calcQuoteAmountSellTrade(targetAmount: Amount, price: Price, feeMultiplier: BigDecimal): BigDecimal {
            val tfd = targetAmount.divide(feeMultiplier, 8, RoundingMode.DOWN)
            val tfu = targetAmount.divide(feeMultiplier, 8, RoundingMode.UP)

            val qdd = tfd.divide(price, 8, RoundingMode.DOWN)
            val qdu = tfd.divide(price, 8, RoundingMode.UP)
            val qud = tfu.divide(price, 8, RoundingMode.DOWN)
            val quu = tfu.divide(price, 8, RoundingMode.UP)

            val a = sellBaseAmount(qdd, price, feeMultiplier)
            val b = sellBaseAmount(qdu, price, feeMultiplier)
            val c = sellBaseAmount(qud, price, feeMultiplier)
            val d = sellBaseAmount(quu, price, feeMultiplier)

            return list(tuple(qdd, a), tuple(qdu, b), tuple(qud, c), tuple(quu, d))
                .iterator()
                .filter { it._2 <= targetAmount }
                .map { it._1 }
                .max()
                .getOrElseThrow { Exception("Can't find quote amount that matches target price") }
        }

        private fun splitTrade(trade: BareTrade, orderType: OrderType, targetAmount: Amount): Tuple2<BareTrade, List<BareTrade>> = if (orderType == OrderType.Buy) {
            val commitQuoteAmount = run {
                var expectedQuote = targetAmount.divide(trade.feeMultiplier, 8, RoundingMode.DOWN)
                val boughtQuote = buyQuoteAmount(expectedQuote, trade.feeMultiplier)
                if (boughtQuote.compareTo(targetAmount) != 0) {
                    expectedQuote = targetAmount.divide(trade.feeMultiplier, 8, RoundingMode.UP)
                }
                expectedQuote
            }
            val l = BareTrade(trade.quoteAmount - commitQuoteAmount, trade.price, trade.feeMultiplier)
            val r = BareTrade(commitQuoteAmount, trade.price, trade.feeMultiplier)

            tuple(l, list(r))
        } else {
            val commitQuoteAmount: Amount
            val targetAmountDelta: Amount

            if (trade.price.compareTo(BigDecimal.ZERO) == 0 && trade.feeMultiplier.compareTo(BigDecimal.ZERO) == 0) {
                commitQuoteAmount = targetAmount
                targetAmountDelta = BigDecimal.ZERO
            } else {
                commitQuoteAmount = calcQuoteAmountSellTrade(targetAmount, trade.price, trade.feeMultiplier)
                targetAmountDelta = sellBaseAmount(commitQuoteAmount, trade.price, trade.feeMultiplier)
            }

            val updateQuoteAmount = trade.quoteAmount - commitQuoteAmount

            val l = BareTrade(updateQuoteAmount, trade.price, trade.feeMultiplier)
            val r1 = BareTrade(commitQuoteAmount, trade.price, trade.feeMultiplier)
            val r = if (targetAmountDelta.compareTo(BigDecimal.ZERO) != 0) {
                val r0 = adjustTargetAmount(targetAmountDelta, OrderType.Sell)
                list(r0, r1)
            } else {
                list(r1)
            }

            tuple(l, r)
        }

        private fun splitMarkets(markets: Array<TranIntentMarket>, currentMarketIdx: Int, trades: Array<BareTrade>): Tuple2<Array<TranIntentMarket>, Array<TranIntentMarket>> {
            val selectedMarket = markets[currentMarketIdx] as TranIntentMarketPartiallyCompleted

            var updatedMarkets = markets
            var committedMarkets = markets

            // Commit current market and prepare next

            val marketCompleted = TranIntentMarketCompleted(
                selectedMarket.market,
                selectedMarket.orderSpeed,
                selectedMarket.fromCurrencyType,
                trades
            )

            committedMarkets = committedMarkets.update(currentMarketIdx, marketCompleted)

            val nextMarketIdx = currentMarketIdx + 1

            if (nextMarketIdx < markets.length()) {
                val nextMarket = markets[nextMarketIdx]
                val nextMarketInit = TranIntentMarketPartiallyCompleted(
                    nextMarket.market,
                    nextMarket.orderSpeed,
                    nextMarket.fromCurrencyType,
                    marketCompleted.targetAmount
                )
                committedMarkets = committedMarkets.update(nextMarketIdx, nextMarketInit)
            }

            // Update current market

            val updatedMarket = TranIntentMarketPartiallyCompleted(
                selectedMarket.market,
                selectedMarket.orderSpeed,
                selectedMarket.fromCurrencyType,
                selectedMarket.fromAmount - marketCompleted.fromAmount
            )

            updatedMarkets = updatedMarkets.update(currentMarketIdx, updatedMarket)


            // Split trades of previous markets
            var i = currentMarketIdx - 1

            while (i >= 0) {
                val m = markets[i] as TranIntentMarketCompleted

                val updatedTrades = mutableListOf<BareTrade>()
                val committedTrades = mutableListOf<BareTrade>()

                var targetAmount = (committedMarkets[i + 1] as TranIntentMarketCompleted).fromAmount

                for (trade in m.trades) {
                    val amount = if (m.orderType == OrderType.Buy) {
                        buyQuoteAmount(trade.quoteAmount, trade.feeMultiplier)
                    } else {
                        sellBaseAmount(trade.quoteAmount, trade.price, trade.feeMultiplier)
                    }

                    if (amount <= targetAmount) {
                        committedTrades.add(trade)
                        targetAmount -= amount
                    } else {
                        if (targetAmount.compareTo(BigDecimal.ZERO) == 0) {
                            updatedTrades.add(trade)
                        } else {
                            val (l, r) = splitTrade(trade, m.orderType, targetAmount)
                            updatedTrades.add(l)
                            committedTrades.addAll(r)
                            targetAmount = BigDecimal.ZERO
                        }
                    }
                }

                val updated = TranIntentMarketCompleted(
                    m.market,
                    m.orderSpeed,
                    m.fromCurrencyType,
                    Array.ofAll(updatedTrades)
                )

                val committed = TranIntentMarketCompleted(
                    m.market,
                    m.orderSpeed,
                    m.fromCurrencyType,
                    Array.ofAll(committedTrades)
                )

                updatedMarkets = updatedMarkets.update(i, updated)
                committedMarkets = committedMarkets.update(i, committed)

                i--
            }

            return tuple(updatedMarkets, committedMarkets)
        }

        private fun mergeMarkets(currentMarkets: Array<TranIntentMarket>, unfilledMarkets: List<Tuple2<Amount, Amount>>?): Array<TranIntentMarket> {
            if (unfilledMarkets == null || unfilledMarkets.length() == 0) return currentMarkets

            var newMarkets = currentMarkets

            for (amounts in unfilledMarkets) {
                newMarkets = mergeMarkets(newMarkets, amounts._1, amounts._2)
            }

            return newMarkets
        }

        private fun mergeMarkets(currentMarkets: Array<TranIntentMarket>, initCurrencyAmount: Amount, currentCurrencyAmount: Amount): Array<TranIntentMarket> {
            var updatedMarkets = currentMarkets
            val currMarketIdx = partiallyCompletedMarketIndex(currentMarkets)!!
            val prevMarketIdx = currMarketIdx - 1

            if (prevMarketIdx >= 0) {
                // 1. Add amount to trades of init market

                if (initCurrencyAmount.compareTo(BigDecimal.ZERO) != 0) {
                    val initMarket = updatedMarkets[0] as TranIntentMarketCompleted
                    val newTrade = adjustFromAmount(currentCurrencyAmount)
                    val newTrades = initMarket.trades.append(newTrade)
                    val newMarket = TranIntentMarketCompleted(
                        initMarket.market,
                        initMarket.orderSpeed,
                        initMarket.fromCurrencyType,
                        newTrades
                    )
                    updatedMarkets = updatedMarkets.update(0, newMarket)
                }


                // 2. Add an amount to trades of previous market

                val oldMarket = updatedMarkets[prevMarketIdx] as TranIntentMarketCompleted
                val newTrade = adjustTargetAmount(currentCurrencyAmount, oldMarket.orderType)
                val newTrades = oldMarket.trades.append(newTrade)
                val newMarket = TranIntentMarketCompleted(
                    oldMarket.market,
                    oldMarket.orderSpeed,
                    oldMarket.fromCurrencyType,
                    newTrades
                )
                updatedMarkets = updatedMarkets.update(prevMarketIdx, newMarket)
            }

            // 3. Update current market

            val oldCurrentMarket = updatedMarkets[currMarketIdx] as TranIntentMarketPartiallyCompleted
            val newCurrentMarket = TranIntentMarketPartiallyCompleted(
                oldCurrentMarket.market,
                oldCurrentMarket.orderSpeed,
                oldCurrentMarket.fromCurrencyType,
                if (prevMarketIdx >= 0)
                    (updatedMarkets[prevMarketIdx] as TranIntentMarketCompleted).targetAmount
                else
                    oldCurrentMarket.fromAmount + initCurrencyAmount + currentCurrencyAmount
            )
            updatedMarkets = updatedMarkets.update(currMarketIdx, newCurrentMarket)

            return updatedMarkets
        }

        private suspend fun simulateInstantTrades(
            fromAmount: Amount,
            orderType: OrderType,
            orderBookFlow: Flow<OrderBookAbstract>,
            feeFlow: Flow<FeeMultiplier>
        ): Flow<Tuple2<Amount, BareTrade>> = flow {
            val orderBook = orderBookFlow.first()
            val fee = feeFlow.first()

            var unusedFromAmount = fromAmount

            if (orderType == OrderType.Buy) {
                if (orderBook.asks.length() == 0) throw OrderBookEmptyException(SubBookType.Sell)

                for ((basePrice, quoteAmount) in orderBook.asks) {
                    val availableFromAmount = buyBaseAmount(quoteAmount, basePrice)

                    if (unusedFromAmount <= availableFromAmount) {
                        val tradeQuoteAmount = calcQuoteAmount(unusedFromAmount, basePrice)
                        val trade = BareTrade(tradeQuoteAmount, basePrice, fee.taker)
                        unusedFromAmount = BigDecimal.ZERO
                        emit(tuple(unusedFromAmount, trade))
                        break
                    } else {
                        unusedFromAmount -= availableFromAmount
                        val trade = BareTrade(quoteAmount, basePrice, fee.taker)
                        emit(tuple(unusedFromAmount, trade))
                    }
                }
            } else {
                if (orderBook.bids.length() == 0) throw OrderBookEmptyException(SubBookType.Buy)

                for ((basePrice, quoteAmount) in orderBook.bids) {
                    if (unusedFromAmount <= quoteAmount) {
                        val trade = BareTrade(unusedFromAmount, basePrice, fee.taker)
                        unusedFromAmount = BigDecimal.ZERO
                        emit(tuple(unusedFromAmount, trade))
                        break
                    } else {
                        unusedFromAmount -= sellQuoteAmount(quoteAmount)
                        val trade = BareTrade(quoteAmount, basePrice, fee.taker)
                        emit(tuple(unusedFromAmount, trade))
                    }
                }
            }
        }
    }

    inner class TransactionIntent(
        val id: PathId,
        val markets: Array<TranIntentMarket>,
        val marketIdx: Int,
        private val TranIntentScope: CoroutineScope
    ) {
        private val fromAmountInputChannel = Channel<Tuple3<Amount, Amount, CompletableDeferred<Boolean>>>()
        private val generalMutex = Mutex()

        fun start(): Job = TranIntentScope.launch {
            val merged = withContext(NonCancellable) {
                val existingIntent = pathManager.getIntent(markets, marketIdx)
                if (existingIntent != null) {
                    val merged = existingIntent.merge(markets[0].fromAmount(markets, 0), markets[marketIdx].fromAmount(markets, marketIdx))
                    if (merged) transactionsDao.deleteActive(id)
                    merged
                } else {
                    false
                }
            }

            if (merged) return@launch

            pathManager.addIntent(this@TransactionIntent)

            logger.debug { "Starting path traversal for ($marketIdx) ${markets.pathString()}" }

            val currentMarket = markets[marketIdx] as TranIntentMarketPartiallyCompleted
            val orderBookFlow = data.getOrderBookFlowBy(currentMarket.market)
            val feeFlow = data.fee
            val newMarketIdx = marketIdx + 1
            var modifiedMarkets = withContext(NonCancellable) {
                TransactionalOperator.create(tranManager, object : TransactionDefinition {
                    override fun getIsolationLevel() = TransactionDefinition.ISOLATION_REPEATABLE_READ
                }).transactional(mono(Dispatchers.Unconfined) {
                    val unfilledMarkets = unfilledMarketsDao.get(markets[0].fromCurrency, currentMarket.fromCurrency)

                    val modifiedMarkets = mergeMarkets(markets, unfilledMarkets)

                    if (unfilledMarkets.length() != 0) {
                        transactionsDao.updateActive(id, modifiedMarkets, marketIdx)
                        unfilledMarketsDao.remove(markets[0].fromCurrency, currentMarket.fromCurrency)
                    }

                    modifiedMarkets
                }).retry().awaitFirst()
            }

            if (currentMarket.orderSpeed == OrderSpeed.Instant) {
                withContext(NonCancellable) {
                    generalMutex.withLock {
                        while (!fromAmountInputChannel.isEmpty) {
                            val (initFromAmount, currFromAmount, approve) = fromAmountInputChannel.receive()
                            modifiedMarkets = mergeMarkets(markets, initFromAmount, currFromAmount)
                            transactionsDao.updateActive(id, modifiedMarkets, marketIdx)
                            approve.complete(true)
                        }
                        fromAmountInputChannel.close()
                    }
                }

                val fromAmount = (modifiedMarkets[marketIdx] as TranIntentMarketPartiallyCompleted).fromAmount
                val trades = tradeInstantly(currentMarket.market, currentMarket.fromCurrency, fromAmount, orderBookFlow, feeFlow)

                logger.debug { "Instant ${currentMarket.orderType} has been completed ${if (trades == null) "with error." else ". Trades: $trades"}" }

                withContext(NonCancellable) {
                    if (trades == null) {
                        val fromCurrencyInit = modifiedMarkets[0].fromCurrency
                        val fromCurrencyInitAmount = modifiedMarkets[0].fromAmount(modifiedMarkets, 0)
                        val fromCurrencyCurrent = modifiedMarkets[marketIdx].fromCurrency
                        val fromCurrencyCurrentAmount = modifiedMarkets[marketIdx].fromAmount(modifiedMarkets, marketIdx)

                        val primaryCurrencyUnfilled = primaryCurrencies.contains(fromCurrencyCurrent)
                            && primaryCurrencies.contains(fromCurrencyInit)
                            && fromCurrencyInitAmount <= fromCurrencyCurrentAmount

                        TransactionalOperator.create(tranManager).transactional(mono(Dispatchers.Unconfined) {
                            transactionsDao.deleteActive(id)

                            if (!primaryCurrencyUnfilled) {
                                unfilledMarketsDao.add(
                                    fromCurrencyInit,
                                    fromCurrencyInitAmount,
                                    fromCurrencyCurrent,
                                    fromCurrencyCurrentAmount
                                )
                            }
                        }).retry().awaitFirstOrNull()

                        pathManager.removeIntent(id)

                        return@withContext
                    }

                    if (trades.length() == 0) {
                        if (marketIdx == 0) {
                            transactionsDao.deleteActive(id)
                            pathManager.removeIntent(id)
                        }
                        return@withContext
                    }

                    if (logger.isDebugEnabled && soundSignalEnabled) SoundUtil.beep()

                    val (unfilledTradeMarkets, committedMarkets) = splitMarkets(modifiedMarkets, marketIdx, trades)

                    val unfilledFromAmount = unfilledTradeMarkets[marketIdx].fromAmount(unfilledTradeMarkets, marketIdx)

                    if (unfilledFromAmount.compareTo(BigDecimal.ZERO) != 0 && marketIdx != 0) {
                        val fromCurrencyInit = unfilledTradeMarkets[0].fromCurrency
                        val fromCurrencyInitAmount = unfilledTradeMarkets[0].fromAmount(unfilledTradeMarkets, 0)
                        val fromCurrencyCurrent = unfilledTradeMarkets[marketIdx].fromCurrency

                        val primaryCurrencyUnfilled = primaryCurrencies.contains(fromCurrencyCurrent)
                            && primaryCurrencies.contains(fromCurrencyInit)
                            && fromCurrencyInitAmount <= unfilledFromAmount

                        if (!primaryCurrencyUnfilled) {
                            unfilledMarketsDao.add(fromCurrencyInit, fromCurrencyInitAmount, fromCurrencyCurrent, unfilledFromAmount)
                        }
                    }

                    if (newMarketIdx != modifiedMarkets.length()) {
                        transactionsDao.updateActive(id, committedMarkets, newMarketIdx)
                        TransactionIntent(id, committedMarkets, newMarketIdx, TranIntentScope).start()
                    } else {
                        TransactionalOperator.create(tranManager).transactional(mono(Dispatchers.Unconfined) {
                            transactionsDao.addCompleted(id, committedMarkets)
                            transactionsDao.deleteActive(id)
                            pathManager.removeIntent(id)
                        }).retry().awaitFirstOrNull()
                    }
                }
            } else {
                var updatedMarkets = modifiedMarkets
                val updatedMarketsRef = AtomicReference(updatedMarkets)
                val cancelByProfitMonitoringJob = AtomicBoolean(false)
                val profitMonitoringJob = startProfitMonitoring(updatedMarketsRef, cancelByProfitMonitoringJob)
                var finishedWithError = false

                val delayedProcessor = delayedTradeManager.get(currentMarket.market, currentMarket.orderType)

                val cancellationMonitorJob = launch {
                    try {
                        delay(Long.MAX_VALUE)
                    } finally {
                        withContext(NonCancellable) {
                            delayedProcessor.unregister(id)
                        }
                    }
                }

                try {
                    val tradesChannel = Channel<BareTrade>(Channel.UNLIMITED)
                    delayedProcessor.register(id, tradesChannel)

                    launch {
                        withContext(NonCancellable) {
                            run {
                                val fromAmount = (updatedMarkets[marketIdx] as TranIntentMarketPartiallyCompleted).fromAmount
                                val approved = delayedProcessor.addAmount(id, fromAmount)
                                if (!approved) return@withContext
                            }

                            fromAmountInputChannel.consumeEach {
                                generalMutex.withLock {
                                    val (initFromAmount, currFromAmount, approve) = it
                                    val approved = delayedProcessor.addAmount(id, currFromAmount)
                                    if (approved) updatedMarkets = mergeMarkets(updatedMarkets, initFromAmount, currFromAmount)
                                    approve.complete(approved)
                                }
                            }
                        }
                    }

                    withContext(NonCancellable) {
                        tradesChannel
                            .asFlow()
                            .collect { receivedTrades ->
                                generalMutex.withLock {
                                    val trades = Array.of(receivedTrades)
                                    val marketSplit = splitMarkets(updatedMarkets, marketIdx, trades)
                                    updatedMarkets = marketSplit._1
                                    val committedMarkets = marketSplit._2

                                    val fromAmount = (updatedMarkets[marketIdx] as TranIntentMarketPartiallyCompleted).fromAmount

                                    if (logger.isDebugEnabled) {
                                        logger.debug("[${currentMarket.market}, ${currentMarket.orderType}] Received delayed trades: $trades")
                                        if (soundSignalEnabled) SoundUtil.beep()
                                    }

                                    if (fromAmount.compareTo(BigDecimal.ZERO) == 0) {
                                        profitMonitoringJob.cancelAndJoin()
                                        cancelByProfitMonitoringJob.set(false)
                                    } else {
                                        updatedMarketsRef.set(updatedMarkets)
                                    }

                                    if (newMarketIdx != modifiedMarkets.length()) {
                                        val newId = UUID.randomUUID()

                                        TransactionalOperator.create(tranManager)
                                            .transactional(mono(Dispatchers.Unconfined) {
                                                if (fromAmount.compareTo(BigDecimal.ZERO) != 0) {
                                                    transactionsDao.updateActive(id, updatedMarkets, marketIdx)
                                                }

                                                transactionsDao.addActive(newId, committedMarkets, newMarketIdx)
                                            }).retry().awaitFirstOrNull()

                                        TransactionIntent(newId, committedMarkets, newMarketIdx, TranIntentScope).start()
                                    } else {
                                        transactionsDao.addCompleted(id, committedMarkets)
                                    }
                                }
                            }
                    }
                } catch (e: CancellationException) {
                } catch (e: Throwable) {
                    finishedWithError = true
                    if (logger.isDebugEnabled) logger.debug(e.message)
                } finally {
                    val isCancelled = !isActive

                    withContext(NonCancellable) {
                        cancellationMonitorJob.cancelAndJoin()

                        generalMutex.withLock {
                            fromAmountInputChannel.close()
                        }

                        delayedProcessor.unregister(id)

                        // Handle an unfilled amount
                        if (cancelByProfitMonitoringJob.get()) return@withContext

                        profitMonitoringJob.cancel()

                        val initMarket = updatedMarkets[0]
                        val currMarket = updatedMarkets[marketIdx]
                        val fromAmount = currMarket.fromAmount(updatedMarkets, marketIdx)

                        if (marketIdx != 0 && fromAmount.compareTo(BigDecimal.ZERO) != 0) {
                            if (!isCancelled || finishedWithError) {
                                val fromCurrencyInit = initMarket.fromCurrency
                                val fromCurrencyInitAmount = initMarket.fromAmount(updatedMarkets, 0)
                                val fromCurrencyCurrent = currMarket.fromCurrency
                                val fromCurrencyCurrentAmount = currMarket.fromAmount(updatedMarkets, marketIdx)

                                val primaryCurrencyUnfilled = primaryCurrencies.contains(fromCurrencyCurrent)
                                    && primaryCurrencies.contains(fromCurrencyInit)
                                    && fromCurrencyInitAmount <= fromCurrencyCurrentAmount

                                TransactionalOperator.create(tranManager).transactional(mono(Dispatchers.Unconfined) {
                                    transactionsDao.deleteActive(id)
                                    if (!primaryCurrencyUnfilled) {
                                        unfilledMarketsDao.add(fromCurrencyInit, fromCurrencyInitAmount, fromCurrencyCurrent, fromCurrencyCurrentAmount)
                                    }
                                }).retry().awaitFirstOrNull()

                                pathManager.removeIntent(id)
                            }
                        } else {
                            transactionsDao.deleteActive(id)
                            pathManager.removeIntent(id)
                        }
                    }
                }
            }
        }

        private suspend fun merge(initFromAmount: Amount, currentFromAmount: Amount): Boolean {
            return withContext(NonCancellable) {
                var merged: CompletableDeferred<Boolean>? = null
                generalMutex.withLock {
                    if (fromAmountInputChannel.isClosedForSend) return@withContext false
                    merged = CompletableDeferred()
                    fromAmountInputChannel.send(tuple(initFromAmount, currentFromAmount, merged!!))
                }
                merged!!.await()
            }
        }

        private suspend fun tradeInstantly(
            market: Market,
            fromCurrency: Currency,
            fromCurrencyAmount: Amount,
            orderBookFlow: Flow<OrderBookAbstract>,
            feeFlow: Flow<FeeMultiplier>
        ): Array<BareTrade>? {
            val trades = LinkedList<BareTrade>()
            val feeMultiplier = feeFlow.first() // TODO: Remove when Poloniex will fix the bug with fee
            var unfilledAmount = fromCurrencyAmount

            val orderType = if (market.baseCurrency == fromCurrency) {
                OrderType.Buy
            } else {
                OrderType.Sell
            }

            try {
                var retryCount = 0

                while (unfilledAmount.compareTo(BigDecimal.ZERO) != 0) {
                    val firstSimulatedTrade = simulateInstantTrades(unfilledAmount, orderType, orderBookFlow, feeFlow).firstOrNull()?._2

                    if (firstSimulatedTrade == null) {
                        delay(2000)
                        continue
                    }

                    val expectQuoteAmount = if (orderType == OrderType.Buy) {
                        calcQuoteAmount(unfilledAmount, firstSimulatedTrade.price)
                    } else {
                        firstSimulatedTrade.quoteAmount
                    }

                    if (expectQuoteAmount.compareTo(BigDecimal.ZERO) == 0) {
                        logger.debug("Quote amount for trade is equal to zero. Unfilled amount: $unfilledAmount")
                        if (trades.size == 0) return null else break
                    }

                    val transaction = try {
                        logger.debug { "Trying to $orderType on market $market with price ${firstSimulatedTrade.price} and amount $expectQuoteAmount" }

                        withContext(NonCancellable) {
                            if (orderType == OrderType.Buy) {
                                poloniexApi.buy(
                                    market,
                                    firstSimulatedTrade.price,
                                    expectQuoteAmount,
                                    BuyOrderType.FillOrKill
                                )
                            } else {
                                poloniexApi.sell(
                                    market,
                                    firstSimulatedTrade.price,
                                    expectQuoteAmount,
                                    BuyOrderType.FillOrKill
                                )
                            }
                        }
                    } catch (e: UnableToFillOrderException) {
                        retryCount = 0
                        logger.debug(e.originalMsg)
                        delay(100)
                        continue
                    } catch (e: TransactionFailedException) {
                        retryCount = 0
                        logger.debug(e.originalMsg)
                        delay(500)
                        continue
                    } catch (e: NotEnoughCryptoException) {
                        logger.error(e.originalMsg)

                        if (retryCount++ == 3) {
                            if (trades.size == 0) return null else break
                        } else {
                            delay(1000)
                            continue
                        }
                    } catch (e: AmountMustBeAtLeastException) {
                        logger.debug(e.originalMsg)
                        if (trades.size == 0) return null else break
                    } catch (e: TotalMustBeAtLeastException) {
                        logger.debug(e.originalMsg)
                        if (trades.size == 0) return null else break
                    } catch (e: RateMustBeLessThanException) {
                        logger.debug(e.originalMsg)
                        if (trades.size == 0) return null else break
                    } catch (e: MaxOrdersExceededException) {
                        retryCount = 0
                        logger.warn(e.originalMsg)
                        delay(1500)
                        continue
                    } catch (e: UnknownHostException) {
                        retryCount = 0
                        delay(2000)
                        continue
                    } catch (e: IOException) {
                        retryCount = 0
                        delay(2000)
                        continue
                    } catch (e: Throwable) {
                        retryCount = 0
                        delay(2000)
                        if (logger.isDebugEnabled) logger.error(e.message)
                        continue
                    }

                    logger.debug { "Instant $orderType trades received: ${transaction.trades}" }

                    for (trade in transaction.trades) {
                        unfilledAmount -= if (orderType == OrderType.Buy) {
                            buyBaseAmount(trade.amount, trade.price)
                        } else {
                            sellQuoteAmount(trade.amount)
                        }

                        val takerFee = if (transaction.feeMultiplier.compareTo(feeMultiplier.taker) != 0) {
                            logger.warn(
                                "Poloniex still has a bug with fees. " +
                                    "Expected: ${feeMultiplier.taker}. " +
                                    "Actual: ${transaction.feeMultiplier}"
                            )

                            feeMultiplier.taker
                        } else {
                            transaction.feeMultiplier
                        }

                        trades.addLast(BareTrade(trade.amount, trade.price, takerFee))


                        // Verify balance calculation and add adjustments

                        val targetAmount = if (orderType == OrderType.Buy) {
                            buyQuoteAmount(trade.amount, takerFee)
                        } else {
                            sellBaseAmount(trade.amount, trade.price, takerFee)
                        }

                        if (trade.takerAdjustment.compareTo(targetAmount) != 0) {
                            logger.warn(
                                "Not consistent amount calculation. " +
                                    "Instant $orderType trade: $trade. " +
                                    "Exchange amount: ${trade.takerAdjustment}. " +
                                    "Calculated amount: $targetAmount"
                            )

                            /*val adjAmount = trade.takerAdjustment - targetAmount
                            val adjTrade = adjustTargetAmount(adjAmount, orderType)
                            trades.addLast(adjTrade)*/
                        }
                    }
                }
            } catch (e: CancellationException) {
            }

            return Array.ofAll(trades)
        }

        private fun CoroutineScope.startProfitMonitoring(
            updatedMarketsRef: AtomicReference<Array<TranIntentMarket>>,
            cancelByProfitMonitoringJob: AtomicBoolean
        ): Job {
            val parentJob = coroutineContext[Job]

            return TranIntentScope.launch {
                val startTime = Instant.now()

                while (isActive) {
                    delay(10000)
                    var updatedMarkets = updatedMarketsRef.get()

                    val initAmount = updatedMarkets.first().fromAmount(updatedMarkets, 0)
                    val targetAmount = updatedMarkets.last().targetAmount(updatedMarkets, updatedMarkets.length() - 1)

                    val profitable = initAmount < targetAmount
                    val timeout = Duration.between(startTime, Instant.now()).toMinutes() > 40

                    if (profitable && !timeout) continue

                    logger.debug {
                        val path = updatedMarkets.pathString()

                        val reason = if (!profitable) {
                            "path is not profitable (${targetAmount - initAmount})"
                        } else {
                            "timeout has occurred"
                        }

                        "Cancelling path ($marketIdx) $path because $reason"
                    }

                    cancelByProfitMonitoringJob.set(true)
                    parentJob?.cancelAndJoin()

                    updatedMarkets = updatedMarketsRef.get()
                    val currMarket = updatedMarkets[marketIdx] as TranIntentMarketPartiallyCompleted
                    val fromCurrency = currMarket.fromCurrency
                    val fromCurrencyAmount = currMarket.fromAmount
                    var bestPath: Array<TranIntentMarket>?

                    while (true) {
                        logger.debug { "Trying to find a new path for ${updatedMarkets.pathString()}..." }

                        bestPath = findNewPath(
                            initAmount,
                            fromCurrency,
                            fromCurrencyAmount,
                            primaryCurrencies,
                            updatedMarkets.length() - marketIdx
                        )

                        if (bestPath != null) {
                            logger.debug { "A new path found ${bestPath.pathString()}" }
                            break
                        } else {
                            logger.debug { "Path not found for ${updatedMarkets.pathString()}" }
                        }

                        delay(60000)
                    }

                    val changedMarkets = updateMarketsWithBestPath(updatedMarkets, marketIdx, bestPath!!)

                    withContext(NonCancellable) {
                        transactionsDao.updateActive(id, changedMarkets, marketIdx)
                    }

                    if (!isActive) break

                    TransactionIntent(id, changedMarkets, marketIdx, TranIntentScope).start()

                    break
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TransactionIntent

            if (id != other.id) return false

            return true
        }

        override fun hashCode(): Int {
            return id.hashCode()
        }
    }

    private class TradeScheduler(private val orderType: OrderType) {
        private val ids = LinkedList<PathId>()
        private val idFromAmount = hashMapOf<PathId, Amount>()
        private val idOutput = hashMapOf<PathId, Channel<BareTrade>>()
        private var commonFromAmount = BigDecimal.ZERO
        private val unregisterMutex = Mutex()
        private val exitIntent = AtomicReference<Tuple2<PathId, CompletableDeferred<Unit>>>(null)

        val commonFromAmountChannel = ConflatedBroadcastChannel(BigDecimal.ZERO)
        val commonFromAmountMutex = Mutex()

        suspend fun register(id: PathId, outputTrades: Channel<BareTrade>) {
            commonFromAmountMutex.withLock {
                ids.addLast(id)
                idFromAmount[id] = BigDecimal.ZERO
                idOutput[id] = outputTrades
            }
        }

        suspend fun unregister(id: PathId) {
            val approval = CompletableDeferred<Unit>()

            withContext(NonCancellable) {
                unregisterMutex.withLock {
                    commonFromAmountMutex.withLock {
                        if (!ids.contains(id)) return@withContext
                        exitIntent.set(tuple(id, approval))
                        commonFromAmount -= idFromAmount.getValue(id)
                        commonFromAmountChannel.send(commonFromAmount)
                    }

                    logger.debug("Sent unregister request from $id. Waiting for approval...")
                    approval.await()
                    logger.debug("Unregister request for $id has been approved")

                    commonFromAmountMutex.withLock {
                        ids.remove(id)
                        idFromAmount.remove(id)
                        idOutput.remove(id)?.close()
                        exitIntent.set(null)
                    }
                }

                logger.debug("Path $id successfully removed from trade scheduler")
            }
        }

        suspend fun unregisterAll(error: Throwable? = null) {
            logger.debug("Unregistering all paths...")

            withContext(NonCancellable) {
                val unregisterIntentJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    commonFromAmountChannel.consumeEach {
                        approveExitIntent()
                    }
                }

                var commonFromAmountChanged = false

                unregisterMutex.withLock {
                    commonFromAmountMutex.withLock {
                        ids.forEach { id ->
                            commonFromAmount -= idFromAmount.remove(id) ?: BigDecimal.ZERO
                            idOutput.remove(id)?.close(error)
                            commonFromAmountChanged = true
                        }
                        ids.clear()
                    }
                }

                unregisterIntentJob.cancelAndJoin()
                if (commonFromAmountChanged) commonFromAmountChannel.send(commonFromAmount)
            }

            logger.debug("All paths have been unregistered")
        }

        suspend fun addAmount(id: PathId, fromAmount: Amount): Boolean {
            commonFromAmountMutex.withLock {
                val output = idOutput[id]
                val idAmount = idFromAmount[id]
                if (output == null || idAmount == null || output.isClosedForSend) return false
                idFromAmount[id] = idAmount + fromAmount
                commonFromAmount += fromAmount
                commonFromAmountChannel.send(commonFromAmount)
                return true
            }
        }

        fun approveExitIntent() {
            (exitIntent.get() ?: return)._2.complete(Unit)
        }

        suspend fun addTrades(tradeList: kotlin.collections.List<BareTrade>) {
            commonFromAmountMutex.withLock {
                for (trade in tradeList) {
                    addTrade(trade)
                }
            }
        }

        private suspend fun addTrade(bareTrade: BareTrade) {
            var trade = bareTrade

            var tradeFromAmount = if (orderType == OrderType.Buy) {
                buyBaseAmount(trade.quoteAmount, trade.price)
            } else {
                sellQuoteAmount(trade.quoteAmount)
            }

            // Trying to find client for received trade
            for (id in ids) {
                if (tradeFromAmount.compareTo(BigDecimal.ZERO) == 0) break

                val idFromAmountValue = idFromAmount.getValue(id)

                if (tradeFromAmount <= idFromAmountValue) {
                    // full trade match
                    val newIdFromAmount = idFromAmountValue - tradeFromAmount
                    idFromAmount[id] = newIdFromAmount
                    if (exitIntent.get()?._1 != id) commonFromAmount -= tradeFromAmount
                    tradeFromAmount = BigDecimal.ZERO
                    val output = idOutput.getValue(id)
                    output.send(trade)
                    if (newIdFromAmount.compareTo(BigDecimal.ZERO) == 0) output.close()
                }
            }

            if (tradeFromAmount.compareTo(BigDecimal.ZERO) != 0) {
                // Client not found for trade
                // Need to split received trade
                for (id in ids) {
                    if (tradeFromAmount.compareTo(BigDecimal.ZERO) == 0) break
                    val idFromAmountValue = idFromAmount.getValue(id)

                    if (idFromAmountValue.compareTo(BigDecimal.ZERO) == 0) continue

                    if (tradeFromAmount > idFromAmountValue) {
                        idFromAmount[id] = BigDecimal.ZERO
                        if (exitIntent.get()?._1 != id) commonFromAmount -= idFromAmountValue
                        tradeFromAmount -= idFromAmountValue

                        val idTargetAmountValue = if (orderType == OrderType.Buy) {
                            buyQuoteAmount(trade.quoteAmount, trade.feeMultiplier)
                        } else {
                            sellBaseAmount(trade.quoteAmount, trade.price, trade.feeMultiplier)
                        }

                        val (lTrade, rTrades) = splitTrade(trade, orderType, idTargetAmountValue)

                        trade = lTrade

                        val output = idOutput.getValue(id)

                        for (rTrade in rTrades) {
                            output.send(rTrade)
                        }
                        output.close()
                    }
                }
            }

            commonFromAmountChannel.send(commonFromAmount)

            if (tradeFromAmount.compareTo(BigDecimal.ZERO) != 0) {
                logger.error("No receiver found for received trade $trade.")
            }
        }

        companion object {
            private val logger = KotlinLogging.logger {}
        }
    }

    private inner class DelayedTradeProcessor(
        val market: Market,
        val orderType: OrderType,
        private val orderBook: Flow<OrderBookAbstract>,
        private val scope: CoroutineScope
    ) {
        private val scheduler = TradeScheduler(orderType)
        private var tradeWorkerJob: Job? = null

        fun start(): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (scheduler.commonFromAmountChannel.value.compareTo(BigDecimal.ZERO) != 0) {
                    startTradeWorker()
                }

                scheduler.commonFromAmountChannel.consumeEach { fromAmount ->
                    toggleTradeWorker(fromAmount)
                }
            } finally {
                withContext(NonCancellable) {
                    stopTradeWorker()
                }
            }
        }

        suspend fun register(id: PathId, outputTrades: Channel<BareTrade>) {
            logger.debug("Path $id registered in trade processor")
            scheduler.register(id, outputTrades)
        }

        suspend fun unregister(id: PathId) {
            logger.debug("Path $id removed from trade processor")
            scheduler.unregister(id)
        }

        suspend fun addAmount(id: PathId, fromAmount: Amount): Boolean {
            logger.debug("Adding amount $fromAmount to $id")
            return scheduler.addAmount(id, fromAmount)
        }

        private suspend fun toggleTradeWorker(fromAmount: Amount) {
            if (fromAmount.compareTo(BigDecimal.ZERO) == 0) {
                stopTradeWorker()
            } else {
                startTradeWorker()
            }
        }

        private suspend fun stopTradeWorker() {
            tradeWorkerJob?.cancelAndJoin()
            tradeWorkerJob = null
        }

        private fun startTradeWorker() {
            if (tradeWorkerJob != null) return
            tradeWorkerJob = launchTradeWorker()
        }

        private fun launchTradeWorker(): Job = scope.launch(Job()) {
            var prevOrderId: Long? = null
            var currOrderId: Long? = null
            var latestTradeId: Long = -1
            var prevPrice: Price? = null
            val orderCreateConfirmed = AtomicReference<CompletableDeferred<Unit>?>(null)
            val orderCancelConfirmed = AtomicReference<CompletableDeferred<OrderUpdateType>?>(null)
            val commonStateMutex = Mutex()

            recoverAfterPowerOff()

            val tradeMonitoringJob = launch(Job(), CoroutineStart.UNDISPATCHED) {
                poloniexApi.accountNotificationStream.collect { notifications ->
                    var tradeList: LinkedList<BareTrade>? = null

                    commonStateMutex.withLock {
                        for (notification in notifications) {
                            when (notification) {
                                is TradeNotification -> run {
                                    if (currOrderId != null && notification.orderId == currOrderId ||
                                        prevOrderId != null && notification.orderId == prevOrderId
                                    ) {
                                        if (notification.tradeId > latestTradeId) {
                                            latestTradeId = notification.tradeId
                                        }

                                        if (tradeList == null) {
                                            tradeList = LinkedList()
                                        }

                                        tradeList!!.add(
                                            BareTrade(
                                                notification.amount,
                                                notification.price,
                                                notification.feeMultiplier
                                            )
                                        )
                                    }
                                }
                                is LimitOrderCreated -> run {
                                    if (notification.orderId == currOrderId) {
                                        orderCreateConfirmed.get()?.complete(Unit)
                                    }
                                }
                                is OrderUpdate -> run {
                                    if (notification.orderId == currOrderId &&
                                        notification.newAmount.compareTo(BigDecimal.ZERO) == 0
                                    ) {
                                        orderCancelConfirmed.get()?.complete(notification.orderType)
                                    }
                                }
                            }
                        }
                    }

                    if (tradeList != null && !tradeList!!.isEmpty()) {
                        scheduler.addTrades(tradeList!!)
                    }
                }
            }

            try {
                var connectionError: Boolean
                var closeAllError: Throwable?

                while (isActive) {
                    closeAllError = null
                    connectionError = false

                    try {
                        coroutineScope {
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                poloniexApi.connection.collect { connected ->
                                    if (!connected) throw DisconnectedException
                                }
                            }

                            withContext(NonCancellable) {
                                scheduler.commonFromAmountMutex.withLock {
                                    commonStateMutex.withLock {
                                        val placeOrderResult = placeOrder(scheduler.commonFromAmountChannel.value)

                                        currOrderId = placeOrderResult._1
                                        prevPrice = placeOrderResult._2

                                        orderCreateConfirmed.set(CompletableDeferred())
                                        orderCancelConfirmed.set(CompletableDeferred())
                                    }
                                }
                            }

                            orderCreateConfirmed.get()?.await()

                            var prevFromAmount = scheduler.commonFromAmountChannel.value

                            scheduler.commonFromAmountChannel
                                .asFlow()
                                .combine(orderBook) { _, book -> book }
                                .conflate()
                                .collect { book ->
                                    withContext(NonCancellable) {
                                        scheduler.commonFromAmountMutex.withLock {
                                            commonStateMutex.withLock {
                                                val currFromAmount = scheduler.commonFromAmountChannel.value
                                                val forceMove = prevFromAmount.compareTo(currFromAmount) != 0
                                                prevFromAmount = currFromAmount

                                                val moveOrderResult = moveOrder(
                                                    book,
                                                    currOrderId!!,
                                                    prevPrice!!,
                                                    currFromAmount,
                                                    forceMove
                                                ) ?: return@withContext

                                                val (orderId, price, _) = moveOrderResult

                                                prevOrderId = currOrderId
                                                currOrderId = orderId
                                                prevPrice = price

                                                orderCreateConfirmed.set(CompletableDeferred())
                                                orderCancelConfirmed.set(CompletableDeferred())
                                            }
                                        }
                                    }

                                    orderCreateConfirmed.get()?.await()
                                    scheduler.approveExitIntent()
                                }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DisconnectedException) {
                        withContext(NonCancellable) {
                            connectionError = true
                            poloniexApi.connection.filter { it }.first()

                            if (currOrderId != null) {
                                processMissedTrades(currOrderId!!, latestTradeId)
                            }
                        }
                    } catch (e: Throwable) {
                        logger.debug(e.message)
                        closeAllError = e
                    } finally {
                        withContext(NonCancellable) {
                            if (currOrderId == null) return@withContext

                            val cancelConfirmedDef = orderCancelConfirmed.get()
                            val cancelConfirmed = cancelConfirmedDef?.isCompleted ?: true
                            if (!cancelConfirmed) cancelOrder(currOrderId!!)
                            val cancelType = withTimeoutOrNull(Duration.ofMinutes(3).toMillis()) {
                                cancelConfirmedDef?.await()
                            }

                            val fromAmountValue = scheduler.commonFromAmountChannel.value

                            if (cancelType == OrderUpdateType.Filled && fromAmountValue > BigDecimal.ZERO) {
                                logger.warn("Poloniex filled all but path still has some amount $fromAmountValue")
                                // TODO: Prepare adjustment trade
                            }

                            currOrderId = null
                            prevOrderId = null
                        }

                        scheduler.approveExitIntent()

                        if (closeAllError != null) {
                            scheduler.unregisterAll(closeAllError)
                        }
                    }

                    if (connectionError) continue

                    // waiting for any balance changes
                    withTimeoutOrNull(Duration.ofMinutes(2).toMillis()) {
                        scheduler.commonFromAmountChannel.asFlow()
                            .onStart { scheduler.approveExitIntent() }
                            .onEach { scheduler.approveExitIntent() }
                            .filter { it.compareTo(BigDecimal.ZERO) != 0 }
                            .first()
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    tradeMonitoringJob.cancelAndJoin()
                    scheduler.unregisterAll()
                }
            }
        }

        private suspend fun placeOrder(fromAmountValue: BigDecimal): Tuple3<Long, Price, Amount> {
            while (true) {
                try {
                    val primaryBook: SubOrderBook
                    val secondaryBook: SubOrderBook
                    val moveToOnePoint: (Price) -> Price

                    val book = orderBook.first()

                    when (orderType) {
                        OrderType.Buy -> run {
                            primaryBook = book.bids
                            secondaryBook = book.asks
                            moveToOnePoint = { price -> price.cut8add1 }
                        }
                        OrderType.Sell -> run {
                            primaryBook = book.asks
                            secondaryBook = book.bids
                            moveToOnePoint = { price -> price.cut8minus1 }
                        }
                    }

                    val price = run {
                        val topPricePrimary = primaryBook.headOption().map { it._1 }.orNull ?: return@run null
                        val newPrice = moveToOnePoint(topPricePrimary)
                        val topPriceSecondary = secondaryBook.headOption().map { it._1 }.orNull
                        if (topPriceSecondary != null && topPriceSecondary.compareTo(newPrice) == 0) {
                            topPricePrimary
                        } else {
                            newPrice
                        }
                    } ?: throw OrderBookEmptyException(orderType)

                    val quoteAmount = when (orderType) {
                        OrderType.Buy -> calcQuoteAmount(fromAmountValue, price)
                        OrderType.Sell -> fromAmountValue
                    }

                    if (quoteAmount.compareTo(BigDecimal.ZERO) == 0) throw AmountIsZeroException

                    val result = when (orderType) {
                        OrderType.Buy -> poloniexApi.buy(market, price, quoteAmount, BuyOrderType.PostOnly)
                        OrderType.Sell -> poloniexApi.sell(market, price, quoteAmount, BuyOrderType.PostOnly)
                    }

                    return tuple(result.orderId, price, quoteAmount)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnableToPlacePostOnlyOrderException) {
                    logger.debug(e.originalMsg)
                    delay(100)
                    continue
                } catch (e: TransactionFailedException) {
                    logger.debug(e.originalMsg)
                    delay(500)
                    continue
                } catch (e: NotEnoughCryptoException) {
                    throw e
                } catch (e: AmountMustBeAtLeastException) {
                    throw e
                } catch (e: TotalMustBeAtLeastException) {
                    throw e
                } catch (e: RateMustBeLessThanException) {
                    throw e
                } catch (e: OrderBookEmptyException) {
                    logger.warn(e.message)
                    delay(1000)
                    continue
                } catch (e: MaxOrdersExceededException) {
                    logger.warn(e.originalMsg)
                    delay(1500)
                    continue
                } catch (e: UnknownHostException) {
                    delay(2000)
                    continue
                } catch (e: IOException) {
                    delay(2000)
                    continue
                } catch (e: Throwable) {
                    delay(2000)
                    if (logger.isDebugEnabled) logger.error(e.message)
                    continue
                }
            }
        }

        private suspend fun moveOrder(
            book: OrderBookAbstract,
            lastOrderId: Long,
            myPrice: Price,
            fromAmountValue: Amount,
            forceMove: Boolean
        ): Tuple3<Long, Price, Amount>? {
            while (true) {
                try {
                    val newPrice = run {
                        val primaryBook: SubOrderBook
                        val secondaryBook: SubOrderBook
                        val priceComparator: Comparator<Price>
                        val moveToOnePoint: (Price) -> Price

                        when (orderType) {
                            OrderType.Buy -> run {
                                primaryBook = book.bids
                                secondaryBook = book.asks
                                priceComparator = Comparator { bookPrice, myPrice ->
                                    when {
                                        bookPrice > myPrice -> 1
                                        bookPrice.compareTo(myPrice) == 0 -> 0
                                        else -> -1
                                    }
                                }
                                moveToOnePoint = { price -> price.cut8add1 }
                            }
                            OrderType.Sell -> run {
                                primaryBook = book.asks
                                secondaryBook = book.bids
                                priceComparator = Comparator { bookPrice, myPrice ->
                                    when {
                                        bookPrice < myPrice -> 1
                                        bookPrice.compareTo(myPrice) == 0 -> 0
                                        else -> -1
                                    }
                                }
                                moveToOnePoint = { price -> price.cut8minus1 }
                            }
                        }

                        val bookPrice1 = primaryBook.headOption().map { it._1 }.orNull
                            ?: throw OrderBookEmptyException(orderType)
                        val myPositionInBook = priceComparator.compare(bookPrice1, myPrice)

                        if (myPositionInBook == 1) {
                            // I am on second position
                            var price = moveToOnePoint(bookPrice1)
                            val ask = secondaryBook.headOption().map { it._1 }.orNull
                            if (ask != null && ask.compareTo(price) == 0) price = bookPrice1
                            price
                        } else {
                            // I am on first position
                            null // Ignore possible price gaps
                            // TODO: Implement better algorithm to handle price gaps
                        }
                    } ?: if (forceMove) myPrice else return null

                    val quoteAmount = when (orderType) {
                        OrderType.Buy -> calcQuoteAmount(fromAmountValue, newPrice)
                        OrderType.Sell -> fromAmountValue
                    }

                    if (quoteAmount.compareTo(BigDecimal.ZERO) == 0) throw AmountIsZeroException

                    val moveOrderResult = poloniexApi.moveOrder(
                        lastOrderId,
                        newPrice,
                        quoteAmount,
                        BuyOrderType.PostOnly
                    )

                    return tuple(moveOrderResult.orderId, newPrice, quoteAmount)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OrderCompletedOrNotExistException) {
                    throw e
                } catch (e: UnableToPlacePostOnlyOrderException) {
                    logger.debug(e.originalMsg)
                    delay(100)
                } catch (e: TransactionFailedException) {
                    logger.debug(e.originalMsg)
                    delay(500)
                } catch (e: InvalidOrderNumberException) {
                    throw e
                } catch (e: NotEnoughCryptoException) {
                    throw e
                } catch (e: AmountMustBeAtLeastException) {
                    throw e
                } catch (e: TotalMustBeAtLeastException) {
                    throw e
                } catch (e: RateMustBeLessThanException) {
                    throw e
                } catch (e: OrderBookEmptyException) {
                    logger.warn(e.message)
                    delay(1000)
                } catch (e: MaxOrdersExceededException) {
                    logger.warn(e.originalMsg)
                    delay(1500)
                } catch (e: UnknownHostException) {
                    delay(2000)
                } catch (e: IOException) {
                    delay(2000)
                } catch (e: Throwable) {
                    delay(2000)
                    if (logger.isDebugEnabled) logger.error(e.message)
                }
            }
        }

        private suspend fun cancelOrder(orderId: Long) {
            while (true) {
                try {
                    poloniexApi.cancelOrder(orderId)
                    break
                } catch (e: OrderCompletedOrNotExistException) {
                    logger.debug("[$market, $orderType] ${e.message}")
                    break
                } catch (e: UnknownHostException) {
                    delay(2000)
                } catch (e: IOException) {
                    delay(2000)
                } catch (e: Throwable) {
                    logger.error(e.message)
                    delay(2000)
                }
            }

            logger.debug { "[$market, $orderType] Order $orderId cancelled" }
        }

        private suspend fun recoverAfterPowerOff() {
            val (orderId, tradeId) = transactionsDao.getLatestOrderAndTradeId(market, orderType) ?: return
            val orderOpen = data.openOrders.first().getOrNull(orderId) != null
            if (orderOpen) cancelOrder(orderId)
            processMissedTrades(orderId, tradeId)
        }

        private suspend fun processMissedTrades(orderId: Long, latestTradeId: Long) {
            while (true) {
                try {
                    val tradeList = poloniexApi.orderTrades(orderId).asSequence()
                        .filter { it.tradeId > latestTradeId }
                        .map { trade -> BareTrade(trade.amount, trade.price, trade.fee) }
                        .toList()

                    if (tradeList.isNotEmpty()) {
                        scheduler.addTrades(tradeList)
                    }

                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (logger.isDebugEnabled) logger.debug(e.message)
                    delay(1000)
                }
            }
        }
    }

    private inner class DelayedTradeManager(private val scope: CoroutineScope) {
        private val processors = hashMapOf<Tuple2<Market, OrderType>, DelayedTradeProcessor>()
        private val mutex = Mutex()

        suspend fun get(market: Market, orderType: OrderType): DelayedTradeProcessor {
            mutex.withLock {
                val key = tuple(market, orderType)
                val processor = processors[key]

                if (processor != null) {
                    return processor
                }

                val orderBook = data.getOrderBookFlowBy(market)
                val newProcessor = DelayedTradeProcessor(market, orderType, orderBook, scope)
                processors[key] = newProcessor

                val processorJob = newProcessor.start()

                scope.launch(Job(), CoroutineStart.UNDISPATCHED) {
                    withContext(NonCancellable) {
                        processorJob.join()

                        mutex.withLock {
                            processors.remove(key)
                        }
                    }
                }

                return newProcessor
            }
        }
    }

    private class PathManager {
        private val paths = mutableListOf<TransactionIntent>()
        private val mutex = Mutex()

        suspend fun getIntent(markets: Array<TranIntentMarket>, marketIdx: Int): TransactionIntent? {
            return mutex.withLock {
                paths.find { it.markets == markets && it.marketIdx == marketIdx }
            }
        }

        suspend fun addIntent(intent: TransactionIntent) {
            mutex.withLock {
                paths.add(intent)
            }
        }

        suspend fun removeIntent(intentId: UUID) {
            mutex.withLock {
                paths.removeIf { it.id == intentId }
            }
        }
    }
}
