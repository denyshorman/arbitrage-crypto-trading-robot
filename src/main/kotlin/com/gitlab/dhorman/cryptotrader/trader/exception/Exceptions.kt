package com.gitlab.dhorman.cryptotrader.trader.exception

import com.gitlab.dhorman.cryptotrader.service.poloniex.model.Amount

abstract class NotProfitableException(override val message: String) : Throwable(message, null, true, false)

class NotProfitableDeltaException(val fromAmount: Amount, val targetAmount: Amount) :
    NotProfitableException("Not profitable: $targetAmount - $fromAmount = ${targetAmount - fromAmount}")

object NotProfitableTimeoutException : NotProfitableException("Not profitable due to long waiting time")

object MoveNotRequiredException : Throwable("", null, true, false)
object CantMoveOrderSafelyException : Throwable("", null, true, false)
object RepeatPlaceMoveOrderLoopAgain : Throwable("", null, true, false)
object CompletePlaceMoveOrderLoop : Throwable("", null, true, false)
