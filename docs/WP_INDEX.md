# 📂 Work Packages — İçindekiler ve Hızlı Başvuru

> **Toplam:** 22 İş Paketi | **195 KB** detaylı dokümantasyon  
> **Referans Dokümanlar:**  
> - [PROJE_ANALIZ_RAPORU.md](./PROJE_ANALIZ_RAPORU.md) — 45 hata tespiti  
> - [UYGULAMA_PLANI.md](./UYGULAMA_PLANI.md) — 5 fazlı yol haritası

---

## ⚡ FAZ 1 — KRİTİK GÜVENLİK (3-5 gün)

> Paralel çalıştırılabilir. Bağımsız paketler.

| # | İş Paketi | Hata Ref. | Dosya | Boyut |
|---|-----------|-----------|-------|-------|
| 1 | [WP-01: Güvenlik Zırhı — API Key Şifreleme](./work-packages/WP-01.md) | H-01 | `AppSettings.kt`, `AndroidManifest.xml` | 10.1 KB |
| 2 | [WP-02: Veri Koruma Kalkanı — Room Migration](./work-packages/WP-02.md) | H-02 | 4 DB dosyası | 8.4 KB |
| 3 | [WP-03: Emir Güvenliği — Duplicate Order Koruması](./work-packages/WP-03.md) | H-04, H-05 | `FuturesTrade.kt`, `ManualTradeDialog.kt` | 10.9 KB |
| 4 | [WP-04: Deadlock Çözümü — Mutex İzolasyonu](./work-packages/WP-04.md) | H-03 | `IntelligentTrade.kt` | 13.1 KB |

---

## 🛡️ FAZ 2 — STABİLİTE (7-10 gün)

> WP-04 → WP-05 → WP-07 → WP-08 sıralı. WP-06, WP-09 paralel.

| # | İş Paketi | Hata Ref. | Dosya | Boyut |
|---|-----------|-----------|-------|-------|
| 5 | [WP-05: Thread Safety Mega Paketi](./work-packages/WP-05.md) | H-08, H-09, H-10, H-11, H-44 | `Trade.kt`, `BotEngine.kt`, `KlineRepository.kt` | 8.7 KB |
| 6 | [WP-06: Ağ Dayanıklılığı — WebSocket Reconnect](./work-packages/WP-06.md) | H-06, H-31 | `BybitManager.kt`, `RetrofitClient.kt` | 7.9 KB |
| 7 | [WP-07: Veri Bütünlüğü — Cüzdan ve DB Yazma](./work-packages/WP-07.md) | H-07, H-14 | `VisualCoordinator.kt`, `BybitManager.kt` | 8.9 KB |
| 8 | [WP-08: Servis Sürekliliği — Restart Recovery](./work-packages/WP-08.md) | H-12, H-25 | `BotService.kt`, `BotEngine.kt`, `[YENİ] BootReceiver.kt` | 11.2 KB |
| 9 | [WP-09: Sayısal Güvenlik — NaN Koruması](./work-packages/WP-09.md) | H-13, H-21, H-22, H-27 | 7 motor dosyası, `TradeExecutor.kt` | 9.8 KB |

---

## 🏛️ FAZ 3 — MİMARİ YENİDEN YAPILANDIRMA (12-17 gün)

> WP-10 → WP-11 → WP-12 → WP-13/WP-14 sıralı.

| # | İş Paketi | Hata Ref. | Dosya | Boyut |
|---|-----------|-----------|-------|-------|
| 10 | [WP-10: UI Refactor — TerminalScreen Parçalama](./work-packages/WP-10.md) | H-15 | `TerminalScreen.kt` → 12+ dosya | 6.9 KB |
| 11 | [WP-11: MVVM Geçişi — ViewModel](./work-packages/WP-11.md) | H-16 | 4 yeni ViewModel | 7.4 KB |
| 12 | [WP-12: DI — Hilt Entegrasyonu](./work-packages/WP-12.md) | H-17 | `build.gradle.kts`, 3 Module, App sınıfı | 11.3 KB |
| 13 | [WP-13: Kod Temizliği — İndikatör Birleştirme](./work-packages/WP-13.md) | H-18, H-45 | `IndicatorUtils.kt`, 3 analiz dosyası | 6.5 KB |
| 14 | [WP-14: Motor Koordinasyonu — Pozisyon Yönetici](./work-packages/WP-14.md) | H-20 | `PositionManager.kt`, `BotEngine.kt` | 10.3 KB |

---

## 🔧 FAZ 4 — İYİLEŞTİRME (8-10 gün)

> Faz 2 sonrası başlayabilir. Faz 3 ile paralel çalışabilir.

| # | İş Paketi | Hata Ref. | Dosya | Boyut |
|---|-----------|-----------|-------|-------|
| 15 | [WP-15: Ağ Güçlendirme — Rate Limiter ve XSS](./work-packages/WP-15.md) | H-19, H-24 | `RateLimiter.kt`, `TradingViewChart.kt` | 10.2 KB |
| 16 | [WP-16: UI Validasyon Paketi](./work-packages/WP-16.md) | H-23, H-35-H-39 | 6 ayar ekranı | 10.2 KB |
| 17 | [WP-17: Motor Akıllılaştırma — Dinamik Ağırlıklar](./work-packages/WP-17.md) | H-30 | `IntelligentAnalyze.kt`, `LearningEngine.kt` | 8.9 KB |
| 18 | [WP-18: Hata Yönetimi Standardizasyonu](./work-packages/WP-18.md) | H-26, H-28, H-29 | Proje geneli, `AppLogger.kt` | 7.8 KB |

---

## ✨ FAZ 5 — KALİTE VE FİNAL (5-8 gün)

> Faz 3 sonrası başlayabilir.

| # | İş Paketi | Hata Ref. | Dosya | Boyut |
|---|-----------|-----------|-------|-------|
| 19 | [WP-19: Test Altyapısı Kurulumu](./work-packages/WP-19.md) | H-41 | `build.gradle.kts`, 7+ test dosyası | 7.1 KB |
| 20 | [WP-20: Erişilebilirlik ve Bildirim İyileştirmesi](./work-packages/WP-20.md) | H-32-H-34, H-40, H-42 | UI dosyaları, `NotificationCenter.kt` | 9.9 KB |
| 21 | [WP-21: Build Optimizasyonu](./work-packages/WP-21.md) | H-43 | `build.gradle.kts`, `libs.versions.toml` | 5.5 KB |
| 22 | [WP-22: Kod Arkeolojisi — Deprecated Temizlik](./work-packages/WP-22.md) | H-45 | `BacktestEngine.kt`, `PriceActionEngine.kt` | 4.3 KB |

---

## 🔗 Bağımlılık Sırası (Kritik Yol)

```
WP-01 ──┐
WP-02 ──┤ (paralel)
WP-03 ──┤
WP-04 ──┘
         ↓
WP-05 → WP-07 → WP-08 → WP-10 → WP-11 → WP-12 → WP-13 → WP-18 → WP-21
                                                    ↓         ↓
                                                  WP-14 → WP-17    WP-22
                                                    ↓
WP-01 → WP-06 → WP-15                            WP-12 → WP-19
WP-05 → WP-09
WP-10 → WP-16 → WP-20
```

---

## 📊 Nasıl Kullanılır

1. **Prompt oluşturmak için:** İlgili WP dosyasını aç, tüm içeriği prompt olarak AI ajanına ver
2. **Sıralama:** Bağımlılık zincirine uy. Faz 1'den başla, Faz 5'e doğru ilerle
3. **Paralel çalışma:** Aynı Faz içinde bağımlılığı olmayan WP'ler paralel çalıştırılabilir
4. **Doğrulama:** Her WP'nin sonundaki "Kabul Kriterleri" ile tamamlanmayı doğrula
