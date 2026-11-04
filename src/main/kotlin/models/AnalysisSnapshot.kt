package com.example.tradebot.intelligent.brain

// import androidx.room.Entity
// import androidx.room.PrimaryKey

// @Entity
data class AnalysisSnapshot(
     val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val symbol: String,
    val finalScore: Double,
    val decision: String, // BUY, SELL, HOLD
    val lastPrice: Double,

    // --- GÖLGE VE KAPTAN MİMARİSİ ---
    var intentDecision: String? = null, // Botun asıl niyeti (Strateji engellemeden önce)
    var vetoReason: String? = null,     // Eğer işlem engellendiyse nedeni (HypeLock, BTCGuard vb.)
    var isCaptainOverride: Boolean = false, // Kaptan (Learning) kararı mı?

    // --- YENİ: DAHİCE HEDEFLER (BOT'UN NİYETİ) ---
    var targetPrice: Double? = null,    // Dinamik Hedef (TP)
    var stopPrice: Double? = null,      // Dinamik Stop (SL)
    var primaryReason: String? = null,  // Analizi tetikleyen ana sinyal (Örn: Unicorn 3.0)
    var timeHorizon: Int? = null,       // Beklenen sonuçlanma süresi (Mum bazlı)
    var marketDnaJson: String? = null,  // O anki piyasa parmak izi (ADX, Chop, Kijun vb.)

    // Derinlemesine veri setleri (JSON String)
    val featureDataJson: String,    // İndikatörler (RSI, MACD vb.)
    val marketContextJson: String,  // BTC Trend, Dominance, Market Mode
    val volatilityJson: String,     // ATR, Volatilite değerleri
    
    // Takip ve Sonuç Verileri
    var outcomePrice: Double? = null,
    var successRating: Double? = null, // -1.0 (Tam Başarısız) ile 1.0 (Tam Başarılı) arası
    var analysisLog: String? = null,    // AI'nın bu işlem hakkındaki sonradan yaptığı yorum
    
    // Bu analizin dersi çıkarıldı mı?
    var isLearned: Boolean = false,
    
    // --- IA OSCILLATOR (v8.0) ---
    var iaOscillator: Double = 50.0,
    var iaSlope: Double = 0.0,
    var signalMarker: String? = null, // "B", "S"
    
    // Her periyodun (TF) ayrı IA OSC skoru (JSON Map<String, Double> formatında)
    var tfScoresJson: String? = null
)
