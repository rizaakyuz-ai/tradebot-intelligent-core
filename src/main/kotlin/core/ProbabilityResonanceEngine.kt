package com.example.tradebot.intelligent.brain.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * IA İndikatör Motoru — V9 (Weighted Geometric Mean Architecture)
 *
 * V8 → V9 TEMEL DEĞİŞİKLİK:
 *
 * V8: Concordance (Uyum) mimarisi.
 *     Problem: Motorlar kısmen uyumlu olduğunda skor 50 civarında takılırdı.
 *     Üç motor bullish, iki motor bearish → concordanceCount = 3 → skor ≈ 62.
 *     Bu skoru alım için çok düşük, beklemek için çok yüksek bırakırdı.
 *
 * V9: Ağırlıklı Geometrik Ortalama (WGM).
 *     - Concordance artık sadece bilgi, karar mekanizması değil.
 *     - Her motor: value (0-1 kalite), direction (±1 yön), rawConfidence.
 *     - WGM = exp(Σ wᵢ × ln(valueᵢ + 0.01))   [+0.01: ln(0) engeli]
 *     - finalScore = 50 ± 49 × WGM
 *     - Tüm motorlar zayıfsa WGM küçük → skor 50'ye yakın (doğru davranış).
 *     - Tüm motorlar güçlüyse WGM büyük → skor 99'a yakın (güçlü sinyal).
 *
 * FAZ BAZLI AĞIRLIK MATRİSİ (6 faz × 6 motor):
 *     Her piyasa fazında hangi motorun daha önemli olduğunu kodlar.
 *     HYPER_DRIVE'da Flow ağırlığı yüksek (momentum sürüyor).
 *     EXHAUSTED'da Zone ağırlığı yüksek (destek/direnç kritik).
 *
 * MOTOR İTİBARI (Reputation):
 *     Her motor geçmiş trade sonuçlarına göre ağırlık alır.
 *     Cold-start: ilk 10 trade reputation değişmez.
 *     Doğru yön: +0.02, Yanlış yön: -0.03. Sınır: [0.5, 2.0].
 */
object ProbabilityResonanceEngine {

    // ── V9: Motor girdi veri yapısı ─────────────────────────────────────────

    data class MotorInput(
        val name: String,
        val value: Double,          // 0.0 .. 1.0 — sinyal kalitesi / gücü
        val direction: Double,      // -1.0 veya +1.0 — kesin yön
        val rawConfidence: Double,  // 0.0 .. 1.0 — bu tahmine güven (bilgi amaçlı)
        val score: Double = 50.0   // 1-99 arası motor skoru
    )

    // ── Faz × Motor ağırlık matrisi ──────────────────────────────────────────
    // Motorlar: Zone, Regime, Flow, Fractal, Pattern, Context
    private val PHASE_WEIGHTS: Map<RegimeEngine.RegimePhase, Map<String, Double>> = mapOf(
        RegimeEngine.RegimePhase.PREPARATION to mapOf(
            "Zone" to 0.30, "Regime" to 0.20, "Flow" to 0.15,
            "Fractal" to 0.20, "Pattern" to 0.10, "Context" to 0.05
        ),
        RegimeEngine.RegimePhase.STARTING to mapOf(
            "Zone" to 0.25, "Regime" to 0.25, "Flow" to 0.15,
            "Fractal" to 0.20, "Pattern" to 0.10, "Context" to 0.05
        ),
        RegimeEngine.RegimePhase.ACCELERATING to mapOf(
            "Zone" to 0.15, "Regime" to 0.30, "Flow" to 0.25,
            "Fractal" to 0.15, "Pattern" to 0.10, "Context" to 0.05
        ),
        RegimeEngine.RegimePhase.MATURE to mapOf(
            "Zone" to 0.20, "Regime" to 0.25, "Flow" to 0.20,
            "Fractal" to 0.15, "Pattern" to 0.15, "Context" to 0.05
        ),
        RegimeEngine.RegimePhase.EXHAUSTED to mapOf(
            "Zone" to 0.35, "Regime" to 0.15, "Flow" to 0.20,
            "Fractal" to 0.10, "Pattern" to 0.15, "Context" to 0.05
        ),
        RegimeEngine.RegimePhase.HYPER_DRIVE to mapOf(
            "Zone" to 0.10, "Regime" to 0.20, "Flow" to 0.35,
            "Fractal" to 0.10, "Pattern" to 0.10, "Context" to 0.15
        )
    )

    // ── Motor itibar sistemi ─────────────────────────────────────────────────
    private val motorReputation = ConcurrentHashMap<String, Double>()
    private val motorTradeCount = ConcurrentHashMap<String, Int>()

    // ── EMA smoothing ve eğim tarihçesi ─────────────────────────────────────
    private val smoothedScores = ConcurrentHashMap<String, Double>()
    private val scoreHistory = HashMap<String, MutableList<Double>>()
    private const val SLOPE_WINDOW = 3

    // ── Sigmoid yardımcı fonksiyonu ──────────────────────────────────────────
    private fun sigmoid(x: Double, k: Double, x0: Double): Double =
        1.0 / (1.0 + exp(-k * (x - x0)))

    // ── ResonanceResult — V8 alanları korunuyor (geriye uyumluluk) ──────────

    data class ResonanceResult(
        // ── Ana çıktı ──────────────────────────────────────────────────
        val resonanceScore: Double,
        val slope: Double,

        // ── Concordance metrikleri (V9'da bilgi amaçlı) ───────────────
        val concordanceCount: Int,
        val directionAgreement: Double,
        val agreementQuality: Double,
        val concordanceBoost: Double,

        // ── Entropy ve Coherence ───────────────────────────────────────
        val entropy: Double,
        val coherence: Double,

        // ── Kontrol ────────────────────────────────────────────────────
        val isVetoed: Boolean,
        val vetoReason: String? = null,
        val fuelMultiplier: Double = 1.0,

        // ── Pozisyon boyutu ────────────────────────────────────────────
        val positionSizeMultiplier: Double = 1.0,
        val riskRewardRatio: Double = 0.0,

        // ── Faz bilgisi ────────────────────────────────────────────────
        val phase: RegimeEngine.RegimePhase = RegimeEngine.RegimePhase.ACCELERATING,

        // ── Debug bilgileri ────────────────────────────────────────────
        val engineWeights: Map<String, Double> = emptyMap(),
        val majorityDirection: Double = 0.0,

        // ── V9 eklentileri ─────────────────────────────────────────────
        val motorInputs: Map<String, Double> = emptyMap(),
        val concordantMotors: List<String> = emptyList(),
        val discordantMotors: List<String> = emptyList()
    )

    // ────────────────────────────────────────────────────────────────────────
    //  ANA FONKSİYON — V9 Weighted Geometric Mean
    // ────────────────────────────────────────────────────────────────────────

    fun calculateResonance(
        symbol: String,
        motorInputs: List<MotorInput>,
        phase: RegimeEngine.RegimePhase,
        volZ: Double = 0.0,
        isFireExit: Boolean = false,
        stopZone: Double = 0.0,
        targetZone: Double = 0.0,
        currentPrice: Double = 0.0,
        zoneMultiplier: Double = 1.0
    ): ResonanceResult {

        if (isFireExit) return vetoResult("FIRE_EXIT", phase, symbol)
        if (motorInputs.isEmpty()) return neutralResult(phase, symbol)

        val baseWeights = PHASE_WEIGHTS[phase] ?: PHASE_WEIGHTS[RegimeEngine.RegimePhase.ACCELERATING]!!

        // Reputation-adjusted weights (cold-start: ilk 10 trade'de rep = 1.0)
        val adjustedWeights = motorInputs.map { motor ->
            val count = motorTradeCount[motor.name] ?: 0
            val rep = if (count < 10) 1.0 else (motorReputation[motor.name] ?: 1.0)
            val base = baseWeights[motor.name] ?: (1.0 / motorInputs.size)
            motor to (base * rep)
        }

        // Normalize (toplam = 1.0 olsun)
        val totalW = adjustedWeights.sumOf { it.second }.coerceAtLeast(1e-10)
        val normalized = adjustedWeights.map { (m, w) -> m to w / totalW }

        // Çoğunluk yönü: confidence ağırlıklı yönsel ortalama
        val weightedDir = normalized.sumOf { (m, w) -> m.direction * w * m.rawConfidence }
        val majorityDir = when {
            weightedDir > 0.01  ->  1.0
            weightedDir < -0.01 -> -1.0
            else                ->  0.0
        }

        // Concordant / discordant motorlar
        val concordantMotors = if (majorityDir == 0.0) emptyList() else
            normalized.filter { (m, _) -> m.direction * majorityDir > 0.0 }.map { it.first.name }
        val discordantMotors = if (majorityDir == 0.0) emptyList() else
            normalized.filter { (m, _) -> m.direction * majorityDir < 0.0 }.map { it.first.name }

        // ── Ağırlıklı Geometrik Ortalama (WGM) ──────────────────────
        var wgmLogSum = 0.0
        normalized.forEach { (motor, w) ->
            val safeScore = motor.score.coerceIn(1.0, 99.0)
            wgmLogSum += w * ln(safeScore)
        }
        val rawScore = exp(wgmLogSum).coerceIn(1.0, 99.0)

        // ── Concordance: score > 60 bullish, score < 40 bearish ──────────────
        val bullishMotors = motorInputs.filter { it.score > 60 }
        val bearishMotors = motorInputs.filter { it.score < 40 }
        val concordanceCount = if (bullishMotors.size >= bearishMotors.size)
            bullishMotors.size else bearishMotors.size

        // ── Direction agreement ───────────────────────────────────────────────
        val directionAgreement = abs(weightedDir).coerceIn(0.0, 1.0)

        // ── Entropy: motorlar arası std sapma (0-1 normalize) ────────────────
        val motorScores = motorInputs.map { it.score }
        val scoreAvg = motorScores.average()
        val scoreStd = sqrt(motorScores.map { (it - scoreAvg).pow(2) }.average())
        val entropy = (scoreStd / 49.0).coerceIn(0.0, 1.0)

        // ── Coherence: concordant motorların min/max değer oranı ─────────────
        val concordantValues = motorInputs.filter { it.name in concordantMotors }.map { it.value }
        val coherence = when {
            concordantValues.size >= 2 ->
                (concordantValues.min() / concordantValues.max().coerceAtLeast(1e-8)).coerceIn(0.0, 1.0)
            concordantValues.size == 1 -> 0.5
            else -> 0.0
        }

        // ── Agreement quality ─────────────────────────────────────────────────
        val agreementQuality = directionAgreement.coerceIn(0.0, 1.0)

        // ── Veto kontrolü ─────────────────────────────────────────────────────
        val isVetoed = entropy > 0.85 || concordanceCount < 2
        val vetoReason: String? = if (entropy > 0.85) "EXTREME_ENTROPY" else if (concordanceCount < 2) "LOW_CONCORDANCE" else null

        val adjustedScore = when {
            isVetoed && vetoReason == "EXTREME_ENTROPY" -> rawScore.coerceIn(30.0, 70.0)
            isVetoed -> rawScore.coerceIn(25.0, 60.0)
            else -> rawScore
        }

        // ── EMA smoothing ─────────────────────────────────────────────────────
        val alpha = when {
            volZ > 1.5  -> 0.4
            volZ < -0.5 -> 0.2
            else        -> 0.3
        }
        val prevSmoothed = smoothedScores[symbol] ?: adjustedScore
        val smoothed = (alpha * adjustedScore + (1.0 - alpha) * prevSmoothed).coerceIn(1.0, 99.0)
        smoothedScores[symbol] = smoothed

        // ── Eğim hesabı ───────────────────────────────────────────────────────
        val history = scoreHistory.getOrPut(symbol) { mutableListOf() }
        history.add(smoothed)
        if (history.size > 30) history.removeAt(0)
        val slope = if (history.size >= SLOPE_WINDOW) {
            val recent = history.takeLast(SLOPE_WINDOW)
            (recent.last() - recent.first()) / (SLOPE_WINDOW - 1).toDouble()
        } else 0.0

        // ── Pozisyon boyutu çarpanı ───────────────────────────────────────────
        val posMultiplier = (when {
            concordanceCount >= 5 -> 1.40
            concordanceCount == 4 -> 1.20
            concordanceCount == 3 -> 1.00
            concordanceCount == 2 -> 0.70
            else                  -> 0.50
        } * zoneMultiplier).coerceIn(0.30, 1.50)

        // ── Risk/ödül oranı ───────────────────────────────────────────────────
        val potentialRisk   = abs(currentPrice - stopZone).coerceAtLeast(1e-8)
        val potentialReward = abs(targetZone - currentPrice).coerceAtLeast(1e-8)
        val rrRatio = potentialReward / potentialRisk

        // ── Fuel multiplier (geriye uyumluluk) ────────────────────────────────
        val fuelMult = when {
            isVetoed           -> 0.0
            concordanceCount <= 1 -> 0.20
            concordanceCount == 2 -> 0.50
            else               -> 1.0
        }

        // ── Concordance boost (geriye uyumluluk alanı) ───────────────────────
        val concordanceBoost = when {
            concordanceCount >= 5 -> 1.40
            concordanceCount == 4 -> 1.20
            concordanceCount == 3 -> 1.00
            concordanceCount == 2 -> 0.70
            else                  -> 0.40
        }

        val engineWts = normalized.associate { (m, w) -> m.name to w * 100.0 }
        val motorInputsMap = motorInputs.associate { it.name to it.value }

        return ResonanceResult(
            resonanceScore         = smoothed,
            slope                  = slope,
            concordanceCount       = concordanceCount,
            directionAgreement     = directionAgreement,
            agreementQuality       = agreementQuality,
            concordanceBoost       = concordanceBoost,
            entropy                = entropy,
            coherence              = coherence,
            isVetoed               = isVetoed,
            vetoReason             = vetoReason,
            fuelMultiplier         = fuelMult,
            positionSizeMultiplier = posMultiplier,
            riskRewardRatio        = rrRatio,
            phase                  = phase,
            engineWeights          = engineWts,
            majorityDirection      = majorityDir,
            motorInputs            = motorInputsMap,
            concordantMotors       = concordantMotors,
            discordantMotors       = discordantMotors
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    //  MOTOR İTİBAR GÜNCELLEMESİ
    // ────────────────────────────────────────────────────────────────────────

    fun updateReputation(motorName: String, wasCorrect: Boolean) {
        val count = motorTradeCount.merge(motorName, 1, Int::plus) ?: 1
        if (count < 10) return  // Cold-start: ilk 10 trade'de güncelleme yok
        val current = motorReputation.getOrDefault(motorName, 1.0)
        val delta = if (wasCorrect) 0.02 else -0.03
        motorReputation[motorName] = (current + delta).coerceIn(0.5, 2.0)
    }

    // ────────────────────────────────────────────────────────────────────────
    //  GERİYE DÖNÜK UYUMLULUK — ConcordanceInputs adaptörü
    // ────────────────────────────────────────────────────────────────────────

    @Deprecated("MotorInput bazlı calculateResonance kullanın")
    data class MotorSignal(
        val name: String,
        val direction: Double,
        val intensity: Double,
        val confidence: Double
    )

    @Deprecated("MotorInput bazlı calculateResonance kullanın")
    data class ConcordanceInputs(
        val symbol: String,
        val signals: List<MotorSignal>,
        val phase: RegimeEngine.RegimePhase,
        val isFireExit: Boolean = false,
        val stopZone: Double = 0.0,
        val targetZone: Double = 0.0,
        val currentPrice: Double = 0.0,
        val successRate: Double = 50.0,
        val zoneMultiplier: Double = 1.0,
        val calibrationSlope: Double = 1.0,
        val calibrationOffset: Double = 0.0,
        val volZ: Double = 0.0
    )

    @Deprecated("MotorInput bazlı calculateResonance kullanın")
    fun calculateResonance(inputs: ConcordanceInputs): ResonanceResult {
        val converted = inputs.signals.map { s ->
            MotorInput(
                name = s.name,
                value = (s.intensity * s.confidence).coerceIn(0.0, 1.0),
                direction = if (s.direction >= 0.0) 1.0 else -1.0,
                rawConfidence = s.confidence.coerceIn(0.0, 1.0)
            )
        }
        return calculateResonance(
            symbol        = inputs.symbol,
            motorInputs   = converted,
            phase         = inputs.phase,
            volZ          = inputs.volZ,
            isFireExit    = inputs.isFireExit,
            stopZone      = inputs.stopZone,
            targetZone    = inputs.targetZone,
            currentPrice  = inputs.currentPrice,
            zoneMultiplier = inputs.zoneMultiplier
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    //  GERİYE DÖNÜK UYUMLULUK — EngineScores adaptörü (BacktestEngine için)
    // ────────────────────────────────────────────────────────────────────────

    @Deprecated("Yeni ConcordanceInputs bazlı calculateResonance kullanın")
    data class EngineScores(
        val regime: Double, val regimeConf: Double = 1.0,
        val zone: Double, val zoneConf: Double = 1.0,
        val pattern: Double, val patternConf: Double = 1.0,
        val fractal: Double, val fractalConf: Double = 1.0,
        val learning: Double, val learningConf: Double = 1.0,
        val risk: Double, val riskConf: Double = 1.0,
        val volume: Double = 50.0, val volumeConf: Double = 1.0
    )

    @Deprecated("Yeni ConcordanceInputs bazlı calculateResonance kullanın")
    fun calculateResonance(
        scores: EngineScores,
        experience: LearningEngine.ExperienceResult,
        fuelLevel: Double,
        apsis: Double,
        volZ: Double,
        phase: RegimeEngine.RegimePhase = RegimeEngine.RegimePhase.ACCELERATING,
        isRefuelZone: Boolean = false,
        isDeepFibZone: Boolean = false
    ): ResonanceResult {
        val signals = listOf(
            MotorSignal("Regime",
                direction  = (scores.regime - 50.0) / 50.0,
                intensity  = abs(scores.regime - 50.0) / 50.0,
                confidence = scores.regimeConf.coerceIn(0.0, 1.0)
            ),
            MotorSignal("Zone",
                direction  = (scores.zone - 50.0) / 50.0,
                intensity  = abs(scores.zone - 50.0) / 50.0,
                confidence = scores.zoneConf.coerceIn(0.0, 1.0)
            ),
            MotorSignal("Pattern",
                direction  = (scores.pattern - 50.0) / 50.0,
                intensity  = abs(scores.pattern - 50.0) / 50.0,
                confidence = scores.patternConf.coerceIn(0.0, 1.0)
            ),
            MotorSignal("Fractal",
                direction  = (scores.fractal - 50.0) / 50.0,
                intensity  = abs(scores.fractal - 50.0) / 50.0,
                confidence = scores.fractalConf.coerceIn(0.0, 1.0)
            ),
            MotorSignal("Flow",
                direction  = (scores.volume - 50.0) / 50.0,
                intensity  = abs(scores.volume - 50.0) / 50.0,
                confidence = scores.volumeConf.coerceIn(0.0, 1.0)
            )
        )
        return calculateResonance(
            ConcordanceInputs(
                symbol    = "legacy",
                signals   = signals,
                phase     = phase,
                volZ      = volZ,
                stopZone  = 0.97,
                targetZone = 1.06,
                currentPrice = 1.0,
                successRate = experience.successRate.coerceIn(30.0, 90.0)
            )
        )
    }

    // ── Yardımcı: Veto ve nötr sonuçlar ─────────────────────────────────────

    private fun vetoResult(reason: String, phase: RegimeEngine.RegimePhase, symbol: String): ResonanceResult {
        val history = scoreHistory.getOrPut(symbol) { mutableListOf() }
        val score = if (reason == "FIRE_EXIT") 10.0 else 35.0
        history.add(score)
        if (history.size > 30) history.removeAt(0)
        return ResonanceResult(
            resonanceScore = score, slope = 0.0,
            concordanceCount = 0, directionAgreement = 0.0,
            agreementQuality = 0.0, concordanceBoost = 0.40,
            entropy = 1.0, coherence = 0.0,
            isVetoed = true, vetoReason = reason,
            fuelMultiplier = 0.0, positionSizeMultiplier = 0.30,
            phase = phase
        )
    }

    private fun neutralResult(phase: RegimeEngine.RegimePhase, symbol: String): ResonanceResult {
        val history = scoreHistory.getOrPut(symbol) { mutableListOf() }
        history.add(50.0)
        if (history.size > 30) history.removeAt(0)
        return ResonanceResult(
            resonanceScore = 50.0, slope = 0.0,
            concordanceCount = 0, directionAgreement = 0.0,
            agreementQuality = 0.0, concordanceBoost = 0.40,
            entropy = 0.5, coherence = 0.0,
            isVetoed = false, fuelMultiplier = 0.5,
            positionSizeMultiplier = 1.0, phase = phase
        )
    }
}
