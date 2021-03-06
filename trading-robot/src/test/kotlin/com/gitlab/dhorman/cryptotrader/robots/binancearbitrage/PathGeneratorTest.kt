package com.gitlab.dhorman.cryptotrader.robots.binancearbitrage

import com.gitlab.dhorman.cryptotrader.exchangesdk.binancespot.BinanceApi
import com.gitlab.dhorman.cryptotrader.robots.binancearbitrage.model.*
import com.gitlab.dhorman.cryptotrader.util.CsvGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.Test
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.seconds

class PathGeneratorTest {
    private lateinit var binanceApi: BinanceApi
    private lateinit var amountCalculator: BuySellAmountCalculator
    private lateinit var pathGenerator: PathGenerator

    private val logger = KotlinLogging.logger {}

    @Test
    fun testSymbol24HourStat() {
        runBlocking(Dispatchers.Default) {
            pathGenerator.symbol24HourStat.collect {
                println("${it.size}")
            }
        }
    }

    @Test
    fun testOrderBookLastTick() {
        runBlocking(Dispatchers.Default) {
            pathGenerator.orderBookLastTick.collect {
                println("${it.size}")
            }
        }
    }

    @Test
    fun testGeneratePaths() {
        runBlocking(Dispatchers.Default) {
            val fromCurrency = "USDT"
            val fromCurrencyAmount = BigDecimal("50")
            val toCurrencies = listOf("USDT")

            val exchangeInfo = binanceApi.getExchangeInfo()

            val fee = pathGenerator.tradeFeeCache.first()

            while (true) {
                val paths = pathGenerator.generate(fromCurrency, toCurrencies)

                val bookTicker = pathGenerator.orderBookLastTick.first().mapValues { it.value.value }
                val symbol24HourStat = pathGenerator.symbol24HourStat.first().mapValues { it.value.value }

                FileOutputStream("./build/reports/paths.csv").use { stream ->
                    paths.forEach { path ->
                        try {
                            val amounts = path.amounts(
                                fromCurrency,
                                fromCurrencyAmount,
                                fee,
                                bookTicker,
                                exchangeInfo.symbolsIndexed,
                                amountCalculator
                            )

                            val waitTime = path.waitTime(
                                fromCurrency,
                                symbol24HourStat,
                                amounts
                            )

                            val profit = amounts.last().second - fromCurrencyAmount

                            val profitability = profit.divide(waitTime, 12, RoundingMode.HALF_EVEN)

                            val line = CsvGenerator.toCsvNewLine(
                                fromCurrency,
                                path.targetCurrency(fromCurrency)!!,
                                path.toShortString(),
                                profit,
                                profitability
                            )

                            stream.write(line.toByteArray())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            println("error ${e.message}")
                        }
                    }
                }

                logger.info("Paths regenerated")

                delay(30.seconds)
            }
        }
    }

    @Test
    fun testTargetAmountFlow() {
        runBlocking(Dispatchers.Default) {
            val path = SimulatedPath(
                listOf(
                    SimulatedPath.OrderIntent(Market("BTC", "USDT"), OrderSpeed.Instant),
                    SimulatedPath.OrderIntent(Market("ETH", "BTC"), OrderSpeed.Instant),
                    SimulatedPath.OrderIntent(Market("ETH", "USDC"), OrderSpeed.Instant)
                )
            )

            val fromAmount = BigDecimal("50")
            val fromCurrency = "USDT"

            val exchangeInfo = binanceApi.getExchangeInfo()
            val fee = pathGenerator.tradeFeeCache.first()

            pathGenerator.orderBookLastTick.takeWhile { book ->
                !path.orderIntents.asSequence().all { intent -> book.containsKey(intent.market.symbol) }
            }.collect()

            val targetAmountFlow = path.targetAmount(
                fromCurrency,
                fromAmount,
                fee,
                pathGenerator.orderBookLastTick.first(),
                exchangeInfo.symbolsIndexed,
                amountCalculator
            )

            targetAmountFlow.conflate().collect { targetAmount ->
                val profit = targetAmount - fromAmount
                println("$fromAmount - $targetAmount = $profit")
            }
        }
    }
}
