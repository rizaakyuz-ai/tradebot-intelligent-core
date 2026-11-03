package com.example.tradebot.intelligent.brain.core

import kotlin.math.*

/**
 * Risk Değerleme, Pozisyon Boyutlandırma ve Sermaye Koruma Motoru — v7.0
 *
 * v6 → v7 temel değişiklikler:
 *
 * 1. YAPISAL SL/TP:
 *    Eski: ATR çarpanı ile sabit mesafe → "1.8x ATR'de dur"
 *    Yeni: ZoneEngine'den gelen stopZone/targetZone → "Bu destek zone'unun altında dur"
 *    ATR fallback yalnızca zone bilgisi yoksa kullanılır.
 *
 * 2. KELLY POZISYON BOYUTU:
 *    Eski: Risk skoru üret, pozisyon boyutunu IA'ya bırak
 *    Yeni: Kelly Criterion → f* = (p × b - q) / b
 *    PRE'den gelen concordance olasılığı + R/R oranı → optimal pozisyon boyutu
 *
 * 3. TIER KISITLAMA:
 *    Faz + concordance + fuel → pozisyon üst sınır
 *    EXHAUSTED + düşük fuel → max %30 pozisyon
 *    MATURE + düşük concordance → max %50 pozisyon
 *
 * 4. KALDIRILDI:
 *    - Entropy çifte cezası (PRE zaten baskılıyor)
 *    - Apsis cezası (RegimeEngine fuel'de çözdü)
 *
 * 5. EKLENDİ:
 *    - Volatilite adaptif SL genişletme (yüksek vol → geniş SL)
 *    - Drawdown koruma (toplam portföy kaybı limiti)
 *    - Concordance bazlı güven çarpanı
 */
object RiskEngine {

    // ── Pozisyon boyutu limitleri ─────────────────────────────────────────────
    private const val MAX_POSITION_RATIO = 0.15       // Tek trade max portföy %15'i
    private const val MAX_PORTFOLIO_RISK = 0.02        // Tek trade max risk portföy %2'si
    private const val KELLY_FRACTION = 0.25            // Quarter-Kelly (agresif değil)
    private const val MIN_RR_RATIO = 1.5               // Minimum kabul edilebilir R/R

    // ────────────────────────────────────────────────────────────────────────
    //  VERİ YAPILARI
    // ────────────────────────────────────────────────────────────────────────

    data class RiskResult(
        val riskScore: Double,              // 1-99 (Yüksek = düşük risk, iyi fırsat)
        val suggestedStop: Double,          // Önerilen stop-loss fiyatı
        val suggestedTakeProfit: Double,    // Önerilen take-profit fiyatı
        val rrRatio: Double,                // Risk/Reward oranı
        val volatilityFactor: Double,       // 0-1 volatilite risk çarpanı
        // ── YENİ ──────────────────────────────────────────────────────────
        val kellyFraction: Double = 0.0,    // Kelly önerisi (0-1 portföy oranı)
        val positionSizeRatio: Double = 0.0,// Nihai pozisyon boyutu (0-MAX_POSITION_RATIO)
        val tierLimit: Double = 1.0,        // Tier kısıtlama çarpanı (0-1)
        val stopType: String = "ATR",       // "ZONE" veya "ATR" (debug)
        val targetType: String = "ATR"      // "ZONE" veya "ATR" (debug)
    )

    /**
     * Risk hesabı girdi paketi.
     * IA tarafından doldurulur.
     */
    data class RiskInputs(
        val lastPrice: Double,
        val atr: Double,                             // Wilder ATR (IchimokuEngine)
        val volZ: Double,                            // Volatilite Z-Score (RegimeEngine)
        val isBullish: Boolean = true,

        // ── ZoneEngine'den ────────────────────────────────────────────────
        val zoneStopLevel: Double = 0.0,             // Yapısal stop seviyesi
        val zoneTargetLevel: Double = 0.0,           // Yapısal hedef seviyesi
        val hasZoneStop: Boolean = false,             // Zone bazlı stop var mı?
        val hasZoneTarget: Boolean = false,            // Zone bazlı target var mı?

        // ── PRE'den ───────────────────────────────────────────────────────
        val tradeProbability: Double = 0.50,          // P(kârlı) — concordance olasılığı
        val concordanceCount: Int = 0,                // Motor uyum sayısı

        // ── RegimeEngine'den ──────────────────────────────────────────────
        val phase: RegimeEngine.RegimePhase = RegimeEngine.RegimePhase.ACCELERATING,
        val fuelLevel: Double = 0.50,
        val sealConfirmed: Boolean = false,

        // ── Portföy bilgisi ───────────────────────────────────────────────
        val totalBalance: Double = 1000.0,            // Toplam portföy değeri
        val currentDrawdown: Double = 0.0,            // Mevcut portföy düşüşü (%)
        val successRate: Double = 50.0                 // LearningEngine başarı oranı (%)
    )

    // ────────────────────────────────────────────────────────────────────────
    //  ANA FONKSİYON
    // ────────────────────────────────────────────────────────────────────────

    fun calculateRisk(inputs: RiskInputs): RiskResult {
        val price  = inputs.lastPrice
        val atr    = inputs.atr.coerceAtLeast(1e-8)
        val volZ   = inputs.volZ

        // ── 1. YAPISAL STOP-LOSS ─────────────────────────────────────────────
        // Öncelik: Zone bazlı > ATR bazlı
        val (stopPrice, stopType) = calculateStopLoss(inputs, price, atr, volZ)

        // ── 2. YAPISAL TAKE-PROFIT ───────────────────────────────────────────
        val (tpPrice, tpType) = calculateTakeProfit(inputs, price, atr, volZ)

        // ── 3. R/R ORANI ─────────────────────────────────────────────────────
        val riskDistance   = abs(price - stopPrice).coerceAtLeast(atr * 0.1)
        val rewardDistance = abs(tpPrice - price).coerceAtLeast(atr * 0.1)
        val rrRatio = rewardDistance / riskDistance

        // ── 4. VOLATİLİTE FAKTÖRÜ ────────────────────────────────────────────
        // Yüksek volatilite → risk artıyor (0-1 arası)
        val volFactor = (abs(volZ) / 3.0).coerceIn(0.0, 1.0)

        // ── 5. TIER KISITLAMA ────────────────────────────────────────────────
        val tierLimit = calculateTierLimit(inputs)

        // ── 6. KELLY POZİSYON BOYUTU ─────────────────────────────────────────
        val kellyF = calculateKellyFraction(inputs.tradeProbability, rrRatio, inputs.successRate)

        // ── 7. NİHAİ POZİSYON BOYUTU ────────────────────────────────────────────
        val drawdownMultiplier = calculateDrawdownProtection(inputs.currentDrawdown)

        // Kelly + Tier + Drawdown birlikte pozisyon boyutunu belirler
        val kellyPosition = if (kellyF <= 0) {
            0.02  // Beklenen değer negatif — minimum pozisyon
        } else {
            kellyF  // Kelly önerisi
        }

        // Tier kısıtlama uygula
        val tieredPosition = kellyPosition * tierLimit

        // Drawdown koruma uygula
        val protectedPosition = tieredPosition * drawdownMultiplier

        // Regime bazlı ayar: inputs.isBullish false ise
        // (bearish rejimde alım) pozisyonu küçült
        val regimeAdjusted = if (!inputs.isBullish && protectedPosition > 0.05) {
            protectedPosition * 0.5
        } else {
            protectedPosition
        }

        val finalPositionSize = regimeAdjusted.coerceIn(0.02, MAX_POSITION_RATIO)

        // ── 8. RİSK SKORU (1-99) ─────────────────────────────────────────────
        // Yüksek RR + düşük volatilite + yüksek concordance + iyi tier = yüksek skor
        val rrScore       = sigmoid(rrRatio, k = 2.0, x0 = 2.0) * 35.0    // Max 35 puan
        val volScore      = (1.0 - volFactor) * 20.0                       // Max 20 puan
        val concordScore  = (inputs.concordanceCount / 5.0) * 20.0         // Max 20 puan
        val tierScore     = tierLimit * 15.0                                // Max 15 puan
        val drawdownScore = drawdownMultiplier * 10.0                      // Max 10 puan

        val riskScore = (rrScore + volScore + concordScore + tierScore + drawdownScore)
            .coerceIn(1.0, 99.0)

        return RiskResult(
            riskScore            = riskScore,
            suggestedStop = stopPrice,
            suggestedTakeProfit = tpPrice,
            rrRatio = rrRatio,
            volatilityFactor = volFactor,
            kellyFraction = kellyF,
            positionSizeRatio = finalPositionSize,
            tierLimit = tierLimit,
            stopType = stopType,
            targetType = tpType
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    //  YAPISAL STOP-LOSS
    // ────────────────────────────────────────────────────────────────────────

    private fun calculateStopLoss(
        inputs: RiskInputs,
        price: Double,
        atr: Double,
        volZ: Double
    ): Pair<Double, String> {

        // Zone bazlı stop (öncelikli)
        if (inputs.hasZoneStop && inputs.zoneStopLevel > 0.0) {
            val zoneStop = inputs.zoneStopLevel
            // Zone stop'un anlamlı olduğunu doğrula (çok yakın veya çok uzak değil)
            val distanceAtr = abs(price - zoneStop) / atr
            if (distanceAtr in 0.5..6.0) {
                // Zone stop'un hemen arkasına bir ATR tampon ekle (Piyasa yapıcı stop patlatmalarına karşı rastgeleleştirilmiş)
                val jitter = kotlin.random.Random.nextDouble(0.10, 0.35)
                val bufferedStop = if (inputs.isBullish)
                    zoneStop - (atr * jitter)
                else
                    zoneStop + (atr * jitter)
                
                // FLASH-CRASH KORUMASI: Mutlak Yüzdelik Stop (Risk Analisti önerisi)
                // Stop seviyesi piyasanın volatilitesine (volZ) göre dinamik esner.
                // Normalde %5, volatilite aşırı yüksekse %12'ye kadar esneyebilir.
                val baseLossPct = 0.05
                val dynamicVolExpansion = (volZ.coerceIn(0.0, 3.0) / 3.0) * 0.07
                val maxLossPct = baseLossPct + dynamicVolExpansion
                
                val finalStop = if (inputs.isBullish) {
                    maxOf(bufferedStop, price * (1.0 - maxLossPct))
                } else {
                    minOf(bufferedStop, price * (1.0 + maxLossPct))
                }
                    
                return Pair(finalStop, "ZONE")
            }
        }

        // ATR bazlı fallback — volatiliteye göre genişleyen
        // Yüksek volatilite → geniş stop (çok erken tetikleme engeli)
        // Düşük volatilite → dar stop (gereksiz kayıp engeli)
        val volExpansion = when {
            volZ > 2.0  -> 0.8  // Çok yüksek vol → extra genişlik
            volZ > 1.0  -> 0.4  // Yüksek vol
            volZ > 0.0  -> 0.0  // Normal
            volZ > -1.0 -> -0.2 // Düşük vol → daralt
            else        -> -0.3 // Çok düşük vol → daha da daralt
        }

        val stopDistance = atr * (2.0 + volExpansion)
        val stop = if (inputs.isBullish) price - stopDistance else price + stopDistance

        return Pair(stop, "ATR")
    }

    // ────────────────────────────────────────────────────────────────────────
    //  YAPISAL TAKE-PROFIT
    // ────────────────────────────────────────────────────────────────────────

    private fun calculateTakeProfit(
        inputs: RiskInputs,
        price: Double,
        atr: Double,
        volZ: Double
    ): Pair<Double, String> {

        // Zone bazlı target (öncelikli)
        if (inputs.hasZoneTarget && inputs.zoneTargetLevel > 0.0) {
            val zoneTarget = inputs.zoneTargetLevel
            val distanceAtr = abs(zoneTarget - price) / atr
            // Hedef en az 1.5 ATR uzakta olmalı (komisyon + slippage karşılaması)
            if (distanceAtr >= 1.5) {
                return Pair(zoneTarget, "ZONE")
            }
        }

        // ATR bazlı fallback
        val tpDistance = atr * (3.5 + (1.0 - (abs(volZ) / 3.0).coerceIn(0.0, 1.0)))
        val tp = if (inputs.isBullish) price + tpDistance else price - tpDistance

        return Pair(tp, "ATR")
    }

    // ────────────────────────────────────────────────────────────────────────
    //  KELLY CRITERION
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Quarter-Kelly pozisyon boyutu.
     *
     * f* = (p × b - q) / b
     * p = kazanma olasılığı
     * b = ortalama kazanç / ortalama kayıp (R/R)
     * q = 1 - p
     *
     * Quarter-Kelly kullanıyoruz (f* / 4) çünkü:
     * - Full Kelly çok agresif (tek kayıp seri portföyü çok etkiler)
     * - Half-Kelly bile kripto volatilitesi için agresif
     * - Quarter-Kelly: makul büyüme, makul drawdown
     *
     * successRate kalibrasyon çarpanı olarak kullanılıyor:
     * LearningEngine'in geçmiş başarı oranı sistematik hatayı düzeltiyor.
     */
    private fun calculateKellyFraction(
        probability: Double,
        rrRatio: Double,
        successRate: Double
    ): Double {
        // Minimum R/R kontrolü — çok düşük R/R'da Kelly anlamsız
        if (rrRatio < MIN_RR_RATIO) return 0.02  // Minimum pozisyon

        // Kalibrasyon: P(kârlı) × (gerçek başarı / beklenen başarı)
        // Eğer successRate 0 ise (Cold Start), nötr %50 kabul et ki Kelly sıfırlanmasın.
        val effectiveSuccessRate = if (successRate <= 0.0) 50.0 else successRate
        val calibrationFactor = (effectiveSuccessRate / 100.0).coerceIn(0.3, 1.2)
        val calibratedP = (probability * calibrationFactor).coerceIn(0.01, 0.99)

        val q = 1.0 - calibratedP
        val b = rrRatio

        val kellyFull = (calibratedP * b - q) / b

        // Negatif Kelly → trade yapma (beklenen değer negatif)
        if (kellyFull <= 0) return 0.0

        // Quarter-Kelly
        val quarterKelly = kellyFull * KELLY_FRACTION

        return quarterKelly.coerceIn(0.0, MAX_POSITION_RATIO)
    }

    // ────────────────────────────────────────────────────────────────────────
    //  TIER KISITLAMA
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Faz + fuel + concordance → pozisyon üst sınır çarpanı.
     *
     * TIER 1: Fire Exit → 0.0 (PRE'de yakalanır, buraya gelmez)
     * TIER 2: EXHAUSTED + düşük fuel → max %30
     * TIER 3: MATURE + seal yok → max %60
     * TIER 4: Normal → max %100
     */
    private fun calculateTierLimit(inputs: RiskInputs): Double {
        return when {
            // TIER 2: Tükenme, yakıt yok
            inputs.phase == RegimeEngine.RegimePhase.EXHAUSTED && inputs.fuelLevel < 0.20 -> 0.30
            // TIER 2: Tükenme, biraz yakıt var
            inputs.phase == RegimeEngine.RegimePhase.EXHAUSTED && inputs.fuelLevel < 0.40 -> 0.50
            // TIER 3: Olgun, seal onayı yok
            inputs.phase == RegimeEngine.RegimePhase.MATURE && !inputs.sealConfirmed -> 0.60
            // TIER 3: Olgun ama seal onaylı
            inputs.phase == RegimeEngine.RegimePhase.MATURE && inputs.sealConfirmed -> 0.80
            // TIER 3: Düşük concordance
            inputs.concordanceCount <= 2 -> 0.50
            // TIER 4: HYPER_DRIVE — tam pozisyon
            inputs.phase == RegimeEngine.RegimePhase.HYPER_DRIVE -> 1.0
            // TIER 4: Normal — PREPARATION, STARTING, ACCELERATING
            else -> 1.0
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  DRAWDOWN KORUMA
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Portföy drawdown'ına göre pozisyon kısıtlama.
     *
     * %0-5 drawdown → normal (1.0)
     * %5-10 drawdown → %80 pozisyon
     * %10-15 drawdown → %50 pozisyon
     * %15+ drawdown → %25 pozisyon (savunma modu)
     */
    private fun calculateDrawdownProtection(currentDrawdown: Double): Double {
        return when {
            currentDrawdown < 0.05  -> 1.00
            currentDrawdown < 0.10  -> 0.80
            currentDrawdown < 0.15  -> 0.50
            else                    -> 0.25
        }
    }

    // ── Sigmoid yardımcısı ───────────────────────────────────────────────────
    private fun sigmoid(x: Double, k: Double = 1.0, x0: Double = 0.0): Double =
        1.0 / (1.0 + exp(-k * (x - x0)))

    // ────────────────────────────────────────────────────────────────────────
    //  GERİYE DÖNÜK UYUMLULUK
    // ────────────────────────────────────────────────────────────────────────

    @Deprecated("Yeni RiskInputs bazlı calculateRisk kullanın")
    fun calculateRiskScore(
        lastPrice: Double,
        atr: Double,
        volZ: Double,
        apsis: Double,
        nearestZone: Double? = null,
        isBullish: Boolean = true,
        entropy: Double = 0.0
    ): RiskResult {
        val inputs = RiskInputs(
            lastPrice = lastPrice,
            atr = atr,
            volZ = volZ,
            isBullish = isBullish,
            zoneStopLevel = nearestZone ?: 0.0,
            hasZoneStop = nearestZone != null,
            tradeProbability = 0.50,
            concordanceCount = 3
        )
        return calculateRisk(inputs)
    }
}
