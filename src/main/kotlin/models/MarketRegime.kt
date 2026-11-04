package com.example.tradebot.intelligent.brain

// import androidx.room.Entity
// import androidx.room.PrimaryKey

// @Entity
data class MarketRegime(
     val id: Long = 0,
    val startTime: Long,
    var endTime: Long? = null,
    val symbol: String,
    val tf: String, // EKLENDİ: Hangi periyotta bu rejim yaşanıyor?
    
    // Rejim Karakteristikleri (Parmak İzi)
    val avgVolatility: Double,
    val trendSlope: Double,      // Fiyat eğimi
    val avgStochK: Double,       // Stoch-RSI K Ortalaması
    val avgStochD: Double,       // Stoch-RSI D Ortalaması
    val adxLevel: Double,
    val regimeType: String,      // BULLISH, BEARISH, SIDEWAYS, VOLATILE
    
    // İndikatör Verimliliği (Hangi indikatör bu rejimde ne kadar başarılıydı?)
    // JSON: {"rsi": 0.85, "macd": 0.90, "bollinger": 0.40} -> Bu rejimdeki başarı katsayıları
    var efficiencyJson: String = "{}",
    var marketDnaJson: String? = null,
    var volatilityJson: String? = null,
    
    val startPrice: Double,
    var endPrice: Double? = null,
    var priceChangePct: Double? = null,
    
    // Düzenleme ve Manuel Müdahale
    var isManual: Boolean = false,
    var isEdited: Boolean = false,
    
    // Güven Skoru (Başarılı tahminlerde artar)
    var reliability: Double = 0.5
)
