package com.gitlab.dhorman.cryptotrader.exchangesdk.binancespot

import com.gitlab.dhorman.cryptotrader.util.*
import com.gitlab.dhorman.cryptotrader.util.limiter.SimpleRequestLimiter
import com.gitlab.dhorman.cryptotrader.util.serializer.*
import com.gitlab.dhorman.cryptotrader.util.signer.HmacSha256Signer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.flux
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.*
import mu.KotlinLogging
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import org.springframework.web.reactive.socket.WebSocketMessage
import java.math.BigDecimal
import java.net.URI
import java.nio.channels.ClosedChannelException
import java.nio.file.StandardOpenOption
import java.security.PrivateKey
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.HashMap
import kotlin.time.minutes
import kotlin.time.seconds

class BinanceApi(
    apiKey: String,
    apiSecret: String,
    privateKey: PrivateKey? = null,
    apiNet: ApiNet,
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("BinanceApi"))
    private val closed = AtomicBoolean(false)

    private val httpConnector = HttpConnector(apiNet, apiKey, apiSecret, json)
    private val webSocketConnector = WebSocketConnector(apiNet, this, scope, json)

    init {
        Runtime.getRuntime().addShutdownHook(Thread { runBlocking { close() } })
    }

    //region Maintenance
    suspend fun close() {
        if (closed.getAndSet(true)) return
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
    //endregion

    //region Wallet API
    suspend fun systemStatus(): SystemStatus {
        return httpConnector.callApi("/wapi/v3/systemStatus.html", HttpMethod.GET, emptyMap(), false, false, serializer())
    }

    suspend fun getUserCoins(timestamp: Instant, recvWindow: Long? = null): List<UserCoin> {
        val params = HashMap<String, String>()
        params["timestamp"] = timestamp.toEpochMilli().toString()
        if (recvWindow != null) params["recvWindow"] = recvWindow.toString()
        return httpConnector.callApi("/sapi/v1/capital/config/getall", HttpMethod.GET, params, true, true, serializer())
    }

    suspend fun tradeFee(timestamp: Instant = Instant.now(), recvWindow: Long? = null, symbol: String? = null): TradeFee {
        val params = buildMap<String, String> {
            put("timestamp", timestamp.toEpochMilli().toString())
            if (recvWindow != null) put("recvWindow", recvWindow.toString())
            if (symbol != null) put("symbol", symbol)
        }
        return httpConnector.callApi("/wapi/v3/tradeFee.html", HttpMethod.GET, params, true, true, serializer())
    }
    //endregion

    //region Market Data API
    suspend fun ping() {
        return httpConnector.callApi("/api/v3/ping", HttpMethod.GET, emptyMap(), false, false, serializer())
    }

    suspend fun serverTime(): Instant {
        return httpConnector.callApi("/api/v3/time", HttpMethod.GET, emptyMap(), false, false, ServerTime.serializer()).serverTime
    }

    suspend fun getExchangeInfo(): ExchangeInfo {
        return httpConnector.callApi("/api/v3/exchangeInfo", HttpMethod.GET, emptyMap(), false, false, serializer())
    }

    suspend fun getCandlestickData(
        symbol: String,
        interval: CandleStickInterval,
        startTime: Instant,
        endTime: Instant,
        limit: Int = 500
    ): List<CandlestickData> {
        val params = HashMap<String, String>()
        params["symbol"] = symbol
        params["interval"] = interval.id
        params["startTime"] = startTime.toEpochMilli().toString()
        params["endTime"] = endTime.toEpochMilli().toString()
        params["limit"] = limit.toString()
        return httpConnector.callApi("/api/v3/klines", HttpMethod.GET, params, false, false, serializer())
    }

    suspend fun getOrderBook(symbol: String, limit: Int = 100): OrderBook {
        val params = mapOf(
            Pair("symbol", symbol),
            Pair("limit", limit.toString())
        )
        return httpConnector.callApi("/api/v3/depth", HttpMethod.GET, params, false, false, serializer())
    }
    //endregion

    //region Spot Account/Trade API
    suspend fun testNewOrder(
        symbol: String,
        side: OrderSide,
        type: OrderType,
        timestamp: Instant,
        timeInForce: TimeInForce? = null,
        quantity: BigDecimal? = null,
        quoteOrderQty: BigDecimal? = null,
        price: BigDecimal? = null,
        newClientOrderId: String? = null,
        stopPrice: BigDecimal? = null,
        icebergQty: BigDecimal? = null,
        newOrderRespType: OrderRespType? = null,
        recvWindow: Long? = null
    ): NewOrder {
        val params = buildMap<String, String> {
            put("symbol", symbol)
            put("side", side.toString())
            put("type", type.toString())
            put("timestamp", timestamp.toEpochMilli().toString())
            if (timeInForce != null) put("timeInForce", timeInForce.toString())
            if (quantity != null) put("quantity", quantity.toPlainString())
            if (quoteOrderQty != null) put("quoteOrderQty", quoteOrderQty.toPlainString())
            if (price != null) put("price", price.toPlainString())
            if (newClientOrderId != null) put("newClientOrderId", newClientOrderId)
            if (stopPrice != null) put("stopPrice", stopPrice.toPlainString())
            if (icebergQty != null) put("icebergQty", icebergQty.toPlainString())
            if (newOrderRespType != null) put("newOrderRespType", newOrderRespType.toString())
            if (recvWindow != null) put("recvWindow", recvWindow.toString())
        }
        return httpConnector.callApi("/api/v3/order/test", HttpMethod.POST, params, true, true, serializer())
    }

    suspend fun placeOrder(
        symbol: String,
        side: OrderSide,
        type: OrderType,
        timestamp: Instant,
        timeInForce: TimeInForce? = null,
        quantity: BigDecimal? = null,
        quoteOrderQty: BigDecimal? = null,
        price: BigDecimal? = null,
        newClientOrderId: String? = null,
        stopPrice: BigDecimal? = null,
        icebergQty: BigDecimal? = null,
        newOrderRespType: OrderRespType? = null,
        recvWindow: Long? = null
    ): NewOrder {
        val params = buildMap<String, String> {
            put("symbol", symbol)
            put("side", side.toString())
            put("type", type.toString())
            put("timestamp", timestamp.toEpochMilli().toString())
            if (timeInForce != null) put("timeInForce", timeInForce.toString())
            if (quantity != null) put("quantity", quantity.toPlainString())
            if (quoteOrderQty != null) put("quoteOrderQty", quoteOrderQty.toPlainString())
            if (price != null) put("price", price.toPlainString())
            if (newClientOrderId != null) put("newClientOrderId", newClientOrderId)
            if (stopPrice != null) put("stopPrice", stopPrice.toPlainString())
            if (icebergQty != null) put("icebergQty", icebergQty.toPlainString())
            if (newOrderRespType != null) put("newOrderRespType", newOrderRespType.toString())
            if (recvWindow != null) put("recvWindow", recvWindow.toString())
        }
        return httpConnector.callApi("/api/v3/order", HttpMethod.POST, params, true, true, serializer())
    }

    suspend fun cancelOrder(
        symbol: String,
        timestamp: Instant,
        orderId: Long? = null,
        origClientOrderId: String? = null,
        newClientOrderId: String? = null,
        recvWindow: Long? = null,
    ): CancelOrder {
        val params = buildMap<String, String> {
            put("symbol", symbol)
            put("timestamp", timestamp.toEpochMilli().toString())

            when {
                orderId != null -> put("orderId", orderId.toString())
                origClientOrderId != null -> put("origClientOrderId", origClientOrderId)
                else -> throw Error.MANDATORY_PARAM_EMPTY_OR_MALFORMED.toException()
            }

            if (newClientOrderId != null) put("newClientOrderId", newClientOrderId)
            if (recvWindow != null) put("recvWindow", recvWindow.toString())
        }
        return httpConnector.callApi("/api/v3/order", HttpMethod.DELETE, params, true, true, serializer())
    }

    suspend fun cancelAllOrders(symbol: String, timestamp: Instant, recvWindow: Long? = null): List<CanceledOrder> {
        val params = buildMap<String, String> {
            put("symbol", symbol)
            put("timestamp", timestamp.toEpochMilli().toString())
            if (recvWindow != null) put("recvWindow", recvWindow.toString())
        }
        return httpConnector.callApi("/api/v3/openOrders", HttpMethod.DELETE, params, true, true, serializer())
    }

    suspend fun getAccountInfo(timestamp: Instant, recvWindow: Long? = null): AccountInfo {
        val params = HashMap<String, String>()
        params["timestamp"] = timestamp.toEpochMilli().toString()
        if (recvWindow != null) params["recvWindow"] = recvWindow.toString()
        return httpConnector.callApi("/api/v3/account", HttpMethod.GET, params, true, true, serializer())
    }

    suspend fun getAccountTrades(
        symbol: String,
        timestamp: Instant,
        startTime: Instant? = null,
        endTime: Instant? = null,
        fromId: Long? = null,
        limit: Int? = null,
        recvWindow: Long? = null
    ): List<AccountTrade> {
        val params = buildMap<String, String> {
            put("symbol", symbol)
            put("timestamp", timestamp.toEpochMilli().toString())
            if (startTime != null) put("startTime", startTime.toEpochMilli().toString())
            if (endTime != null) put("endTime", endTime.toEpochMilli().toString())
            if (fromId != null) put("fromId", fromId.toString())
            if (limit != null) put("limit", limit.toString())
            if (recvWindow != null) put("recvWindow", recvWindow.toString())
        }
        return httpConnector.callApi("/api/v3/myTrades", HttpMethod.GET, params, true, true, serializer())
    }
    //endregion

    //region Market Streams API
    fun aggregateTradeStream(symbol: String): Flow<EventData<AggregateTradeEvent>> {
        return webSocketConnector.subscribeTo("$symbol@aggTrade", serializer())
    }

    fun tradeStream(symbol: String): Flow<EventData<TradeEvent>> {
        return webSocketConnector.subscribeTo("$symbol@trade", serializer())
    }

    fun candlestickStream(symbol: String, interval: CandleStickInterval): Flow<EventData<CandlestickEvent>> {
        return webSocketConnector.subscribeTo("$symbol@kline_${interval.id}", serializer())
    }

    fun individualSymbolMiniTickerStream(symbol: String): Flow<EventData<MiniTickerEvent>> {
        return webSocketConnector.subscribeTo("$symbol@miniTicker", serializer())
    }

    val allMarketMiniTickersStream: Flow<EventData<List<MiniTickerEvent>>> = run {
        webSocketConnector.subscribeTo("!miniTicker@arr", serializer())
    }

    fun individualSymbolTickerStream(symbol: String): Flow<EventData<TickerEvent>> {
        return webSocketConnector.subscribeTo("$symbol@ticker", serializer())
    }

    val allMarketTickersStream: Flow<EventData<List<TickerEvent>>> = run {
        webSocketConnector.subscribeTo("!ticker@arr", serializer())
    }

    fun individualSymbolBookTickerStream(symbol: String): Flow<EventData<BookTickerEvent>> {
        return webSocketConnector.subscribeTo("$symbol@bookTicker", serializer())
    }

    val allBookTickerStream: Flow<EventData<BookTickerEvent>> = run {
        webSocketConnector.subscribeTo("!bookTicker", serializer())
    }

    fun partialBookDepthStream(
        symbol: String,
        level: PartialBookDepthEvent.Level,
        updateSpeed: BookUpdateSpeed? = null
    ): Flow<EventData<PartialBookDepthEvent>> {
        val updateSpeedStr = if (updateSpeed == null) "" else "@${updateSpeed.timeMs}ms"
        return webSocketConnector.subscribeTo("$symbol@depth${level.id}$updateSpeedStr", serializer())
    }

    fun diffDepthStream(
        symbol: String,
        updateSpeed: BookUpdateSpeed? = null
    ): Flow<EventData<DiffDepthEvent>> {
        val updateSpeedStr = if (updateSpeed == null) "" else "@${updateSpeed.timeMs}ms"
        return webSocketConnector.subscribeTo("$symbol@depth$updateSpeedStr", serializer())
    }
    //endregion

    //region User Data Streams Public API
    val accountStream: Flow<EventData<AccountEvent>> = run {
        webSocketConnector.subscribeToPrivateChannel()
    }
    //endregion

    //region User Data Streams API
    suspend fun getListenKey(): String {
        val resp = httpConnector.callApi("/api/v3/userDataStream", HttpMethod.POST, emptyMap(), true, false, serializer<ListenKey>())
        return resp.listenKey
    }

    suspend fun pingListenKey(listenKey: String) {
        val params = mapOf(Pair("listenKey", listenKey))
        httpConnector.callApi("/api/v3/userDataStream", HttpMethod.PUT, params, true, false, serializer<Unit>())
    }

    suspend fun deleteListenKey(listenKey: String) {
        val params = mapOf(Pair("listenKey", listenKey))
        httpConnector.callApi("/api/v3/userDataStream", HttpMethod.DELETE, params, true, false, serializer<Unit>())
    }
    //endregion

    //region Public Models
    enum class ApiNet {
        Main,
        Test
    }

    @Serializable(CandleStickInterval.Companion.CandleStickIntervalSerializer::class)
    enum class CandleStickInterval(val id: String) {
        INTERVAL_1_MINUTE("1m"),
        INTERVAL_3_MINUTES("3m"),
        INTERVAL_5_MINUTES("5m"),
        INTERVAL_15_MINUTES("15m"),
        INTERVAL_30_MINUTES("30m"),
        INTERVAL_1_HOUR("1h"),
        INTERVAL_2_HOURS("2h"),
        INTERVAL_4_HOURS("4h"),
        INTERVAL_6_HOURS("6h"),
        INTERVAL_8_HOURS("8h"),
        INTERVAL_12_HOURS("12h"),
        INTERVAL_1_DAY("1d"),
        INTERVAL_3_DAYS("3d"),
        INTERVAL_1_WEEK("1w"),
        INTERVAL_1_MONTH("1M");

        companion object {
            object CandleStickIntervalSerializer : KSerializer<CandleStickInterval> {
                override val descriptor: SerialDescriptor = buildSerialDescriptor("BinanceCandleStickIntervalSerializer", PrimitiveKind.STRING)

                override fun deserialize(decoder: Decoder): CandleStickInterval {
                    return when (val intervalStr = decoder.decodeString()) {
                        "1m" -> INTERVAL_1_MINUTE
                        "3m" -> INTERVAL_3_MINUTES
                        "5m" -> INTERVAL_5_MINUTES
                        "15m" -> INTERVAL_15_MINUTES
                        "30m" -> INTERVAL_30_MINUTES
                        "1h" -> INTERVAL_1_HOUR
                        "2h" -> INTERVAL_2_HOURS
                        "4h" -> INTERVAL_4_HOURS
                        "6h" -> INTERVAL_6_HOURS
                        "8h" -> INTERVAL_8_HOURS
                        "12h" -> INTERVAL_12_HOURS
                        "1d" -> INTERVAL_1_DAY
                        "3d" -> INTERVAL_3_DAYS
                        "1w" -> INTERVAL_1_WEEK
                        "1M" -> INTERVAL_1_MONTH
                        else -> throw SerializationException("Not recognized candlestick interval $intervalStr")
                    }
                }

                override fun serialize(encoder: Encoder, value: CandleStickInterval) {
                    encoder.encodeString(value.id)
                }
            }
        }
    }

    enum class OrderType {
        LIMIT,
        MARKET,
        STOP_LOSS,
        STOP_LOSS_LIMIT,
        TAKE_PROFIT,
        TAKE_PROFIT_LIMIT,
        LIMIT_MAKER,
    }

    enum class OrderSide {
        BUY,
        SELL,
    }

    enum class OrderStatus {
        NEW,
        PARTIALLY_FILLED,
        FILLED,
        CANCELED,
        PENDING_CANCEL,
        REJECTED,
        EXPIRED,
    }

    enum class TimeInForce {
        GTC,
        IOC,
        FOK,
    }

    enum class OrderRespType {
        ACK,
        RESULT,
        FULL,
    }

    enum class RateLimitType {
        REQUEST_WEIGHT,
        ORDERS,
        RAW_REQUESTS,
    }

    enum class RateLimitInterval {
        SECOND,
        MINUTE,
        HOUR,
        DAY,
    }

    enum class TradingPermission {
        SPOT,
        MARGIN,
        LEVERAGED,
    }

    enum class ContingencyType {
        OCO
    }

    enum class ExecutionType {
        NEW,
        CANCELED,
        REPLACED,
        REJECTED,
        TRADE,
        EXPIRED,
    }

    enum class OCOStatus {
        RESPONSE,
        EXEC_STARTED,
        ALL_DONE,
    }

    enum class OCOOrderStatus {
        EXECUTING,
        ALL_DONE,
        REJECT,
    }

    @Serializable
    data class SystemStatus(
        val status: Int,
        val msg: String
    )

    @Serializable
    data class ServerTime(
        @Serializable(InstantAsLongMillisSerializer::class) val serverTime: Instant
    )

    @Serializable
    data class UserCoin(
        val coin: String,
        val depositAllEnable: Boolean,
        val withdrawAllEnable: Boolean,
        val name: String,
        @Serializable(BigDecimalAsStringSerializer::class) val free: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val locked: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val freeze: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val withdrawing: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val ipoing: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val ipoable: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val storage: BigDecimal,
        val isLegalMoney: Boolean,
        val trading: Boolean,
        val networkList: List<Network>
    ) {
        @Serializable
        data class Network(
            val network: String,
            val coin: String,
            @Serializable(BigDecimalAsStringSerializer::class) val withdrawIntegerMultiple: BigDecimal? = null,
            val isDefault: Boolean,
            val depositEnable: Boolean,
            val withdrawEnable: Boolean,
            val depositDesc: String? = null,
            val withdrawDesc: String? = null,
            val specialTips: String? = null,
            val name: String,
            val resetAddressStatus: Boolean,
            val addressRegex: String,
            val memoRegex: String,
            @Serializable(BigDecimalAsStringSerializer::class) val withdrawFee: BigDecimal,
            @Serializable(BigDecimalAsStringSerializer::class) val withdrawMin: BigDecimal,
            @Serializable(BigDecimalAsStringSerializer::class) val withdrawMax: BigDecimal? = null,
            val minConfirm: Long? = null,
            val unLockConfirm: Long? = null
        )
    }

    @Serializable
    data class TradeFee(
        val tradeFee: List<Fee>,
        val success: Boolean
    ) {
        @Serializable
        data class Fee(
            val symbol: String,
            @Serializable(BigDecimalAsDoubleSerializer::class) val maker: BigDecimal,
            @Serializable(BigDecimalAsDoubleSerializer::class) val taker: BigDecimal
        )
    }

    @Serializable
    data class ExchangeInfo(
        val timezone: String,
        @Serializable(InstantAsLongMillisSerializer::class) val serverTime: Instant,
        val rateLimits: List<RateLimit>,
        val exchangeFilters: List<ExchangeFilter>,
        val symbols: List<Symbol>
    ) {
        val symbolsIndexed: Map<String, Symbol> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val map = HashMap<String, Symbol>(symbols.size, 1.0F)
            for (symbol in symbols) {
                map[symbol.symbol] = symbol
            }
            map
        }

        enum class ExchangeFilter {
            EXCHANGE_MAX_NUM_ORDERS,
            EXCHANGE_MAX_NUM_ALGO_ORDERS,
        }

        @Serializable
        data class RateLimit(
            val rateLimitType: RateLimitType,
            val interval: RateLimitInterval,
            val intervalNum: Long,
            val limit: Long
        ) {
            @Transient
            val httpHeader = "${rateLimitType.headerPrefix()}-$intervalNum${interval.toShortDescription()}"

            private fun RateLimitType.headerPrefix() = when (this) {
                RateLimitType.REQUEST_WEIGHT -> "X-MBX-USED-WEIGHT"
                RateLimitType.ORDERS -> "X-MBX-ORDER-COUNT"
                RateLimitType.RAW_REQUESTS -> "X-MBX-REQ-COUNT" // TODO: Check this header name
            }

            private fun RateLimitInterval.toShortDescription() = when (this) {
                RateLimitInterval.SECOND -> "s"
                RateLimitInterval.MINUTE -> "m"
                RateLimitInterval.HOUR -> "h"
                RateLimitInterval.DAY -> "d"
            }
        }

        @Serializable
        data class Symbol(
            val symbol: String,
            val status: Status,
            val baseAsset: String,
            val quoteAsset: String,
            val baseAssetPrecision: Int,
            val quoteAssetPrecision: Int,
            val baseCommissionPrecision: Int,
            val quoteCommissionPrecision: Int,
            val icebergAllowed: Boolean,
            val ocoAllowed: Boolean,
            val quoteOrderQtyMarketAllowed: Boolean,
            val isSpotTradingAllowed: Boolean,
            val isMarginTradingAllowed: Boolean,
            val orderTypes: List<OrderType>,
            val filters: List<Filter>,
            val permissions: List<TradingPermission>
        ) {
            val filtersIndexed: Map<Filter.Type, Filter> by lazy(LazyThreadSafetyMode.PUBLICATION) {
                val map = EnumMap<Filter.Type, Filter>(Filter.Type::class.java)
                for (filter in filters) map[filter.filterType] = filter
                map
            }

            @Serializable
            enum class Status {
                PRE_TRADING,
                TRADING,
                POST_TRADING,
                END_OF_DAY,
                HALT,
                AUCTION_MATCH,
                BREAK,
            }

            @Serializable
            data class Filter(
                val filterType: Type,
                @Serializable(BigDecimalAsStringSerializer::class) val minPrice: BigDecimal? = null,
                @Serializable(BigDecimalAsStringSerializer::class) val maxPrice: BigDecimal? = null,
                @Serializable(BigDecimalAsStringSerializer::class) val tickSize: BigDecimal? = null,
                @Serializable(BigDecimalAsStringSerializer::class) val multiplierUp: BigDecimal? = null,
                @Serializable(BigDecimalAsStringSerializer::class) val multiplierDown: BigDecimal? = null,
                val avgPriceMins: Long? = null,
                @Serializable(BigDecimalAsStringSerializer::class) val minQty: BigDecimal? = null,
                @Serializable(BigDecimalAsStringSerializer::class) val maxQty: BigDecimal? = null,
                @Serializable(BigDecimalAsStringSerializer::class) val stepSize: BigDecimal? = null,
                @Serializable(BigDecimalAsStringSerializer::class) val minNotional: BigDecimal? = null,
                val applyToMarket: Boolean? = null,
                val limit: Long? = null,
                val maxNumOrders: Long? = null,
                val maxNumAlgoOrders: Long? = null
            ) {
                enum class Type {
                    PRICE_FILTER,
                    PERCENT_PRICE,
                    LOT_SIZE,
                    MIN_NOTIONAL,
                    ICEBERG_PARTS,
                    MARKET_LOT_SIZE,
                    MAX_NUM_ORDERS,
                    MAX_NUM_ALGO_ORDERS,
                    MAX_NUM_ICEBERG_ORDERS,
                    MAX_POSITION,
                }
            }
        }
    }

    data class CandlestickData(
        val openTime: Instant,
        val open: BigDecimal,
        val high: BigDecimal,
        val low: BigDecimal,
        val close: BigDecimal,
        val volume: BigDecimal,
        val closeTime: Instant,
        val quoteAssetVolume: BigDecimal,
        val tradesCount: Long,
        val takerBuyBaseAssetVolume: BigDecimal,
        val takerBuyQuoteAssetVolume: BigDecimal,
        val ignore: BigDecimal
    ) {
        object CandlestickDataDeserializer : KSerializer<CandlestickData> {
            override val descriptor: SerialDescriptor = buildSerialDescriptor("BinanceCandlestickDataDeserializer", StructureKind.LIST)

            override fun deserialize(decoder: Decoder): CandlestickData {
                return decoder.decodeStructure(descriptor) {
                    var openTime: Instant? = null
                    var open: BigDecimal? = null
                    var high: BigDecimal? = null
                    var low: BigDecimal? = null
                    var close: BigDecimal? = null
                    var volume: BigDecimal? = null
                    var closeTime: Instant? = null
                    var quoteAssetVolume: BigDecimal? = null
                    var tradesCount: Long? = null
                    var takerBuyBaseAssetVolume: BigDecimal? = null
                    var takerBuyQuoteAssetVolume: BigDecimal? = null
                    var ignore: BigDecimal? = null

                    loop@ while (true) {
                        when (val index = decodeElementIndex(descriptor)) {
                            CompositeDecoder.DECODE_DONE -> break@loop
                            0 -> openTime = decodeSerializableElement(descriptor, index, InstantAsLongMillisSerializer)
                            1 -> open = decodeSerializableElement(descriptor, index, BigDecimalAsStringSerializer)
                            2 -> high = decodeSerializableElement(descriptor, index, BigDecimalAsStringSerializer)
                            3 -> low = decodeSerializableElement(descriptor, index, BigDecimalAsStringSerializer)
                            4 -> close = decodeSerializableElement(descriptor, index, BigDecimalAsStringSerializer)
                            5 -> volume = decodeSerializableElement(descriptor, index, BigDecimalAsStringSerializer)
                            6 -> closeTime = decodeSerializableElement(descriptor, index, InstantAsLongMillisSerializer)
                            7 -> quoteAssetVolume = decodeSerializableElement(descriptor, index, BigDecimalAsStringSerializer)
                            8 -> tradesCount = decodeLongElement(descriptor, index)
                            9 -> takerBuyBaseAssetVolume = decodeSerializableElement(descriptor, index, BigDecimalAsStringSerializer)
                            10 -> takerBuyQuoteAssetVolume = decodeSerializableElement(descriptor, index, BigDecimalAsStringSerializer)
                            11 -> ignore = decodeSerializableElement(descriptor, index, BigDecimalAsStringSerializer)
                            else -> {
                            }
                        }
                    }

                    CandlestickData(
                        openTime!!,
                        open!!,
                        high!!,
                        low!!,
                        close!!,
                        volume!!,
                        closeTime!!,
                        quoteAssetVolume!!,
                        tradesCount!!,
                        takerBuyBaseAssetVolume!!,
                        takerBuyQuoteAssetVolume!!,
                        ignore!!
                    )
                }
            }

            override fun serialize(encoder: Encoder, value: CandlestickData) = throw RuntimeException("Not implemented")
        }
    }

    @Serializable
    data class OrderBook(
        val lastUpdateId: Long,
        val bids: List<@Serializable(Record.RecordDeserializer::class) Record>,
        val asks: List<@Serializable(Record.RecordDeserializer::class) Record>
    ) {
        data class Record(
            val price: BigDecimal,
            val qty: BigDecimal
        ) {
            object RecordDeserializer : KSerializer<Record> {
                override val descriptor: SerialDescriptor = buildSerialDescriptor("BinanceOrderBookRecordDeserializer", StructureKind.LIST) {
                    element("price", BigDecimalAsStringSerializer.descriptor)
                    element("qty", BigDecimalAsStringSerializer.descriptor)
                }

                override fun deserialize(decoder: Decoder): Record {
                    return decoder.decodeStructure(descriptor) {
                        var price: BigDecimal? = null
                        var qty: BigDecimal? = null

                        loop@ while (true) {
                            when (val index = decodeElementIndex(descriptor)) {
                                CompositeDecoder.DECODE_DONE -> break@loop
                                0 -> price = decodeSerializableElement(descriptor, index, BigDecimalAsStringSerializer)
                                1 -> qty = decodeSerializableElement(descriptor, index, BigDecimalAsStringSerializer)
                            }
                        }

                        Record(price!!, qty!!)
                    }
                }

                override fun serialize(encoder: Encoder, value: Record) = throw RuntimeException("Not implemented")
            }
        }
    }

    @Serializable
    data class NewOrder(
        val symbol: String,
        val orderId: Long,
        val orderListId: Long,
        val clientOrderId: String,
        @Serializable(InstantAsLongMillisSerializer::class) val transactTime: Instant,
        @Serializable(BigDecimalAsStringSerializer::class) val price: BigDecimal? = null,
        @Serializable(BigDecimalAsStringSerializer::class) val origQty: BigDecimal? = null,
        @Serializable(BigDecimalAsStringSerializer::class) val executedQty: BigDecimal? = null,
        @Serializable(BigDecimalAsStringSerializer::class) val cummulativeQuoteQty: BigDecimal? = null,
        val status: OrderStatus? = null,
        val timeInForce: TimeInForce? = null,
        val type: OrderType? = null,
        val side: OrderSide? = null,
        val fills: List<Order>? = null
    ) {
        @Serializable
        data class Order(
            @Serializable(BigDecimalAsStringSerializer::class) val price: BigDecimal,
            @Serializable(BigDecimalAsStringSerializer::class) val qty: BigDecimal,
            @Serializable(BigDecimalAsStringSerializer::class) val commission: BigDecimal,
            val commissionAsset: String
        )
    }

    @Serializable
    data class CancelOrder(
        val symbol: String,
        val origClientOrderId: String,
        val orderId: Long,
        val orderListId: Long,
        val clientOrderId: String,
        @Serializable(BigDecimalAsStringSerializer::class) val price: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val origQty: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val executedQty: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val cummulativeQuoteQty: BigDecimal,
        val status: OrderStatus,
        val timeInForce: TimeInForce,
        val type: OrderType,
        val side: OrderSide
    )

    @Serializable
    data class CanceledOrder(
        val symbol: String,
        val origClientOrderId: String,
        val orderId: Long,
        val orderListId: Long,
        val clientOrderId: String,
        @Serializable(BigDecimalAsStringSerializer::class) val price: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val origQty: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val executedQty: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val cummulativeQuoteQty: BigDecimal,
        val status: OrderStatus,
        val timeInForce: TimeInForce,
        val type: OrderType,
        val side: OrderSide
    )

    @Serializable
    data class AccountInfo(
        val makerCommission: Long,
        val takerCommission: Long,
        val buyerCommission: Long,
        val sellerCommission: Long,
        val canTrade: Boolean,
        val canWithdraw: Boolean,
        val canDeposit: Boolean,
        @Serializable(InstantAsLongMillisSerializer::class) val updateTime: Instant,
        val accountType: String,
        val balances: List<Balance>,
        val permissions: List<String>
    ) {
        @Serializable
        data class Balance(
            val asset: String,
            @Serializable(BigDecimalAsStringSerializer::class) val free: BigDecimal,
            @Serializable(BigDecimalAsStringSerializer::class) val locked: BigDecimal
        )
    }

    @Serializable
    data class AccountTrade(
        val symbol: String,
        val id: Long,
        val orderId: Long,
        val orderListId: Long,
        @Serializable(BigDecimalAsStringSerializer::class) val price: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val qty: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val quoteQty: BigDecimal,
        @Serializable(BigDecimalAsStringSerializer::class) val commission: BigDecimal,
        val commissionAsset: String,
        @Serializable(InstantAsLongMillisSerializer::class) val time: Instant,
        val isBuyer: Boolean,
        val isMaker: Boolean,
        val isBestMatch: Boolean
    )

    @Serializable
    data class ListenKey(
        val listenKey: String,
    )
    //endregion

    //region Public Events
    @Serializable
    data class AggregateTradeEvent(
        @SerialName("e") val eventType: String,
        @SerialName("E") @Serializable(InstantAsLongMillisSerializer::class) val eventTime: Instant,
        @SerialName("s") val symbol: String,
        @SerialName("a") val aggregateTradeId: Long,
        @SerialName("p") @Serializable(BigDecimalAsStringSerializer::class) val price: BigDecimal,
        @SerialName("q") @Serializable(BigDecimalAsStringSerializer::class) val quantity: BigDecimal,
        @SerialName("f") val firstTradeId: Long,
        @SerialName("l") val lastTradeId: Long,
        @SerialName("T") @Serializable(InstantAsLongMillisSerializer::class) val tradeTime: Instant,
        @SerialName("m") val buyerMarketMaker: Boolean,
        @SerialName("M") val ignore: Boolean
    )

    @Serializable
    data class TradeEvent(
        @SerialName("e") val eventType: String,
        @SerialName("E") @Serializable(InstantAsLongMillisSerializer::class) val eventTime: Instant,
        @SerialName("s") val symbol: String,
        @SerialName("t") val tradeId: Long,
        @SerialName("p") @Serializable(BigDecimalAsStringSerializer::class) val price: BigDecimal,
        @SerialName("q") @Serializable(BigDecimalAsStringSerializer::class) val quantity: BigDecimal,
        @SerialName("b") val buyerOrderId: Long,
        @SerialName("a") val sellerOrderId: Long,
        @SerialName("T") @Serializable(InstantAsLongMillisSerializer::class) val tradeTime: Instant,
        @SerialName("m") val buyerMarketMaker: Boolean,
        @SerialName("M") val ignore: Boolean
    )

    @Serializable
    data class CandlestickEvent(
        @SerialName("e") val eventType: String,
        @SerialName("E") @Serializable(InstantAsLongMillisSerializer::class) val eventTime: Instant,
        @SerialName("s") val symbol: String,
        @SerialName("k") val data: Data
    ) {
        @Serializable
        data class Data(
            @SerialName("t") @Serializable(InstantAsLongMillisSerializer::class) val klineStartTime: Instant,
            @SerialName("T") @Serializable(InstantAsLongMillisSerializer::class) val klineCloseTime: Instant,
            @SerialName("s") val symbol: String,
            @SerialName("i") val interval: CandleStickInterval,
            @SerialName("f") val firstTradeId: Long,
            @SerialName("L") val lastTradeId: Long,
            @SerialName("o") @Serializable(BigDecimalAsStringSerializer::class) val openPrice: BigDecimal,
            @SerialName("c") @Serializable(BigDecimalAsStringSerializer::class) val closePrice: BigDecimal,
            @SerialName("h") @Serializable(BigDecimalAsStringSerializer::class) val highPrice: BigDecimal,
            @SerialName("l") @Serializable(BigDecimalAsStringSerializer::class) val lowPrice: BigDecimal,
            @SerialName("v") @Serializable(BigDecimalAsStringSerializer::class) val baseAssetVolume: BigDecimal,
            @SerialName("n") val tradesCount: Long,
            @SerialName("x") val klineClosed: Boolean,
            @SerialName("q") @Serializable(BigDecimalAsStringSerializer::class) val quoteAssetVolume: BigDecimal,
            @SerialName("V") @Serializable(BigDecimalAsStringSerializer::class) val takerBuyBaseAssetVolume: BigDecimal,
            @SerialName("Q") @Serializable(BigDecimalAsStringSerializer::class) val takerBuyQuoteAssetVolume: BigDecimal,
            @SerialName("B") @Serializable(BigDecimalAsStringSerializer::class) val ignore: BigDecimal
        )
    }

    @Serializable
    data class MiniTickerEvent(
        @SerialName("e") val eventType: String,
        @SerialName("E") @Serializable(InstantAsLongMillisSerializer::class) val eventTime: Instant,
        @SerialName("s") val symbol: String,
        @SerialName("c") @Serializable(BigDecimalAsStringSerializer::class) val closePrice: BigDecimal,
        @SerialName("o") @Serializable(BigDecimalAsStringSerializer::class) val openPrice: BigDecimal,
        @SerialName("h") @Serializable(BigDecimalAsStringSerializer::class) val highPrice: BigDecimal,
        @SerialName("l") @Serializable(BigDecimalAsStringSerializer::class) val lowPrice: BigDecimal,
        @SerialName("v") @Serializable(BigDecimalAsStringSerializer::class) val totalTradedBaseAssetVolume: BigDecimal,
        @SerialName("q") @Serializable(BigDecimalAsStringSerializer::class) val totalTradedQuoteAssetVolume: BigDecimal
    )

    @Serializable
    data class TickerEvent(
        @SerialName("e") val eventType: String,
        @SerialName("E") @Serializable(InstantAsLongMillisSerializer::class) val eventTime: Instant,
        @SerialName("s") val symbol: String,
        @SerialName("p") @Serializable(BigDecimalAsStringSerializer::class) val priceChange: BigDecimal,
        @SerialName("P") @Serializable(BigDecimalAsStringSerializer::class) val priceChangePercent: BigDecimal,
        @SerialName("w") @Serializable(BigDecimalAsStringSerializer::class) val weightedAveragePrice: BigDecimal,
        @SerialName("x") @Serializable(BigDecimalAsStringSerializer::class) val firstTradeBefore24hrRollingWindow: BigDecimal,
        @SerialName("c") @Serializable(BigDecimalAsStringSerializer::class) val lastPrice: BigDecimal,
        @SerialName("Q") @Serializable(BigDecimalAsStringSerializer::class) val lastQuantity: BigDecimal,
        @SerialName("b") @Serializable(BigDecimalAsStringSerializer::class) val bestBidPrice: BigDecimal,
        @SerialName("B") @Serializable(BigDecimalAsStringSerializer::class) val bestBidQuantity: BigDecimal,
        @SerialName("a") @Serializable(BigDecimalAsStringSerializer::class) val bestAskPrice: BigDecimal,
        @SerialName("A") @Serializable(BigDecimalAsStringSerializer::class) val bestAskQuantity: BigDecimal,
        @SerialName("o") @Serializable(BigDecimalAsStringSerializer::class) val openPrice: BigDecimal,
        @SerialName("h") @Serializable(BigDecimalAsStringSerializer::class) val highPrice: BigDecimal,
        @SerialName("l") @Serializable(BigDecimalAsStringSerializer::class) val lowPrice: BigDecimal,
        @SerialName("v") @Serializable(BigDecimalAsStringSerializer::class) val totalTradedBaseAssetVolume: BigDecimal,
        @SerialName("q") @Serializable(BigDecimalAsStringSerializer::class) val totalTradedQuoteAssetVolume: BigDecimal,
        @SerialName("O") @Serializable(InstantAsLongMillisSerializer::class) val statisticsOpenTime: Instant,
        @SerialName("C") @Serializable(InstantAsLongMillisSerializer::class) val statisticsCloseTime: Instant,
        @SerialName("F") val firstTradeId: Long,
        @SerialName("L") val lastTradeId: Long,
        @SerialName("n") val tradesCount: Long
    )

    @Serializable
    data class BookTickerEvent(
        @SerialName("u") val orderBookUpdateId: Long,
        @SerialName("s") val symbol: String,
        @SerialName("b") @Serializable(BigDecimalAsStringSerializer::class) val bestBidPrice: BigDecimal,
        @SerialName("B") @Serializable(BigDecimalAsStringSerializer::class) val bestBidQty: BigDecimal,
        @SerialName("a") @Serializable(BigDecimalAsStringSerializer::class) val bestAskPrice: BigDecimal,
        @SerialName("A") @Serializable(BigDecimalAsStringSerializer::class) val bestAskQty: BigDecimal
    )

    enum class BookUpdateSpeed(val timeMs: Long) {
        TIME_100_MS(100),
        TIME_1000_MS(1000),
    }

    @Serializable
    data class PartialBookDepthEvent(
        val lastUpdateId: Long,
        val bids: List<@Serializable(OrderBook.Record.RecordDeserializer::class) OrderBook.Record>,
        val asks: List<@Serializable(OrderBook.Record.RecordDeserializer::class) OrderBook.Record>
    ) {
        enum class Level(val id: Long) {
            LEVEL_5(5),
            LEVEL_10(10),
            LEVEL_20(20),
        }
    }

    @Serializable
    data class DiffDepthEvent(
        @SerialName("e") val eventType: String,
        @SerialName("E") @Serializable(InstantAsLongMillisSerializer::class) val eventTime: Instant,
        @SerialName("s") val symbol: String,
        @SerialName("U") val firstUpdateIdInEvent: Long,
        @SerialName("b") val bids: List<@Serializable(OrderBook.Record.RecordDeserializer::class) OrderBook.Record>,
        @SerialName("a") val asks: List<@Serializable(OrderBook.Record.RecordDeserializer::class) OrderBook.Record>
    )

    interface AccountEvent

    @Serializable
    data class AccountUpdateEvent(
        @SerialName("e") val eventType: String,
        @SerialName("E") @Serializable(InstantAsLongMillisSerializer::class) val eventTime: Instant,
        @SerialName("u") @Serializable(InstantAsLongMillisSerializer::class) val lastAccountUpdateTime: Instant,
        @SerialName("m") val makerCommissionRate: Long,
        @SerialName("t") val takerCommissionRate: Long,
        @SerialName("b") val buyerCommissionRate: Long,
        @SerialName("s") val sellerCommissionRate: Long,
        @SerialName("T") val canTrade: Boolean,
        @SerialName("W") val canWithdraw: Boolean,
        @SerialName("D") val canDeposit: Boolean,
        @SerialName("B") val balances: List<Balance>
    ) : AccountEvent {
        @Serializable
        data class Balance(
            @SerialName("a") val asset: String,
            @SerialName("f") @Serializable(BigDecimalAsStringSerializer::class) val freeAmount: BigDecimal,
            @SerialName("l") @Serializable(BigDecimalAsStringSerializer::class) val lockedAmount: BigDecimal
        )
    }

    @Serializable
    data class OutboundAccountPositionEvent(
        @SerialName("e") val eventType: String,
        @SerialName("E") @Serializable(InstantAsLongMillisSerializer::class) val eventTime: Instant,
        @SerialName("u") @Serializable(InstantAsLongMillisSerializer::class) val lastAccountUpdateTime: Instant,
        @SerialName("B") val balances: List<Balance>
    ) : AccountEvent {
        @Serializable
        data class Balance(
            @SerialName("a") val asset: String,
            @SerialName("f") @Serializable(BigDecimalAsStringSerializer::class) val free: BigDecimal,
            @SerialName("l") @Serializable(BigDecimalAsStringSerializer::class) val locked: BigDecimal
        )
    }

    @Serializable
    data class BalanceUpdateEvent(
        @SerialName("e") val eventType: String,
        @SerialName("E") @Serializable(InstantAsLongMillisSerializer::class) val eventTime: Instant,
        @SerialName("T") @Serializable(InstantAsLongMillisSerializer::class) val clearTime: Instant,
        @SerialName("a") val asset: String,
        @SerialName("d") @Serializable(BigDecimalAsStringSerializer::class) val balanceDelta: BigDecimal
    ) : AccountEvent

    @Serializable
    data class OrderUpdateEvent(
        @SerialName("e") val eventType: String,
        @SerialName("E") @Serializable(InstantAsLongMillisSerializer::class) val eventTime: Instant,
        @SerialName("O") @Serializable(InstantAsLongMillisSerializer::class) val orderCreationTime: Instant,
        @SerialName("T") @Serializable(InstantAsLongMillisSerializer::class) val transactionTime: Instant,
        @SerialName("s") val symbol: String,
        @SerialName("i") val orderId: Long,
        @SerialName("t") val tradeId: Long,
        @SerialName("g") val orderListId: Long,
        @SerialName("c") val clientOrderId: String,
        @SerialName("C") val originalClientOrderId: String,
        @SerialName("S") val side: OrderSide,
        @SerialName("o") val orderType: OrderType,
        @SerialName("f") val timeInForce: TimeInForce,
        @SerialName("x") val currentExecutionType: ExecutionType,
        @SerialName("X") val currentOrderStatus: OrderStatus,
        @SerialName("r") val orderRejectReason: String,
        @SerialName("w") val inBook: Boolean,
        @SerialName("m") val makerSide: Boolean,
        @SerialName("q") @Serializable(BigDecimalAsStringSerializer::class) val orderQuantity: BigDecimal,
        @SerialName("Q") @Serializable(BigDecimalAsStringSerializer::class) val quoteOrderQty: BigDecimal,
        @SerialName("F") @Serializable(BigDecimalAsStringSerializer::class) val icebergQuantity: BigDecimal,
        @SerialName("p") @Serializable(BigDecimalAsStringSerializer::class) val orderPrice: BigDecimal,
        @SerialName("P") @Serializable(BigDecimalAsStringSerializer::class) val stopPrice: BigDecimal,
        @SerialName("L") @Serializable(BigDecimalAsStringSerializer::class) val lastExecutedPrice: BigDecimal,
        @SerialName("l") @Serializable(BigDecimalAsStringSerializer::class) val lastExecutedQuantity: BigDecimal,
        @SerialName("Y") @Serializable(BigDecimalAsStringSerializer::class) val lastQuoteAssetTransactedQuantity: BigDecimal,
        @SerialName("z") @Serializable(BigDecimalAsStringSerializer::class) val cumulativeFilledQuantity: BigDecimal,
        @SerialName("Z") @Serializable(BigDecimalAsStringSerializer::class) val cumulativeQuoteAssetTransactedQuantity: BigDecimal,
        @SerialName("n") @Serializable(BigDecimalAsStringSerializer::class) val commissionAmount: BigDecimal,
        @SerialName("N") val commissionAsset: String? = null,
        @SerialName("I") val ignore0: Long,
        @SerialName("M") val ignore1: Boolean
    ) : AccountEvent

    @Serializable
    data class ListStatusEvent(
        @SerialName("e") val eventType: String,
        @SerialName("E") @Serializable(InstantAsLongMillisSerializer::class) val eventTime: Instant,
        @SerialName("T") @Serializable(InstantAsLongMillisSerializer::class) val transactionTime: Instant,
        @SerialName("s") val symbol: String,
        @SerialName("C") val listClientOrderId: String,
        @SerialName("g") val orderListId: Long,
        @SerialName("c") val contingencyType: ContingencyType,
        @SerialName("l") val listStatusType: OCOStatus,
        @SerialName("L") val listOrderStatus: OCOOrderStatus,
        @SerialName("r") val listRejectReason: String,
        @SerialName("O") val orders: List<Order>
    ) : AccountEvent {
        @Serializable
        data class Order(
            @SerialName("s") val symbol: String,
            @SerialName("i") val orderId: Long,
            @SerialName("c") val clientOrderId: String
        )
    }
    //endregion

    //region Exceptions
    class Exception(val code: Long, val description: String) : Throwable("$code: $description", null, true, false)

    class DisconnectedException(override val cause: Throwable? = null) : Throwable("WebSocket connection was closed", cause, true, false)

    enum class Error(val code: Long, val msg: String) {
        UNKNOWN(1000, "An unknown error occurred while processing the request."),
        DISCONNECTED(-1001, "Internal error; unable to process your request. Please try again."),
        UNAUTHORIZED(-1002, "You are not authorized to execute this request."),
        TOO_MANY_REQUESTS(-1003, "Too many requests queued."),
        UNEXPECTED_RESP(-1006, "An unexpected response was received from the message bus. Execution status unknown."),
        TIMEOUT(-1007, "Timeout waiting for response from backend server. Send status unknown; execution status unknown."),
        UNKNOWN_ORDER_COMPOSITION(-1014, "Unsupported order combination."),
        TOO_MANY_ORDERS(-1015, "Too many new orders."),
        SERVICE_SHUTTING_DOWN(-1016, "This service is no longer available."),
        UNSUPPORTED_OPERATION(-1020, "This operation is not supported."),
        INVALID_TIMESTAMP(-1021, "Timestamp for this request is outside of the recvWindow."),
        INVALID_SIGNATURE(-1022, "Signature for this request is not valid."),
        NOT_FOUND_AUTHENTICATED_OR_AUTHORIZED(-1099, "Not found, authenticated, or authorized"),
        ILLEGAL_CHARS(-1100, "Illegal characters found in a parameter."),
        TOO_MANY_PARAMETERS(-1101, "Too many parameters sent for this endpoint."),
        MANDATORY_PARAM_EMPTY_OR_MALFORMED(-1102, "A mandatory parameter was not sent, was empty/null, or malformed."),
        UNKNOWN_PARAM(-1103, "An unknown parameter was sent."),
        UNREAD_PARAMETERS(-1104, "Not all sent parameters were read."),
        PARAM_EMPTY(-1105, "A parameter was empty."),
        PARAM_NOT_REQUIRED(-1106, "A parameter was sent when not required."),
        BAD_PRECISION(-1111, "Precision is over the maximum defined for this asset."),
        NO_DEPTH(-1112, "No orders on book for symbol."),
        TIF_NOT_REQUIRED(-1114, "TimeInForce parameter sent when not required."),
        INVALID_TIF(-1115, "Invalid timeInForce."),
        INVALID_ORDER_TYPE(-1116, "Invalid orderType."),
        INVALID_SIDE(-1117, "Invalid side."),
        EMPTY_NEW_CL_ORD_ID(-1118, "New client order ID was empty."),
        EMPTY_ORG_CL_ORD_ID(-1119, "Original client order ID was empty."),
        BAD_INTERVAL(-1120, "Invalid interval."),
        BAD_SYMBOL(-1121, "Invalid symbol."),
        INVALID_LISTEN_KEY(-1125, "This listenKey does not exist."),
        MORE_THAN_XX_HOURS(-1127, "Lookup interval is too big."),
        OPTIONAL_PARAMS_BAD_COMBO(-1128, "Combination of optional parameters invalid."),
        INVALID_PARAMETER(-1130, "Invalid data sent for a parameter."),
        BAD_RECV_WINDOW(-1131, "recvWindow must be less than 60000"),
        NEW_ORDER_REJECTED(-2010, "New order rejected"),
        CANCEL_REJECTED(-2011, "Cancel rejected"),
        NO_SUCH_ORDER(-2013, "Order does not exist."),
        BAD_API_KEY_FMT(-2014, "API-key format invalid."),
        REJECTED_MBX_KEY(-2015, "Invalid API-key, IP, or permissions for action."),
        NO_TRADING_WINDOW(-2016, "No trading window could be found for the symbol. Try ticker/24hrs instead."),
        PAIR_ADMIN_BAN_TRADE(-3021, "Margin account are not allowed to trade this trading pair."),
        ACCOUNT_BAN_TRADE(-3022, "You account's trading is banned."),
        WARNING_MARGIN_LEVEL(-3023, "You can't transfer out/place order under current margin level."),
        FEW_LIABILITY_LEFT(-3024, "The unpaid debt is too small after this repayment."),
        INVALID_EFFECTIVE_TIME(-3025, "Your input date is invalid."),
        VALIDATION_FAILED(-3026, "Your input param is invalid."),
        NOT_VALID_MARGIN_ASSET(-3027, "Not a valid margin asset."),
        NOT_VALID_MARGIN_PAIR(-3028, "Not a valid margin pair."),
        TRANSFER_FAILED(-3029, "Transfer failed."),
        ACCOUNT_BAN_REPAY(-3036, "This account is not allowed to repay."),
        PNL_CLEARING(-3037, "PNL is clearing. Wait a second."),
        LISTEN_KEY_NOT_FOUND(-3038, "Listen key not found."),
        PRICE_INDEX_NOT_FOUND(-3042, "PriceIndex not available for this margin pair."),
        NOT_WHITELIST_USER(-3999, "This function is only available for invited users."),
        CAPITAL_INVALID(-4001, "Invalid operation."),
        CAPITAL_IG(-4002, "Invalid get."),
        CAPITAL_IEV(-4003, "Your input email is invalid."),
        CAPITAL_UA(-4004, "You don't login or auth."),
        CAPAITAL_TOO_MANY_REQUEST(-4005, "Too many new requests."),
        CAPITAL_ONLY_SUPPORT_PRIMARY_ACCOUNT(-4006, "Support main account only."),
        CAPITAL_ADDRESS_VERIFICATION_NOT_PASS(-4007, "Address validation is not passed."),
        CAPITAL_ADDRESS_TAG_VERIFICATION_NOT_PASS(-4008, "Address tag validation is not passed."),
        ASSET_NOT_SUPPORTED(-5011, "This asset is not supported."),
        DAILY_PRODUCT_NOT_EXIST(-6001, "Daily product not exists."),
        DAILY_PRODUCT_NOT_ACCESSIBLE(-6003, "Product not exist or you don't have permission"),
        DAILY_PRODUCT_NOT_PURCHASABLE(-6004, "Product not in purchase status"),
        DAILY_LOWER_THAN_MIN_PURCHASE_LIMIT(-6005, "Smaller than min purchase limit"),
        DAILY_REDEEM_AMOUNT_ERROR(-6006, "Redeem amount error"),
        DAILY_REDEEM_TIME_ERROR(-6007, "Not in redeem time"),
        DAILY_PRODUCT_NOT_REDEEMABLE(-6008, "Product not in redeem status"),
        REQUEST_FREQUENCY_TOO_HIGH(-6009, "Request frequency too high"),
        EXCEEDED_USER_PURCHASE_LIMIT(-6011, "Exceeding the maximum num allowed to purchase per user"),
        BALANCE_NOT_ENOUGH(-6012, "Balance not enough"),
        PURCHASING_FAILED(-6013, "Purchasing failed"),
        UPDATE_FAILED(-6014, "Exceed up-limit allowed to purchased"),
        EMPTY_REQUEST_BODY(-6015, "Empty request body"),
        PARAMS_ERR(-6016, "Parameter err"),
        NOT_IN_WHITELIST(-6017, "Not in whitelist"),
        ASSET_NOT_ENOUGH(-6018, "Asset not enough"),
        PENDING(-6019, "Need confirm"),
    }
    //endregion

    //region Extensions
    private fun Error.toException() = Exception(code, msg)
    //endregion

    private class WebSocketConnector(
        apiNet: ApiNet,
        val binanceApi: BinanceApi,
        val scope: CoroutineScope,
        val json: Json,
    ) {
        private val apiUrlStream = when (apiNet) {
            ApiNet.Main -> API_STREAM_URL_MAIN_NET
            ApiNet.Test -> API_STREAM_URL_TEST_NET
        }

        private val streamCache = ConcurrentHashMap<String, Flow<EventData<*>>>()

        private val webSocketClient = springWebsocketClient(
            connectTimeoutMs = 10000,
            readTimeoutMs = 5000,
            writeTimeoutMs = 5000,
            maxFramePayloadLength = 65536 * 4,
        )

        private val connection: Flow<ConnectionData> = run {
            channelFlow<ConnectionData> connection@{
                logger.debug("Starting WebSocket connection channel")

                while (isActive) {
                    logger.debug("Establishing connection...")

                    try {
                        var connectionData: ConnectionData? = null

                        val session = webSocketClient.execute(URI.create(apiUrlStream)) { session ->
                            mono(Dispatchers.Unconfined) {
                                logger.info("Connection established")

                                coroutineScope {
                                    val wsMsgCounter = AtomicLong(0)
                                    val wsMsgReceiver = Channel<WebSocketMessage>(Channel.RENDEZVOUS)
                                    val requestResponses = ConcurrentHashMap<Long, List<Channel<WebSocketInboundMessage>>>()
                                    connectionData = ConnectionData()

                                    this@connection.send(connectionData!!)

                                    // Messages consumer
                                    launch(start = CoroutineStart.UNDISPATCHED) {
                                        session.receive().asFlow()
                                            .onEach { wsMsgCounter.incrementAndGet() }
                                            .filter { it.type == WebSocketMessage.Type.TEXT }
                                            .collect { msg ->
                                                val payloadJsonString = msg.payloadAsText
                                                if (logger.isTraceEnabled) logger.trace("Received: $payloadJsonString")

                                                val event = try {
                                                    try {
                                                        json.decodeFromString<WebSocketInboundMessage.Push>(payloadJsonString)
                                                    } catch (e: SerializationException) {
                                                        try {
                                                            json.decodeFromString<WebSocketInboundMessage.Response>(payloadJsonString)
                                                        } catch (e: SerializationException) {
                                                            val error = json.decodeFromString<WebSocketInboundMessage.Error>(payloadJsonString)
                                                            if (error.id == null) {
                                                                throw error.toException()
                                                            } else {
                                                                error
                                                            }
                                                        }
                                                    }
                                                } catch (e: Throwable) {
                                                    logger.error("Can't handle websocket message: ${e.message}. Payload: $payloadJsonString")
                                                    return@collect
                                                }

                                                when (event) {
                                                    is WebSocketInboundMessage.Push -> ignoreErrors { connectionData!!.inboundChannelRegistry.get(event.stream)?.send(event) }
                                                    is WebSocketInboundMessage.Response -> requestResponses.remove(event.id)?.forEach { ignoreErrors { it.send(event) } }
                                                    is WebSocketInboundMessage.Error -> requestResponses.remove(event.id)?.forEach { ignoreErrors { it.send(event) } }
                                                }
                                            }
                                        throw ClosedChannelException()
                                    }

                                    // Message sender
                                    launch(start = CoroutineStart.UNDISPATCHED) {
                                        val output = flux(Dispatchers.Unconfined) {
                                            val reqLimiter = SimpleRequestLimiter(5, 1000)
                                            for (msg in wsMsgReceiver) {
                                                delay(reqLimiter.waitMs())
                                                send(msg)
                                            }
                                        }
                                        session.send(output).awaitFirstOrNull()
                                        throw ClosedChannelException()
                                    }

                                    // Ping requests producer
                                    launch {
                                        var prevTs = wsMsgCounter.get()
                                        while (isActive) {
                                            delay(2000)
                                            val currentTs = wsMsgCounter.get()
                                            if (currentTs == prevTs) {
                                                val pingMsg = session.pingMessage { it.wrap("ping".toByteArray()) }
                                                wsMsgReceiver.send(pingMsg)
                                            }
                                            prevTs = currentTs
                                        }
                                    }

                                    // Request messages aggregator
                                    launch {
                                        val reqQueue = LinkedList<InternalWebSocketOutboundMessage>()

                                        while (isActive) {
                                            reqQueue.add(connectionData!!.outboundChannel.receive())

                                            withTimeoutOrNull(250) {
                                                for (req in connectionData!!.outboundChannel) reqQueue.add(req)
                                            }

                                            val groupedRequests = reqQueue
                                                .groupBy { it.outboundMessage.method }
                                                .mapValues { (method, requests) ->
                                                    when (method) {
                                                        WebSocketOutboundMessage.Method.SUBSCRIBE, WebSocketOutboundMessage.Method.UNSUBSCRIBE -> {
                                                            val channels = LinkedList<String>()
                                                            val reqResponses = LinkedList<Channel<WebSocketInboundMessage>>()
                                                            for (req in requests) {
                                                                channels.addAll(req.outboundMessage.params)
                                                                reqResponses.add(req.inboundChannel)
                                                            }
                                                            val newReq = WebSocketOutboundMessage(method, channels, connectionData!!.generateId())
                                                            requestResponses[newReq.id] = reqResponses
                                                            listOf(newReq)
                                                        }
                                                        else -> {
                                                            val newRequests = LinkedList<WebSocketOutboundMessage>()
                                                            for (request in requests) {
                                                                newRequests.add(request.outboundMessage)
                                                                requestResponses[request.outboundMessage.id] = listOf(request.inboundChannel)
                                                            }
                                                            newRequests
                                                        }
                                                    }
                                                }

                                            reqQueue.clear()

                                            for ((_, requests) in groupedRequests) {
                                                for (request in requests) {
                                                    val jsonStr = json.encodeToString(request)
                                                    val webSocketMsg = session.textMessage(jsonStr)
                                                    wsMsgReceiver.send(webSocketMsg)
                                                }
                                            }
                                        }
                                    }
                                }

                                null
                            }
                        }

                        try {
                            session.awaitFirstOrNull()
                            throw ClosedChannelException()
                        } catch (e: Throwable) {
                            val error = DisconnectedException(e)
                            connectionData?.close(error)
                            throw error
                        }
                    } catch (e: DisconnectedException) {
                        when (e.cause) {
                            is CancellationException -> {
                                // ignore
                            }
                            else -> {
                                logger.warn("${e.message}.${if (e.cause != null) " Cause: ${e.cause}" else ""}")
                                delay(1000)
                            }
                        }
                    } finally {
                        logger.info("Connection closed")
                    }
                }

                logger.debug("Closing WebSocket connection channel")
            }
                .shareIn(scope, SharingStarted.WhileSubscribed(0, 0), 1)
                .filter { !it.isClosed() }
        }

        fun <T> subscribeTo(
            channel: String,
            payloadType: DeserializationStrategy<out T>,
        ): Flow<EventData<T>> {
            @Suppress("UNCHECKED_CAST")
            return streamCache.getOrPut(channel) {
                subscribeToImpl(channel, payloadType)
                    .shareIn(scope, SharingStarted.WhileSubscribed(0, 0), 1)
                    .transformFirst { if (it.subscribed) emit(it.subscribed()) }
            } as Flow<EventData<T>>
        }

        private fun <T> subscribeToImpl(
            channel: String,
            payloadType: DeserializationStrategy<out T>,
        ): Flow<EventData<T>> = channelFlow {
            connection.conflate().collect { connection ->
                var state = SubscriptionState.INIT
                var eventData = EventData<T>()

                while (true) {
                    when (state) {
                        SubscriptionState.INIT -> {
                            try {
                                withContext(NonCancellable) {
                                    val registered = connection.inboundChannelRegistry.register(channel, Channel(64))
                                    state = if (registered) SubscriptionState.SUBSCRIBE else SubscriptionState.EXIT
                                }
                            } catch (e: CancellationException) {
                                state = SubscriptionState.EXIT
                            }
                        }
                        SubscriptionState.SUBSCRIBE -> {
                            val request = WebSocketOutboundMessage(WebSocketOutboundMessage.Method.SUBSCRIBE, listOf(channel), connection.generateId())
                            val internalRequest = InternalWebSocketOutboundMessage(request, connection.inboundChannelRegistry.get(channel)!!)
                            state = try {
                                withContext(NonCancellable) {
                                    connection.outboundChannel.send(internalRequest)
                                }
                                SubscriptionState.CONFIRM_SUBSCRIPTION
                            } catch (e: CancellationException) {
                                SubscriptionState.CONFIRM_SUBSCRIPTION
                            } catch (e: DisconnectedException) {
                                SubscriptionState.EXIT
                            }
                        }
                        SubscriptionState.CONFIRM_SUBSCRIPTION -> {
                            try {
                                withContext(NonCancellable) {
                                    val msg = try {
                                        withTimeout(10.seconds) {
                                            connection.inboundChannelRegistry.get(channel)!!.receive()
                                        }
                                    } catch (e: TimeoutCancellationException) {
                                        eventData = eventData.setError(Exception("Subscribe confirmation has not been received within specified timeout"))
                                        state = SubscriptionState.SUBSCRIBE
                                        this@channelFlow.send(eventData)
                                        return@withContext
                                    } catch (e: DisconnectedException) {
                                        eventData = eventData.setError(e)
                                        state = SubscriptionState.EXIT
                                        this@channelFlow.send(eventData)
                                        return@withContext
                                    }

                                    try {
                                        when (msg) {
                                            is WebSocketInboundMessage.Response -> {
                                                eventData = eventData.setSubscribed(true)
                                                state = SubscriptionState.CONSUME_EVENTS
                                                if (logger.isDebugEnabled) logger.debug("Subscribed to channel $channel")
                                            }
                                            is WebSocketInboundMessage.Error -> {
                                                eventData = eventData.setError(msg.toException())
                                                state = SubscriptionState.SUBSCRIBE
                                            }
                                            is WebSocketInboundMessage.Push -> {
                                                eventData = eventData.setError(IllegalStateException("Push event was received before confirmation event"))
                                                state = SubscriptionState.UNSUBSCRIBE
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        eventData = eventData.setError(e)
                                        state = SubscriptionState.UNSUBSCRIBE
                                    }

                                    this@channelFlow.send(eventData)
                                }
                            } catch (e: CancellationException) {
                                state = when (state) {
                                    SubscriptionState.SUBSCRIBE -> SubscriptionState.SUBSCRIBE
                                    SubscriptionState.CONFIRM_SUBSCRIPTION -> SubscriptionState.CONFIRM_SUBSCRIPTION
                                    SubscriptionState.CONSUME_EVENTS -> SubscriptionState.UNSUBSCRIBE
                                    SubscriptionState.UNSUBSCRIBE -> SubscriptionState.UNSUBSCRIBE
                                    else -> SubscriptionState.EXIT
                                }
                            }
                        }
                        SubscriptionState.CONSUME_EVENTS -> {
                            try {
                                try {
                                    for (msg in connection.inboundChannelRegistry.get(channel)!!) {
                                        when (msg) {
                                            is WebSocketInboundMessage.Push -> {
                                                eventData = eventData.setPayload(json.decodeFromJsonElement(payloadType, msg.data))
                                                this@channelFlow.send(eventData)
                                            }
                                            is WebSocketInboundMessage.Error -> {
                                                throw msg.toException()
                                            }
                                            is WebSocketInboundMessage.Response -> {
                                                throw IllegalStateException("Subscribe/Unsubscribe event can't be received during events consumption")
                                            }
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: DisconnectedException) {
                                    eventData = eventData.setError(e)
                                    state = SubscriptionState.EXIT
                                    this@channelFlow.send(eventData)
                                } catch (e: Throwable) {
                                    eventData = eventData.setError(e)
                                    state = SubscriptionState.UNSUBSCRIBE
                                    this@channelFlow.send(eventData)
                                }
                            } catch (e: CancellationException) {
                                state = when (state) {
                                    SubscriptionState.EXIT -> SubscriptionState.EXIT
                                    else -> SubscriptionState.UNSUBSCRIBE
                                }
                            }
                        }
                        SubscriptionState.UNSUBSCRIBE -> {
                            val request = WebSocketOutboundMessage(WebSocketOutboundMessage.Method.UNSUBSCRIBE, listOf(channel), connection.generateId())
                            val internalRequest = InternalWebSocketOutboundMessage(request, connection.inboundChannelRegistry.get(channel)!!)
                            state = try {
                                withContext(NonCancellable) {
                                    connection.outboundChannel.send(internalRequest)
                                }
                                SubscriptionState.CONFIRM_UNSUBSCRIPTION
                            } catch (e: CancellationException) {
                                SubscriptionState.CONFIRM_UNSUBSCRIPTION
                            } catch (e: DisconnectedException) {
                                SubscriptionState.EXIT
                            }
                        }
                        SubscriptionState.CONFIRM_UNSUBSCRIPTION -> {
                            try {
                                withContext(NonCancellable) {
                                    try {
                                        withTimeout(1.5.minutes) {
                                            for (msg in connection.inboundChannelRegistry.get(channel)!!) {
                                                when (msg) {
                                                    is WebSocketInboundMessage.Push -> {
                                                        eventData = eventData.setPayload(json.decodeFromJsonElement(payloadType, msg.data))
                                                        ignoreErrors { this@channelFlow.send(eventData) }
                                                    }
                                                    is WebSocketInboundMessage.Response -> {
                                                        eventData = eventData.setSubscribed(false)
                                                        state = SubscriptionState.EXIT
                                                        if (logger.isDebugEnabled) logger.debug("Unsubscribed from channel $channel")
                                                        this@channelFlow.send(eventData)
                                                        return@withTimeout
                                                    }
                                                    is WebSocketInboundMessage.Error -> {
                                                        throw msg.toException()
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: TimeoutCancellationException) {
                                        state = SubscriptionState.UNSUBSCRIBE
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: DisconnectedException) {
                                        eventData = eventData.setError(e)
                                        state = SubscriptionState.EXIT
                                        this@channelFlow.send(eventData)
                                    } catch (e: Throwable) {
                                        eventData = eventData.setError(e)
                                        state = SubscriptionState.UNSUBSCRIBE
                                        this@channelFlow.send(eventData)
                                    }
                                }
                            } catch (e: CancellationException) {
                                state = when (state) {
                                    SubscriptionState.UNSUBSCRIBE -> SubscriptionState.UNSUBSCRIBE
                                    else -> SubscriptionState.EXIT
                                }
                            }
                        }
                        SubscriptionState.EXIT -> {
                            connection.inboundChannelRegistry.remove(channel)?.close()
                            return@collect
                        }
                    }
                }
            }
        }

        fun subscribeToPrivateChannel() = channelFlow {
            suspend fun fetchListenKey(): Pair<String, Long> {
                val listenKey = try {
                    logger.info("Trying to fetch listen key...")
                    val key = binanceApi.getListenKey()
                    logger.info("Listen key fetched")
                    key
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn("Can't get listen key: ${e.message}")
                    throw e
                }

                return Pair(listenKey, LISTEN_KEY_PING_INTERVAL.toLongMilliseconds())
            }

            suspend fun keepListenKeyAlive(listenKey: String) {
                while (isActive) {
                    try {
                        logger.info("Ping listen key")
                        binanceApi.pingListenKey(listenKey)
                        logger.info("Pong listen key")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.warn("Can't ping listen key: ${e.message}")
                        throw e
                    }

                    delay(LISTEN_KEY_PING_INTERVAL)
                }
            }

            while (isActive) {
                try {
                    coroutineScope {
                        val (listenKey, waitTillNextPoll) = fetchListenKey()

                        launch(start = CoroutineStart.UNDISPATCHED) { delay(waitTillNextPoll); keepListenKeyAlive(listenKey) }

                        subscribeTo(listenKey, JsonElement.serializer()).collect { eventData ->
                            if (eventData.payload == null) {
                                send(eventData.newPayload())
                                return@collect
                            }

                            val eventType = eventData.payload.jsonObject["e"]?.jsonPrimitive?.contentOrNull

                            val accountEventSerializer = when (eventType) {
                                "outboundAccountInfo" -> AccountUpdateEvent.serializer()
                                "outboundAccountPosition" -> OutboundAccountPositionEvent.serializer()
                                "balanceUpdate" -> BalanceUpdateEvent.serializer()
                                "executionReport" -> OrderUpdateEvent.serializer()
                                "listStatus" -> ListStatusEvent.serializer()
                                null -> {
                                    logger.warn("Event type is null in private channel ${eventData.payload}")
                                    null
                                }
                                else -> {
                                    logger.debug("Not recognized event received in private channel ${eventData.payload}")
                                    null
                                }
                            } ?: return@collect

                            val accountEvent = try {
                                json.decodeFromJsonElement(accountEventSerializer, eventData.payload)
                            } catch (e: Throwable) {
                                logger.error("Can't parse json: ${e.message} ${eventData.payload}")
                                return@collect
                            }

                            send(eventData.newPayload(accountEvent))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn("Subscription to private channel was interrupted: ${e.message}")
                }
            }
        }

        //region Support Models
        private class ConnectionData(
            val outboundChannel: Channel<InternalWebSocketOutboundMessage> = Channel(Channel.RENDEZVOUS),
            val inboundChannelRegistry: ChannelRegistry = ChannelRegistry(),
        ) {
            private val reqIdCounter = AtomicLong(0)
            fun generateId(): Long = reqIdCounter.getAndIncrement()

            fun isClosed(): Boolean {
                return outboundChannel.isClosedForSend
            }

            suspend fun close(error: DisconnectedException) {
                outboundChannel.close(error)
                inboundChannelRegistry.close(error)
            }

            class ChannelRegistry {
                private val mutex = Mutex()
                private var closed = AtomicBoolean(false)
                private val registry = ConcurrentHashMap<String, Channel<WebSocketInboundMessage>>()

                suspend fun register(channelKey: String, channel: Channel<WebSocketInboundMessage>): Boolean {
                    if (closed.get()) return false
                    return mutex.withLock {
                        if (closed.get()) {
                            false
                        } else {
                            registry[channelKey] = channel
                            true
                        }
                    }
                }

                fun get(channelKey: String): Channel<WebSocketInboundMessage>? {
                    return registry[channelKey]
                }

                fun remove(channelKey: String): Channel<WebSocketInboundMessage>? {
                    return registry.remove(channelKey)
                }

                suspend fun close(error: DisconnectedException) {
                    mutex.withLock {
                        closed.set(true)
                        registry.forEachValue(1L) { it.close(error) }
                    }
                }
            }
        }

        private enum class SubscriptionState {
            INIT,
            SUBSCRIBE,
            CONFIRM_SUBSCRIPTION,
            CONSUME_EVENTS,
            UNSUBSCRIBE,
            CONFIRM_UNSUBSCRIPTION,
            EXIT,
        }

        private data class InternalWebSocketOutboundMessage(
            val outboundMessage: WebSocketOutboundMessage,
            val inboundChannel: Channel<WebSocketInboundMessage>,
        )
        //endregion

        @Serializable
        private data class WebSocketOutboundMessage(
            val method: Method,
            val params: List<String>,
            val id: Long,
        ) {
            enum class Method {
                SUBSCRIBE,
                UNSUBSCRIBE,
                LIST_SUBSCRIPTIONS,
                GET_PROPERTY,
                SET_PROPERTY,
            }
        }

        @Serializable
        private sealed class WebSocketInboundMessage {
            @Serializable
            data class Push(
                val stream: String,
                val data: JsonElement,
            ) : WebSocketInboundMessage()

            @Serializable
            data class Response(
                val result: JsonElement,
                val id: Long,
            ) : WebSocketInboundMessage()

            @Serializable
            data class Error(
                val code: Long,
                val msg: String,
                val id: Long? = null,
            ) : WebSocketInboundMessage()
        }

        companion object {
            private val logger = KotlinLogging.logger {}

            private const val API_STREAM_URL_MAIN_NET = "wss://stream.binance.com:9443/stream"
            private const val API_STREAM_URL_TEST_NET = "wss://testnet.binance.vision/stream"

            private val LISTEN_KEY_PING_INTERVAL = 45.minutes

            private fun WebSocketInboundMessage.Error.toException() = Exception(code, msg)
        }
    }

    private class HttpConnector(
        apiNet: ApiNet,
        val apiKey: String,
        apiSecret: String,
        val json: Json,
    ) {
        private val apiUrl = when (apiNet) {
            ApiNet.Main -> API_URL_MAIN_NET
            ApiNet.Test -> API_URL_TEST_NET
        }

        private val signer = HmacSha256Signer(apiSecret, toHexString)

        private val webClient = springWebClient(
            connectTimeoutMs = 5000,
            readTimeoutMs = 5000,
            writeTimeoutMs = 5000,
            maxInMemorySize = 2 * 1024 * 1024,
        )

        suspend fun <T> callApi(
            method: String,
            httpMethod: HttpMethod,
            params: Map<String, String>,
            requiresApiKey: Boolean,
            requiresSignature: Boolean,
            retType: DeserializationStrategy<T>,
        ): T {
            var paramsStr = ""
            if (params.isNotEmpty()) {
                paramsStr = params.toQueryString()
                if (requiresSignature) {
                    val signature = signer.sign(paramsStr)
                    paramsStr = paramsStr.appendToQueryString(SIGNATURE, signature)
                }
                paramsStr = paramsStr.appendToUri()
            }
            var request = webClient.method(httpMethod).uri("$apiUrl$method$paramsStr")
            if (requiresApiKey) {
                request = request.header(API_KEY_HEADER, apiKey)
            }

            return request.awaitExchange { response ->
                val data = response.awaitBody<String>()

                if (response.statusCode().is2xxSuccessful) {
                    json.decodeFromString(retType, data)!!
                } else {
                    val error = json.decodeFromString<ErrorMsg>(data)
                    throw Exception(error.code, error.msg)
                }
            }
        }

        @Serializable
        private data class ErrorMsg(
            val code: Long,
            val msg: String,
        )

        companion object {
            private const val API_URL_MAIN_NET = "https://api.binance.com"
            private const val API_URL_TEST_NET = "https://testnet.binance.vision"

            private const val API_KEY_HEADER = "X-MBX-APIKEY"
            private const val SIGNATURE = "signature"

            private fun Map<String, String>.toQueryString() = asSequence().map { "${it.key}=${it.value}" }.joinToString("&")
            private fun String.appendToQueryString(key: String, value: String) = "${if (isBlank()) "" else "$this&"}$key=$value"
            private fun String.appendToUri() = if (isBlank()) "" else "?$this"
        }
    }

    companion object {
        //region Constants
        private const val API_KEY_HEADER = "X-MBX-APIKEY"
        private const val SIGNATURE = "signature"
        private val LISTEN_KEY_PING_INTERVAL = 45.minutes
        private val FILE_OPTIONS = arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        //endregion

        //region Factories
        fun createMainNetApi(): BinanceApi {
            val apiKey = Secrets.get("BINANCE_API_KEY")
                ?: throw RuntimeException("BINANCE_API_KEY environment variable is not defined")

            val apiSecret = Secrets.get("BINANCE_API_SECRET")
                ?: throw RuntimeException("BINANCE_API_SECRET environment variable is not defined")

            return BinanceApi(
                apiKey = apiKey,
                apiSecret = apiSecret,
                apiNet = ApiNet.Main,
            )
        }

        fun createTestNetApi(): BinanceApi {
            val apiKey = Secrets.get("BINANCE_TEST_NET_API_KEY")
                ?: throw RuntimeException("BINANCE_TEST_NET_API_SECRET environment variable is not defined")

            val apiSecret = Secrets.get("BINANCE_TEST_NET_API_SECRET")
                ?: throw RuntimeException("BINANCE_TEST_NET_API_SECRET environment variable is not defined")

            return BinanceApi(
                apiKey = apiKey,
                apiSecret = apiSecret,
                apiNet = ApiNet.Test,
            )
        }
        //endregion
    }
}
