package com.gitlab.dhorman.cryptotrader.exchangesdk.poloniexspot.codec

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.*
import com.gitlab.dhorman.cryptotrader.exchangesdk.poloniexspot.model.*

object AccountNotificationJsonCodec {
    class Decoder : JsonDeserializer<AccountNotification>() {
        override fun deserialize(p: JsonParser, ctx: DeserializationContext): AccountNotification {
            val arrayNode: ArrayNode = p.readValueAsTree()
            val codec = p.codec as ObjectMapper
            return when (val type = arrayNode[0].textValue()) {
                "b" -> codec.treeToValue<BalanceUpdate>(arrayNode)!!
                "n" -> codec.treeToValue<LimitOrderCreated>(arrayNode)!!
                "o" -> codec.treeToValue<OrderUpdate>(arrayNode)!!
                "t" -> codec.treeToValue<TradeNotification>(arrayNode)!!
                "p" -> codec.treeToValue<OrderPendingAck>(arrayNode)!!
                "k" -> codec.treeToValue<OrderKilled>(arrayNode)!!
                else -> throw Exception("Not recognized account notification type $type")
            }
        }
    }
}
