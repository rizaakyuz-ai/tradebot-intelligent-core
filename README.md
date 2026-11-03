# TradeBot Intelligent Brain — Quantitative Stochastic Decision Core

A high-concurrency quantitative decision pipeline engineered in Kotlin, implementing non-linear multi-engine signal aggregation, dynamic fuzzy regime classification, and Bayesian-style empirical weight updating.

---

## Architectural Principles

### 1. Weighted Geometric Mean (WGM) Probability Resonance Engine
Conventional linear signal aggregators ($S = \sum w_i s_i$) suffer from indicator saturation and inability to handle asymmetric confidence. This architecture implements a **Weighted Geometric Mean (WGM)** probability engine:

$$\text{WGM} = \exp\left( \sum_{i=1}^n w_i \cdot \ln(v_i + 0.01) \right)$$

* **Non-Linear Dampening:** If a critical risk or structural engine yields near-zero quality ($v_i \to 0$), the geometric product naturally penalizes the overall confidence score toward neutral (50) rather than allowing high momentum to trigger a false-positive execution.
* **Bounded Output Mapping:** Scores map strictly into normalized directional bounds:
  $$\text{Score} = 50 \pm 49 \times \text{WGM}$$

### 2. Macro Market Regime Classification via Fuzzy Membership
Rather than brittle threshold-based trend indicators, `RegimeEngine.kt` computes continuous fuzzy membership functions across three orthogonal dimensions:
1. **Structural Price Action:** Swing high/low (HH/HL vs. LH/LL) structural sequences.
2. **Volume & Capital Flow:** EWMA-smoothed Chaikin Money Flow (CMF) divergence.
3. **Volatility & Momentum Asymmetry:** Dynamically adapts smoothing parameters ($\alpha$) in real-time based on normalized Average True Range (ATR).

### 3. $6 \times 6$ Phase-Weight Matrix & Online Bayesian Reputation
* **Macro Phase Awareness:** Dynamically selects weights across 6 sub-engines (Zone, Regime, Flow, Fractal, Pattern, Context) based on detected macro regimes:
  * `PREPARATION`, `STARTING`, `ACCELERATING`, `MATURE`, `EXHAUSTED`, `HYPER_DRIVE`.
* **Empirical Reputation Feedback:** Tracks real trade outcomes in SQLite Room. Engine weights dynamically adapt via an online Bayesian update rule:
  * Predictive Success: $w_i \leftarrow w_i + 0.02$
  * Predictive Failure: $w_i \leftarrow w_i - 0.03$
  * Weights are strictly bounded in $[0.5, 2.0]$ with cold-start damping.

### 4. Concurrency & Mutex Thread Isolation
* Enforces execution thread safety in asynchronous streaming contexts using **Kotlin Coroutine Mutex** primitives.
* Complete isolation between real-time WebSocket ticker streams, evaluation loops, and atomic persistence layers.
* Robust protection against NaN, infinity, and zero-variance states in floating-point calculations.

---

## Repository Structure

```text
├── src/main/kotlin/core/
│   ├── ProbabilityResonanceEngine.kt   # WGM probability aggregation & reputation matrix
│   ├── RegimeEngine.kt                 # Fuzzy regime & volatility classification
│   ├── ZoneEngine.kt                   # Dynamic support/resistance & liquidity zones
│   ├── RiskEngine.kt                   # Position sizing, drawdown bounds, capital preservation
│   ├── PatternEngine.kt                # Multi-candle structural pattern recognition
│   ├── SignalProcessor.kt              # Concurrency-safe signal routing
│   └── OutcomeTracker.kt               # Closed trade feedback & performance evaluation
└── docs/                               # Architectural blueprints & engineering specifications
```
