package com.gitlab.dhorman.cryptotrader.robots.poloniexarbitrage.trader.dao

import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.fasterxml.jackson.module.kotlin.readValue
import com.gitlab.dhorman.cryptotrader.exchangesdk.poloniexspot.model.Currency
import com.gitlab.dhorman.cryptotrader.robots.poloniexarbitrage.trader.model.TranIntentMarket
import com.gitlab.dhorman.cryptotrader.robots.poloniexarbitrage.trader.model.TranIntentMarketPartiallyCompleted
import com.gitlab.dhorman.cryptotrader.robots.poloniexarbitrage.trader.model.Views
import io.vavr.Tuple2
import io.vavr.Tuple4
import io.vavr.collection.Array
import io.vavr.kotlin.tuple
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

@Repository
class TransactionsDao(
    @Qualifier("pg_client") private val entityTemplate: R2dbcEntityTemplate,
    private val mapper: ObjectMapper
) {
    suspend fun getActive(): List<Tuple2<UUID, Array<TranIntentMarket>>> {
        return entityTemplate.databaseClient.sql("SELECT id, markets FROM poloniex_active_transactions")
            .fetch().all()
            .map {
                tuple(
                    it["id"] as UUID,
                    mapper.readValue<Array<TranIntentMarket>>(it["markets"] as String)
                )
            }
            .collectList()
            .awaitSingle()
    }

    suspend fun getActive(id: UUID): Array<TranIntentMarket>? {
        return entityTemplate.databaseClient.sql("SELECT markets FROM poloniex_active_transactions where id = $1")
            .bind(0, id)
            .fetch().one()
            .map { mapper.readValue<Array<TranIntentMarket>>(it["markets"] as String) }
            .awaitFirstOrNull()
    }

    suspend fun addActive(id: UUID, markets: Array<TranIntentMarket>, activeMarketId: Int) {
        val marketsJson = mapper
            .configure(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS.mappedFeature(), true)
            .writerFor(jacksonTypeRef<Array<TranIntentMarket>>())
            .withView(Views.DB::class.java)
            .writeValueAsString(markets)

        val fromCurrency = markets[activeMarketId].fromCurrency
        val fromAmount = (markets[activeMarketId] as TranIntentMarketPartiallyCompleted).fromAmount

        entityTemplate.databaseClient.sql("INSERT INTO poloniex_active_transactions(id, markets, from_currency, from_amount) VALUES ($1, $2, $3, $4)")
            .bind(0, id)
            .bind(1, marketsJson)
            .bind(2, fromCurrency)
            .bind(3, fromAmount)
            .then()
            .awaitFirstOrNull()
    }

    suspend fun deleteActive(id: UUID) {
        entityTemplate.databaseClient.sql("DELETE FROM poloniex_active_transactions WHERE id = $1")
            .bind(0, id)
            .then()
            .awaitFirstOrNull()
    }

    suspend fun updateActive(id: UUID, markets: Array<TranIntentMarket>, activeMarketId: Int) {
        val marketsJson = mapper
            .configure(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS.mappedFeature(), true)
            .writerFor(jacksonTypeRef<Array<TranIntentMarket>>())
            .withView(Views.DB::class.java)
            .writeValueAsString(markets)

        val fromCurrency = markets[activeMarketId].fromCurrency
        val fromAmount = (markets[activeMarketId] as TranIntentMarketPartiallyCompleted).fromAmount

        entityTemplate.databaseClient.sql("UPDATE poloniex_active_transactions SET markets = $1, from_currency = $2, from_amount = $3 WHERE id = $4")
            .bind(0, marketsJson)
            .bind(1, fromCurrency)
            .bind(2, fromAmount)
            .bind(3, id)
            .then()
            .awaitFirstOrNull()
    }

    suspend fun getCompleted(): List<Tuple4<Long, Array<TranIntentMarket>, Instant, Instant>> {
        return entityTemplate.databaseClient.sql("SELECT * FROM poloniex_completed_transactions ORDER BY completed_ts DESC")
            .fetch().all()
            .map {
                tuple(
                    it["id"] as Long,
                    mapper.readValue<Array<TranIntentMarket>>(it["markets"] as String),
                    (it["created_ts"] as LocalDateTime).toInstant(ZoneOffset.UTC),
                    (it["completed_ts"] as LocalDateTime).toInstant(ZoneOffset.UTC)
                )
            }
            .collectList()
            .awaitSingle()
    }

    suspend fun addCompleted(activeTranId: UUID, markets: Array<TranIntentMarket>) {
        val marketsJson = mapper
            .configure(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS.mappedFeature(), true)
            .writerFor(jacksonTypeRef<Array<TranIntentMarket>>())
            .withView(Views.DB::class.java)
            .writeValueAsString(markets)

        entityTemplate.databaseClient.sql("INSERT INTO poloniex_completed_transactions(created_ts, markets) VALUES ((SELECT created_ts FROM poloniex_active_transactions WHERE id = $1), $2)")
            .bind(0, activeTranId)
            .bind(1, marketsJson)
            .then()
            .awaitFirstOrNull()
    }

    suspend fun balanceInUse(currency: Currency): Tuple2<Currency, BigDecimal>? {
        return entityTemplate.databaseClient.sql("SELECT from_currency, SUM(from_amount) amount FROM poloniex_active_transactions WHERE from_currency = $1 GROUP BY from_currency")
            .bind(0, currency)
            .fetch().one()
            .map {
                tuple(
                    it["from_currency"] as Currency,
                    it["amount"] as BigDecimal
                )
            }
            .awaitFirstOrNull()
    }

    suspend fun balancesInUse(currencies: Iterable<Currency>): List<Tuple2<Currency, BigDecimal>> {
        // TODO: Escape input and wait until driver will support List input
        val currencyList = currencies.joinToString(",") { "'$it'" }

        return entityTemplate.databaseClient.sql("SELECT from_currency, SUM(from_amount) amount FROM poloniex_active_transactions WHERE from_currency IN ($currencyList) GROUP BY from_currency")
            .fetch().all()
            .map {
                tuple(
                    it["from_currency"] as Currency,
                    it["amount"] as BigDecimal
                )
            }
            .collectList()
            .awaitSingle()
    }
}
