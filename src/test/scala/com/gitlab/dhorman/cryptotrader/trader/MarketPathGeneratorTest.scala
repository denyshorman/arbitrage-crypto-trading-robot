package com.gitlab.dhorman.cryptotrader.trader

import java.time.Instant

import com.gitlab.dhorman.cryptotrader.core
import com.gitlab.dhorman.cryptotrader.core.MarketPathGenerator.{ExhaustivePath, Path}
import com.gitlab.dhorman.cryptotrader.core.Orders.{DelayedOrder, InstantOrder}
import com.gitlab.dhorman.cryptotrader.core._
import org.scalatest.FlatSpec
import io.circe.syntax._

import scala.collection.immutable.{TreeMap, TreeSet}

class MarketPathGeneratorTest extends FlatSpec {

  private val markets = Set(
    Market("BTC", "BCN"),
    Market("BTC", "BTS"),
    Market("BTC", "BURST"),
    Market("BTC", "CLAM"),
    Market("BTC", "DGB"),
    Market("BTC", "DOGE"),
    Market("BTC", "DASH"),
    Market("BTC", "GAME"),
    Market("BTC", "HUC"),
    Market("BTC", "LTC"),
    Market("BTC", "MAID"),
    Market("BTC", "OMNI"),
    Market("BTC", "NAV"),
    Market("BTC", "NMC"),
    Market("BTC", "NXT"),
    Market("BTC", "PPC"),
    Market("BTC", "STR"),
    Market("BTC", "SYS"),
    Market("BTC", "VIA"),
    Market("BTC", "VTC"),
    Market("BTC", "XCP"),
    Market("BTC", "XMR"),
    Market("BTC", "XPM"),
    Market("BTC", "XRP"),
    Market("BTC", "XEM"),
    Market("BTC", "ETH"),
    Market("BTC", "SC"),
    Market("BTC", "FCT"),
    Market("BTC", "DCR"),
    Market("BTC", "LSK"),
    Market("BTC", "LBC"),
    Market("BTC", "STEEM"),
    Market("BTC", "SBD"),
    Market("BTC", "ETC"),
    Market("BTC", "REP"),
    Market("BTC", "ARDR"),
    Market("BTC", "ZEC"),
    Market("BTC", "STRAT"),
    Market("BTC", "PASC"),
    Market("BTC", "GNT"),
    Market("BTC", "BCH"),
    Market("BTC", "ZRX"),
    Market("BTC", "CVC"),
    Market("BTC", "OMG"),
    Market("BTC", "GAS"),
    Market("BTC", "STORJ"),
    Market("BTC", "EOS"),
    Market("BTC", "SNT"),
    Market("BTC", "KNC"),
    Market("BTC", "BAT"),
    Market("BTC", "LOOM"),
    Market("BTC", "QTUM"),
    Market("BTC", "BNT"),
    Market("BTC", "MANA"),
    Market("BTC", "FOAM"),
    Market("BTC", "BCHABC"),
    Market("BTC", "BCHSV"),
    Market("BTC", "NMR"),
    Market("BTC", "POLY"),
    Market("BTC", "LPT"),
    Market("USDT", "BTC"),
    Market("USDT", "DOGE"),
    Market("USDT", "DASH"),
    Market("USDT", "LTC"),
    Market("USDT", "NXT"),
    Market("USDT", "STR"),
    Market("USDT", "XMR"),
    Market("USDT", "XRP"),
    Market("USDT", "ETH"),
    Market("USDT", "SC"),
    Market("USDT", "LSK"),
    Market("USDT", "ETC"),
    Market("USDT", "REP"),
    Market("USDT", "ZEC"),
    Market("USDT", "GNT"),
    Market("USDT", "BCH"),
    Market("USDT", "ZRX"),
    Market("USDT", "EOS"),
    Market("USDT", "SNT"),
    Market("USDT", "KNC"),
    Market("USDT", "BAT"),
    Market("USDT", "LOOM"),
    Market("USDT", "QTUM"),
    Market("USDT", "BNT"),
    Market("USDT", "MANA"),
    Market("XMR", "BCN"),
    Market("XMR", "DASH"),
    Market("XMR", "LTC"),
    Market("XMR", "MAID"),
    Market("XMR", "NXT"),
    Market("XMR", "ZEC"),
    Market("ETH", "LSK"),
    Market("ETH", "STEEM"),
    Market("ETH", "ETC"),
    Market("ETH", "REP"),
    Market("ETH", "ZEC"),
    Market("ETH", "GNT"),
    Market("ETH", "BCH"),
    Market("ETH", "ZRX"),
    Market("ETH", "CVC"),
    Market("ETH", "OMG"),
    Market("ETH", "GAS"),
    Market("ETH", "EOS"),
    Market("ETH", "SNT"),
    Market("ETH", "KNC"),
    Market("ETH", "BAT"),
    Market("ETH", "LOOM"),
    Market("ETH", "QTUM"),
    Market("ETH", "BNT"),
    Market("ETH", "MANA"),
    Market("USDC", "BTC"),
    Market("USDC", "DOGE"),
    Market("USDC", "LTC"),
    Market("USDC", "STR"),
    Market("USDC", "USDT"),
    Market("USDC", "XMR"),
    Market("USDC", "XRP"),
    Market("USDC", "ETH"),
    Market("USDC", "ZEC"),
    Market("USDC", "BCH"),
    Market("USDC", "FOAM"),
    Market("USDC", "BCHABC"),
    Market("USDC", "BCHSV"),
    Market("USDC", "GRIN"),
  )

  private val currencies = markets.flatMap(m => Set(m.b, m.q))

  private val marketPathGenerator = new MarketPathGenerator(markets)

  "MarketPathGenerator" should "generate for USDC -> USDT" in {
    val paths = marketPathGenerator.generate(("USDC", "USDT"))
    paths.toList.sortBy(_.length).foreach(p => {
      println(p)
    })
  }

  "MarketPathGenerator" should "generate all possible ways between currency A -> A" in {
    val set = currencies.par.flatMap(c => marketPathGenerator.generate((c, c)))
    println(set.size)
  }

  "MarketPathGenerator" should "generate all permutations for USDC and USDT" in {
    val paths = marketPathGenerator.generateAll(("USDC", "USDT"))
    val flattenPaths: List[Path] = paths.flatMap(_._2).toList.sortBy(_.size)
    for (path <- flattenPaths) println(path)
  }

  "MarketPathGenerator" should "generate all permutations with orders" in {
    val paths = marketPathGenerator.generateAllPermutationsWithOrders(Iterable("USDC", "USDT"))
    val flattenPaths = paths.flatMap(_._2).toList.sortBy(_.size)
    for (path <- flattenPaths) println(path)
    println(s"Total: ${flattenPaths.size}")
  }

  "MarketPathGenerator ExhaustivePath" should "generate json" in {
    val i = InstantOrder("USDC_BTC", "BTC", "BTC", 40, 0.5, OrderType.Buy, 1.2, 1.3, 0, 0.99, List())
    val d = DelayedOrder("USDT_BTC", "BTC", "USDT", 0.5, 1.2, 40, 41, OrderType.Sell, 1.2, core.TradeStatOrder(0, 0, 0, 0, 0, 0, 0, 0, Instant.MIN, Instant.MIN))

    val json = ExhaustivePath(("USDC", "USDT"), List(i, d)).asJson.spaces2

    println(json)
  }

  "MarketPathGenerator ExhaustivePath TreeMap" should "generate json" in {
    val i = InstantOrder("USDC_BTC", "BTC", "BTC", 40, 0.5, OrderType.Buy, 1.2, 1.3, 0, 0.99, List())
    val d = DelayedOrder("USDT_BTC", "BTC", "USDT", 0.5, 1.2, 40, 41, OrderType.Sell, 1.2, core.TradeStatOrder(0, 0, 0, 0, 0, 0, 0, 0, Instant.MIN, Instant.MIN))

    val path = ExhaustivePath(("USDC", "USDT"), List(i, d))

    var state = TreeSet[ExhaustivePath]()((x,y) => {
      x.simpleMultiplier.compare(y.simpleMultiplier)
    })

    state = state + path
    state = state + path

    assert(state.size == 1)
  }
}
