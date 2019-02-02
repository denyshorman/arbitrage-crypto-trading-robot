package com.gitlab.dhorman.cryptotrader.core

import org.scalatest.FlatSpec
import Prices._

class PricesTest extends FlatSpec {
  "Prices" should "correctly cut and add 1 to the end" in {
    val price: BigDecimal = 3.123456789
    val res = price.cut8add1
    assert(res == 3.12345679)
  }
}