package com.example.tradebot.intelligent.brain.core

import android.content.Context
import com.example.tradebot.dataset.Database
import com.example.tradebot.engine.BotEngine
import com.example.tradebot.intelligent.brain.AnalysisSnapshot
import com.example.tradebot.intelligent.brain.IntelligentDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale
import kotlin.math.abs

object OutcomeTracker {

    /**
     * Bekleyen analizleri denetler ve DİNAMİK HEDEFLERE (TP/SL) göre sonuçlandırır.
     */
    suspend fun trackOpenSnapshots(context: Context) = coroutineScope {
        val db = IntelligentDatabase.getDatabase(context)
        val openSnapshots = db.snapshotDao().getPendingSnapshots()

        if (openSnapshots.isEmpty()) return@coroutineScope

        val symbolsToFetch = openSnapshots.map { it.symbol }.distinct()
        val priceMap = symbolsToFetch.map { symbol ->
            async { symbol to fetchCurrentPrice(symbol) }
        }.awaitAll().toMap()

        openSnapshots.forEach { snapshot ->
            val currentPrice = priceMap[snapshot.symbol] ?: return@forEach
            val currentTime = System.currentTimeMillis()
            val elapsedMinutes = (currentTime - snapshot.timestamp) / (1000 * 60)

            // DİNAMİK HEDEFLER (Snapshot'tan gelir)
            val tp = snapshot.targetPrice
            val sl = snapshot.stopPrice
            val isBuy = snapshot.decision.contains("BUY")

            // 1. HEDEF VEYA STOP KONTROLÜ
            var isSuccess = false
            var isFailed = false
            var isFinished = false

            if (tp != null && sl != null) {
                if (isBuy) {
                    if (currentPrice >= tp) { isSuccess = true; isFinished = true }
                    else if (currentPrice <= sl) { isFailed = true; isFinished = true }
                } else if (snapshot.decision == "SELL") {
                    if (currentPrice <= tp) { isSuccess = true; isFinished = true }
                    else if (currentPrice >= sl) { isFailed = true; isFinished = true }
                }
            }

            // 2. ZAMAN AŞIMI (Ichimoku 26 Mum Periyodu - Varsayılan 4 saat)
            val horizonLimit = (snapshot.timeHorizon ?: 26) * 15 // 15m TF varsayılan
            val isTimedOut = elapsedMinutes >= horizonLimit

            if (isFinished || isTimedOut) {
                val priceChangePct = (currentPrice - snapshot.lastPrice) / snapshot.lastPrice
                
                snapshot.successRating = when {
                    isSuccess -> 1.0
                    isFailed -> -1.0
                    else -> 0.0 // Zaman aşımı veya nötr durum
                }
                
                snapshot.outcomePrice = currentPrice
                db.snapshotDao().updateSnapshot(snapshot)

                // --- ÖĞRENME TETİKLEYİCİSİ (OTOPSİ) ---
                if (!snapshot.isLearned) {
                    val klines = Database.getCachedKlines(snapshot.symbol, "15m")
                    if (klines.isNotEmpty()) {
                        LearningEngine.archiveCompletedRegime(
                            context = context,
                            symbol = snapshot.symbol,
                            tf = "15m",
                            snapshot = snapshot,
                            endPrice = currentPrice,
                            klines = klines
                        )
                        snapshot.isLearned = true
                        db.snapshotDao().updateSnapshot(snapshot)
                    }
                }
                
                val statusStr = when {
                    isSuccess -> "✅ HEDEF VURULDU"
                    isFailed -> "🚨 STOP OLDU"
                    else -> "⌛ ZAMAN AŞIMI"
                }
                
                IntelligentBotEngine.emitLog(String.format(Locale.US, "🏁 [OTOPSİ] %s: %s | Değişim: %.2f%% | Neden: %s",
                    snapshot.symbol, statusStr, priceChangePct * 100, snapshot.primaryReason))
            }
        }
    }

    private suspend fun fetchCurrentPrice(symbol: String): Double? {
        return try {
            val klines = Database.getCachedKlines(symbol, "1m")
            if (klines.isNotEmpty()) klines.last().close else null
        } catch (e: Exception) {
            null
        }
    }
}
