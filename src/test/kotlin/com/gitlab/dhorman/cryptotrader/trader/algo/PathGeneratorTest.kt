package com.gitlab.dhorman.cryptotrader.trader.algo

import com.gitlab.dhorman.cryptotrader.core.*
import com.gitlab.dhorman.cryptotrader.service.poloniex.ExtendedPoloniexApi
import com.gitlab.dhorman.cryptotrader.service.poloniex.core.PoloniexBuySellAmountCalculator
import com.gitlab.dhorman.cryptotrader.service.poloniex.model.Amount
import com.gitlab.dhorman.cryptotrader.service.poloniex.model.CurrencyType
import com.gitlab.dhorman.cryptotrader.trader.PathGenerator
import com.gitlab.dhorman.cryptotrader.trader.dao.TransactionsDao
import com.gitlab.dhorman.cryptotrader.trader.findOne
import com.gitlab.dhorman.cryptotrader.trader.model.TranIntentMarketCompleted
import com.gitlab.dhorman.cryptotrader.trader.model.TranIntentMarketPartiallyCompleted
import com.gitlab.dhorman.cryptotrader.trader.model.TranIntentMarketPredicted
import com.gitlab.dhorman.cryptotrader.util.CsvGenerator
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import io.vavr.collection.Array
import io.vavr.kotlin.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


@SpringBootTest
class PathGeneratorTest {
    private val logger = KotlinLogging.logger {}

    @Autowired
    private lateinit var pathGenerator: PathGenerator

    @Autowired
    private lateinit var poloniexApi: ExtendedPoloniexApi

    @Autowired
    private lateinit var amountCalculator: PoloniexBuySellAmountCalculator

    @Test
    fun `Generate paths, sort by profitability, and add all to csv file`() {
        runBlocking {
            val fromCurrencyAmount: Amount = BigDecimal("100")
            val toCurrencies = list("USDT", "USDC", "USDJ", "PAX", "DAI")
            val csvGenerator = CsvGenerator()
            csvGenerator.addLine("from_currency", "target_currency", "path", "profit", "profitability")
            toCurrencies.asFlow()
                .flatMapMerge(toCurrencies.size()) { fromCurrency ->
                    flow {
                        emit(pathGenerator.findBest(fromCurrencyAmount, fromCurrency, fromCurrencyAmount, toCurrencies))
                    }
                        .map { tuple(fromCurrency, it) }
                        .flowOn(Dispatchers.Default)

                }.collect { (fromCurrency, paths) ->
                    for ((path, profit, profitability) in paths) {
                        csvGenerator.addLine(fromCurrency, path.targetCurrency(fromCurrency)!!, path.marketsTinyString(), profit, profitability)
                    }
                }

            csvGenerator.dumpToFile("build/reports/simulatedPaths.csv")
        }
    }

    @Test
    fun `Test generated metrics for specific path`() {
        runBlocking {
            val fromCurrency = "USDT"
            val fromCurrencyAmount = BigDecimal("100")
            val feeMultiplier = FeeMultiplier(BigDecimal("0.99910000"), BigDecimal("0.99910000"))
            val orderBooks = poloniexApi.orderBooksPollingStream.first()
            val tradeVolumeStat = poloniexApi.tradeVolumeStat.first()

            val simulatedPath = SimulatedPath(
                Array.of(
                    SimulatedPath.OrderIntent(Market("USDT", "AVA"), OrderSpeed.Delayed),
                    SimulatedPath.OrderIntent(Market("TRX", "AVA"), OrderSpeed.Delayed),
                    SimulatedPath.OrderIntent(Market("BTC", "TRX"), OrderSpeed.Instant),
                    SimulatedPath.OrderIntent(Market("DAI", "BTC"), OrderSpeed.Delayed)
                )
            )

            val targetAmount = simulatedPath.targetAmount(fromCurrency, fromCurrencyAmount, feeMultiplier, orderBooks, amountCalculator)
            val waitTime = simulatedPath.waitTime(fromCurrency, fromCurrencyAmount, feeMultiplier, orderBooks, tradeVolumeStat, amountCalculator)

            println("Target amount: $targetAmount")
            println("Wait time: $waitTime")
        }
    }

    @Test
    fun `Test findOne`() {
        runBlocking {
            val paths = listOf(
                tuple(
                    SimulatedPath(
                        Array.of(
                            SimulatedPath.OrderIntent(Market("USDT", "ETH"), OrderSpeed.Instant),
                            SimulatedPath.OrderIntent(Market("USDC", "ETH"), OrderSpeed.Delayed)
                        )
                    ), BigDecimal.ONE, BigDecimal.ONE
                ),
                tuple(
                    SimulatedPath(
                        Array.of(
                            SimulatedPath.OrderIntent(Market("USDT", "BTC"), OrderSpeed.Delayed),
                            SimulatedPath.OrderIntent(Market("USDC", "BTC"), OrderSpeed.Delayed)
                        )
                    ), BigDecimal.ONE, BigDecimal.ONE
                ),
                tuple(
                    SimulatedPath(
                        Array.of(
                            SimulatedPath.OrderIntent(Market("USDT", "LTC"), OrderSpeed.Delayed),
                            SimulatedPath.OrderIntent(Market("USDC", "LTC"), OrderSpeed.Delayed)
                        )
                    ), BigDecimal.ONE, BigDecimal.ONE
                )
            )

            val transactionsDao = mock<TransactionsDao>()

            whenever(transactionsDao.getActive()).thenReturn(
                listOf(
                    tuple(
                        UUID.randomUUID(), Array.of(
                            TranIntentMarketCompleted(Market("USDT", "USDJ"), OrderSpeed.Instant, CurrencyType.Quote, Array.of()),
                            TranIntentMarketPartiallyCompleted(Market("USDT", "ETH"), OrderSpeed.Instant, CurrencyType.Base, BigDecimal.ONE),
                            TranIntentMarketPredicted(Market("USDC", "ETH"), OrderSpeed.Delayed, CurrencyType.Quote)
                        )
                    ),
                    tuple(
                        UUID.randomUUID(), Array.of(
                            TranIntentMarketCompleted(Market("USDT", "USDJ"), OrderSpeed.Instant, CurrencyType.Quote, Array.of()),
                            TranIntentMarketPartiallyCompleted(Market("USDT", "BTC"), OrderSpeed.Delayed, CurrencyType.Base, BigDecimal.ONE),
                            TranIntentMarketPredicted(Market("USDC", "BTC"), OrderSpeed.Delayed, CurrencyType.Quote)
                        )
                    )
                )
            )

            val bestPath = paths.findOne(transactionsDao)
            assertNotNull(bestPath)
            assertEquals(paths[2], bestPath)
        }
    }

    @Test
    fun `Test findOne 2`() {
        runBlocking {
            val paths = listOf(
                tuple(
                    SimulatedPath(
                        Array.of(
                            SimulatedPath.OrderIntent(Market("USDT", "ETH"), OrderSpeed.Instant),
                            SimulatedPath.OrderIntent(Market("USDC", "ETH"), OrderSpeed.Delayed)
                        )
                    ), BigDecimal.ONE, BigDecimal.ONE
                ),
                tuple(
                    SimulatedPath(
                        Array.of(
                            SimulatedPath.OrderIntent(Market("USDT", "BTC"), OrderSpeed.Delayed),
                            SimulatedPath.OrderIntent(Market("USDC", "BTC"), OrderSpeed.Delayed)
                        )
                    ), BigDecimal.ONE, BigDecimal.ONE
                ),
                tuple(
                    SimulatedPath(
                        Array.of(
                            SimulatedPath.OrderIntent(Market("USDT", "LTC"), OrderSpeed.Instant),
                            SimulatedPath.OrderIntent(Market("USDC", "LTC"), OrderSpeed.Instant)
                        )
                    ), BigDecimal.ONE, BigDecimal.ONE
                )
            )

            val transactionsDao = mock<TransactionsDao>()

            whenever(transactionsDao.getActive()).thenReturn(
                listOf(
                    tuple(
                        UUID.randomUUID(), Array.of(
                            TranIntentMarketCompleted(Market("USDT", "USDJ"), OrderSpeed.Instant, CurrencyType.Quote, Array.of()),
                            TranIntentMarketPartiallyCompleted(Market("USDT", "ETH"), OrderSpeed.Instant, CurrencyType.Base, BigDecimal.ONE),
                            TranIntentMarketPredicted(Market("USDC", "ETH"), OrderSpeed.Delayed, CurrencyType.Quote)
                        )
                    ),
                    tuple(
                        UUID.randomUUID(), Array.of(
                            TranIntentMarketCompleted(Market("USDT", "USDJ"), OrderSpeed.Instant, CurrencyType.Quote, Array.of()),
                            TranIntentMarketPartiallyCompleted(Market("USDT", "BTC"), OrderSpeed.Delayed, CurrencyType.Base, BigDecimal.ONE),
                            TranIntentMarketPredicted(Market("USDC", "BTC"), OrderSpeed.Delayed, CurrencyType.Quote)
                        )
                    )
                    ,
                    tuple(
                        UUID.randomUUID(), Array.of(
                            TranIntentMarketPartiallyCompleted(Market("USDT", "LTC"), OrderSpeed.Instant, CurrencyType.Base, BigDecimal.ONE),
                            TranIntentMarketPredicted(Market("USDC", "LTC"), OrderSpeed.Instant, CurrencyType.Quote)
                        )
                    )
                )
            )

            val bestPath = paths.findOne(transactionsDao)
            assertNotNull(bestPath)
            assertEquals(paths[0], bestPath)
        }
    }
}
