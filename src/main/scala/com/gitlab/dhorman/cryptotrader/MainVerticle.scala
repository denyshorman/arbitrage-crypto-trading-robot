package com.gitlab.dhorman.cryptotrader

import com.gitlab.dhorman.cryptotrader.trader.Trader
import com.typesafe.scalalogging.Logger
import io.vertx.lang.scala.ScalaVerticle
import io.vertx.scala.core.Vertx

class MainVerticle extends ScalaVerticle {
  private val logger = Logger[MainVerticle]
  private var module: MainModule = _
  private var trader: Trader = _

  override def start(): Unit = {
    logger.info("Start MainVerticle")
    val main = this

    module = new MainModule {
      override lazy val vertx: Vertx = main.vertx
    }

    trader = new Trader(module.poloniexApi)
    trader.start()
  }

  override def stop(): Unit = {
    logger.info("Stop MainVerticle")
    trader.stop()
  }
}
