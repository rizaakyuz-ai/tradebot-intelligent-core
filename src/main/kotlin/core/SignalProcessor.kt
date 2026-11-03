package com.example.tradebot.intelligent.brain.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * SignalProcessor — Kalman Filtre, CVD Proxy, Eğim Hesabı
 * Tüm düşük gecikmeli sinyal işleme burada yapılır.
 */
object SignalProcessor {

    // ─── ZLEMA FİLTRE (Sıfır Gecikmeli EMA) ───────────
    data class ZlemaState(
        var ema1: Double = 0.0,
        var ema2: Double = 0.0,
        val alpha: Double = 0.4
    )

    private val zlemaStates = ConcurrentHashMap<String, ZlemaState>()

    fun zlemaFilter(key: String, measurement: Double, alpha: Double = 0.4): Double {
        val state = zlemaStates.getOrPut(key) {
            ZlemaState(ema1 = measurement, ema2 = measurement, alpha = alpha)
        }
        state.ema1 = (measurement - state.ema1) * state.alpha + state.ema1
        state.ema2 = (state.ema1 - state.ema2) * state.alpha + state.ema2
        return 2.0 * state.ema1 - state.ema2
    }

    fun resetFilter(key: String) {
        zlemaStates.remove(key)
    }

    // ─── CVD PROXY (Volume Weighted Price Pressure) ──
    // Kriptodaki fitilleri (wicks) hesaba katan geliştirilmiş Hacim Deltası.

    fun calculateCVD(klines: List<com.example.tradebot.dataset.KlineItem>): List<Double> {
        if (klines.isEmpty()) return emptyList()
        val cvd = mutableListOf<Double>()
        var cumulative = 0.0
        klines.forEach { k ->
            val range = (k.high - k.low).coerceAtLeast(1e-10)
            // Buying Pressure (BP) = Close - Low, Selling Pressure (SP) = High - Close
            // Normalized Pressure = (BP - SP) / Range = (2 * Close - High - Low) / Range
            val pressure = (2.0 * k.close - k.high - k.low) / range
            val delta = pressure.coerceIn(-1.0, 1.0) * k.volume
            cumulative += delta
            cvd.add(cumulative)
        }
        return cvd
    }

    // CVD divergence: fiyat yeni dip ama CVD daha yüksek dip
    fun detectCVDDivergence(
        prices: List<Double>,
        cvdValues: List<Double>,
        lookback: Int = 20
    ): Double {
        if (prices.size < lookback || cvdValues.size < lookback) return 0.0
        val pSlice = prices.takeLast(lookback)
        val cSlice = cvdValues.takeLast(lookback)

        // Son 2 lokal dip bul
        val dips = mutableListOf<Int>()
        val peaks = mutableListOf<Int>()
        for (i in 1 until pSlice.size - 1) {
            if (pSlice[i] < pSlice[i-1] && pSlice[i] < pSlice[i+1]) {
                dips.add(i)
            }
            if (pSlice[i] > pSlice[i-1] && pSlice[i] > pSlice[i+1]) {
                peaks.add(i)
            }
        }

        var bullishDiv = false
        if (dips.size >= 2) {
            val lastDip = dips.last()
            val prevDip = dips[dips.size - 2]
            val priceLowerLow = pSlice[lastDip] < pSlice[prevDip]
            val cvdHigherLow = cSlice[lastDip] > cSlice[prevDip]
            bullishDiv = priceLowerLow && cvdHigherLow
        }

        var bearishDiv = false
        if (peaks.size >= 2) {
            val lastPeak = peaks.last()
            val prevPeak = peaks[peaks.size - 2]
            val priceHigherHigh = pSlice[lastPeak] > pSlice[prevPeak]
            val cvdLowerHigh = cSlice[lastPeak] < cSlice[prevPeak]
            bearishDiv = priceHigherHigh && cvdLowerHigh
        }

        return when {
            bullishDiv -> 1.0   // Bullish divergence
            bearishDiv -> -1.0 // Bearish divergence
            else -> 0.0
        }
    }

    // ─── EĞİM VE TÜREV HESABI ──────────────────────

    data class SlopeResult(
        val slope: Double,          // birinci türev (eğim)
        val acceleration: Double,   // ikinci türev (eğrilik)
        val isConvex: Boolean,      // konveks mi (dip onayı)
        val isConcave: Boolean,     // konkav mi (tepe onayı)
        val inflexion: Double       // -1 ile +1 arası, 0 = inflexiyon noktası
    )

    private val slopeHistory = ConcurrentHashMap<String, MutableList<Double>>()

    fun calculateSlope(key: String, prices: List<Double>): SlopeResult {
        if (prices.size < 5) return SlopeResult(0.0, 0.0, false, false, 0.0)

        val zlemaKey = "${key}_slope"

        // ZLEMA state ilk kez oluşturuluyorsa, son birkaç fiyatı sırayla ver
        if (!zlemaStates.containsKey(zlemaKey)) {
            prices.takeLast(10).dropLast(1).forEach { p ->
                zlemaFilter(zlemaKey, p)
            }
        }

        // ÖNCE eski filtrelenmiş değeri hesapla, SONRA ZLEMA güncelle
        val prevState = zlemaStates[zlemaKey]
        val prevFiltered = if (prevState != null) (2.0 * prevState.ema1 - prevState.ema2) else prices.last()
        val currentFiltered = zlemaFilter(zlemaKey, prices.last())

        // Birinci türev (eğim)
        val slope = currentFiltered - prevFiltered

        // Normalize eğim
        val avgPrice = prices.takeLast(10).average().coerceAtLeast(1e-10)
        val normalizedSlope = (slope / avgPrice * 1000.0).coerceIn(-1.0, 1.0)

        // İkinci türev (eğrilik)
        val hist = slopeHistory.getOrPut(key) { mutableListOf() }
        hist.add(normalizedSlope)
        if (hist.size > 20) hist.removeAt(0)

        val acceleration = if (hist.size >= 2) {
            hist.last() - hist[hist.size - 2]
        } else 0.0

        return SlopeResult(
            slope = normalizedSlope,
            acceleration = acceleration,
            isConvex = acceleration > 0,
            isConcave = acceleration < 0,
            inflexion = normalizedSlope
        )
    }

    // ─── Z-SKOR HESABI ──────────────────────────────

    fun zScore(values: List<Double>, current: Double, period: Int = 20): Double {
        if (values.size < 2) return 0.0
        val slice = values.takeLast(period)
        val mean = slice.average()
        val std = sqrt(slice.map { (it - mean).pow(2) }.average()).coerceAtLeast(1e-10)
        return (current - mean) / std
    }

    // ─── ATR SQUEEZE TESPİTİ ────────────────────────

    fun detectSqueeze(klines: List<com.example.tradebot.dataset.KlineItem>, period: Int = 20): Double {
        if (klines.size < period + 5) return 0.0
        val atrs = mutableListOf<Double>()
        for (i in 1 until klines.size) {
            val tr = maxOf(
                klines[i].high - klines[i].low,
                abs(klines[i].high - klines[i-1].close),
                abs(klines[i].low - klines[i-1].close)
            )
            atrs.add(tr)
        }
        if (atrs.size < period) return 0.0
        val currentATR = atrs.last()
        return zScore(atrs, currentATR, period)
        // Negatif = squeeze (volatilite daraldı)
        // Pozitif = genişleme
    }

    // ─── MUM KARAKTERİ ANALİZİ ─────────────────────

    data class CandleCharacter(
        val wickRatio: Double,      // alt iğne / toplam range
        val bodyRatio: Double,      // gövde / toplam range
        val isHammer: Boolean,      // uzun alt iğne + küçük gövde
        val isStar: Boolean,        // uzun üst iğne + küçük gövde
        val closedAboveOpen: Boolean // yeşil mum
    )

    fun analyzeCandleCharacter(kline: com.example.tradebot.dataset.KlineItem): CandleCharacter {
        val range = (kline.high - kline.low).coerceAtLeast(1e-10)
        val body = abs(kline.close - kline.open)
        val lowerWick = minOf(kline.open, kline.close) - kline.low
        val upperWick = kline.high - maxOf(kline.open, kline.close)

        return CandleCharacter(
            wickRatio = lowerWick / range,
            bodyRatio = body / range,
            isHammer = lowerWick / range > 0.5 && body / range < 0.3,
            isStar = upperWick / range > 0.5 && body / range < 0.3,
            closedAboveOpen = kline.close > kline.open
        )
    }

    // ─── CANLI FİYAT KALMAN GÜNCELLEMESİ ───────────

    fun updateLivePrice(symbol: String, livePrice: Double): SlopeResult {
        val key = "${symbol}_15m"
        val zlemaKey = "${key}_slope"
        // ÖNCE eski state'i oku, SONRA ZLEMA güncelle
        val prevState = zlemaStates[zlemaKey]
        val prevFiltered = if (prevState != null) (2.0 * prevState.ema1 - prevState.ema2) else livePrice
        val filtered = zlemaFilter(zlemaKey, livePrice)

        val slope = filtered - prevFiltered
        val avgPrice = livePrice.coerceAtLeast(1e-10)
        val normalizedSlope = (slope / avgPrice * 1000.0).coerceIn(-1.0, 1.0)

        val hist = slopeHistory.getOrPut(key) { mutableListOf() }
        val acceleration = if (hist.isNotEmpty()) {
            normalizedSlope - hist.last()
        } else 0.0

        hist.add(normalizedSlope)
        if (hist.size > 30) hist.removeAt(0)

        return SlopeResult(
            slope = normalizedSlope,
            acceleration = acceleration,
            isConvex = acceleration > 0,
            isConcave = acceleration < 0,
            inflexion = normalizedSlope
        )
    }

    // ─── OI TREND TESPİTİ ──────────────────────────

    fun detectOITrend(oiValues: List<Double>): Double {
        if (oiValues.size < 3) return 0.0
        val last3 = oiValues.takeLast(3)
        val slope = (last3[2] - last3[0]) / 2.0
        val avg = last3.average().coerceAtLeast(1e-10)
        return (slope / avg * 100.0).coerceIn(-1.0, 1.0)
        // Pozitif = OI artıyor (yeni para giriyor)
        // Negatif = OI azalıyor (pozisyon kapatılıyor)
    }
}
