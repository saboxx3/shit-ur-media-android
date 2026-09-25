# 💩 Shit Ur Media - Android Video Sıkıştırıcı & Kalite Düşürücü

Videoları agresif şekilde sıkıştırmak, nostaljik çamur kalitesine çekmek veya boyut sınırı olan platformlar için optimize etmek amacıyla geliştirilmiş açık kaynaklı Android uygulaması.

**Kotlin**, **Jetpack Compose** ve **FFmpegKit** mimarisi üzerine inşa edilmiştir.

---

## ✨ Öne Çıkan Özellikler

- **Özel Hazır Ayarlar (Presets):**
  - 📟 **Nokia 3GP Vibe:** 176x144 QCIF çözünürlük, 12 FPS ve 8000 Hz mono ses. 2006'da kızılötesi veya Bluetooth ile atılmış video hissi.
  - 🥔 **144p Patates:** Piksel çorbası kıvamında ultra düşük bitrate.
  - 💬 **Discord Hedefleri (8MB & 25MB):** Discord'un dosya yükleme sınırlarına takılmadan geçmesi için dinamik olarak hesaplanan bitrate optimizasyonu.
- **Gelişmiş Ayarlar Menüsü:**
  - 🎚️ **Manuel Video & Ses Bitrate:** Kaydırma çubuklarıyla video ve ses kalitesini doğrudan belirleme.
  - 🎞️ **Dinamik FPS Tespiti:** Seçilen videonun orijinal kare hızını Android'in yerleşik `MediaExtractor` motoruyla otomatik tespit eder ve FPS slider'ının tavan değerini ona göre sınırlar.
  - ⚡ **Filtre Optimizasyonu:** Render yükünü hafifletmek için kare hızını (FPS) boyutlandırma ve efekt filtrelerinden önce kırpar.
  - 🎨 **Filtre Efektleri:** Siyah-Beyaz (`format=gray`), Aynalama (`hflip`) ve Sesi Kapatma (`-an`).
- **Uygulama İçi Oynatıcı:** İşlenen videoyu harici galeriye gitmeden, doğrudan uygulama arayüzündeki yerleşik `VideoView` bileşeniyle anında izleyebilme.

---

## 🛠️ Teknik Altyapı

- **Arayüz:** Material 3 destekli modern Jetpack Compose.
- **Medya Motoru:** `FFmpegKit` (C/C++ NDK ikilileri).
- **Geniş Cihaz Uyumluluğu:** GPL kısıtlamalarına takılmadan tüm Android varyantlarında kararlı çalışan evrensel MPEG-4 ve MediaCodec hatları.
- **Dil & Eşzamanlılık:** Kotlin Coroutines.

---

## 🚀 Projeyi Derleme & Kurulum

1. Repoyu klonlayın:
   ```bash
   git clone https://github.com/<KULLANICI_ADIN>/shit-ur-media-android.git
   ```
2. Projeyi **Android Studio** ile açın.
3. Gradle senkronizasyonunu tamamlayıp APK çıktısını alın:
   ```bash
   ./gradlew assembleDebug
   ```
4. Üretilen APK dosyası şu dizinde yer alır:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```
