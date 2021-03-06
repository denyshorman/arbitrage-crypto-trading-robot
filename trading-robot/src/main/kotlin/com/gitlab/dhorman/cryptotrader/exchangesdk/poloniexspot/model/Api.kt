package com.gitlab.dhorman.cryptotrader.exchangesdk.poloniexspot.model

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.gitlab.dhorman.cryptotrader.robots.poloniexarbitrage.core.Market
import com.gitlab.dhorman.cryptotrader.robots.poloniexarbitrage.core.OrderBookAbstract
import com.gitlab.dhorman.cryptotrader.robots.poloniexarbitrage.core.oneMinusAdjPoloniex
import com.gitlab.dhorman.cryptotrader.exchangesdk.poloniexspot.codec.BooleanStringNumberJsonCodec
import com.gitlab.dhorman.cryptotrader.exchangesdk.poloniexspot.codec.OrderBookSnapshotCodec
import io.vavr.collection.Array
import io.vavr.collection.Map
import io.vavr.collection.TreeMap
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

@JsonIgnoreProperties(ignoreUnknown = true)
data class Error(
    @JsonProperty("error") val msg: String?
)

@JsonDeserialize(using = OrderBookSnapshotCodec.Decoder::class)
data class OrderBookSnapshot(
    override val asks: TreeMap<Price, Amount>,
    override val bids: TreeMap<Price, Amount>,
    val isFrozen: Boolean,
    val snapshot: Long
) : OrderBookAbstract(asks, bids)

data class Ticker0(
    val id: Int,
    val last: BigDecimal,
    val lowestAsk: BigDecimal,
    val highestBid: BigDecimal,
    val percentChange: BigDecimal,
    val baseVolume: BigDecimal,
    val quoteVolume: BigDecimal,

    @JsonSerialize(using = BooleanStringNumberJsonCodec.Encoder::class)
    @JsonDeserialize(using = BooleanStringNumberJsonCodec.Decoder::class)
    val isFrozen: Boolean,
    val high24hr: BigDecimal,
    val low24hr: BigDecimal
)

data class CompleteBalance(
    val available: BigDecimal,
    val onOrders: BigDecimal,
    val btcValue: BigDecimal
)

data class OpenOrder(
    @JsonProperty("orderNumber") val orderId: Long,
    val type: OrderType,
    @JsonProperty("rate") val price: Price,
    val startingAmount: Amount,
    val amount: Amount,
    val total: BigDecimal,
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") val date: LocalDateTime,
    val margin: Boolean
)

data class OpenOrderWithMarket(
    val orderId: Long,
    val type: OrderType,
    val price: Price,
    val startingAmount: Amount,
    val amount: Amount,
    val total: BigDecimal,
    val date: LocalDateTime,
    val margin: Boolean,
    val market: Market
) {
    companion object {
        fun from(openOrder: OpenOrder, market: Market): OpenOrderWithMarket {
            return OpenOrderWithMarket(
                openOrder.orderId,
                openOrder.type,
                openOrder.price,
                openOrder.startingAmount,
                openOrder.amount,
                openOrder.total,
                openOrder.date,
                openOrder.margin,
                market
            )
        }
    }
}

enum class ChartDataCandlestickPeriod(@get:JsonValue val id: Int) {
    PERIOD_5_MIN(300),
    PERIOD_15_MIN(900),
    PERIOD_30_MIN(1800),
    PERIOD_2_HOURS(7200),
    PERIOD_4_HOURS(14400),
    PERIOD_DAY(86400);

    val millis = id * 1000L
    val sec = id
    val min = sec / 60
    val hour = min / 60

    companion object {
        @JsonCreator
        @JvmStatic
        fun valueById(id: Int) = values().find { it.id == id }
    }
}

data class Candlestick(
    @JsonProperty("date") val date: Instant,
    @JsonProperty("high") val highestPrice: BigDecimal,
    @JsonProperty("low") val lowestPrice: BigDecimal,
    @JsonProperty("open") val openPrice: BigDecimal,
    @JsonProperty("close") val closePrice: BigDecimal,
    @JsonProperty("volume") val baseVolume: BigDecimal,
    @JsonProperty("quoteVolume") val quoteVolume: BigDecimal,
    @JsonProperty("weightedAverage") val averagePrice: BigDecimal
)

data class TradeHistory(
    @JsonProperty("globalTradeID") val globalTradeId: Long,
    @JsonProperty("tradeID") val tradeId: Long,
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") val date: LocalDateTime,
    val type: OrderType,
    @JsonProperty("rate") val price: Price,
    val amount: Amount,
    val total: BigDecimal,
    val orderNumber: Long
)

data class CurrencyDetails(
    val id: Int,
    val name: String,
    val humanType: String,
    val currencyType: String?,
    val txFee: BigDecimal,
    val minConf: BigDecimal,
    val depositAddress: String?,
    val disabled: Boolean,
    val delisted: Boolean,
    val frozen: Boolean,
    val hexColor: String,
    @get:JsonProperty("isGeofenced") val isGeofenced: Boolean
)

//TODO: Verify values
enum class TradeCategory(@get:JsonValue val id: String) {
    Exchange("exchange"),
    Margin("margin"),
}

data class TradeHistoryPrivate(
    @JsonProperty("globalTradeID") val globalTradeId: Long,
    @JsonProperty("tradeID") val tradeId: Long,
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") val date: LocalDateTime,
    @JsonProperty("rate") val price: Price,
    val amount: BigDecimal,
    val total: BigDecimal,
    val fee: BigDecimal,
    val feeDisplay: String,
    @JsonProperty("orderNumber") val orderId: Long,
    val type: OrderType,
    val category: TradeCategory
) {
    val feeMultiplier get(): BigDecimal = fee.oneMinusAdjPoloniex
}

data class OrderTrade(
    @JsonProperty("globalTradeID") val globalTradeId: Long,
    @JsonProperty("tradeID") val tradeId: Long,
    @JsonProperty("currencyPair") val market: Market,
    val type: OrderType,
    @JsonProperty("rate") val price: Price,
    val amount: Amount,
    val total: BigDecimal,
    val fee: BigDecimal,
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") val date: LocalDateTime
) {
    val feeMultiplier get(): BigDecimal = fee.oneMinusAdjPoloniex
}


data class AvailableAccountBalance(
    val exchange: Map<Currency, Amount>,
    val margin: Map<Currency, Amount>,
    val lending: Map<Currency, Amount>
)

data class LimitOrderResult(
    @JsonProperty("orderNumber") val orderId: Long,
    @JsonProperty("resultingTrades") val trades: Array<BuyResultingTrade>,
    val fee: BigDecimal,
    val tokenFee: BigDecimal,
    val tokenFeeCurrency: Currency?,
    @JsonProperty("currencyPair") val market: Market,
    val amountUnfilled: Amount?, // available when ImmediateOrCancel order type is used
    val clientOrderId: Long?
) {
    val feeMultiplier get(): BigDecimal = fee.oneMinusAdjPoloniex
    val tokenFeeMultiplier get(): BigDecimal = tokenFee.oneMinusAdjPoloniex
}

data class BuyResultingTrade(
    @JsonProperty("tradeID") val tradeId: Long,
    val type: OrderType,
    @JsonProperty("rate") val price: Price,
    val amount: Amount,
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") val date: LocalDateTime,
    val total: BigDecimal,
    val takerAdjustment: BigDecimal,
    val tokenCurrency: Currency?
)

enum class BuyOrderType(@get:JsonValue val id: String) {
    // A "fill or kill" order is a limit order that must be filled immediately in its entirety or it is canceled (killed). The purpose of a fill or kill order is to ensure that a position is entered instantly and at a specific price.
    FillOrKill("fillOrKill"),

    // An Immediate Or Cancel (IOC) order requires all or part of the order to be executed immediately, and any unfilled parts of the order are canceled. Partial fills are accepted with this type of order duration, unlike a fill-or-kill order, which must be filled immediately in its entirety or be canceled.
    ImmediateOrCancel("immediateOrCancel"),

    // https://support.bitfinex.com/hc/en-us/articles/115003507365-Post-Only-Limit-Order-Option
    // The post-only limit order option ensures the limit order will be added to the order book and not match with a pre-existing order. If your order would cause a match with a pre-existing order, your post-only limit order will be canceled. This ensures that you will pay the maker fee and not the taker fee.
    PostOnly("postOnly")
}

enum class CancelOrderIdType { Server, Client }

data class CancelOrderWrapper(
    val success: Boolean,
    val amount: Amount,
    val message: String,
    val fee: BigDecimal,
    @JsonProperty("currencyPair") val market: Market,
    val clientOrderId: Long?
)

data class CancelOrder(
    val amount: Amount,
    val feeMultiplier: BigDecimal,
    val market: Market,
    val clientOrderId: Long?
)

data class CancelAllOrdersWrapper(
    val success: Boolean,
    val message: String,
    val orderNumbers: Array<Long>
)

data class CancelAllOrders(
    val orderNumbers: Array<Long>
)

data class MoveOrderWrapper(
    val success: Boolean,
    @JsonProperty("error") val errorMsg: String?,
    @JsonProperty("orderNumber") val orderId: Long?,
    val resultingTrades: Map<Market, Array<BuyResultingTrade>>?,
    val fee: BigDecimal,
    @JsonProperty("currencyPair") val market: Market,
    val clientOrderId: Long?
)

data class MoveOrderResult(
    @JsonProperty("orderNumber") val orderId: Long,
    val resultingTrades: Map<Market, Array<BuyResultingTrade>>,
    val feeMultiplier: BigDecimal,
    val market: Market,
    val clientOrderId: Long?
)

data class FeeInfo(
    val makerFee: BigDecimal,
    val takerFee: BigDecimal,
    val marginMakerFee: BigDecimal,
    val marginTakerFee: BigDecimal,
    val thirtyDayVolume: BigDecimal,
    val nextTier: BigDecimal
)

enum class AccountType(@get:JsonValue val id: String) {
    Exchange("exchange"),
    Margin("margin")
}

data class OrderStatus(
    val status: OrderStatusType,
    @JsonProperty("rate") val price: Price,
    val amount: Amount,
    @JsonProperty("currencyPair") val market: Market,
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") val date: LocalDateTime,
    val total: BigDecimal,
    val type: OrderType,
    val startingAmount: Amount,
    val fee: BigDecimal
) {
    val feeMultiplier get(): BigDecimal = fee.oneMinusAdjPoloniex
}

data class OrderStatusWrapper(
    val success: Boolean,
    val result: Map<Long, OrderStatus>
)

data class OrderStatusErrorWrapper(
    val success: Boolean,
    val result: Map<String, String>
)

enum class OrderStatusType(@get:JsonValue val id: String) {
    Open("Open"),
    PartiallyFilled("Partially filled")
}
