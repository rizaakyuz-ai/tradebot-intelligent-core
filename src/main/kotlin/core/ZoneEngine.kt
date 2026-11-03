package com.example.tradebot.intelligent.brain.core

import com.example.tradebot.dataset.KlineItem
import kotlinx.serialization.Serializable
import kotlin.math.*

/**
 * POI (Point of Interest) Alan Tespit Motoru — v7.0 (Bayesian Living Zones)
 *
 * Temel felsefe değişikliği (v6 → v7):
 *
 * v6: Zone tespit et → sabit puan ver → skora karıştır.
 *     Statik, geriye bakıcı, bağlamdan kopuk.
 *
 * v7: Üç katmanlı mimari:
 *
 *  KATMAN 1 — TESPİT (Detection):
 *   - Order Block: Hacim onaylı, adaptif pencere, impulsif hareket persentil bazlı
 *   - FVG: Minimum 1.0 ATR boşluk, rejim bazlı ağırlık
 *   - HVN/POC/VA: Lineer hacim dağılımı (close bin değil, mum aralığı boyunca)
 *                 POC (Point of Control) ve Value Area (%70 hacim) ayrıca tespit
 *   - LVN: HVN'nin yan ürünü, gerçek liquidity void
 *   - Deep Fib: Son majör swing'e gerçek Fibonacci (%61.8-%78.6)
 *   - Equilibrium Gravity: Kijun değil, IchimokuEngine'den gelen equilibrium + ATR tolerans
 *
 *  KATMAN 2 — OLASILIK GÜNCELLEMESİ (Bayesian Update):
 *   - Her zone için Beta dağılımı: P(tutunma) = α/(α+β)
 *   - α = başarılı test (kapanış bazlı tutunma)
 *   - β = başarısız test (kapanış bazlı kırılım)
 *   - Mitigation: Kapanış bazlı (wick = test, kapanış zone dışı = kırılım)
 *   - Volatilite normalizasyonu: Zone oluştuğu andaki ATR vs şu anki ATR
 *
 *  KATMAN 3 — BAĞLAM ENTEGRASYONU (Context Integration):
 *   - Rejim uyumu: regimeScore × zoneBias — trend yönüyle uyumlu zone güçlenir
 *   - Kripto adaptasyonu: Likidite grabı tespiti (stop avı sonrası dönüş)
 *   - Saat etkisi: 00:00-08:00 UTC düşük likidite döneminde oluşan zone'lar zayıf
 *
 *  ÇIKTILAR — Karar mekanizmasına doğrudan bağlı:
 *   - buyPotential/sellPotential: Resonance skoru için (mevcut)
 *   - entryZone: FractalEngine T0 ile çift onay → giriş kalitesi
 *   - stopZone: Doğal stop-loss seviyesi → RiskEngine'e
 *   - targetZone: Bir sonraki direnç/destek → ProjectionEngine'e
 *   - zoneBreached: Pozisyon izleme çıkış sinyali → IntelligentTrade'e
 *   - positionSizeMultiplier: Zone kalitesine göre pozisyon çarpanı
 */
object ZoneEngine {

    // ── Kripto düşük likidite penceresi (UTC) ────────────────────────────────
    private const val LOW_LIQ_START_UTC = 0   // 00:00 UTC
    private const val LOW_LIQ_END_UTC   = 8   // 08:00 UTC

    // ── Beta dağılımı başlangıç prior'ı ─────────────────────────────────────
    // α0 = β0 = 1.0 → Uniform prior (bilgi yok, eşit olasılık)
    private const val BETA_PRIOR = 2.0

    // ── Value Area hacim eşiği ────────────────────────────────────────────────
    private const val VALUE_AREA_THRESHOLD = 0.70  // Toplam hacmin %70'i

    // ── Minimum boşluk eşiği (FVG) ───────────────────────────────────────────
    private const val FVG_MIN_ATR_FACTOR = 0.6

    // ── Zone geçerlilik toleransı ─────────────────────────────────────────────
    private const val BREACH_ATR_FACTOR = 0.5  // Kapanış bu kadar dışarıda → kırıldı

    // ────────────────────────────────────────────────────────────────────────
    //  VERİ YAPILARI
    // ────────────────────────────────────────────────────────────────────────

    @Serializable
    enum class ZoneType {
        ORDER_BLOCK, FVG, BREAKER, MITIGATION,
        HVN, POC, VALUE_AREA, LVN,
        LIQUIDITY_GRAB, DEEP_FIB, EQUILIBRIUM_GRAVITY
    }

    @Serializable
    data class PriceZone(
        val top: Double,
        val bottom: Double,
        val type: ZoneType,
        val tf: String,
        val startTime: Long,
        val isBullish: Boolean = true,
        val initialStrength: Double = 1.0,
        var isMitigated: Boolean = false,
        var testCount: Int = 0,
        val volumeProfile: Double = 0.0,
        val isStrict: Boolean = false,
        var confluenceCount: Int = 1,
        val confluenceTypes: MutableSet<ZoneType> = mutableSetOf(),
        val logic: String = "",
        // ── YENİ ─────────────────────────────────────────────────────────
        var successfulTests: Int = 0,        // Başarılı dokunuş (Bayesian α)
        var failedTests: Int = 0,            // Başarısız dokunuş (Bayesian β)
        val formationAtr: Double = 0.0,      // Oluştuğu andaki ATR (vol normalize için)
        val formationHour: Int = 0,          // UTC saat (likidite etkisi için)
        var isLiquidityGrab: Boolean = false // Stop avı sonrası dönüş tespit edildi mi
    ) {
        // Bayesian tutunma olasılığı: Beta dağılımı beklenen değeri
        val retentionProbability: Double
            get() {
                val alpha = BETA_PRIOR + successfulTests
                val beta  = BETA_PRIOR + failedTests
                return alpha / (alpha + beta)
            }
    }

    @Serializable
    data class ZonePotential(
        // ── Mevcut alanlar (geriye dönük uyumluluk) ──────────────────────────
        val buyPotential: Double,
        val sellPotential: Double,
        val activeConfluences: Int = 0,
        val isRefuelZone: Boolean = false,
        val isDeepFibZone: Boolean = false,
        val isUnicorn: Boolean = false,
        // ── YENİ — Karar mekanizmasına doğrudan bağlı ────────────────────────
        val entryZone: PriceZone? = null,          // En kaliteli giriş zone'u
        val stopZone: Double = 0.0,                 // Doğal stop-loss seviyesi
        val targetZone: Double = 0.0,               // Hedef fiyat (sonraki zone)
        val zoneBreached: Boolean = false,           // Aktif pozisyon çıkış sinyali
        val positionSizeMultiplier: Double = 1.0,   // Zone kalitesine göre çarpan
        val nearestBullishZone: PriceZone? = null,  // En yakın aktif destek
        val nearestBearishZone: PriceZone? = null,  // En yakın aktif direnç
        val pocLevel: Double = 0.0,                  // Point of Control (en yüksek hacim)
        val valueAreaHigh: Double = 0.0,             // Value Area üst sınır
        val valueAreaLow: Double = 0.0               // Value Area alt sınır
    )

    data class ZoneConfig(
        val hvnLookback: Int,
        val voidMultiplier: Double,
        val proximityAtr: Double,
        val clusterThreshold: Double
    )

    private fun getConfigForTf(tf: String): ZoneConfig = when (tf) {
        "5m", "15m"    -> ZoneConfig(200, 6.0, 2.5, 0.6)
        "60m", "1h", "4h" -> ZoneConfig(200, 4.5, 1.8, 0.45)
        "1d", "D"      -> ZoneConfig(200, 3.5, 1.4, 0.35)
        else           -> ZoneConfig(200, 5.0, 2.0, 0.5)
    }

    // ────────────────────────────────────────────────────────────────────────
    //  KATMAN 1: TESPİT
    // ────────────────────────────────────────────────────────────────────────

    fun scanZones(ctx: IntelligentAnalyze.SharedAnalysisContext): List<PriceZone> {
        val klines   = ctx.klines
        val tf       = ctx.tf
        val ichi     = ctx.lastIchimoku
        val atr      = ctx.atr.coerceAtLeast(1e-8)

        if (klines.size < 60) return emptyList()

        val allZones = mutableListOf<PriceZone>()

        // ── Order Block (hacim onaylı, adaptif pencere) ───────────────────────
        val obs = detectOrderBlocks(klines, tf, atr, ichi.adaptiveLookback)
        allZones.addAll(obs)

        // ── FVG (minimum 1.0 ATR, gerçek boşluk) ─────────────────────────────
        allZones.addAll(detectFVGs(klines, tf, atr))

        // ── Breaker & Mitigation (kırılan OB'lar) ────────────────────────────
        allZones.addAll(detectBreakersAndMitigations(klines, tf, obs, atr))

        // ── Hacim Profili: HVN + POC + Value Area + LVN ──────────────────────
        val volumeZones = detectVolumeProfile(klines, tf, atr)
        allZones.addAll(volumeZones)

        // ── Liquidity Grab (stop avı tespiti) ────────────────────────────────
        allZones.addAll(detectLiquidityGrabs(klines, tf, atr))

        // ── Deep Fib (gerçek Fibonacci — son majör swing'e) ───────────────────
        detectDeepFib(klines, tf, atr, ichi)?.let { allZones.add(it) }

        // ── Equilibrium Gravity (Kijun değil — yapısal denge) ─────────────────
        if (ichi.equilibrium > 0.0) {
            allZones.add(PriceZone(
                top           = ichi.equilibrium + (atr * 0.5),
                bottom        = ichi.equilibrium - (atr * 0.5),
                type          = ZoneType.EQUILIBRIUM_GRAVITY,
                tf            = tf,
                startTime     = System.currentTimeMillis(),
                isBullish     = klines.last().close > ichi.equilibrium,
                initialStrength = 3.0,
                formationAtr  = atr,
                logic         = "Structural Equilibrium Gravity (Long-term Magnet)"
            ))
        }

        // ── Confluence Taraması ───────────────────────────────────────────────
        markConfluences(allZones, atr, tf)

        // ── Bayesian Güncelleme (test geçmişi) ───────────────────────────────
        updateBayesian(klines, allZones, atr)

        // ── Harita filtresi: çok uzak zone'ları temizle ───────────────────────
        val lastPrice = klines.last().close
        return allZones.filter {
            val center = (it.top + it.bottom) / 2.0
            abs(center - lastPrice) < atr * 20.0
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  KATMAN 2 + 3: OLASILIK & BAĞLAM — calculatePotentials
    // ────────────────────────────────────────────────────────────────────────

    fun calculatePotentials(
        ctx: IntelligentAnalyze.SharedAnalysisContext,
        zones: List<PriceZone>,
        regimeScore: Double = 0.0,          // RegimeEngine'den: -1..+1
        activePositionSymbol: String? = null // Pozisyon izleme için
    ): ZonePotential {
        if (zones.isEmpty()) return ZonePotential(50.0, 50.0)

        val tf        = ctx.tf
        val klines    = ctx.klines
        val config    = getConfigForTf(tf)
        val lastPrice = klines.last().close
        val atr       = ctx.atr.coerceAtLeast(1e-8)

        // ── Volatilite normalizasyon oranı ────────────────────────────────────
        // Şu anki ATR / zone oluştuğundaki ATR → toleransı dinamik ayarlar
        var rawBuyScore  = 0.0
        var rawSellScore = 0.0
        var isRefuel     = false
        var isDeepFib    = false
        var isUnicorn    = false
        var maxConfluence = 0
        var zoneBreached  = false
        var bestBullishZone: PriceZone? = null
        var bestBearishZone: PriceZone? = null
        var bestBullishDist  = Double.MAX_VALUE
        var bestBearishDist  = Double.MAX_VALUE
        var pocLevel         = 0.0
        var valueAreaHigh    = 0.0
        var valueAreaLow     = 0.0

        // ── Her zone için değerlendirme ───────────────────────────────────────
        zones.forEach { zone ->
            // Mitigated zone'lar kesinlikle puan almıyor
            if (zone.isMitigated) return@forEach

            val center = (zone.top + zone.bottom) / 2.0

            // POC/Value Area seviyelerini çıkar
            if (zone.type == ZoneType.POC) pocLevel = center
            if (zone.type == ZoneType.VALUE_AREA && zone.isBullish) valueAreaLow = zone.bottom
            if (zone.type == ZoneType.VALUE_AREA && !zone.isBullish) valueAreaHigh = zone.top

            // ── Volatilite normalize edilmiş proximity ─────────────────────
            // Zone oluştuğundaki ATR varsa dinamik tolerans, yoksa şu anki ATR
            val volRatio  = if (zone.formationAtr > 0.0) atr / zone.formationAtr else 1.0
            val dynProx   = config.proximityAtr * volRatio.coerceIn(0.5, 2.0)

            val dist = if (zone.isBullish) (lastPrice - zone.top)   / atr
                       else               (zone.bottom - lastPrice) / atr

            // ── Aktif pozisyon kırılım kontrolü ───────────────────────────
            if (activePositionSymbol != null) {
                val breachCheck = if (zone.isBullish)
                    lastPrice < (zone.bottom - atr * BREACH_ATR_FACTOR)
                else
                    lastPrice > (zone.top    + atr * BREACH_ATR_FACTOR)
                if (breachCheck && zone.confluenceCount >= 2) zoneBreached = true
            }

            // ── En yakın destek/direnç ────────────────────────────────────
            if (zone.isBullish && dist > -0.5 && dist < bestBullishDist) {
                bestBullishDist  = dist
                bestBullishZone  = zone
            }
            if (!zone.isBullish && dist > -0.5 && dist < bestBearishDist) {
                bestBearishDist  = dist
                bestBearishZone  = zone
            }

            // ── Proximity kontrolü (aktif menzil) ─────────────────────────
            if (dist < dynProx && dist > -1.0) {

                // ── Bayesian Tutunma Olasılığı ─────────────────────────────
                // retentionProbability: Beta(α,β) beklenen değeri
                val retention = zone.retentionProbability  // 0.0-1.0

                // ── Freshness — SMC'ye uygun olgunluk eğrisi ──────────────
                val freshness = when (zone.testCount) {
                    0    -> 1.00
                    1    -> 1.25
                    2    -> 1.15
                    3    -> 1.05
                    4    -> 0.90
                    5    -> 0.75
                    else -> 0.50
                }

                // ── Likidite saati zayıflatıcısı ──────────────────────────
                // 00:00-08:00 UTC düşük likidite döneminde oluşan zone'lar
                val liquidityFactor = if (zone.formationHour in LOW_LIQ_START_UTC..LOW_LIQ_END_UTC)
                    0.65 else 1.0

                // ── Rejim uyum çarpanı ─────────────────────────────────────
                // regimeScore × zoneBias: uyumlu ise güçlendirme, çelişkili ise zayıflatma
                val zoneBias       = if (zone.isBullish) 1.0 else -1.0
                val regimeAlignment = (regimeScore * zoneBias).coerceIn(-1.0, 1.0)
                // +1.0 → tam uyumlu (1.5x), -1.0 → tam çelişkili (0.5x)
                val regimeMult     = 1.0 + (regimeAlignment * 0.5)

                // ── Liquidity Grab bonusu ──────────────────────────────────
                // Stop avı sonrası dönüş tespit edildiyse zone çok güçlü
                val grabBonus = if (zone.isLiquidityGrab) 1.4 else 1.0

                // ── Unicorn bonusu (3+ farklı tip çakışması) ──────────────
                val unicornBonus = when {
                    zone.confluenceTypes.size >= 4 -> 1.8
                    zone.confluenceTypes.size >= 3 -> 1.5
                    zone.confluenceTypes.size >= 2 -> 1.2
                    else                           -> 1.0
                }

                // ── Proximity çarpanı (yaklaştıkça artar) ─────────────────
                val proximityMult = (1.0 - (dist / dynProx)).coerceIn(0.1, 1.2)

                // ── Ham güç hesabı ─────────────────────────────────────────
                // Tüm faktörler çarpımlı — biri çok düşük ise toplam düşer
                val rawStrength = zone.initialStrength *
                                  proximityMult *
                                  freshness *
                                  retention *        // Bayesian olasılık
                                  regimeMult *       // Rejim uyumu
                                  grabBonus *        // Stop avı teyidi
                                  unicornBonus *     // Confluence kalitesi
                                  liquidityFactor *  // Likidite saati
                                  10.0               // Skala normalizasyon

                if (zone.isBullish) rawBuyScore  += rawStrength
                else                rawSellScore += rawStrength

                // Stratejik flagler
                if (zone.confluenceTypes.size >= 3) isUnicorn = true
                if (zone.type == ZoneType.DEEP_FIB && abs(dist) < 0.5) isDeepFib = true
                if ((zone.tf == "4h" || zone.tf == "1d") && abs(dist) < 0.5) isRefuel = true
                if (zone.confluenceCount > maxConfluence) maxConfluence = zone.confluenceCount
            }
        }

        // ── Sigmoid Dönüşümü (keskin sıçrama yerine kademeli) ────────────────
        // tanh bazlı: 50 + 49 × tanh(rawScore / k)
        // k normalizasyon faktörü: ortalama beklenen rawScore
        val k = 25.0
        val buyPotential  = (50.0 + 49.0 * tanh(rawBuyScore  / k)).coerceIn(1.0, 99.0)
        val sellPotential = (50.0 + 49.0 * tanh(rawSellScore / k)).coerceIn(1.0, 99.0)

        // ── Stop Zone (doğal stop-loss) ───────────────────────────────────────
        // En kaliteli bullish zone'un altı
        val stopZone = bestBullishZone?.let {
            it.bottom - (atr * BREACH_ATR_FACTOR)
        } ?: (klines.last().close * 0.95)

        // ── Target Zone (hedef fiyat — bir sonraki direnç) ────────────────────
        val targetZone = bestBearishZone?.let {
            (it.top + it.bottom) / 2.0
        } ?: (klines.last().close * 1.05)

        // ── Giriş Zone (en kaliteli) ──────────────────────────────────────────
        // Retention yüksek + confluenceCount yüksek + yakın
        val entryZone = zones
            .filter { !it.isMitigated && it.isBullish }
            .maxByOrNull { it.retentionProbability * it.confluenceCount * it.initialStrength }

        // ── Pozisyon boyutu çarpanı ───────────────────────────────────────────
        // Unicorn + yüksek retention + refuel zone → büyük pozisyon
        val posMultiplier = when {
            isUnicorn && isRefuel                    -> 1.40
            isUnicorn                                -> 1.25
            isRefuel && (entryZone?.retentionProbability ?: 0.0) > 0.7 -> 1.20
            (entryZone?.retentionProbability ?: 0.0) > 0.8              -> 1.15
            else                                     -> 1.00
        }

        return ZonePotential(
            buyPotential           = buyPotential,
            sellPotential          = sellPotential,
            activeConfluences      = maxConfluence,
            isRefuelZone           = isRefuel,
            isDeepFibZone          = isDeepFib,
            isUnicorn              = isUnicorn,
            entryZone              = entryZone,
            stopZone               = stopZone,
            targetZone             = targetZone,
            zoneBreached           = zoneBreached,
            positionSizeMultiplier = posMultiplier,
            nearestBullishZone     = bestBullishZone,
            nearestBearishZone     = bestBearishZone,
            pocLevel               = pocLevel,
            valueAreaHigh          = valueAreaHigh,
            valueAreaLow           = valueAreaLow
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    //  TESPİT FONKSİYONLARI
    // ────────────────────────────────────────────────────────────────────────

    // ── Order Block (hacim onaylı, adaptif pencere) ───────────────────────────
    fun detectOrderBlocks(
        klines: List<KlineItem>,
        tf: String,
        atr: Double,
        adaptiveLookback: Int = 100
    ): List<PriceZone> {
        val blocks = mutableListOf<PriceZone>()

        // Adaptif pencere: adaptiveLookback / 6 veya maksimum 80 mum
        val windowSize = (adaptiveLookback / 6).coerceIn(20, 80)
        val startIdx   = max(10, klines.size - windowSize)

        // İmpulsif hareket eşiği: Son penceredeki hareketlerin %85 persentili
        val allMoves = (startIdx until klines.size - 3).map { i ->
            abs(klines[i + 3].close - klines[i].open)
        }
        val impulseThreshold = if (allMoves.isNotEmpty())
            allMoves.sorted()[((allMoves.size - 1) * 0.85).toInt()]
        else atr * 2.0

        for (i in startIdx until klines.size - 4) {
            val curr  = klines[i]
            val next1 = klines[i + 1]
            val next3 = klines[i + 3]

            val totalMove = abs(next3.close - curr.open)

            // İmpulsif hareket: persentil bazlı (sabit ATR çarpanı değil)
            val isImpulsive = totalMove >= impulseThreshold

            // Hacim onayı: bu mum ve sonraki 2 mumun hacmi pencere ortalamasından yüksek mi?
            val windowAvgVol = klines.subList(startIdx, i + 1)
                                     .map { it.volume }.average()
            val movementVol  = (klines[i].volume + klines[i+1].volume) / 2.0
            val isVolConfirmed = movementVol > windowAvgVol * 1.3

            // Yapısal kırılım: adaptif penceredeki high/low kırıldı mı?
            val swingLookback = min(adaptiveLookback / 4, i - startIdx).coerceAtLeast(5)
            val recentHigh    = klines.subList(max(0, i - swingLookback), i).maxOf { it.high }
            val recentLow     = klines.subList(max(0, i - swingLookback), i).minOf { it.low }

            val formHour = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                           .apply { timeInMillis = curr.openTime }.get(java.util.Calendar.HOUR_OF_DAY)

            // Bullish OB: Düşüş mumu → yükseliş mumu → impulsif yukarı → yapısal kırılım
            if (curr.close < curr.open &&
                next1.close > next1.open &&
                isImpulsive &&
                isVolConfirmed &&
                next3.close > recentHigh) {
                blocks.add(PriceZone(
                    top = curr.high, bottom = curr.low,
                    type = ZoneType.ORDER_BLOCK, tf = tf,
                    startTime = curr.openTime, isBullish = true,
                    initialStrength = 2.5, isStrict = true,
                    formationAtr = atr, formationHour = formHour,
                    logic = "Institutional Buy Block [Vol:${isVolConfirmed}]"
                ))
            }

            // Bearish OB: Yükseliş mumu → düşüş mumu → impulsif aşağı → yapısal kırılım
            if (curr.close > curr.open &&
                next1.close < next1.open &&
                isImpulsive &&
                isVolConfirmed &&
                next3.close < recentLow) {
                blocks.add(PriceZone(
                    top = curr.high, bottom = curr.low,
                    type = ZoneType.ORDER_BLOCK, tf = tf,
                    startTime = curr.openTime, isBullish = false,
                    initialStrength = 2.5, isStrict = true,
                    formationAtr = atr, formationHour = formHour,
                    logic = "Institutional Sell Block [Vol:${isVolConfirmed}]"
                ))
            }
        }
        return blocks
    }

    // ── FVG (minimum 1.0 ATR, anlamlı boşluk) ────────────────────────────────
    private fun detectFVGs(
        klines: List<KlineItem>,
        tf: String,
        atr: Double
    ): List<PriceZone> {
        val gaps     = mutableListOf<PriceZone>()
        val minGap   = atr * FVG_MIN_ATR_FACTOR  // 1.0 ATR minimum

        for (i in max(0, klines.size - 150) until klines.size - 2) {
            val k1 = klines[i]
            val k3 = klines[i + 2]
            val formHour = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                           .apply { timeInMillis = klines[i+1].openTime }
                           .get(java.util.Calendar.HOUR_OF_DAY)

            if (k3.low - k1.high >= minGap) {
                gaps.add(PriceZone(
                    top = k3.low, bottom = k1.high,
                    type = ZoneType.FVG, tf = tf,
                    startTime = klines[i+1].openTime, isBullish = true,
                    initialStrength = 1.8,
                    formationAtr = atr, formationHour = formHour,
                    logic = "Bullish FVG [${String.format("%.2f", (k3.low - k1.high) / atr)}xATR]"
                ))
            } else if (k1.low - k3.high >= minGap) {
                gaps.add(PriceZone(
                    top = k1.low, bottom = k3.high,
                    type = ZoneType.FVG, tf = tf,
                    startTime = klines[i+1].openTime, isBullish = false,
                    initialStrength = 1.8,
                    formationAtr = atr, formationHour = formHour,
                    logic = "Bearish FVG [${String.format("%.2f", (k1.low - k3.high) / atr)}xATR]"
                ))
            }
        }
        return gaps
    }

    // ── Breaker & Mitigation ──────────────────────────────────────────────────
    private fun detectBreakersAndMitigations(
        klines: List<KlineItem>,
        tf: String,
        obs: List<PriceZone>,
        atr: Double
    ): List<PriceZone> {
        val derived   = mutableListOf<PriceZone>()
        val lastPrice = klines.last().close

        obs.forEach { ob ->
            // Kırılım kapanış bazlı + BREACH_ATR_FACTOR tolerans
            val bullBreached = ob.isBullish  && lastPrice < (ob.bottom - atr * BREACH_ATR_FACTOR)
            val bearBreached = !ob.isBullish && lastPrice > (ob.top    + atr * BREACH_ATR_FACTOR)

            if (bullBreached) {
                derived.add(ob.copy(
                    type = ZoneType.BREAKER, isBullish = false,
                    initialStrength = 3.0,
                    logic = "Broken Support → Breaker Block"
                ))
            } else if (bearBreached) {
                derived.add(ob.copy(
                    type = ZoneType.MITIGATION, isBullish = true,
                    initialStrength = 2.8,
                    logic = "Broken Resistance → Mitigation Block"
                ))
            }
        }
        return derived
    }

    // ── Hacim Profili: HVN + POC + Value Area + LVN ──────────────────────────
    private fun detectVolumeProfile(
        klines: List<KlineItem>,
        tf: String,
        atr: Double
    ): List<PriceZone> {
        val result    = mutableListOf<PriceZone>()
        val slice     = klines.takeLast(200)
        val minP      = slice.minOf { it.low }
        val maxP      = slice.maxOf { it.high }
        val range     = maxP - minP
        if (range <= 0) return result

        // Dinamik bin sayısı: Her bin = 1 ATR genişliğinde
        val binCount  = (range / atr).toInt().coerceIn(20, 100)
        val step      = range / binCount
        val binVols   = DoubleArray(binCount)

        // LİNEER HACİM DAĞILIMI: Her mumun hacmini kapsadığı bin aralığına eşit dağıt
        slice.forEach { k ->
            val lowBin  = ((k.low  - minP) / step).toInt().coerceIn(0, binCount - 1)
            val highBin = ((k.high - minP) / step).toInt().coerceIn(0, binCount - 1)
            val span    = (highBin - lowBin + 1).coerceAtLeast(1)
            val volPer  = k.volume / span
            for (b in lowBin..highBin) binVols[b] += volPer
        }

        val totalVol  = binVols.sum().coerceAtLeast(1.0)
        val avgVol    = totalVol / binCount

        // ── POC (Point of Control) — en yüksek hacimli bin ──────────────────
        val pocIdx    = binVols.indices.maxByOrNull { binVols[it] } ?: 0
        val pocTop    = minP + (pocIdx + 1) * step
        val pocBottom = minP + pocIdx * step
        result.add(PriceZone(
            top = pocTop, bottom = pocBottom,
            type = ZoneType.POC, tf = tf,
            startTime = System.currentTimeMillis(),
            isBullish = klines.last().close > (pocTop + pocBottom) / 2.0,
            initialStrength = 3.5, volumeProfile = binVols[pocIdx],
            formationAtr = atr,
            logic = "Point of Control [${String.format("%.1f", binVols[pocIdx] / avgVol)}x avg vol]"
        ))

        // ── Value Area (%70 hacim kapanana kadar POC'tan genişle) ────────────
        var cumulVol  = binVols[pocIdx]
        var vaLow     = pocIdx
        var vaHigh    = pocIdx
        while (cumulVol / totalVol < VALUE_AREA_THRESHOLD &&
               (vaLow > 0 || vaHigh < binCount - 1)) {
            val expandUp   = if (vaHigh < binCount - 1) binVols[vaHigh + 1] else 0.0
            val expandDown = if (vaLow  > 0)            binVols[vaLow  - 1] else 0.0
            if (expandUp >= expandDown && vaHigh < binCount - 1) {
                vaHigh++; cumulVol += binVols[vaHigh]
            } else if (vaLow > 0) {
                vaLow--;  cumulVol += binVols[vaLow]
            } else { vaHigh++; cumulVol += binVols[vaHigh] }
        }
        val vaTopPrice = minP + (vaHigh + 1) * step
        val vaLowPrice = minP + vaLow * step
        result.add(PriceZone(
            top = vaTopPrice, bottom = vaLowPrice,
            type = ZoneType.VALUE_AREA, tf = tf,
            startTime = System.currentTimeMillis(),
            isBullish = true, initialStrength = 2.0,
            volumeProfile = cumulVol, formationAtr = atr,
            logic = "Value Area [${String.format("%.0f", cumulVol / totalVol * 100)}% of volume]"
        ))

        // ── HVN (ortalamadan 2.2x yüksek binler) ─────────────────────────────
        binVols.forEachIndexed { idx, vol ->
            if (vol > avgVol * 2.2 && idx != pocIdx) {
                result.add(PriceZone(
                    top = minP + (idx + 1) * step,
                    bottom = minP + idx * step,
                    type = ZoneType.HVN, tf = tf,
                    startTime = System.currentTimeMillis(),
                    isBullish = true, initialStrength = 2.0,
                    volumeProfile = vol, formationAtr = atr,
                    logic = "HVN [${String.format("%.1f", vol / avgVol)}x avg]"
                ))
            }
        }

        // ── LVN (ortalamadan 0.4x düşük binler — gerçek liquidity void) ──────
        binVols.forEachIndexed { idx, vol ->
            if (vol < avgVol * 0.4 && idx > 0 && idx < binCount - 1) {
                result.add(PriceZone(
                    top = minP + (idx + 1) * step,
                    bottom = minP + idx * step,
                    type = ZoneType.LVN, tf = tf,
                    startTime = System.currentTimeMillis(),
                    isBullish = klines.last().close > (minP + idx * step),
                    initialStrength = 1.5, volumeProfile = vol,
                    formationAtr = atr,
                    logic = "LVN — Low Volume (Fast Pass Zone)"
                ))
            }
        }

        return result
    }

    // ── Liquidity Grab (Stop avı tespiti) ─────────────────────────────────────
    // Fiyat bir zone'un altına/üstüne spike yapıp geri döndüyse — stop avı
    private fun detectLiquidityGrabs(
        klines: List<KlineItem>,
        tf: String,
        atr: Double
    ): List<PriceZone> {
        val grabs = mutableListOf<PriceZone>()
        val lookback = min(klines.size - 1, 100)

        for (i in (klines.size - lookback) until klines.size - 2) {
            val k     = klines[i]
            val prev  = klines[i - 1]
            val next  = klines[i + 1]

            // Bullish Grab: Fiyat aşağı spike yaptı ama yukarda kapandı
            // Wick oranı: alt gölge gövdenin 2x'inden büyük + kapanış önceki düşük seviyenin üstü
            val lowerWick  = k.close.coerceAtMost(k.open) - k.low
            val bodySize   = abs(k.close - k.open).coerceAtLeast(atr * 0.1)
            val upperWick  = k.high - k.close.coerceAtLeast(k.open)

            val isBullGrab = lowerWick > bodySize * 2.0 &&
                             lowerWick > atr * 0.8 &&
                             k.close > prev.low &&
                             next.close > k.close

            val isBearGrab = upperWick > bodySize * 2.0 &&
                             upperWick > atr * 0.8 &&
                             k.close < prev.high &&
                             next.close < k.close

            val formHour = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                           .apply { timeInMillis = k.openTime }
                           .get(java.util.Calendar.HOUR_OF_DAY)

            if (isBullGrab) {
                grabs.add(PriceZone(
                    top = k.close.coerceAtMost(k.open) + (atr * 0.3),
                    bottom = k.low,
                    type = ZoneType.LIQUIDITY_GRAB, tf = tf,
                    startTime = k.openTime, isBullish = true,
                    initialStrength = 3.2, isLiquidityGrab = true,
                    formationAtr = atr, formationHour = formHour,
                    logic = "Bullish Liquidity Grab (Stop Hunt)"
                ))
            }
            if (isBearGrab) {
                grabs.add(PriceZone(
                    top = k.high,
                    bottom = k.close.coerceAtLeast(k.open) - (atr * 0.3),
                    type = ZoneType.LIQUIDITY_GRAB, tf = tf,
                    startTime = k.openTime, isBullish = false,
                    initialStrength = 3.2, isLiquidityGrab = true,
                    formationAtr = atr, formationHour = formHour,
                    logic = "Bearish Liquidity Grab (Stop Hunt)"
                ))
            }
        }
        return grabs
    }

    // ── Deep Fib (Gerçek Fibonacci — son majör swing'e) ───────────────────────
    private fun detectDeepFib(
        klines: List<KlineItem>,
        tf: String,
        atr: Double,
        ichi: IchimokuEngine.IchimokuData
    ): PriceZone? {
        // Son majör swing: IchimokuEngine'den gelen adaptif penceredaki en büyük hareket
        val lookback  = ichi.adaptiveLookback.coerceAtLeast(30)
        val slice     = klines.takeLast(lookback)
        if (slice.size < 10) return null

        // En büyük swing hareketi bul: swing high → swing low veya tersi
        val high = slice.maxOf { it.high }
        val low  = slice.minOf { it.low }
        val highIdx = slice.indexOfFirst { it.high == high }
        val lowIdx  = slice.indexOfFirst { it.low  == low  }

        val lastPrice = klines.last().close

        return if (highIdx > lowIdx) {
            // Düşüş: High'dan Low'a — fiyat şu an aşağıda, Fib geri çekilme seviyeleri
            // %61.8 ve %78.6 geri çekilme = "deep" alım bölgesi
            val fib618 = low + (high - low) * 0.618
            val fib786 = low + (high - low) * 0.786
            PriceZone(
                top = fib618, bottom = fib786,
                type = ZoneType.DEEP_FIB, tf = tf,
                startTime = System.currentTimeMillis(),
                isBullish = lastPrice < fib618, initialStrength = 4.0,
                formationAtr = atr,
                logic = "Deep Fib Zone [61.8%=${String.format("%.4f", fib618)} 78.6%=${String.format("%.4f", fib786)}]"
            )
        } else {
            // Yükseliş: Low'dan High'a — fiyat tepede, Fib retracement
            val fib618 = high - (high - low) * 0.618
            val fib786 = high - (high - low) * 0.786
            PriceZone(
                top = fib786, bottom = fib618,
                type = ZoneType.DEEP_FIB, tf = tf,
                startTime = System.currentTimeMillis(),
                isBullish = false, initialStrength = 4.0,
                formationAtr = atr,
                logic = "Deep Fib Resistance [61.8%=${String.format("%.4f", fib618)} 78.6%=${String.format("%.4f", fib786)}]"
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  BAYESIAN GÜNCELLEME
    // ────────────────────────────────────────────────────────────────────────

    private fun updateBayesian(
        klines: List<KlineItem>,
        zones: MutableList<PriceZone>,
        atr: Double
    ) {
        zones.forEach { zone ->
            val futureKlines = klines.filter { it.openTime > zone.startTime }

            futureKlines.forEach { k ->
                val touched = if (zone.isBullish)
                    k.low <= zone.top + (atr * 0.2)    // Zone'a dokundu
                else
                    k.high >= zone.bottom - (atr * 0.2)

                if (touched) {
                    zone.testCount++

                    // Kapanış bazlı başarı/başarısızlık
                    val isSuccessful = if (zone.isBullish)
                        k.close > zone.bottom - (atr * BREACH_ATR_FACTOR)
                    else
                        k.close < zone.top + (atr * BREACH_ATR_FACTOR)

                    if (isSuccessful) {
                        zone.successfulTests++
                    } else {
                        zone.failedTests++
                        // Kapanış bazlı kırılım → mitigation
                        if (!zone.isStrict || k.close < zone.bottom || k.close > zone.top) {
                            zone.isMitigated = true
                        }
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  CONFLUENCE MARKALAMA
    // ────────────────────────────────────────────────────────────────────────

    private fun markConfluences(
        zones: MutableList<PriceZone>,
        atr: Double,
        tf: String
    ) {
        val config = getConfigForTf(tf)
        zones.forEach { it.confluenceCount = 1; it.confluenceTypes.clear(); it.confluenceTypes.add(it.type) }

        for (i in zones.indices) {
            for (j in i + 1 until zones.size) {
                val c1 = (zones[i].top + zones[i].bottom) / 2.0
                val c2 = (zones[j].top + zones[j].bottom) / 2.0
                if (abs(c1 - c2) < atr * config.clusterThreshold) {
                    zones[i].confluenceCount++
                    zones[j].confluenceCount++
                    zones[i].confluenceTypes.add(zones[j].type)
                    zones[j].confluenceTypes.add(zones[i].type)
                }
            }
        }
    }
}
