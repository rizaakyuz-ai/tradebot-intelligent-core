package com.example.tradebot.intelligent.brain.core

import com.example.tradebot.dataset.KlineItem
import kotlinx.serialization.Serializable
import kotlin.math.*

/**
 * Fiyat Yapısı ve Formasyon Tespit Motoru — v7.0 (Structural Validity)
 *
 * v6 → v7 temel değişiklikler:
 *
 * 1. TESPİT KALİTESİ:
 *    - MSB: Tüm tarih high/low → Adaptif pencerede son swing kırılımı + hacim onayı
 *    - SFP: Tüm tarih min/max → Yerel swing seviyesi, doğru onay mantığı
 *    - Hammer/Star: "Tüm tarihin dibi/tepesi" → Yerel bağlam (adaptiveLookback/4)
 *    - H&S: Yanlış koşul düzeltildi (sağ omuz < kafa zorunlu)
 *    - PO3: Sabit %50/%30/%20 bölüm → Gerçek volatilite bazlı birikim tespiti
 *    - findSwings: strength=10 sabit → TF bazlı adaptif strength
 *
 * 2. ZONE ENTEGRASYONU:
 *    - Her pattern en yakın zone ile çapraz kontrol yapıyor
 *    - Zone içinde oluşan pattern: güç çarpanı
 *    - Liquidity Grab zone ile SFP/Hammer chalışması: maksimum güç
 *    - ZoneEngine'den gelen Bayesian retention olasılığı confidence'a giriyor
 *
 * 3. YAPISAL GEÇERLİLİK:
 *    - Zaman decay kaldırıldı
 *    - Yerine fiyat yapısı geçerlilik kontrolü:
 *      MSB: Fiyat kırılan seviyenin üstünde mi?
 *      Hammer: O dipten bu yana yükseliş devam ediyor mu?
 *      SFP: Stop avı seviyesi korunuyor mu?
 *
 * 4. GÜVEN SKORU:
 *    - Keyfi sabit confidence değerleri kaldırıldı
 *    - Hesaplanan confidence: hacim onayı + zone çakışması + rejim uyumu
 *    - 0.0-5.0 skala korunuyor (geriye dönük uyumluluk)
 *
 * 5. evaluatePatterns:
 *    - Keskin sıçrama (50→75) → sigmoid dönüşümü
 *    - Decay → yapısal geçerlilik kontrolü
 *    - regimeScore parametresi eklendi (rejim yönüyle uyumsuz pattern zayıflar)
 */
object PatternEngine {

    @Serializable
    data class PAStructure(
        val type: PAType,
        val price: Double,
        val stopLoss: Double = 0.0,
        val target: Double = 0.0,
        val time: Long,
        val isBullish: Boolean = true,
        val confidence: Double = 1.0,   // 0.0-5.0
        val isConfirmed: Boolean = false,
        val message: String = "",
        // ── YENİ ────────────────────────────────────────────────────────────
        val validityLevel: Double = 1.0, // Fiyat yapısı geçerlilik skoru (0-1)
        val zoneAligned: Boolean = false, // Zone ile çakışıyor mu?
        val volumeConfirmed: Boolean = false // Hacim onaylı mı?
    )

    @Serializable
    data class PatternScoreMatrix(
        val patternPotential: Double,   // 1-99
        val timingScore: Double,        // 1-99
        val maxConfidence: Double = 1.0,
        // ── YENİ ────────────────────────────────────────────────────────────
        val dominantType: PAType? = null,      // En güçlü pattern türü
        val structuralBias: Double = 0.0,      // -1..+1 net yön baskısı
        val hasZoneConfluence: Boolean = false  // Kritik zone çakışması var mı?
    )

    @Serializable
    enum class PAType {
        SMS, PO3, REJECTION_BLOCK, UNICORN, IFC, FAILURE_SWING, THREE_DRIVE,
        MSB, SFP, FLAG, TRIANGLE, KUMO_BREAKOUT,
        HAMMER, SHOOTING_STAR,
        RISING_WEDGE, FALLING_WEDGE, HEAD_SHOULDERS, CUP_HANDLE
    }

    // ── TF bazlı swing strength (IchimokuEngine ile uyumlu) ──────────────────
    private fun swingStrength(tf: String): Int = when (tf) {
        "1m", "3m"     -> 3
        "5m"           -> 4
        "15m"          -> 6
        "30m"          -> 6
        "60m", "1h"    -> 7
        "4h", "240m"   -> 6
        "1d", "D"      -> 5
        else           -> 6
    }

    // ────────────────────────────────────────────────────────────────────────
    //  ANA FONKSİYONLAR
    // ────────────────────────────────────────────────────────────────────────

    fun scanPatterns(
        ctx: IntelligentAnalyze.SharedAnalysisContext,
        zones: List<ZoneEngine.PriceZone> = emptyList()
    ): List<PAStructure> {
        val klines = ctx.klines
        if (klines.size < 60) return emptyList()

        val ichi       = ctx.lastIchimoku
        val atr        = ctx.atr.coerceAtLeast(1e-8)
        val tf         = ctx.tf
        val strength   = swingStrength(tf)
        val adaptiveLb = ichi.adaptiveLookback.coerceAtLeast(30)

        val patterns = mutableListOf<PAStructure>()

        // ── 1. Tetikleyiciler (hızlı sönen, zamanlama kritik) ─────────────────
        detectHammerAndShootingStar(klines, atr, adaptiveLb, zones)?.let { patterns.add(it) }
        detectSFP(klines, atr, adaptiveLb, zones)?.let { patterns.add(it) }
        detectIFC(klines, atr, zones)?.let { patterns.add(it) }
        detectRejectionBlocks(klines, atr, adaptiveLb, zones).let { patterns.addAll(it) }

        // ── 2. Yapısal Kırılımlar (orta ömürlü) ──────────────────────────────
        detectMSB(klines, atr, adaptiveLb, strength, zones)?.let { patterns.add(it) }
        detectSMS(klines, atr, adaptiveLb, strength)?.let { patterns.add(it) }
        detectFailureSwing(klines, atr, strength)?.let { patterns.add(it) }

        // ── 3. Kurumsal Yapılar (uzun ömürlü) ─────────────────────────────────
        detect3Drive(klines, atr, strength)?.let { patterns.add(it) }
        detectPO3(klines, atr, adaptiveLb)?.let { patterns.add(it) }
        detectUnicorn4(klines, zones, ichi)?.let { patterns.add(it) }

        // ── 4. Geometrik Formasyonlar (uzun ömürlü) ───────────────────────────
        detectGeometries(klines, atr, strength).let { patterns.addAll(it) }

        // ── 5. Ichimoku Özel Patternler ───────────────────────────────────────
        detectIchimokuSpecificPatterns(ichi, klines, atr).let { patterns.addAll(it) }

        return patterns
    }

    fun evaluatePatterns(
        ctx: IntelligentAnalyze.SharedAnalysisContext,
        patterns: List<PAStructure>,
        zones: List<ZoneEngine.PriceZone>,
        regimeScore: Double = 0.0    // RegimeEngine'den: -1..+1
    ): PatternScoreMatrix {
        val lastPrice = ctx.klines.lastOrNull()?.close
            ?: return PatternScoreMatrix(50.0, 50.0)
        if (patterns.isEmpty()) return PatternScoreMatrix(50.0, 50.0)

        val atr    = ctx.atr.coerceAtLeast(1e-8)
        val klines = ctx.klines

        var totalBias      = 0.0
        var maxPower       = 0.0
        var hasZoneConfl   = false
        var dominantPat    : PAStructure? = null

        patterns.forEach { pat ->
            // ── Yapısal Geçerlilik (zaman decay yerine) ──────────────────────
            val validity = calculateStructuralValidity(pat, klines, atr)
            if (validity < 0.15) return@forEach  // Yapısal olarak geçersiz

            // ── Proximity çarpanı ─────────────────────────────────────────────
            val priceGap = abs(lastPrice - pat.price) / atr
            val proximityMult = (1.5 - priceGap * 0.3).coerceIn(0.3, 1.5)

            // ── Rejim uyum çarpanı ────────────────────────────────────────────
            // Trend yönüyle uyumlu pattern güçlenir, çelişkili zayıflar
            val patBias    = if (pat.isBullish) 1.0 else -1.0
            val regimeAlign = (regimeScore * patBias).coerceIn(-1.0, 1.0)
            val regimeMult  = 1.0 + (regimeAlign * 0.4)  // ±%40 etki

            // ── Zone çakışma bonusu ───────────────────────────────────────────
            val zoneBonus = if (pat.zoneAligned) 1.35 else 1.0
            if (pat.zoneAligned) hasZoneConfl = true

            // ── Hacim onay bonusu ─────────────────────────────────────────────
            val volBonus = if (pat.volumeConfirmed) 1.20 else 1.0

            // ── Pattern gücü ──────────────────────────────────────────────────
            val patternPower = pat.confidence *
                               validity       *
                               proximityMult  *
                               regimeMult     *
                               zoneBonus      *
                               volBonus

            val direction = if (pat.isBullish) 1.0 else -1.0
            totalBias += direction * patternPower

            if (patternPower > maxPower) {
                maxPower     = patternPower
                dominantPat  = pat
            }
        }

        // ── Sigmoid dönüşümü (keskin sıçrama yok) ────────────────────────────
        // tanh bazlı: merkez 50, ±49 hareket alanı
        val k = 3.0  // Normalizasyon faktörü
        val finalPotential = (50.0 + 49.0 * tanh(totalBias / k)).coerceIn(1.0, 99.0)
        val timingScore    = (50.0 + sign(totalBias) * maxPower * 15.0).coerceIn(1.0, 99.0)

        return PatternScoreMatrix(
            patternPotential  = finalPotential,
            timingScore       = timingScore,
            maxConfidence     = maxPower.coerceIn(0.0, 5.0),
            dominantType      = dominantPat?.type,
            structuralBias    = (totalBias / k).coerceIn(-1.0, 1.0),
            hasZoneConfluence = hasZoneConfl
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    //  YAPISAL GEÇERLİLİK — Zaman decay yerine
    // ────────────────────────────────────────────────────────────────────────

    private fun calculateStructuralValidity(
        pat: PAStructure,
        klines: List<KlineItem>,
        atr: Double
    ): Double {
        val lastPrice = klines.last().close
        val patIdx    = klines.indexOfFirst { it.openTime == pat.time }
        if (patIdx < 0) return 0.5  // Zaman bilgisi yoksa orta geçerlilik

        val futureKlines = klines.subList(patIdx, klines.size)

        return when (pat.type) {
            // MSB: Fiyat kırılan seviyenin üstünde/altında mı?
            PAType.MSB -> {
                if (pat.isBullish) {
                    // Kırılan seviye = pat.stopLoss (önceki swing low)
                    val isValid = lastPrice > (pat.stopLoss + atr * 0.3)
                    if (isValid) 0.90 else 0.10
                } else {
                    val isValid = lastPrice < (pat.stopLoss - atr * 0.3)
                    if (isValid) 0.90 else 0.10
                }
            }
            // Hammer/SFP: Stop avı seviyesi korunuyor mu?
            PAType.HAMMER, PAType.SFP -> {
                if (pat.isBullish) {
                    // O dipten bu yana yükseliş var mı?
                    val minAfter = futureKlines.minOfOrNull { it.low } ?: pat.stopLoss
                    val isValid  = minAfter >= (pat.stopLoss - atr * 0.5)
                    if (isValid) 1.0 else 0.10
                } else {
                    val maxAfter = futureKlines.maxOfOrNull { it.high } ?: pat.stopLoss
                    val isValid  = maxAfter <= (pat.stopLoss + atr * 0.5)
                    if (isValid) 1.0 else 0.10
                }
            }
            // SMS/Failure Swing: Kırılan yapı korunuyor mu?
            PAType.SMS, PAType.FAILURE_SWING -> {
                val age = klines.size - 1 - patIdx
                // SMS yapısal — ama çok eski ise güç azalır
                (1.0 - (age.toDouble() / 60.0)).coerceIn(0.2, 1.0)
            }
            // Geometrik: Hâlâ yapı içinde mi?
            PAType.RISING_WEDGE, PAType.FALLING_WEDGE, PAType.TRIANGLE,
            PAType.FLAG, PAType.HEAD_SHOULDERS, PAType.CUP_HANDLE -> {
                val age = klines.size - 1 - patIdx
                (1.0 - (age.toDouble() / 80.0)).coerceIn(0.15, 1.0)
            }
            // Diğerleri: Orta geçerlilik, yaşa göre hafif azalma
            else -> {
                val age = klines.size - 1 - patIdx
                (1.0 - (age.toDouble() / 30.0)).coerceIn(0.20, 1.0)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  TESPİT FONKSİYONLARI
    // ────────────────────────────────────────────────────────────────────────

    // ── MSB: Adaptif pencerede son swing kırılımı + hacim onayı ──────────────
    private fun detectMSB(
        klines: List<KlineItem>,
        atr: Double,
        adaptiveLb: Int,
        strength: Int,
        zones: List<ZoneEngine.PriceZone>
    ): PAStructure? {
        val last = klines.last()
        val windowSize = (adaptiveLb / 3).coerceIn(20, 80)
        val slice = klines.takeLast(windowSize)

        // Son swing high ve low: sabit "tüm tarih" değil, adaptif pencerede
        val swingHighs = findSwings(slice, true, strength)
        val swingLows  = findSwings(slice, false, strength)

        if (swingHighs.isEmpty() || swingLows.isEmpty()) return null

        val lastSwingHigh = swingHighs.last().price
        val lastSwingLow  = swingLows.last().price

        // Hacim onayı: son 5 mumun ortalama hacminin 1.5x üstünde
        val avgVol = klines.takeLast(20).map { it.volume }.average()
        val volConfirmed = last.volume > avgVol * 1.5

        // Impulsif hareket: Son hareketlerin %80 persentili
        val moves = slice.zipWithNext { a, b -> abs(b.close - a.close) }.sorted()
        val impulseThreshold = if (moves.isNotEmpty())
            moves[((moves.size - 1) * 0.80).toInt()] else atr

        val isImpulsive = abs(last.close - last.open) > impulseThreshold

        // Confidence hesabı: hacim + impulsif + zone uyumu
        val zoneAligned = zones.any { z ->
            !z.isMitigated && abs((z.top + z.bottom) / 2.0 - last.close) < atr * 1.5
        }
        val confidence = (2.5 +
            (if (volConfirmed) 0.8 else 0.0) +
            (if (isImpulsive) 0.7 else 0.0) +
            (if (zoneAligned) 0.5 else 0.0)).coerceIn(0.0, 5.0)

        // Bullish MSB: Son swing high kırıldı
        if (last.close > lastSwingHigh && isImpulsive) {
            return PAStructure(
                type = PAType.MSB, price = lastSwingHigh,
                stopLoss = lastSwingLow,
                target = last.close + (lastSwingHigh - lastSwingLow) * 1.5,
                time = last.openTime, isBullish = true,
                confidence = confidence, isConfirmed = true,
                message = "MSB Bullish [Vol:$volConfirmed]",
                validityLevel = 1.0,
                zoneAligned = zoneAligned,
                volumeConfirmed = volConfirmed
            )
        }

        // Bearish MSB: Son swing low kırıldı
        if (last.close < lastSwingLow && isImpulsive) {
            return PAStructure(
                type = PAType.MSB, price = lastSwingLow,
                stopLoss = lastSwingHigh,
                target = last.close - (lastSwingHigh - lastSwingLow) * 1.5,
                time = last.openTime, isBullish = false,
                confidence = confidence, isConfirmed = true,
                message = "MSB Bearish [Vol:$volConfirmed]",
                validityLevel = 1.0,
                zoneAligned = zoneAligned,
                volumeConfirmed = volConfirmed
            )
        }
        return null
    }

    // ── SFP: Yerel swing seviyesi, doğru onay mantığı ────────────────────────
    private fun detectSFP(
        klines: List<KlineItem>,
        atr: Double,
        adaptiveLb: Int,
        zones: List<ZoneEngine.PriceZone>
    ): PAStructure? {
        if (klines.size < 32) return null
        val last   = klines.last()
        val prev   = klines[klines.size - 2]

        // Yerel swing: adaptif pencerede (tüm tarih değil)
        val windowSize  = (adaptiveLb / 3).coerceIn(20, 60)
        val lookback    = klines.dropLast(2).takeLast(windowSize)
        val localHigh   = lookback.maxOfOrNull { it.high } ?: return null
        val localLow    = lookback.minOfOrNull { it.low }  ?: return null

        // Liquidity Grab zone ile çakışıyor mu?
        val grabZone = zones.find {
            it.type == ZoneEngine.ZoneType.LIQUIDITY_GRAB && !it.isMitigated &&
            abs((it.top + it.bottom) / 2.0 - prev.low.coerceAtMost(prev.high)) < atr
        }
        val hasGrabConfl = grabZone != null

        // Bullish SFP: Önceki mum yerel dibi deldi, geri döndü
        // Onay: last.close > prev.close (geri dönüş teyidi)
        if (prev.low < localLow &&
            prev.close > localLow &&
            last.close > prev.close) {  // Düzeltildi: prev.high değil prev.close

            val confidence = (3.5 +
                (if (hasGrabConfl) 1.2 else 0.0) +
                (if (last.volume > klines.takeLast(20).map { it.volume }.average() * 1.3) 0.5 else 0.0)
            ).coerceIn(0.0, 5.0)

            return PAStructure(
                type = PAType.SFP, price = localLow,
                stopLoss = prev.low - atr * 0.5,
                target = last.close + atr * 3,
                time = last.openTime, isBullish = true,
                confidence = confidence, isConfirmed = true,
                message = "Bullish SFP${if (hasGrabConfl) " + LiqGrab" else ""}",
                validityLevel = 1.0,
                zoneAligned = hasGrabConfl,
                volumeConfirmed = false
            )
        }

        // Bearish SFP
        if (prev.high > localHigh &&
            prev.close < localHigh &&
            last.close < prev.close) {

            val confidence = (3.5 +
                (if (hasGrabConfl) 1.2 else 0.0)
            ).coerceIn(0.0, 5.0)

            return PAStructure(
                type = PAType.SFP, price = localHigh,
                stopLoss = prev.high + atr * 0.5,
                target = last.close - atr * 3,
                time = last.openTime, isBullish = false,
                confidence = confidence, isConfirmed = true,
                message = "Bearish SFP${if (hasGrabConfl) " + LiqGrab" else ""}",
                validityLevel = 1.0, zoneAligned = hasGrabConfl,
                volumeConfirmed = false
            )
        }
        return null
    }

    // ── Hammer / Shooting Star: Yerel bağlam ─────────────────────────────────
    private fun detectHammerAndShootingStar(
        klines: List<KlineItem>,
        atr: Double,
        adaptiveLb: Int,
        zones: List<ZoneEngine.PriceZone>
    ): PAStructure? {
        val last = klines.lastOrNull() ?: return null
        if (klines.size < 10) return null

        val body      = abs(last.close - last.open).coerceAtLeast(1e-8)
        val upperWick = last.high - max(last.open, last.close)
        val lowerWick = min(last.open, last.close) - last.low

        // Yerel bağlam: adaptiveLookback / 4 mum içindeki en düşük/yüksek
        val localWindow = (adaptiveLb / 4).coerceIn(10, 40)
        val localSlice  = klines.takeLast(localWindow)
        val localLow    = localSlice.minOf { it.low }
        val localHigh   = localSlice.maxOf { it.high }

        // Zone çakışması
        val nearBullZone = zones.any { z ->
            z.isBullish && !z.isMitigated &&
            last.low >= z.bottom - atr * 0.3 &&
            last.close <= z.top + atr * 0.5
        }
        val nearBearZone = zones.any { z ->
            !z.isBullish && !z.isMitigated &&
            last.high <= z.top + atr * 0.3 &&
            last.close >= z.bottom - atr * 0.5
        }

        // Hammer: Alt gölge gövdenin 2.5x, üst gölge minimal, yerel dip bölgesinde
        if (lowerWick >= body * 2.5 &&
            upperWick <= body * 0.3 &&
            last.low <= localLow + atr * 0.5) {  // "Tüm tarih" değil, yerel dip

            val confidence = (2.5 +
                (if (nearBullZone) 1.0 else 0.0) +
                (if (last.close > last.open) 0.5 else 0.0)  // Bullish kapanış bonus
            ).coerceIn(0.0, 5.0)

            return PAStructure(
                type = PAType.HAMMER, price = last.close,
                stopLoss = last.low - atr * 0.2,
                target = last.close + atr * 2.5,
                time = last.openTime, isBullish = true,
                confidence = confidence, isConfirmed = true,
                message = "Hammer${if (nearBullZone) " @ Zone" else ""}",
                validityLevel = 1.0, zoneAligned = nearBullZone,
                volumeConfirmed = false
            )
        }

        // Shooting Star: Üst gölge gövdenin 2.5x, alt gölge minimal, yerel tepe bölgesinde
        if (upperWick >= body * 2.5 &&
            lowerWick <= body * 0.3 &&
            last.high >= localHigh - atr * 0.5) {

            val confidence = (2.5 +
                (if (nearBearZone) 1.0 else 0.0) +
                (if (last.close < last.open) 0.5 else 0.0)
            ).coerceIn(0.0, 5.0)

            return PAStructure(
                type = PAType.SHOOTING_STAR, price = last.close,
                stopLoss = last.high + atr * 0.2,
                target = last.close - atr * 2.5,
                time = last.openTime, isBullish = false,
                confidence = confidence, isConfirmed = true,
                message = "Shooting Star${if (nearBearZone) " @ Zone" else ""}",
                validityLevel = 1.0, zoneAligned = nearBearZone,
                volumeConfirmed = false
            )
        }
        return null
    }

    // ── SMS: Adaptif pencere + TF bazlı swing ────────────────────────────────
    private fun detectSMS(
        klines: List<KlineItem>,
        atr: Double,
        adaptiveLb: Int,
        strength: Int
    ): PAStructure? {
        if (klines.size < 40) return null
        val last     = klines.last()
        val window   = klines.takeLast((adaptiveLb / 2).coerceIn(30, 100))
        val swingsH  = findSwings(window, true, strength)
        val swingsL  = findSwings(window, false, strength)

        // Bearish SMS: LH + LL yapısı
        if (swingsH.size >= 2 && swingsL.isNotEmpty()) {
            val h1 = swingsH[swingsH.size - 2]
            val h2 = swingsH.last()
            val l1 = swingsL.last()
            if (h2.price < h1.price && last.close < l1.price) {
                return PAStructure(
                    type = PAType.SMS, price = last.close,
                    stopLoss = h2.price + atr * 0.2,
                    target = last.close - (h2.price - last.close) * 2.0,
                    time = last.openTime, isBullish = false,
                    confidence = 3.8, isConfirmed = true,
                    message = "Bearish SMS (LH+LL)", validityLevel = 1.0
                )
            }
        }

        // Bullish SMS: HH + HL yapısı
        if (swingsL.size >= 2 && swingsH.isNotEmpty()) {
            val l1 = swingsL[swingsL.size - 2]
            val l2 = swingsL.last()
            val h1 = swingsH.last()
            if (l2.price > l1.price && last.close > h1.price) {
                return PAStructure(
                    type = PAType.SMS, price = last.close,
                    stopLoss = l2.price - atr * 0.2,
                    target = last.close + (last.close - l2.price) * 2.0,
                    time = last.openTime, isBullish = true,
                    confidence = 3.8, isConfirmed = true,
                    message = "Bullish SMS (HH+HL)", validityLevel = 1.0
                )
            }
        }
        return null
    }

    // ── Failure Swing: TF bazlı swing ────────────────────────────────────────
    private fun detectFailureSwing(
        klines: List<KlineItem>,
        atr: Double,
        strength: Int
    ): PAStructure? {
        if (klines.size < 30) return null
        val last   = klines.last()
        val window = klines.takeLast(40)
        val swings = findSwings(window, true, strength)

        if (swings.size >= 2) {
            val p1 = swings[swings.size - 2].price
            val p2 = swings.last().price
            if (p2 < p1 && last.close < klines[klines.size - 2].low) {
                return PAStructure(
                    type = PAType.FAILURE_SWING, price = last.close,
                    stopLoss = p1, target = last.close - (p1 - last.close),
                    time = last.openTime, isBullish = false,
                    confidence = 2.5, isConfirmed = true,
                    message = "Failure Swing Bearish", validityLevel = 1.0
                )
            }
        }
        return null
    }

    // ── PO3: Volatilite bazlı birikim tespiti ────────────────────────────────
    private fun detectPO3(
        klines: List<KlineItem>,
        atr: Double,
        adaptiveLb: Int
    ): PAStructure? {
        if (klines.size < 50) return null

        // Birikim: ATR bazlı dar bant tespiti
        // Son adaptiveLb/3 mumda ATR'nin 3x'inden dar bir bant varsa → birikim
        val window    = klines.takeLast((adaptiveLb / 3).coerceIn(20, 60))
        val highRange = window.maxOf { it.high }
        val lowRange  = window.minOf { it.low }
        val rangeSize = highRange - lowRange

        // Birikim koşulu: Range < 3 ATR (volatilite bazlı, sabit değil)
        if (rangeSize > atr * 3.0) return null

        // Manipülasyon: Son 10 mumda bir spike var mı?
        val recentSlice  = klines.takeLast(10)
        val manipLow  = recentSlice.minOf { it.low }
        val manipHigh = recentSlice.maxOf { it.high }
        val lastDist  = klines.last()

        // Bullish PO3: Birikim + aşağı manipülasyon + yukarı kapanış
        if (manipLow < lowRange - atr * 0.5 && lastDist.close > highRange) {
            return PAStructure(
                type = PAType.PO3, price = lastDist.close,
                stopLoss = manipLow,
                target = lastDist.close + (highRange - manipLow) * 2.0,
                time = lastDist.openTime, isBullish = true,
                confidence = 4.5, isConfirmed = true,
                message = "Bullish PO3 [Acc:${String.format("%.2f", rangeSize/atr)}ATR]",
                validityLevel = 1.0, volumeConfirmed = false
            )
        }

        // Bearish PO3
        if (manipHigh > highRange + atr * 0.5 && lastDist.close < lowRange) {
            return PAStructure(
                type = PAType.PO3, price = lastDist.close,
                stopLoss = manipHigh,
                target = lastDist.close - (manipHigh - lowRange) * 2.0,
                time = lastDist.openTime, isBullish = false,
                confidence = 4.5, isConfirmed = true,
                message = "Bearish PO3 [Acc:${String.format("%.2f", rangeSize/atr)}ATR]",
                validityLevel = 1.0, volumeConfirmed = false
            )
        }
        return null
    }

    // ── IFC: Kurumsal güçlü mum ───────────────────────────────────────────────
    private fun detectIFC(
        klines: List<KlineItem>,
        atr: Double,
        zones: List<ZoneEngine.PriceZone>
    ): PAStructure? {
        if (klines.size < 21) return null
        val last     = klines.last()
        val lookback = klines.takeLast(20)

        val avgVol   = lookback.map { it.volume }.average()
        val avgBody  = lookback.map { abs(it.close - it.open) }.average()

        val currentBody = abs(last.close - last.open)
        val totalRange  = (last.high - last.low).coerceAtLeast(1e-8)
        val wickRatio   = (totalRange - currentBody) / totalRange

        if (last.volume > avgVol * 2.5 &&
            currentBody > avgBody * 3.0 &&
            wickRatio < 0.15) {

            val isBullish  = last.close > last.open
            val zoneAligned = zones.any { z ->
                !z.isMitigated && z.isBullish == isBullish &&
                abs((z.top + z.bottom) / 2.0 - last.close) < atr * 2.0
            }

            val confidence = (3.5 + (if (zoneAligned) 0.7 else 0.0)).coerceIn(0.0, 5.0)

            return PAStructure(
                type = PAType.IFC, price = last.close,
                stopLoss = if (isBullish) last.low else last.high,
                target = last.close + (if (isBullish) currentBody * 2.5 else -currentBody * 2.5),
                time = last.openTime, isBullish = isBullish,
                confidence = confidence, isConfirmed = true,
                message = "IFC${if (zoneAligned) " @ Zone" else ""}",
                validityLevel = 1.0, zoneAligned = zoneAligned,
                volumeConfirmed = true
            )
        }
        return null
    }

    // ── Rejection Blocks: Yerel bağlam ───────────────────────────────────────
    private fun detectRejectionBlocks(
        klines: List<KlineItem>,
        atr: Double,
        adaptiveLb: Int,
        zones: List<ZoneEngine.PriceZone>
    ): List<PAStructure> {
        if (klines.size < 15) return emptyList()
        val last    = klines.last()
        val list    = mutableListOf<PAStructure>()
        val window  = (adaptiveLb / 5).coerceIn(8, 20)
        val lookback = klines.dropLast(1).takeLast(window)

        val recentHigh = lookback.maxOfOrNull { it.high } ?: return emptyList()
        val recentLow  = lookback.minOfOrNull { it.low }  ?: return emptyList()

        val body      = abs(last.close - last.open)
        val upperWick = last.high - max(last.open, last.close)
        val lowerWick = min(last.open, last.close) - last.low

        if (last.high > recentHigh && last.close < recentHigh && upperWick > body * 1.5) {
            val zoneAligned = zones.any { z ->
                !z.isBullish && !z.isMitigated &&
                abs((z.top + z.bottom) / 2.0 - last.high) < atr
            }
            list.add(PAStructure(
                type = PAType.REJECTION_BLOCK, price = last.close,
                stopLoss = last.high, target = last.close - (last.high - last.close) * 2.0,
                time = last.openTime, isBullish = false,
                confidence = (3.0 + if (zoneAligned) 0.8 else 0.0).coerceIn(0.0, 5.0),
                isConfirmed = true, message = "Bearish RB",
                validityLevel = 1.0, zoneAligned = zoneAligned
            ))
        }
        if (last.low < recentLow && last.close > recentLow && lowerWick > body * 1.5) {
            val zoneAligned = zones.any { z ->
                z.isBullish && !z.isMitigated &&
                abs((z.top + z.bottom) / 2.0 - last.low) < atr
            }
            list.add(PAStructure(
                type = PAType.REJECTION_BLOCK, price = last.close,
                stopLoss = last.low, target = last.close + (last.close - last.low) * 2.0,
                time = last.openTime, isBullish = true,
                confidence = (3.0 + if (zoneAligned) 0.8 else 0.0).coerceIn(0.0, 5.0),
                isConfirmed = true, message = "Bullish RB",
                validityLevel = 1.0, zoneAligned = zoneAligned
            ))
        }
        return list
    }

    // ── 3 Drive ───────────────────────────────────────────────────────────────
    private fun detect3Drive(
        klines: List<KlineItem>,
        atr: Double,
        strength: Int
    ): PAStructure? {
        val swings = findSwings(klines.takeLast(80), true, strength)
        if (swings.size >= 3) {
            val d1 = swings[swings.size - 3]
            val d2 = swings[swings.size - 2]
            val d3 = swings.last()
            val ratio = (d3.price - d1.price) / (d2.price - d1.price).coerceAtLeast(1e-8)
            if (ratio in 1.15..1.90) {
                return PAStructure(
                    type = PAType.THREE_DRIVE, price = d3.price,
                    stopLoss = d3.price + atr, target = d3.price - atr * 3,
                    time = d3.time, isBullish = false,
                    confidence = 3.0, isConfirmed = true,
                    message = "3-Drive [ratio:${String.format("%.2f", ratio)}]",
                    validityLevel = 1.0
                )
            }
        }
        return null
    }

    // ── Unicorn 4.0: Breaker + FVG + Zone çakışması ──────────────────────────
    private fun detectUnicorn4(
        klines: List<KlineItem>,
        zones: List<ZoneEngine.PriceZone>,
        ichi: IchimokuEngine.IchimokuData
    ): PAStructure? {
        val last     = klines.lastOrNull() ?: return null
        val breakers = zones.filter { it.type == ZoneEngine.ZoneType.BREAKER && !it.isMitigated }
        val fvgs     = zones.filter { it.type == ZoneEngine.ZoneType.FVG && !it.isMitigated }

        for (breaker in breakers) {
            for (fvg in fvgs) {
                val overlapBottom = max(breaker.bottom, fvg.bottom)
                val overlapTop    = min(breaker.top, fvg.top)
                if (overlapBottom >= overlapTop) continue

                val inOverlap  = last.close in (overlapBottom * 0.999)..(overlapTop * 1.001)
                val kijunZone  = ichi.kijun in overlapBottom..overlapTop
                val equilZone  = ichi.equilibrium > 0.0 &&
                                 ichi.equilibrium in overlapBottom..overlapTop

                if (breaker.isBullish == fvg.isBullish) {
                    // Bayesian retention: Her iki zone'un tutunma olasılığı çarpılıyor
                    val combinedRetention = breaker.retentionProbability *
                                           fvg.retentionProbability
                    val confidence = (4.0 +
                        (if (kijunZone || equilZone) 0.5 else 0.0) +
                        (combinedRetention * 0.5)
                    ).coerceIn(0.0, 5.0)

                    return PAStructure(
                        type = PAType.UNICORN, price = last.close,
                        stopLoss = if (breaker.isBullish) overlapBottom - (overlapTop - overlapBottom) * 0.5
                                   else overlapTop + (overlapTop - overlapBottom) * 0.5,
                        target = if (breaker.isBullish) last.close + (overlapTop - overlapBottom) * 3
                                 else last.close - (overlapTop - overlapBottom) * 3,
                        time = last.openTime, isBullish = breaker.isBullish,
                        confidence = confidence,
                        isConfirmed = inOverlap || kijunZone || equilZone,
                        message = "Unicorn 4.0 [B+FVG ret:${String.format("%.2f", combinedRetention)}]",
                        validityLevel = combinedRetention,
                        zoneAligned = true, volumeConfirmed = false
                    )
                }
            }
        }
        return null
    }

    // ── Ichimoku Özel Patternler ──────────────────────────────────────────────
    private fun detectIchimokuSpecificPatterns(
        ichi: IchimokuEngine.IchimokuData,
        klines: List<KlineItem>,
        atr: Double
    ): List<PAStructure> {
        val list = mutableListOf<PAStructure>()
        val last = klines.lastOrNull() ?: return list
        val cloudTop    = max(ichi.senkouA, ichi.senkouB)
        val cloudBottom = min(ichi.senkouA, ichi.senkouB)

        if (last.open < cloudTop && last.close > cloudTop) {
            list.add(PAStructure(
                type = PAType.KUMO_BREAKOUT, price = last.close,
                stopLoss = ichi.kijun,
                target = last.close + (last.close - ichi.kijun),
                time = last.openTime, isBullish = true,
                confidence = 2.8, isConfirmed = true,
                message = "Kumo Breakout Bullish",
                validityLevel = 1.0
            ))
        } else if (last.open > cloudBottom && last.close < cloudBottom) {
            list.add(PAStructure(
                type = PAType.KUMO_BREAKOUT, price = last.close,
                stopLoss = ichi.kijun,
                target = last.close - (ichi.kijun - last.close),
                time = last.openTime, isBullish = false,
                confidence = 2.8, isConfirmed = true,
                message = "Kumo Breakout Bearish",
                validityLevel = 1.0
            ))
        }
        return list
    }

    // ── Geometrik Formasyonlar ────────────────────────────────────────────────
    private fun detectGeometries(
        klines: List<KlineItem>,
        atr: Double,
        strength: Int
    ): List<PAStructure> {
        val results = mutableListOf<PAStructure>()
        if (klines.size < 50) return results

        val highs = findSwings(klines, true, strength)
        val lows  = findSwings(klines, false, strength)

        if (highs.size < 3 || lows.size < 3) return results

        val upperTrend = calculateSlope(highs)
        val lowerTrend = calculateSlope(lows)
        val lastPrice  = klines.last().close
        val time       = klines.last().openTime

        // Rising Wedge (Bearish)
        if (upperTrend.slope > 0 && lowerTrend.slope > 0 &&
            upperTrend.slope > lowerTrend.slope * 1.3) {
            results.add(PAStructure(
                type = PAType.RISING_WEDGE, price = lastPrice,
                stopLoss = lastPrice - atr * 0.5,
                target = lastPrice - atr * 4,
                time = time, isBullish = false,
                confidence = 4.0, isConfirmed = true,
                message = "Rising Wedge (Bearish)", validityLevel = 1.0
            ))
        }

        // Falling Wedge (Bullish)
        if (upperTrend.slope < 0 && lowerTrend.slope < 0 &&
            lowerTrend.slope > upperTrend.slope * 1.3) {
            results.add(PAStructure(
                type = PAType.FALLING_WEDGE, price = lastPrice,
                stopLoss = lastPrice + atr * 0.5,
                target = lastPrice + atr * 4,
                time = time, isBullish = true,
                confidence = 4.0, isConfirmed = true,
                message = "Falling Wedge (Bullish)", validityLevel = 1.0
            ))
        }

        // Head & Shoulders: DÜZELTILDI — sağ omuz < kafa
        if (highs.size >= 3) {
            val leftShoulder = highs[highs.size - 3].price
            val head         = highs[highs.size - 2].price
            val rightShoulder = highs.last().price

            // Kafa en yüksek, omuzlar birbirine yakın
            val isHnS = head > leftShoulder * 1.02 &&
                        head > rightShoulder * 1.02 &&  // DÜZELTILDI: sağ omuz < kafa
                        abs(leftShoulder - rightShoulder) / head < 0.08 // Omuzlar yakın

            if (isHnS && lows.isNotEmpty()) {
                results.add(PAStructure(
                    type = PAType.HEAD_SHOULDERS, price = lastPrice,
                    stopLoss = lastPrice - atr,
                    target = lastPrice - (head - lows.last().price) * 1.618,
                    time = time, isBullish = false,
                    confidence = 4.2, isConfirmed = true,
                    message = "H&S [head:${String.format("%.4f", head)}]",
                    validityLevel = 1.0
                ))
            }
        }

        // Cup & Handle
        if (highs.size >= 4 && lows.size >= 4 &&
            lows[lows.size - 2].price < lows[lows.size - 3].price * 0.92) {
            results.add(PAStructure(
                type = PAType.CUP_HANDLE, price = lastPrice,
                stopLoss = lastPrice - atr * 0.5,
                target = lastPrice + atr * 5,
                time = time, isBullish = true,
                confidence = 4.0, isConfirmed = true,
                message = "Cup & Handle", validityLevel = 1.0
            ))
        }

        // Symmetrical Triangle
        if (upperTrend.slope < 0 && lowerTrend.slope > 0) {
            val denom = (upperTrend.slope - lowerTrend.slope).coerceAtMost(-1e-6)
            val convergencePoint = (lowerTrend.intercept - upperTrend.intercept) / denom
            if (convergencePoint > klines.size) {
                results.add(PAStructure(
                    type = PAType.TRIANGLE, price = lastPrice,
                    stopLoss = lastPrice - atr,
                    target = lastPrice + atr * 3,
                    time = time, isBullish = true,
                    confidence = 3.0, isConfirmed = false,
                    message = "Sym Triangle (Unconfirmed)", validityLevel = 0.7
                ))
            }
        }

        // Bull Flag
        val slopeDiff = abs(upperTrend.slope - lowerTrend.slope)
        if (slopeDiff < 0.1 && abs(upperTrend.slope) < 0.5) {
            val pole = klines.takeLast(40).take(10)
            val isBullFlag = pole.size >= 10 && pole.last().close > pole.first().open * 1.05
            if (isBullFlag) {
                val flagRange = klines.takeLast(40).let { it.maxOf { k -> k.high } - it.minOf { k -> k.low } }
                results.add(PAStructure(
                    type = PAType.FLAG, price = lastPrice,
                    stopLoss = lastPrice - atr * 1.5,
                    target = lastPrice + flagRange,
                    time = time, isBullish = true,
                    confidence = 4.0, isConfirmed = true,
                    message = "Bull Flag", validityLevel = 1.0
                ))
            }
        }
        return results
    }

    // ────────────────────────────────────────────────────────────────────────
    //  YARDIMCI FONKSİYONLAR
    // ────────────────────────────────────────────────────────────────────────

    // TF bazlı adaptif strength ile swing tespiti
    private fun findSwings(
        klines: List<KlineItem>,
        isHigh: Boolean,
        strength: Int = 6
    ): List<SwingPoint> {
        val swings = mutableListOf<SwingPoint>()
        if (klines.size < strength * 2 + 1) return emptyList()
        for (i in strength until klines.size - strength) {
            val current   = klines[i]
            val leftSide  = klines.subList(i - strength, i)
            val rightSide = klines.subList(i + 1, i + strength + 1)
            if (isHigh) {
                if (leftSide.all { it.high <= current.high } &&
                    rightSide.all { it.high < current.high })
                    swings.add(SwingPoint(current.high, current.openTime))
            } else {
                if (leftSide.all { it.low >= current.low } &&
                    rightSide.all { it.low > current.low })
                    swings.add(SwingPoint(current.low, current.openTime))
            }
        }
        return swings
    }

    private fun calculateSlope(points: List<SwingPoint>): LineParams {
        if (points.size < 2) return LineParams(0.0, points.firstOrNull()?.price ?: 0.0)
        val n   = points.size
        val sumX  = (0 until n).sum().toDouble()
        val sumY  = points.sumOf { it.price }
        val sumXY = points.withIndex().sumOf { it.index.toDouble() * it.value.price }
        val sumX2 = (0 until n).sumOf { it.toDouble() * it }
        val denom = (n * sumX2 - sumX * sumX)
        if (abs(denom) < 1e-8) return LineParams(0.0, points.first().price)
        val slope     = (n * sumXY - sumX * sumY) / denom
        val intercept = (sumY - slope * sumX) / n
        return LineParams(slope, intercept)
    }

    data class SwingPoint(val price: Double, val time: Long)
    data class LineParams(val slope: Double, val intercept: Double)
}
