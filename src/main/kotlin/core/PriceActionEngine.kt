// @Deprecated — Bu motor ZoneEngine + PatternEngine tarafından tamamen değiştirilmiştir.
// Hiçbir yerden çağrılmıyor. Gelecekte kaldırılacak.
package com.example.tradebot.intelligent.brain.core

import com.example.tradebot.dataset.KlineItem
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * SMC/ICT Kurumsal İz Takip Motoru
 * Fiyat Hareketlerini (Price Action) matematiksel matris verisine dönüştürür.
 */
object PriceActionEngine {

    // --- VERİ YAPILARI ---

    data class PriceZone(
        val top: Double,
        val bottom: Double,
        val type: ZoneType,
        val tf: String,
        val startTime: Long,
        var isMitigated: Boolean = false,
        var strength: Double = 1.0,
        val originalPrice: Double = 0.0,
        val isBullish: Boolean = true
    )

    enum class ZoneType { ORDER_BLOCK, FVG, BREAKER, SUPPORT_RESISTANCE, GOLDEN_POCKET }

    data class PAStructure(
        val type: PAType,
        val price: Double,
        val time: Long,
        var strength: Double = 1.0,
        val isConfirmed: Boolean = false,
        val isBullish: Boolean = true
    )

    enum class PAType { SFP, MSB, ENGULFING, PINBAR, BOB }

    // --- ALAN TESPİT ALGORİTMALARI (POI) ---

    fun detectOrderBlocks(klines: List<KlineItem>, tf: String): List<PriceZone> {
        val blocks = mutableListOf<PriceZone>()
        if (klines.size < 10) return blocks

        for (i in 1 until klines.size - 3) {
            val curr = klines[i]
            val next = klines[i + 1]

            // Boğa OB: Sert yükseliş öncesi son düşüş mumu
            if (curr.close < curr.open && next.close > next.open && next.close > curr.high) {
                blocks.add(PriceZone(
                    top = curr.high,
                    bottom = curr.low,
                    type = ZoneType.ORDER_BLOCK,
                    tf = tf,
                    startTime = curr.openTime,
                    isBullish = true,
                    strength = if (next.volume > curr.volume) 1.2 else 1.0
                ))
            }
            // Ayı OB: Sert düşüş öncesi son yükseliş mumu
            else if (curr.close > curr.open && next.close < next.open && next.close < curr.low) {
                blocks.add(PriceZone(
                    top = curr.high,
                    bottom = curr.low,
                    type = ZoneType.ORDER_BLOCK,
                    tf = tf,
                    startTime = curr.openTime,
                    isBullish = false,
                    strength = if (next.volume > curr.volume) 1.2 else 1.0
                ))
            }
        }
        return blocks
    }

    fun detectFVGs(klines: List<KlineItem>, tf: String): List<PriceZone> {
        val gaps = mutableListOf<PriceZone>()
        if (klines.size < 4) return gaps

        for (i in 0 until klines.size - 2) {
            val k1 = klines[i]
            val k3 = klines[i + 2]

            // Bullish FVG
            if (k1.high < k3.low) {
                gaps.add(PriceZone(
                    top = k3.low,
                    bottom = k1.high,
                    type = ZoneType.FVG,
                    tf = tf,
                    startTime = klines[i+1].openTime,
                    isBullish = true
                ))
            }
            // Bearish FVG
            else if (k1.low > k3.high) {
                gaps.add(PriceZone(
                    top = k1.low,
                    bottom = k3.high,
                    type = ZoneType.FVG,
                    tf = tf,
                    startTime = klines[i+1].openTime,
                    isBullish = false
                ))
            }
        }
        return gaps
    }

    fun detectSupportResistance(klines: List<KlineItem>, tf: String): List<PriceZone> {
        val sr = mutableListOf<PriceZone>()
        if (klines.size < 50) return sr
        
        val prices = klines.takeLast(50).map { it.close }
        val avg = prices.average()
        val std = Math.sqrt(prices.map { Math.pow(it - avg, 2.0) }.average())
        
        sr.add(PriceZone(avg + std, avg - std, ZoneType.SUPPORT_RESISTANCE, tf, System.currentTimeMillis()))
        return sr
    }

    fun calculateGoldenPocket(klines: List<KlineItem>, tf: String): PriceZone? {
        if (klines.isEmpty()) return null
        val high = klines.maxOf { it.high }
        val low = klines.minOf { it.low }
        val range = high - low
        if (range == 0.0) return null

        return PriceZone(
            top = high - (range * 0.618),
            bottom = high - (range * 0.786),
            type = ZoneType.GOLDEN_POCKET,
            tf = tf,
            startTime = klines.first().openTime
        )
    }

    // --- FORMASYON TESPİT ALGORİTMALARI (PA) ---

    fun detectSFP(klines: List<KlineItem>): PAStructure? {
        if (klines.size < 15) return null
        val last = klines.last()
        
        // Bullish SFP
        val localLow = klines.dropLast(1).takeLast(10).minOf { it.low }
        if (last.low < localLow && last.close > localLow) {
            return PAStructure(PAType.SFP, last.close, last.openTime, strength = 1.0, isBullish = true)
        }
        
        // Bearish SFP
        val localHigh = klines.dropLast(1).takeLast(10).maxOf { it.high }
        if (last.high > localHigh && last.close < localHigh) {
            return PAStructure(PAType.SFP, last.close, last.openTime, strength = 1.0, isBullish = false)
        }
        
        return null
    }

    fun detectMSB(klines: List<KlineItem>): PAStructure? {
        if (klines.size < 20) return null
        val last = klines.last()
        
        // Bullish MSB
        val recentHigh = klines.dropLast(1).takeLast(15).maxOf { it.high }
        if (last.close > recentHigh) {
            return PAStructure(PAType.MSB, last.close, last.openTime, strength = 1.2, isConfirmed = last.volume > 0, isBullish = true)
        }
        
        // Bearish MSB
        val recentLow = klines.dropLast(1).takeLast(15).minOf { it.low }
        if (last.close < recentLow) {
            return PAStructure(PAType.MSB, last.close, last.openTime, strength = 1.2, isConfirmed = last.volume > 0, isBullish = false)
        }
        
        return null
    }

    fun detectEngulfing(klines: List<KlineItem>): PAStructure? {
        if (klines.size < 2) return null
        val last = klines.last()
        val prev = klines[klines.size - 2]
        
        // Bullish Engulfing
        if (last.close > last.open && prev.close < prev.open && last.close > prev.open && last.open < prev.close) {
            return PAStructure(PAType.ENGULFING, last.close, last.openTime, strength = 1.0, isBullish = true)
        }
        // Bearish Engulfing
        if (last.close < last.open && prev.close > prev.open && last.close < prev.open && last.open > prev.close) {
            return PAStructure(PAType.ENGULFING, last.close, last.openTime, strength = 1.0, isBullish = false)
        }
        
        return null
    }

    fun detectPinbar(klines: List<KlineItem>): PAStructure? {
        val last = klines.last()
        val body = abs(last.close - last.open)
        val range = (last.high - last.low).coerceAtLeast(0.0001)
        
        // Bullish Pinbar (Uzun alt iğne)
        val lowerWick = min(last.open, last.close) - last.low
        if (lowerWick > body * 2.5 && body < range * 0.25) {
            return PAStructure(PAType.PINBAR, last.close, last.openTime, strength = 0.9, isBullish = true)
        }
        
        // Bearish Pinbar (Uzun üst iğne)
        val upperWick = last.high - max(last.open, last.close)
        if (upperWick > body * 2.5 && body < range * 0.25) {
            return PAStructure(PAType.PINBAR, last.close, last.openTime, strength = 0.9, isBullish = false)
        }
        
        return null
    }
}
