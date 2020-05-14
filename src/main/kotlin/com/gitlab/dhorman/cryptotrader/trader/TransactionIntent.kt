package com.gitlab.dhorman.cryptotrader.trader

import com.gitlab.dhorman.cryptotrader.core.*
import com.gitlab.dhorman.cryptotrader.service.poloniex.ExtendedPoloniexApi
import com.gitlab.dhorman.cryptotrader.service.poloniex.exception.*
import com.gitlab.dhorman.cryptotrader.service.poloniex.getOrderBookFlowBy
import com.gitlab.dhorman.cryptotrader.service.poloniex.model.Amount
import com.gitlab.dhorman.cryptotrader.service.poloniex.model.BuyOrderType
import com.gitlab.dhorman.cryptotrader.service.poloniex.model.OrderType
import com.gitlab.dhorman.cryptotrader.trader.algo.MergeTradeAlgo
import com.gitlab.dhorman.cryptotrader.trader.algo.SplitTradeAlgo
import com.gitlab.dhorman.cryptotrader.trader.core.AdjustedPoloniexBuySellAmountCalculator
import com.gitlab.dhorman.cryptotrader.trader.dao.BlacklistedMarketsDao
import com.gitlab.dhorman.cryptotrader.trader.dao.SettingsDao
import com.gitlab.dhorman.cryptotrader.trader.dao.TransactionsDao
import com.gitlab.dhorman.cryptotrader.trader.dao.UnfilledMarketsDao
import com.gitlab.dhorman.cryptotrader.trader.exception.NotProfitableDeltaException
import com.gitlab.dhorman.cryptotrader.trader.exception.NotProfitableException
import com.gitlab.dhorman.cryptotrader.trader.exception.NotProfitableTimeoutException
import com.gitlab.dhorman.cryptotrader.trader.model.TranIntentMarket
import com.gitlab.dhorman.cryptotrader.trader.model.TranIntentMarketExtensions
import com.gitlab.dhorman.cryptotrader.trader.model.TranIntentMarketPartiallyCompleted
import com.gitlab.dhorman.cryptotrader.util.*
import io.vavr.Tuple2
import io.vavr.Tuple3
import io.vavr.collection.Array
import io.vavr.collection.List
import io.vavr.kotlin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.transaction.ReactiveTransactionManager
import java.io.IOException
import java.math.BigDecimal
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class TransactionIntent(
    val id: PathId,
    val markets: Array<TranIntentMarket>,
    val marketIdx: Int,
    private val TranIntentScope: CoroutineScope,
    private val intentManager: IntentManager,
    private val tranIntentMarketExtensions: TranIntentMarketExtensions,
    private val transactionsDao: TransactionsDao,
    private val poloniexApi: ExtendedPoloniexApi,
    private val amountCalculator: AdjustedPoloniexBuySellAmountCalculator,
    private val unfilledMarketsDao: UnfilledMarketsDao,
    private val settingsDao: SettingsDao,
    private val tranManager: ReactiveTransactionManager,
    private val mergeAlgo: MergeTradeAlgo,
    private val splitAlgo: SplitTradeAlgo,
    private val delayedTradeManager: DelayedTradeManager,
    private val pathHelper: PathHelper,
    private val blacklistedMarkets: BlacklistedMarketsDao
) {
    private val soundSignalEnabled = System.getenv("ENABLE_SOUND_SIGNAL") != null
    private val fromAmountInputChannel = Channel<Tuple3<Amount, Amount, CompletableDeferred<Boolean>>>()
    private val generalMutex = Mutex()

    private val tranIntentFactory = TransactionIntentFactory(
        TranIntentScope,
        intentManager,
        tranIntentMarketExtensions,
        transactionsDao,
        poloniexApi,
        amountCalculator,
        unfilledMarketsDao,
        settingsDao,
        tranManager,
        mergeAlgo,
        splitAlgo,
        delayedTradeManager,
        pathHelper,
        blacklistedMarkets
    )

    fun start(): Job = TranIntentScope.launch(CoroutineName("INTENT $id")) {
        logger.debug { "Start intent ($marketIdx) ${tranIntentMarketExtensions.pathString(markets)}" }

        val merged = withContext(NonCancellable) {
            val existingIntent = intentManager.get(markets, marketIdx)
            if (existingIntent != null) {
                val initFromAmount = tranIntentMarketExtensions.fromAmount(markets[0], markets, 0)
                val currentFromAmount = tranIntentMarketExtensions.fromAmount(markets[marketIdx], markets, marketIdx)

                logger.debug { "Existing intent found. Trying to merge current intent with an amount ($initFromAmount, $currentFromAmount) into ${existingIntent.id}..." }

                val merged = existingIntent.merge(initFromAmount, currentFromAmount)

                if (merged) {
                    transactionsDao.deleteActive(id)
                    logger.debug { "Current intent has been merged into ${existingIntent.id}" }
                } else {
                    logger.debug { "Current intent has not been merged into ${existingIntent.id}" }
                }

                merged
            } else {
                logger.debug("Merge intent has not been found for current intent")

                false
            }
        }

        if (merged) return@launch

        logger.debug { "Starting path traversal" }

        val currentMarket = markets[marketIdx] as TranIntentMarketPartiallyCompleted
        val orderBookFlow = poloniexApi.getOrderBookFlowBy(currentMarket.market)
        val feeFlow = poloniexApi.feeStream
        val newMarketIdx = marketIdx + 1
        var modifiedMarkets = withContext(NonCancellable) {
            tranManager.repeatableReadTran {
                val unfilledMarkets = unfilledMarketsDao.get(settingsDao.primaryCurrencies, currentMarket.fromCurrency)

                val modifiedMarkets = mergeAlgo.mergeMarkets(markets, unfilledMarkets)

                if (unfilledMarkets.length() != 0) {
                    transactionsDao.updateActive(id, modifiedMarkets, marketIdx)
                    unfilledMarketsDao.remove(settingsDao.primaryCurrencies, currentMarket.fromCurrency)

                    logger.debug { "Merged unfilled amounts $unfilledMarkets into current intent. Before: $markets ; After: $modifiedMarkets" }
                }

                modifiedMarkets
            }
        }

        try {
            try {
                intentManager.add(this@TransactionIntent)

                if (currentMarket.orderSpeed == OrderSpeed.Instant) {
                    withContext(NonCancellable) {
                        generalMutex.withLock {
                            while (!fromAmountInputChannel.isEmpty) {
                                val (initFromAmount, currFromAmount, approve) = fromAmountInputChannel.receive()
                                val prevMarkets = modifiedMarkets
                                modifiedMarkets = mergeAlgo.mergeMarkets(prevMarkets, initFromAmount, currFromAmount)
                                transactionsDao.updateActive(id, modifiedMarkets, marketIdx)
                                approve.complete(true)
                                logger.debug { "Merged amount (init = $initFromAmount, current = $currFromAmount) into $prevMarkets => $modifiedMarkets" }
                            }
                            fromAmountInputChannel.close()
                        }
                    }

                    val fromAmount = (modifiedMarkets[marketIdx] as TranIntentMarketPartiallyCompleted).fromAmount
                    val forceCancel = AtomicBoolean(false)
                    val forceCancelJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            delay(Long.MAX_VALUE)
                        } finally {
                            forceCancel.set(true)
                        }
                    }

                    withContext(NonCancellable) {
                        val delayedProcessor = delayedTradeManager.get(currentMarket.market, !currentMarket.orderType)
                        val trades = try {
                            delayedProcessor.pause()
                            tradeInstantly(modifiedMarkets, currentMarket.market, currentMarket.orderType, fromAmount, orderBookFlow, feeFlow, forceCancel)
                        } finally {
                            delayedProcessor.resume()
                            forceCancelJob.cancel()
                        }

                        logger.debug { "Instant ${currentMarket.orderType} has been completed. Trades: $trades}" }

                        if (logger.isDebugEnabled && soundSignalEnabled) SoundUtil.beep()

                        logger.debug { "Splitting markets (markets = $modifiedMarkets, idx = $marketIdx, trades = $trades)" }

                        val (unfilledTradeMarkets, committedMarkets) = splitAlgo.splitMarkets(modifiedMarkets, marketIdx, trades)

                        logger.debug { "Split result [updatedMarkets = $unfilledTradeMarkets], [committedMarkets = $committedMarkets]" }

                        modifiedMarkets = unfilledTradeMarkets

                        updateCurrentAndHandleNewMarkets(newMarketIdx, modifiedMarkets, committedMarkets)

                        val unfilledFromAmount = tranIntentMarketExtensions.fromAmount(modifiedMarkets[marketIdx], modifiedMarkets, marketIdx)

                        if (unfilledFromAmount.compareTo(BigDecimal.ZERO) == 0) {
                            logger.debug("Delete current intent from active transactions list because unfilled amount is zero")
                            transactionsDao.deleteActive(id)
                        } else {
                            logger.debug("Current intent has unfilled amount $unfilledFromAmount")
                            throw NotEnoughCryptoException(modifiedMarkets[marketIdx].fromCurrency, "Need to add to unfilled amount list")
                        }
                    }
                } else {
                    val delayedProcessor = delayedTradeManager.get(currentMarket.market, currentMarket.orderType)
                    val tradesChannel = Channel<List<BareTrade>>(Channel.UNLIMITED)

                    try {
                        delayedProcessor.register(id, tradesChannel)

                        coroutineScope {
                            val profitMonitoringJob = launch(start = CoroutineStart.UNDISPATCHED) {
                                val startTime = Instant.now()
                                var counter = 0L

                                while (isActive) {
                                    val updatedMarkets = modifiedMarkets

                                    val initAmount = tranIntentMarketExtensions.fromAmount(updatedMarkets.first(), updatedMarkets, 0)
                                    val targetAmount = tranIntentMarketExtensions.targetAmount(updatedMarkets.last(), updatedMarkets, updatedMarkets.length() - 1)

                                    val profitable = initAmount < targetAmount
                                    val timeout = Duration.between(startTime, Instant.now()).toMinutes() > 40

                                    if (profitable && !timeout) {
                                        if (counter++ % 15 == 0L) {
                                            logger.debug { "Expected profit: +${targetAmount - initAmount}" }
                                        }
                                        delay(2000)
                                        continue
                                    }

                                    logger.debug {
                                        val reason = if (!profitable) {
                                            "path is not profitable (${targetAmount - initAmount})"
                                        } else {
                                            "timeout has occurred"
                                        }

                                        val market = updatedMarkets[marketIdx].market

                                        "Cancelling path ($marketIdx $market) because $reason"
                                    }


                                    if (!profitable) throw NotProfitableDeltaException(initAmount, targetAmount)
                                    throw NotProfitableTimeoutException
                                }
                            }

                            val cancellationMonitoringJob = launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    delay(Long.MAX_VALUE)
                                } finally {
                                    withContext(NonCancellable) {
                                        logger.debug { "Cancel event received for delayed trade. Trying to unregister intent..." }

                                        generalMutex.withLock {
                                            fromAmountInputChannel.close()
                                        }

                                        delayedProcessor.unregister(id)
                                    }
                                }
                            }

                            val depositBalanceMonitoringJob = launch(start = CoroutineStart.UNDISPATCHED) {
                                withContext(NonCancellable) {
                                    run {
                                        val fromAmount = (modifiedMarkets[marketIdx] as TranIntentMarketPartiallyCompleted).fromAmount
                                        logger.debug { "Trying to add first amount $fromAmount of current intent to trade processor..." }

                                        val approved = delayedProcessor.addAmount(id, fromAmount)

                                        if (approved) {
                                            logger.debug { "First amount has been added to trade processor." }
                                        } else {
                                            logger.debug { "First amount hasn't been added to trade processor." }
                                            return@withContext
                                        }
                                    }

                                    for ((initFromAmount, currFromAmount, approve) in fromAmountInputChannel) {
                                        generalMutex.withLock {
                                            logger.debug { "Intent has received some amount ($initFromAmount, $currFromAmount). Trying to add it to trade processor..." }

                                            val approved = delayedProcessor.addAmount(id, currFromAmount)

                                            if (approved) {
                                                modifiedMarkets = mergeAlgo.mergeMarkets(modifiedMarkets, initFromAmount, currFromAmount)
                                                transactionsDao.updateActive(id, modifiedMarkets, marketIdx)
                                                logger.debug { "Amount ($initFromAmount, $currFromAmount) has been added to trade processor." }
                                            } else {
                                                logger.debug { "Amount ($initFromAmount, $currFromAmount) hasn't been added to trade processor." }
                                            }

                                            approve.complete(approved)
                                        }
                                    }
                                }
                            }

                            withContext(NonCancellable) {
                                tradesChannel.asFlow().collect { receivedTrades ->
                                    val trades = receivedTrades.toArray()

                                    if (logger.isDebugEnabled) {
                                        logger.debug("Received delayed trades: $trades")
                                        if (soundSignalEnabled) SoundUtil.beep()
                                    }

                                    generalMutex.withLock {
                                        val oldMarkets = modifiedMarkets

                                        logger.debug { "Splitting markets (markets = $oldMarkets, idx = $marketIdx, trades = $trades)" }

                                        val marketSplit = splitAlgo.splitMarkets(oldMarkets, marketIdx, trades)

                                        modifiedMarkets = marketSplit._1
                                        val committedMarkets = marketSplit._2

                                        logger.debug { "Split result [updatedMarkets = $modifiedMarkets], [committedMarkets = $committedMarkets]" }

                                        updateCurrentAndHandleNewMarkets(newMarketIdx, modifiedMarkets, committedMarkets)
                                    }
                                }

                                logger.debug("Trades channel has been closed successfully")

                                val unfilledFromAmount = (modifiedMarkets[marketIdx] as TranIntentMarketPartiallyCompleted).fromAmount

                                if (unfilledFromAmount.compareTo(BigDecimal.ZERO) == 0) {
                                    logger.debug("From amount is zero. Deleting active transaction...")
                                    transactionsDao.deleteActive(id)
                                }

                                logger.debug("Cancelling supporting jobs...")

                                cancelAndJoinAll(profitMonitoringJob, cancellationMonitoringJob, depositBalanceMonitoringJob)

                                logger.debug("Supporting jobs have been cancelled")
                            }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            generalMutex.withLock {
                                fromAmountInputChannel.close()
                            }

                            delayedProcessor.unregister(id)
                        }
                    }
                }
            } catch (e: NotProfitableException) {
                val initAmount = tranIntentMarketExtensions.fromAmount(modifiedMarkets.first(), modifiedMarkets, 0)
                val threshold = BigDecimal.ONE

                if (initAmount < threshold) {
                    throw TotalMustBeAtLeastException(threshold, "Cancelled by profit monitoring job. Can't move because $initAmount < $threshold")
                } else {
                    throw e
                }
            } catch (e: OrderMatchingDisabledException) {
                blacklistedMarkets.add(currentMarket.market, settingsDao.blacklistMarketTime)
                throw NotProfitableTimeoutException
            } catch (e: MarketDisabledException) {
                blacklistedMarkets.add(currentMarket.market, settingsDao.blacklistMarketTime)
                throw NotProfitableTimeoutException
            } finally {
                withContext(NonCancellable) {
                    intentManager.remove(id)
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (marketIdx == 0) {
                    transactionsDao.deleteActive(id)
                }
            }
        } catch (e: NotProfitableException) {
            TranIntentScope.launch(CoroutineName("INTENT $id PATH FINDER")) {
                val initAmount = tranIntentMarketExtensions.fromAmount(modifiedMarkets.first(), modifiedMarkets, 0)
                val currMarket = modifiedMarkets[marketIdx] as TranIntentMarketPartiallyCompleted
                val fromCurrency = currMarket.fromCurrency
                val fromCurrencyAmount = currMarket.fromAmount
                var bestPath: Array<TranIntentMarket>?

                while (true) {
                    logger.debug { "Trying to find a new path..." }

                    bestPath = pathHelper.findNewPath(initAmount, fromCurrency, fromCurrencyAmount, settingsDao.primaryCurrencies)

                    if (bestPath != null) {
                        logger.debug { "A new profitable path found ${tranIntentMarketExtensions.pathString(bestPath)}" }
                        break
                    } else {
                        logger.debug { "Profitable path not found" }
                    }

                    delay(60000)
                }

                val changedMarkets = pathHelper.updateMarketsWithBestPath(modifiedMarkets, marketIdx, bestPath!!)

                withContext(NonCancellable) {
                    transactionsDao.updateActive(id, changedMarkets, marketIdx)
                }

                tranIntentFactory.create(id, changedMarkets, marketIdx).start()
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                logger.debug { "Intent completed with error: ${e.message}" }

                val initMarket = modifiedMarkets[0]
                val currMarket = modifiedMarkets[marketIdx]
                val fromAmount = tranIntentMarketExtensions.fromAmount(currMarket, modifiedMarkets, marketIdx)

                if (marketIdx != 0 && fromAmount.compareTo(BigDecimal.ZERO) != 0) {
                    val fromCurrencyInit = initMarket.fromCurrency
                    val fromCurrencyInitAmount = tranIntentMarketExtensions.fromAmount(initMarket, modifiedMarkets, 0)
                    val fromCurrencyCurrent = currMarket.fromCurrency
                    val fromCurrencyCurrentAmount = tranIntentMarketExtensions.fromAmount(currMarket, modifiedMarkets, marketIdx)

                    val primaryCurrencyUnfilled = settingsDao.primaryCurrencies.contains(fromCurrencyCurrent)
                        && settingsDao.primaryCurrencies.contains(fromCurrencyInit)
                        && fromCurrencyInitAmount <= fromCurrencyCurrentAmount

                    if (primaryCurrencyUnfilled) {
                        logger.debug("Deleting from active transactions list because primary current is unfilled")
                        transactionsDao.deleteActive(id)
                    } else {
                        val existingIntentCandidate = intentManager.get(modifiedMarkets, marketIdx)
                        val mergedToSimilarIntent = existingIntentCandidate?.merge(fromCurrencyInitAmount, fromCurrencyCurrentAmount)

                        if (mergedToSimilarIntent == true) {
                            transactionsDao.deleteActive(id)
                            logger.debug { "Current intent has been merged into ${existingIntentCandidate.id}" }
                        } else {
                            tranManager.defaultTran {
                                transactionsDao.deleteActive(id)
                                unfilledMarketsDao.add(fromCurrencyInit, fromCurrencyInitAmount, fromCurrencyCurrent, fromCurrencyCurrentAmount)
                            }

                            logger.debug {
                                "Added to unfilled markets " +
                                    "[init = ($fromCurrencyInit, $fromCurrencyInitAmount)], " +
                                    "[current = ($fromCurrencyCurrent, $fromCurrencyCurrentAmount)]"
                            }
                        }
                    }
                } else {
                    logger.debug {
                        "Delete from active transactions list because " +
                            "marketIdx equal to zero (${marketIdx == 0}) " +
                            "or fromAmount ($fromAmount) is equal to zero (${fromAmount.compareTo(BigDecimal.ZERO) == 0})"
                    }
                    transactionsDao.deleteActive(id)
                }
            }
        }
    }

    private suspend fun updateCurrentAndHandleNewMarkets(newMarketIdx: Int, currentMarkets: Array<TranIntentMarket>, committedMarkets: Array<TranIntentMarket>) {
        if (newMarketIdx != currentMarkets.length()) {
            val newId = UUID.randomUUID()

            tranManager.defaultTran {
                transactionsDao.updateActive(id, currentMarkets, marketIdx)
                transactionsDao.addActive(newId, committedMarkets, newMarketIdx)
            }

            logger.debug("Starting new intent...")

            tranIntentFactory.create(newId, committedMarkets, newMarketIdx).start()
        } else {
            tranManager.defaultTran {
                transactionsDao.updateActive(id, currentMarkets, marketIdx)
                transactionsDao.addCompleted(id, committedMarkets)
            }

            logger.debug("Add current intent to completed transactions list")
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
        path: Array<TranIntentMarket>,
        market: Market,
        orderType: OrderType,
        fromCurrencyAmount: Amount,
        orderBookFlow: Flow<OrderBookAbstract>,
        feeFlow: Flow<FeeMultiplier>,
        cancel: AtomicBoolean
    ): Array<BareTrade> {
        while (true) {
            if (cancel.get()) throw CancellationException()

            val initAmount = tranIntentMarketExtensions.fromAmount(path.first(), path, 0)
            val targetAmount = tranIntentMarketExtensions.targetAmount(path.last(), path, path.length() - 1)
            if (initAmount >= targetAmount) throw NotProfitableDeltaException(initAmount, targetAmount)

            val feeMultiplier = feeFlow.first() // TODO: Remove when Poloniex will fix the bug with fee
            val simulatedInstantTrades = simulateInstantTrades(fromCurrencyAmount, orderType, orderBookFlow, feeFlow).toList(LinkedList())

            if (simulatedInstantTrades.isEmpty()) {
                logger.warn("Can't do instant trade. Wait...")
                delay(2000)
                continue
            }

            logger.debug { "Simulated instant trades before execution: $simulatedInstantTrades" }

            val lastTradePrice = simulatedInstantTrades.last()._2.price

            val expectQuoteAmount = if (orderType == OrderType.Buy) {
                amountCalculator.quoteAmount(fromCurrencyAmount, lastTradePrice, BigDecimal.ONE)
            } else {
                fromCurrencyAmount
            }

            if (expectQuoteAmount.compareTo(BigDecimal.ZERO) == 0) {
                logger.debug("Quote amount for trade is equal to zero. From an amount: $fromCurrencyAmount")
                throw AmountIsZeroException
            }

            val transaction = try {
                logger.debug { "Trying to $orderType on market $market with price $lastTradePrice and amount $expectQuoteAmount" }
                // TODO: Handle case when request has been sent but response has not been received due to connection issues.
                poloniexApi.placeLimitOrder(market, orderType, lastTradePrice, expectQuoteAmount, BuyOrderType.FillOrKill)
            } catch (e: Throwable) {
                when (e) {
                    is CancellationException -> throw e
                    is UnableToFillOrderException -> {
                        logger.debug(e.originalMsg)
                        delay(100)
                    }
                    is TransactionFailedException -> {
                        logger.debug(e.originalMsg)
                        delay(500)
                    }
                    is MaxOrdersExceededException -> {
                        logger.warn(e.originalMsg)
                        delay(1500)
                    }
                    is UnknownHostException, is IOException, is ConnectException, is SocketException -> {
                        delay(2000)
                    }
                    is NotEnoughCryptoException,
                    is AmountMustBeAtLeastException,
                    is TotalMustBeAtLeastException,
                    is RateMustBeLessThanException,
                    is OrderMatchingDisabledException,
                    is MarketDisabledException -> {
                        logger.debug(e.message)
                        throw e
                    }
                    else -> {
                        logger.error(e.message)
                        throw e
                    }
                }
                continue
            }

            if (transaction.trades.size() == 0) {
                logger.warn("Instant $orderType has successfully completed but trades list is empty. Self-trade occurred ?")
                delay(1000)
                continue
            }

            logger.debug { "Instant $orderType trades received: ${transaction.trades}" }

            return transaction.trades.toVavrStream().map { trade ->
                val takerFee = if (transaction.feeMultiplier.compareTo(feeMultiplier.taker) != 0) {
                    logger.warn("Poloniex still has a bug with fees. Expected: ${feeMultiplier.taker}. Actual: ${transaction.feeMultiplier}")
                    feeMultiplier.taker
                } else {
                    transaction.feeMultiplier
                }

                BareTrade(trade.amount, trade.price, takerFee)
            }.toArray()
        }
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
                val availableFromAmount = amountCalculator.fromAmountBuy(quoteAmount, basePrice, fee.taker)

                if (unusedFromAmount <= availableFromAmount) {
                    val tradeQuoteAmount = amountCalculator.quoteAmount(unusedFromAmount, basePrice, fee.taker)
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
                    unusedFromAmount -= amountCalculator.fromAmountSell(quoteAmount, basePrice, fee.taker)
                    val trade = BareTrade(quoteAmount, basePrice, fee.taker)
                    emit(tuple(unusedFromAmount, trade))
                }
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

    companion object {
        private val logger = KotlinLogging.logger {}

        class TransactionIntentFactory(
            private val TranIntentScope: CoroutineScope,
            private val intentManager: IntentManager,
            private val tranIntentMarketExtensions: TranIntentMarketExtensions,
            private val transactionsDao: TransactionsDao,
            private val poloniexApi: ExtendedPoloniexApi,
            private val amountCalculator: AdjustedPoloniexBuySellAmountCalculator,
            private val unfilledMarketsDao: UnfilledMarketsDao,
            private val settingsDao: SettingsDao,
            private val tranManager: ReactiveTransactionManager,
            private val mergeAlgo: MergeTradeAlgo,
            private val splitAlgo: SplitTradeAlgo,
            private val delayedTradeManager: DelayedTradeManager,
            private val pathHelper: PathHelper,
            private val blacklistedMarkets: BlacklistedMarketsDao
        ) {
            fun create(id: PathId, markets: Array<TranIntentMarket>, marketIdx: Int): TransactionIntent {
                return TransactionIntent(
                    id,
                    markets,
                    marketIdx,
                    TranIntentScope,
                    intentManager,
                    tranIntentMarketExtensions,
                    transactionsDao,
                    poloniexApi,
                    amountCalculator,
                    unfilledMarketsDao,
                    settingsDao,
                    tranManager,
                    mergeAlgo,
                    splitAlgo,
                    delayedTradeManager,
                    pathHelper,
                    blacklistedMarkets
                )
            }
        }
    }
}
