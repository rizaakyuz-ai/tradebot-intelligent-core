package com.example.tradebot.intelligent.brain.core

import android.content.Context
import com.example.tradebot.analyze.Analyze
import com.example.tradebot.dataset.KlineItem
import com.example.tradebot.intelligent.brain.IntelligentDatabase
import com.example.tradebot.intelligent.brain.MarketRegime
import kotlinx.serialization.Serializable
import kotlin.math.*

/**
 * Piyasa Rejimi ve Trend Analiz Motoru — v7.0 (Fuzzy Logic + Dynamic Weighting)
 *
 * v6 → v7 temel değişiklikler:
 *
 * 1. REJİM TESPİTİ: Stoch-RSI crossover → 3 bağımsız fuzzy sinyal
 *    - Yapısal üstünlük (HH/HL — LH/LL swing zinciri)
 *    - Para akışı üstünlüğü (EWMA-CMF trendi)
 *    - Momentum asimetrisi (yükseliş vs düşüş mum gövdesi)
 *
 * 2. DİNAMİK AĞIRLIK: Volatilite rejimine göre her sinyalin ağırlığı değişiyor
 *    - Yüksek vol → yapısal ağırlık artar
 *    - Düşük vol  → para akışı ağırlığı artar
 *
 * 3. EWMA SMOOTHING: Rejim skoru her mum güncelleniyor
 *    - Alpha volatiliteye göre değişiyor
 *    - Yüksek vol'da hızlı güncelleme (dönüşleri kaçırmaz)
 *    - Düşük vol'da yavaş güncelleme (gürültüye tepki vermez)
 *
 * 4. SIDEWAYS: ATR sabit eşik → fuzzy membership (3 koşul)
 *
 * 5. FUEL: Apsis bazlı → ER + momentum ivmesi + para akışı derinliği
 *    Faz bazlı dinamik ağırlık ile. Apsis'ten tamamen bağımsız.
 *
 * 6. FAZ TESPİTİ: 6 fazın tamamı (PREPARATION ve STARTING eklendi)
 *    BB squeeze tespiti ile PREPARATION, T0 yaşı ile STARTING
 *
 * 7. POLAR STRENGTH: Giriş kalitesi farkındalıklı
 *    BULLISH'te düşük Stoch → yüksek güç (dip içinde alım fırsatı)
 *
 * 8. checkSeals: Artık aktif — confidence ve zayıf rejim kararını etkiliyor
 *
 * 9. volatilityZ: High-Low → Wilder ATR (IchimokuEngine'den)
 *
 * 10. MarketState: atr alanı eklendi (IA'daki sahte ATR'yi değiştirir)
 *     apsisEquilibrium, sealConfirmed, t0Strength eklendi
 */
object RegimeEngine {

    @Serializable
    enum class RegimeType { BULLISH, BEARISH, SIDEWAYS }

    @Serializable
    enum class RegimePhase {
        PREPARATION,  // BB squeeze + düşük hacim + nötr apsis → birikim
        STARTING,     // Yeni T0 + checkSeals + küçük apsis → kırılım başladı
        ACCELERATING, // Sağlıklı trend devam ediyor
        MATURE,       // Stoch aşırı bölge + orta apsis → doyuma yakın
        EXHAUSTED,    // Büyük apsis + checkSeals false → geri dönüş riski
        HYPER_DRIVE   // FractalEngine'den: BB dışı + hacim + CMF anomalisi
    }

    @Serializable
    data class MarketState(
        val type: RegimeType,
        val phase: RegimePhase,
        val strength: Double,           // Polarize trend gücü (1-99)
        val efficiency: Double,         // Verimlilik (1-99)
        val fuelLevel: Double,          // Yakıt seviyesi (0.0-1.0) — fuzzy bazlı
        val apsisDistance: Double,      // Kijun'a uzaklık (ATR birimi) — valuation için
        val volatilityZ: Double,        // Volatilite Z-Score (Wilder ATR bazlı)
        val futureTrend: RegimeType,
        val invalidationPrice: Double = 0.0,
        val confidence: Double = 0.0,
        val stochRsiK: Double = 50.0,
        val stochRsiD: Double = 50.0,
        val cmf: Double = 0.0,
        val uncertainty: Double = 0.0,
        val riskWarning: String? = null,
        // ── YENİ ALANLAR ────────────────────────────────────────────────────
        val atr: Double = 0.0,          // Wilder ATR — IA'daki sahte ATR'yi değiştirir
        val apsisEquilibrium: Double = 0.0, // Equilibrium'a uzaklık (uzun vade)
        val sealConfirmed: Boolean = false, // checkSeals sonucu
        val t0Strength: FractalEngine.T0Strength = FractalEngine.T0Strength.NONE,
        val regimeScore: Double = 0.0   // Ham fuzzy skor (-1..+1) — debug/log
    )

    // ── EWMA Rejim Skoru Hafızası ────────────────────────────────────────────
    // Her sembol+TF için EWMA smoothed rejim skoru saklanıyor
    private val smoothedScores = HashMap<String, Double>()

    // ────────────────────────────────────────────────────────────────────────
    //  ANA FONKSİYON
    // ────────────────────────────────────────────────────────────────────────

    suspend fun detectRegime(
        context: Context,
        ctx: IntelligentAnalyze.SharedAnalysisContext,
        manualRegimes: List<MarketRegime> = emptyList()
    ): MarketState {

        val klines  = ctx.klines
        val tf      = ctx.tf
        val symbol  = ctx.symbol
        val lastTime = klines.lastOrNull()?.openTime ?: 0L

        // 0. MANUAL OVERRIDE
        val activeManual = manualRegimes.find {
            it.startTime <= lastTime &&
            (it.endTime ?: Long.MAX_VALUE) >= lastTime &&
            it.tf == tf
        }
        if (activeManual != null) {
            val mType = try { RegimeType.valueOf(activeManual.regimeType) }
                        catch (e: Exception) { RegimeType.SIDEWAYS }
            return MarketState(
                type = mType, phase = RegimePhase.MATURE, strength = 90.0,
                efficiency = 90.0, fuelLevel = 1.0, apsisDistance = 0.0,
                volatilityZ = 0.0, futureTrend = mType,
                invalidationPrice = activeManual.startPrice, confidence = 100.0,
                atr = ctx.lastIchimoku.atr, riskWarning = "MANUAL"
            )
        }

        if (klines.size < 60) return defaultState()

        // ── Temel veri ──────────────────────────────────────────────────────
        val ichi      = ctx.lastIchimoku
        val atr       = ichi.atr.coerceAtLeast(1e-8)
        val lastPrice = klines.last().close
        val lastInd   = ctx.lastIndicator
        val stochRsi  = Pair(lastInd.stochRsiK, lastInd.stochRsiD)
        val cmfValue  = lastInd.cmf

        // ── FractalEngine T0 bilgisi ─────────────────────────────────────────
        val t0Points     = FractalEngine.findStructuralT0(ctx)
        val fractalMatrix = FractalEngine.evaluateFractals(ctx, t0Points, 0.0)
        val lastT0       = t0Points.lastOrNull()
        val t0Strength   = fractalMatrix.t0Strength

        // ── 1. VOLATİLİTE Z-SCORE (Wilder ATR bazlı) ────────────────────────
        val volZ = calculateVolatilityZ(ctx.ichimoku, atr)

        // ── 2. FUZZY MEMBERSHİP HESABI ──────────────────────────────────────

        // 2a. Yapısal Üstünlük (Swing High/Low zinciri)
        val structuralFuzzy = calculateStructuralFuzzy(ctx, atr)

        // 2b. Para Akışı Üstünlüğü (EWMA-CMF)
        val flowFuzzy = calculateFlowFuzzy(ctx.indicators, cmfValue)

        // 2c. Momentum Asimetrisi (yükseliş vs düşüş mum gövdesi)
        val momentumFuzzy = calculateMomentumFuzzy(klines, atr)

        // ── 3. DİNAMİK AĞIRLIK (volatilite rejimine göre) ───────────────────
        val (wStructural, wFlow, wMomentum) = when {
            volZ > 1.5  -> Triple(0.45, 0.30, 0.25) // Yüksek vol: yapı baskın
            volZ < -0.5 -> Triple(0.25, 0.45, 0.30) // Düşük vol: para akışı baskın
            else        -> Triple(0.35, 0.35, 0.30) // Normal: dengeli
        }

        // ── 4. HAM REJİM SKORU (-1..+1) ─────────────────────────────────────
        val rawScore =
            (structuralFuzzy.bullish - structuralFuzzy.bearish) * wStructural +
            (flowFuzzy.bullish       - flowFuzzy.bearish)       * wFlow       +
            (momentumFuzzy.bullish   - momentumFuzzy.bearish)   * wMomentum

        // ── 5. EWMA SMOOTHING ────────────────────────────────────────────────
        // Alpha: yüksek vol'da hızlı (dönüşleri kaçırmaz),
        //        düşük vol'da yavaş (gürültüye tepki vermez)
        val alpha = when {
            volZ > 1.5  -> 0.35
            volZ < -0.5 -> 0.18
            else        -> 0.22
        }
        val smoothKey = "${symbol}_${tf}"
        val prevSmoothed = smoothedScores[smoothKey] ?: rawScore
        val smoothedScore = alpha * rawScore + (1.0 - alpha) * prevSmoothed
        smoothedScores[smoothKey] = smoothedScore

        // ── 6. SIDEWAYS MEMBERSHIP (önce kontrol et) ─────────────────────────
        val sidewaysMembership = calculateSidewaysMembership(
            structuralFuzzy, flowFuzzy, lastInd.rvol
        )

        // ── 7. REJİM TİPİ ────────────────────────────────────────────────────
        val regimeType = when {
            sidewaysMembership > 0.60 -> RegimeType.SIDEWAYS
            smoothedScore > 0.40      -> RegimeType.BULLISH
            smoothedScore > 0.15      -> RegimeType.BULLISH  // Zayıf BULLISH
            smoothedScore < -0.40     -> RegimeType.BEARISH
            smoothedScore < -0.15     -> RegimeType.BEARISH  // Zayıf BEARISH
            else                      -> RegimeType.SIDEWAYS
        }

        // ── 8. CHECK SEALS ───────────────────────────────────────────────────
        val lastKline = klines.last()
        val macdHist  = Triple(0.0, 0.0, lastInd.macdHist)
        val sealConf  = checkSeals(lastKline, ichi, stochRsi, macdHist, cmfValue,
                                   isBullish = regimeType == RegimeType.BULLISH)

        // Zayıf rejimde checkSeals false → SIDEWAYS'e çek
        val finalType = if (!sealConf &&
                            smoothedScore in -0.40..0.40 &&
                            regimeType != RegimeType.SIDEWAYS) {
            RegimeType.SIDEWAYS
        } else {
            regimeType
        }

        // ── 9. APSİS HESABI ──────────────────────────────────────────────────
        val apsisKijun  = (lastPrice - ichi.kijun) / atr
        val apsisEquil  = if (ichi.equilibrium > 0.0)
                              (lastPrice - ichi.equilibrium) / atr
                          else apsisKijun

        // ── 10. FAZ TESPİTİ ──────────────────────────────────────────────────
        val phase = detectPhase(
            ctx         = ctx,
            regimeType  = finalType,
            apsisKijun  = apsisKijun,
            stochRsiK   = stochRsi.first,
            lastT0      = lastT0,
            sealConf    = sealConf,
            fractalMatrix = fractalMatrix,
            klines      = klines,
            atr         = atr
        )

        // ── 11. FUEL (fuzzy, faz-bazlı dinamik ağırlık) ──────────────────────
        val fuel = calculateFuel(ctx.indicators, lastInd, phase, atr, t0Strength, sealConf)

        // ── 12. POLAR STRENGTH (giriş kalitesi farkındalıklı) ────────────────
        val polarStrength = calculatePolarStrength(finalType, stochRsi.first, sealConf)

        // ── 13. CONFIDENCE ───────────────────────────────────────────────────
        // checkSeals onayı + rejim skoru gücü + t0 gücü
        val sealBonus    = if (sealConf) 15.0 else 0.0
        val t0Bonus      = when (t0Strength) {
            FractalEngine.T0Strength.HIGH   -> 15.0
            FractalEngine.T0Strength.MEDIUM -> 8.0
            else                            -> 0.0
        }
        val scoreConf    = abs(smoothedScore) * 50.0  // 0-50 arası
        val rawConf      = (scoreConf + sealBonus + t0Bonus).coerceIn(1.0, 99.0)

        // ── 14. UNCERTAINTY ──────────────────────────────────────────────────
        val uncertainty = calculateUncertainty(finalType, stochRsi, macdHist, cmfValue, sealConf)

        // ── 15. PERSISTENCE GÜNCELLEME ───────────────────────────────────────
        updateRegimePersistence(context, symbol, tf, finalType, klines, ichi)

        return MarketState(
            type              = finalType,
            phase             = phase,
            strength          = polarStrength,
            efficiency        = calculateEfficiency(klines),
            fuelLevel         = fuel,
            apsisDistance     = apsisKijun,
            volatilityZ       = volZ,
            futureTrend       = finalType,
            invalidationPrice = ichi.kijun,
            confidence        = rawConf,
            stochRsiK         = stochRsi.first,
            stochRsiD         = stochRsi.second,
            cmf               = cmfValue,
            uncertainty       = uncertainty,
            riskWarning       = when {
                phase == RegimePhase.EXHAUSTED -> "EXHAUSTED"
                fuel < 0.25 && phase != RegimePhase.HYPER_DRIVE -> "LOW_FUEL"
                else -> null
            },
            atr               = atr,
            apsisEquilibrium  = apsisEquil,
            sealConfirmed     = sealConf,
            t0Strength        = t0Strength,
            regimeScore       = smoothedScore
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    //  FUZZY MEMBERSHIP HESAPLARI
    // ────────────────────────────────────────────────────────────────────────

    private data class FuzzyTriple(val bullish: Double, val bearish: Double, val neutral: Double)

    // ── Sigmoid yardımcısı ───────────────────────────────────────────────────
    private fun sigmoid(x: Double, k: Double = 1.0, x0: Double = 0.0) =
        1.0 / (1.0 + exp(-k * (x - x0)))

    // ── 2a. Yapısal Üstünlük ─────────────────────────────────────────────────
    // IchimokuEngine'deki swing High/Low zincirinin eğimi.
    // Son 3 swing High ve Low'un yükselen mi alçalan mı olduğunu ATR'ye normalize ederek ölçüyoruz.
    private fun calculateStructuralFuzzy(
        ctx: IntelligentAnalyze.SharedAnalysisContext,
        atr: Double
    ): FuzzyTriple {
        val ichiList = ctx.ichimoku
        val size     = ichiList.size
        if (size < 3) return FuzzyTriple(0.5, 0.5, 0.0)

        // Son 3 swing high ve swing low değerlerini topla
        val recentHighs = mutableListOf<Double>()
        val recentLows  = mutableListOf<Double>()
        for (i in (size - 1) downTo 0) {
            val ichi = ichiList[i]
            if (ichi.swingHigh > 0.0 && recentHighs.size < 3) recentHighs.add(ichi.swingHigh)
            if (ichi.swingLow  > 0.0 && recentLows.size  < 3) recentLows.add(ichi.swingLow)
            if (recentHighs.size >= 3 && recentLows.size >= 3) break
        }

        if (recentHighs.size < 2 || recentLows.size < 2) return FuzzyTriple(0.5, 0.5, 0.0)

        // Swing eğimleri: pozitif = yükseliyor, negatif = alçalıyor
        // ATR'ye normalize: 0.5 ATR'lik değişim anlamlı eşik
        val highSlope = (recentHighs[0] - recentHighs.last()) / (atr * recentHighs.size)
        val lowSlope  = (recentLows[0]  - recentLows.last())  / (atr * recentLows.size)

        // HH + HL → güçlü BULLISH. LH + LL → güçlü BEARISH.
        val combinedSlope = (highSlope + lowSlope) / 2.0

        val bullish = sigmoid(combinedSlope, k = 3.0, x0 = 0.25)
        val bearish = sigmoid(-combinedSlope, k = 3.0, x0 = 0.25)
        val neutral = (1.0 - abs(bullish - bearish)).coerceIn(0.0, 1.0)

        return FuzzyTriple(bullish, bearish, neutral)
    }

    // ── 2b. Para Akışı Üstünlüğü (EWMA-CMF) ────────────────────────────────
    // Tek anlık CMF değil — son N mumun EWMA trendi.
    // ±0.10 anlamlı yön eşiği (kripto literatüründe yaygın).
    private fun calculateFlowFuzzy(
        indicators: List<Analyze.IndicatorResult>,
        currentCmf: Double
    ): FuzzyTriple {
        // Son 10 mumun EWMA-CMF (alpha = 0.25)
        val alpha   = 0.25
        var ewmaCmf = indicators.firstOrNull()?.cmf ?: currentCmf
        for (ind in indicators.takeLast(10)) {
            ewmaCmf = alpha * ind.cmf + (1.0 - alpha) * ewmaCmf
        }

        // Sigmoid: merkez 0.10, eğim 20
        val bullish = sigmoid(ewmaCmf, k = 8.0, x0 = 0.10)
        val bearish = sigmoid(-ewmaCmf, k = 8.0, x0 = 0.10)
        val neutral = (1.0 - abs(bullish - bearish)).coerceIn(0.0, 1.0)

        return FuzzyTriple(bullish, bearish, neutral)
    }

    // ── 2c. Momentum Asimetrisi ──────────────────────────────────────────────
    // Yükseliş mumlarının ortalama gövde büyüklüğü vs düşüş mumları.
    // Pozitif asimetri → alıcılar daha güçlü baskı uyguluyor.
    private fun calculateMomentumFuzzy(
        klines: List<KlineItem>,
        atr: Double
    ): FuzzyTriple {
        val slice = klines.takeLast(20)
        if (slice.size < 4) return FuzzyTriple(0.5, 0.5, 0.0)

        val bullishBodies = slice.filter { it.close > it.open }
                                 .map { (it.close - it.open) / atr }
        val bearishBodies = slice.filter { it.close < it.open }
                                 .map { (it.open - it.close) / atr }

        val avgBull = bullishBodies.average().takeIf { bullishBodies.isNotEmpty() } ?: 0.0
        val avgBear = bearishBodies.average().takeIf { bearishBodies.isNotEmpty() } ?: 0.0

        val asymmetry = avgBull - avgBear  // Pozitif → alıcılar güçlü

        // Sigmoid: eğim 5, merkez 0
        val bullish = sigmoid(asymmetry, k = 5.0, x0 = 0.0)
        val bearish = sigmoid(-asymmetry, k = 5.0, x0 = 0.0)
        val neutral = (1.0 - abs(bullish - bearish)).coerceIn(0.0, 1.0)

        return FuzzyTriple(bullish, bearish, neutral)
    }

    // ── Sideways Membership ──────────────────────────────────────────────────
    // ATR sabit eşik yerine üç fuzzy koşul.
    // Yapısal belirsizlik + nötr para akışı + düşük verimlilik → SIDEWAYS
    private fun calculateSidewaysMembership(
        structural: FuzzyTriple,
        flow: FuzzyTriple,
        rvol: Double
    ): Double {
        // Yapısal belirsizlik: iki yön ne kadar eşitse o kadar sideways
        val sidewaysStructural = 1.0 - abs(structural.bullish - structural.bearish)

        // Para akışı nötrlüğü
        val sidewaysFlow = flow.neutral

        // Düşük relative volume: piyasa hareketsiz
        // rvol < 0.7 → sideways üyeliği yüksek
        val sidewaysVolume = sigmoid(-rvol, k = 4.0, x0 = -0.7)

        return ((sidewaysStructural * 0.40) +
                (sidewaysFlow       * 0.35) +
                (sidewaysVolume     * 0.25)).coerceIn(0.0, 1.0)
    }

    // ────────────────────────────────────────────────────────────────────────
    //  FAZ TESPİTİ — 6 fazın tamamı
    // ────────────────────────────────────────────────────────────────────────

    private fun detectPhase(
        ctx: IntelligentAnalyze.SharedAnalysisContext,
        regimeType: RegimeType,
        apsisKijun: Double,
        stochRsiK: Double,
        lastT0: FractalEngine.T0Point?,
        sealConf: Boolean,
        fractalMatrix: FractalEngine.FractalScoreMatrix,
        klines: List<KlineItem>,
        atr: Double
    ): RegimePhase {
        val lastInd = ctx.lastIndicator

        // ── HYPER_DRIVE (FractalEngine'den, RegimeEngine tekrar hesaplamıyor) ─
        if (fractalMatrix.isHyperDrive) return RegimePhase.HYPER_DRIVE

        // ── PREPARATION: BB squeeze + düşük hacim + nötr apsis ───────────────
        // BB genişliği daralıyor = fiyat sıkışıyor = birikim
        val bbWidth = if (lastInd.bbUpper > 0 && lastInd.close > 0) {
            (lastInd.bbUpper - lastInd.bbLower) / lastInd.close
        } else 0.05

        // Son 20 mumun BB genişliği ortalaması
        val avgBbWidth = ctx.indicators.takeLast(20).mapNotNull { ind ->
            if (ind.bbUpper > 0 && ind.close > 0)
                (ind.bbUpper - ind.bbLower) / ind.close
            else null
        }.average().takeIf { it.isFinite() } ?: bbWidth

        val isBbSqueeze   = bbWidth < avgBbWidth * 0.70  // Bantlar %30 daraldı
        val isLowVolume   = lastInd.rvol < 0.80
        val isNeutralApsis = abs(apsisKijun) < 1.5

        if (isBbSqueeze && isLowVolume && isNeutralApsis) return RegimePhase.PREPARATION

        // ── STARTING: Yeni T0 + checkSeals + küçük apsis ─────────────────────
        // T0 "yeni" mi? Son adaptiveLookback / 4 mum içinde oluştu mu?
        val adaptiveLb = ctx.lastIchimoku.adaptiveLookback.coerceAtLeast(20)
        val t0IsRecent = lastT0 != null &&
                         (ctx.klines.size - 1 - lastT0.index) < (adaptiveLb / 4)

        if (t0IsRecent && sealConf && abs(apsisKijun) < 2.0) return RegimePhase.STARTING

        // ── EXHAUSTED: Büyük apsis + checkSeals false ─────────────────────────
        // Trend desteği kaybedildi — geri dönüş riski yüksek
        if (abs(apsisKijun) > 4.2 && !sealConf) return RegimePhase.EXHAUSTED

        // ── MATURE: Stoch aşırı bölge + orta-büyük apsis ─────────────────────
        val isStochExtreme = stochRsiK > 75.0 || stochRsiK < 25.0
        val isMiddleApsis  = abs(apsisKijun) in 2.0..4.2

        if (isStochExtreme && isMiddleApsis) return RegimePhase.MATURE

        // ── ACCELERATING: Diğer tüm durumlar ─────────────────────────────────
        return RegimePhase.ACCELERATING
    }

    // ────────────────────────────────────────────────────────────────────────
    //  FUEL — Fuzzy, Faz-Bazlı Dinamik Ağırlık
    // ────────────────────────────────────────────────────────────────────────

    private fun calculateFuel(
        indicators: List<Analyze.IndicatorResult>,
        lastInd: Analyze.IndicatorResult,
        phase: RegimePhase,
        atr: Double,
        t0Strength: FractalEngine.T0Strength,
        sealConf: Boolean
    ): Double {

        // ── ER Bileşeni ──────────────────────────────────────────────────────
        // Verimli hareket = kapasite korunuyor
        // ER = 0.40 → orta, ER = 0.65 → güçlü, ER = 0.20 → zayıf
        val er = lastInd.adx / 100.0  // ADX normalize proxy (0-1)
        val erFuzzy = sigmoid(er, k = 8.0, x0 = 0.40)

        // ── Momentum İvmesi Bileşeni ─────────────────────────────────────────
        // MACD histogram eğimi: son 3 değerin lineer eğimi
        // Pozitif ve büyüyen → ivme güçlü (hareket hızlanıyor)
        val histValues = indicators.takeLast(3).map { it.macdHist }
        val histSlope  = if (histValues.size >= 2) {
            (histValues.last() - histValues.first()) / (atr.coerceAtLeast(1e-8))
        } else 0.0
        val momentumFuzzy = sigmoid(histSlope, k = 6.0, x0 = 0.0)

        // ── Para Akışı Derinliği Bileşeni ────────────────────────────────────
        // CMF × hacim derinliği: büyük para mı, küçük para mı akıyor?
        // CMF 0.20 ama hacim 0.5x → kırılgan. CMF 0.20 ve hacim 2x → sağlam.
        val flowDepth = lastInd.cmf * min(lastInd.rvol, 2.5) / 2.5
        val flowFuzzy = sigmoid(flowDepth, k = 10.0, x0 = 0.08)

        // ── Faz Bazlı Dinamik Ağırlık ────────────────────────────────────────
        val (wEr, wMom, wFlow) = when (phase) {
            RegimePhase.PREPARATION  -> Triple(0.20, 0.30, 0.50) // Sıkışmada para akışı kritik
            RegimePhase.STARTING     -> Triple(0.25, 0.40, 0.35) // Kırılımda momentum baskın
            RegimePhase.ACCELERATING -> Triple(0.35, 0.35, 0.30) // Dengeli
            RegimePhase.MATURE       -> Triple(0.40, 0.25, 0.35) // ER kritik (yorgunluk)
            RegimePhase.EXHAUSTED    -> Triple(0.45, 0.20, 0.35) // ER dominant (düzensiz hareket)
            RegimePhase.HYPER_DRIVE  -> Triple(0.15, 0.45, 0.40) // Momentum + para akışı baskın
        }

        var fuel = ((erFuzzy * wEr) + (momentumFuzzy * wMom) + (flowFuzzy * wFlow))
                    .coerceIn(0.0, 1.0)

        // ── HYPER_DRIVE sabit fuel (momentum anomalisi döneminde ATR yanıltıcı) ─
        if (phase == RegimePhase.HYPER_DRIVE) {
            fuel = fuel.coerceAtLeast(0.70)
        }

        return fuel
    }

    // ────────────────────────────────────────────────────────────────────────
    //  POLAR STRENGTH — Giriş Kalitesi Farkındalıklı
    // ────────────────────────────────────────────────────────────────────────

    private fun calculatePolarStrength(
        type: RegimeType,
        stochRsiK: Double,
        sealConf: Boolean
    ): Double {
        val sealBonus = if (sealConf) 10.0 else 0.0

        return when (type) {
            RegimeType.BULLISH -> {
                // BULLISH rejimde düşük Stoch → giriş fırsatı → güç yüksek
                // Stoch 20'de bonus 15, Stoch 50'de bonus 0, Stoch 80'de negatif
                val entryBonus = ((50.0 - stochRsiK) / 50.0).coerceIn(-0.5, 1.0) * 20.0
                (65.0 + entryBonus + sealBonus).coerceIn(40.0, 99.0)
            }
            RegimeType.BEARISH -> {
                // BEARISH rejimde yüksek Stoch → tepe dönüş fırsatı → güç yüksek
                val entryBonus = ((stochRsiK - 50.0) / 50.0).coerceIn(-0.5, 1.0) * 20.0
                (65.0 + entryBonus + sealBonus).coerceIn(40.0, 99.0)
            }
            RegimeType.SIDEWAYS -> 40.0
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  CHECK SEALS — Rejim güvenilirlik onayı
    // ────────────────────────────────────────────────────────────────────────
    // v6'da tanımlıydı ama hiç çağrılmıyordu. Artık aktif.
    // Dört koşulun tamamı: fiyat/bulut, MACD, CMF, Stoch yönü.

    private fun checkSeals(
        kline: KlineItem,
        ichi: IchimokuEngine.IchimokuData,
        stoch: Pair<Double, Double>,
        macd: Triple<Double, Double, Double>,
        cmf: Double,
        isBullish: Boolean
    ): Boolean {
        val cloudTop    = max(ichi.senkouA, ichi.senkouB)
        val cloudBottom = min(ichi.senkouA, ichi.senkouB)

        return if (isBullish) {
            val priceSeal = kline.close > cloudTop && kline.close > ichi.kijun
            val macdSeal  = macd.third > 0
            val cmfSeal   = cmf > 0.05
            val stochSeal = stoch.first > stoch.second && stoch.first > 20
            val sealCount = listOf(priceSeal, macdSeal, cmfSeal, stochSeal).count { it }
            sealCount >= 3
        } else {
            val priceSeal = kline.close < cloudBottom && kline.close < ichi.kijun
            val macdSeal  = macd.third < 0
            val cmfSeal   = cmf < -0.05
            val stochSeal = stoch.first < stoch.second && stoch.first < 80
            val sealCount = listOf(priceSeal, macdSeal, cmfSeal, stochSeal).count { it }
            sealCount >= 3
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  YARDIMCI HESAPLAMALAR
    // ────────────────────────────────────────────────────────────────────────

    // VolatilityZ: Wilder ATR bazlı (eski high-low farkı yerine)
    private fun calculateVolatilityZ(
        ichiList: List<IchimokuEngine.IchimokuData>,
        currentAtr: Double
    ): Double {
        val historicalAtrs = ichiList.takeLast(50).map { it.atr }
        if (historicalAtrs.size < 5) return 0.0
        val avg = historicalAtrs.average()
        val std = sqrt(historicalAtrs.map { (it - avg).pow(2) }.average())
                      .coerceAtLeast(1e-8)
        return (currentAtr - avg) / std
    }

    private fun calculateEfficiency(klines: List<KlineItem>): Double {
        val slice = klines.takeLast(14)
        if (slice.size < 2) return 50.0
        val totalChange  = abs(slice.last().close - slice.first().close)
        val sumOfChanges = slice.zipWithNext { a, b -> abs(b.close - a.close) }.sum()
        return (totalChange / sumOfChanges.coerceAtLeast(1e-8)) * 100.0
    }

    private fun calculateUncertainty(
        type: RegimeType,
        stoch: Pair<Double, Double>,
        macd: Triple<Double, Double, Double>,
        cmf: Double,
        sealConf: Boolean
    ): Double {
        if (type == RegimeType.SIDEWAYS) return 0.70
        var conflictCount = 0
        if (type == RegimeType.BULLISH) {
            if (stoch.first < stoch.second) conflictCount++
            if (macd.third < 0) conflictCount++
            if (cmf < 0) conflictCount++
        } else {
            if (stoch.first > stoch.second) conflictCount++
            if (macd.third > 0) conflictCount++
            if (cmf > 0) conflictCount++
        }
        val sealPenalty = if (!sealConf) 0.15 else 0.0
        return (conflictCount * 0.22 + sealPenalty).coerceIn(0.05, 0.90)
    }

    // ────────────────────────────────────────────────────────────────────────
    //  PERSISTENCE & SYNC
    // ────────────────────────────────────────────────────────────────────────

    private suspend fun updateRegimePersistence(
        context: Context,
        symbol: String,
        tf: String,
        currentType: RegimeType,
        klines: List<KlineItem>,
        ichi: IchimokuEngine.IchimokuData
    ) {
        val lastKline = klines.last()
        val db        = IntelligentDatabase.getDatabase(context)
        val dao       = db.regimeDao()
        val lastActive = dao.getRegimesBySymbol(symbol).find { it.tf == tf && it.endTime == null }

        if (lastActive != null && lastActive.regimeType != currentType.name) {
            lastActive.endTime  = lastKline.openTime
            lastActive.endPrice = lastKline.close
            lastActive.priceChangePct =
                (lastKline.close - lastActive.startPrice) / lastActive.startPrice
            dao.updateRegime(lastActive)
            createNewRegime(dao, symbol, tf, currentType, lastKline, ichi)
        } else if (lastActive == null) {
            createNewRegime(dao, symbol, tf, currentType, lastKline, ichi)
        }
    }

    private suspend fun createNewRegime(
        dao: com.example.tradebot.intelligent.brain.RegimeDao,
        symbol: String,
        tf: String,
        type: RegimeType,
        kline: KlineItem,
        ichi: IchimokuEngine.IchimokuData
    ) {
        dao.insertRegime(MarketRegime(
            startTime    = kline.openTime,
            symbol       = symbol,
            tf           = tf,
            regimeType   = type.name,
            startPrice   = kline.close,
            avgVolatility = ichi.atr,
            trendSlope   = 0.0,
            avgStochK    = 50.0,
            avgStochD    = 50.0,
            adxLevel     = 25.0
        ))
    }

    suspend fun syncHistoryRegimes(
        context: Context,
        symbol: String,
        tf: String,
        klines: List<KlineItem>
    ) {
        if (klines.size < 60) return
        val db  = IntelligentDatabase.getDatabase(context)
        val dao = db.regimeDao()

        val allIndicators = try { Analyze.calculateAllIndicators(klines, tf) }
                            catch (e: Exception) { emptyList() }
        val ichiList      = try { IchimokuEngine.calculateIchimokuList(klines, tf) }
                            catch (e: Exception) { emptyList() }

        if (allIndicators.size < 60 || ichiList.size < 60) return

        val fullCtx = IntelligentAnalyze.SharedAnalysisContext(
            symbol = symbol, tf = tf, klines = klines,
            indicators = allIndicators, ichimoku = ichiList,
            lastIndicator = allIndicators.last(),
            lastIchimoku  = ichiList.last(),
            atr = ichiList.last().atr
        )

        val allT0Points = FractalEngine.findStructuralT0(fullCtx)
        if (allT0Points.isEmpty()) return

        dao.deleteAutoRegimesBySymbolTf(symbol, tf)

        var currentRegime: MarketRegime? = null
        val regimesToInsert = mutableListOf<MarketRegime>()

        for (i in 60 until klines.size) {
            val kline = klines[i]
            val ichi  = ichiList[i]
            val atr   = ichi.atr.coerceAtLeast(1e-8)

            val subT0 = allT0Points.filter { it.index <= i }
            if (subT0.isEmpty()) continue

            // Fuzzy rejim skoru geçmişte de kullanılmalı ama hesap maliyetli.
            // syncHistory için basitleştirilmiş T0 bazlı yön (gerçek zamanlı değil)
            val lastT0 = subT0.last()
            val detectedType = when (lastT0.type) {
                FractalEngine.T0Type.BOTTOM,
                FractalEngine.T0Type.HYPER_BULL -> RegimeType.BULLISH
                FractalEngine.T0Type.TOP,
                FractalEngine.T0Type.HYPER_BEAR -> RegimeType.BEARISH
            }

            if (currentRegime == null || currentRegime!!.regimeType != detectedType.name) {
                currentRegime?.let {
                    it.endTime  = kline.openTime
                    it.endPrice = kline.close
                    it.priceChangePct = if (it.startPrice != 0.0)
                        (it.endPrice!! - it.startPrice) / it.startPrice else 0.0
                }
                currentRegime = MarketRegime(
                    startTime     = kline.openTime,
                    symbol        = symbol,
                    tf            = tf,
                    regimeType    = detectedType.name,
                    startPrice    = kline.close,
                    avgVolatility = atr,
                    trendSlope    = 0.0,
                    avgStochK     = allIndicators[i].stochRsiK,
                    avgStochD     = allIndicators[i].stochRsiD,
                    adxLevel      = 25.0
                )
                regimesToInsert.add(currentRegime!!)
            }
        }
        regimesToInsert.forEach { dao.insertRegime(it) }
    }

    fun defaultState() = MarketState(
        type = RegimeType.SIDEWAYS, phase = RegimePhase.PREPARATION,
        strength = 15.0, efficiency = 50.0, fuelLevel = 0.5,
        apsisDistance = 0.0, volatilityZ = 0.0, futureTrend = RegimeType.SIDEWAYS,
        invalidationPrice = 0.0, confidence = 20.0, atr = 0.0
    )
}
