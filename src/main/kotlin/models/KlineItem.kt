package com.example.tradebot.dataset

import kotlinx.serialization.Serializable

@Serializable
data class KlineItem(
    val symbol: String,
    val openTime: Long,
    val open: Double,
    var high: Double, 
    var low: Double,  
    var close: Double, 
    var volume: Double,
    val closeTime: Long,
    val quoteVolume: Double,
    val trades: Int = 0,
    val takerBuyBaseVolume: Double = 0.0,
    val takerBuyQuoteVolume: Double = 0.0,
    val ignore: String = ""
)
