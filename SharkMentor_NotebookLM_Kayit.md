# SharkMentor Geliştirme, Mimari ve Çözüm Günlüğü (NotebookLM Kaynağı)

**Tarih:** 28 - 29 Ekim 2026  
**Proje:** SharkMentor (YKS/LGS Minimalist AI Destekli Odak ve Çalışma Koçu)  
**Platform:** Android (Kotlin, Jetpack Compose, Material3, Coroutines, Foreground Service, Gemini AI, DeepSeek)  
**Hedef Kitle / Amaç:** Bu doküman, SharkMentor projesindeki UI/UX sadeleştirmeleri, arkaplan servis mimarisi, büyük ekran/tablet optimizasyonları ve giderilen kritik hataların tüm teknik ve operasyonel detaylarını NotebookLM için kaynak olarak özetler.

---

## 1. UI/UX, Minimalizm ve Arayüz Sadeliği Değerlendirmeleri

### 1.1. Minimalist Eğitim Uygulaması Felsefesi
- **Sorun:** Öğrencinin dikkatini dağıtmayacak, bilişsel yükü en aza indirecek bir uygulama hedeflenirken; menülerde mükerrer butonlar, fazla büyük/kaba kartlar ve gizli kalan kritik işlevler oluşmuştu.
- **Uygulanan İyileştirmeler:**
  1. **Notlar Sekmesinin Erişilebilirliği ("Gizli Hazine Sendromu"):** Notlar ekranı daha önce sadece sağ üst çekmecede Zen Odası ile Ambiyans Sesleri arasına sıkışmıştı. Bir öğrencinin ders çalışırken en çok ihtiyaç duyduğu iki şey "Planı" ve "Hızlı Notları / Formülleri" olduğundan, Notlar doğrudan ana gezinme hiyerarşisine (`SharkMentorBottomBar`) ve masaüstü akışına taşındı.
  2. **Gereksiz Mükerrerliklerin Temizlenmesi:** Notlara aynı anda hamburger menüden, üst bardan, planlar sekmesinden ve alt bardan olmak üzere 4 farklı yerden giriş vardı. Bu karmaşa elenerek tek ve tutarlı bir erişim noktası belirlendi.
  3. **AI Tarayıcı & Soru Tarama Kirliliğinin Giderilmesi:** Plan sekmesinde bulunan mükerrer AI tarayıcı butonu kaldırıldı; soru çözümü ve görsel tarama işlevleri tek bir merkezde (`SharkAiScreen`) toplandı.
  4. **Buton ve Kart Boyutları:** Plan ekleme, klasör yönetimi ve görev kartları kaba/büyük tasarımdan kompakt, zarif ve parmak dostu minimalist Material3 kartlarına dönüştürüldü.
  5. **Sayaç Ekranları (Pomodoro, Derin Odak, Kronometre):** Sayaç göstergeleri modern, tipografisi güçlü ve dikkati dağıtmayan estetik halka tasarımlarıyla güncellendi.

---

## 2. Büyük Ekran ve Tablet Uyumluluğu

### 2.1. Yatay Ekranda Aşırı Genişleme Problemi
- **Sorun:** Tabletlerde (örn. Lenovo Tab M10, 1920x1200) alt gezinme çubuğu ve ana ekrandaki sayaç/puan kartları ekranın en sağına ve soluna yayılarak estetiği ve tek elle/iki elle ergonomiyi bozuyordu.
- **Çözüm:**
  - `MainScreen.kt` içerisindeki alt gezinme barı (`SharkMentorBottomBar`) ve `HomeScreen.kt` içerisindeki sayaç/hedef kartları `Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center)` içerisine alınarak `widthIn(max = 680.dp)` ile sınırlandırıldı.
  - Böylece telefonlarda tam ekran, tabletlerde ise tam ortalanmış zarif bir çalışma masası düzeni elde edildi.

### 2.2. Shark AI Cevap Baloncuğu & Genişletilmiş Çözüm Ekranı
- **Sorun:** AI yanıt baloncuğu `widthIn(max = 300.dp)` ile kısıtlı olduğundan özellikle tabletlerde ve geniş ekranlarda ekranın küçük bir köşesinde sönük ve sıkışık kalıyordu.
- **Çözüm:**
  - `SharkAiScreen.kt` içindeki AI mesaj kutusu `Modifier.weight(1f).fillMaxWidth()` haline getirilerek ekranın genişliğini dolduracak şekilde ferahlatıldı.
  - Üst kısma parlayan `Shark AI • Çözüm & Analiz` rozeti, tek dokunuşla kopyalama butonu, ayırıcı çizgi ve yüksek kontrastlı arka plan (`#F8FAFC` metin rengi, 24.sp satır aralığı) eklendi.

### 2.3. Soru Çözümleri İçin Matematik ve Fen Sembolleri (`RichChatText.kt`)
- Matematik, Fizik ve Kimya sorularının adım adım çözümlerinin anlaşılırlığını artırmak için metin işleme motoru (`RichChatText`) baştan tasarlandı:
  - **Üsler:** `x²`, `y³`, `aⁿ`, `x⁻¹`
  - **Alt İndisler:** `H₂O`, `x₁`, `aₙ`
  - **Köklü İfadeler:** `√(x)`, `³√(8)`, `ⁿ√(x)`
  - **Yunan Alfabesi:** `Δ`, `π`, `θ`, `α`, `β`, `λ`, `ω`, `σ`
  - **Matematik Operatörleri:** `×`, `·`, `÷`, `±`, `≤`, `≥`, `≠`, `≈`, `∞`, `→`, `⇄`
  - **Adım Rozetleri:** `1. Adım:`, `Çözüm:`, `Sonuç:`, `Not:`, `Dikkat:` gibi başlıklar renkli ve kalın rozetlerle görselleştirildi.
  - **Satır İçi Kod/Formül:** Ters tırnak (`` `...` ``) içerisine alınan denklemler monospace tipografi ile korundu.

---

## 3. Yapay Zeka Motoru & Token Sınırı (MAX_TOKENS) Çözümü

### 3.1. Sorun Analizi
- Uzun ve kapsamlı soru çözümlerinde Gemini API 2048 token tavanına ulaşıyor ve akış yarıda kesilerek ekranda `(Content generation stopped. Reason: MAX_TOKENS)` hatası beliriyordu.

### 3.2. Yapılan Mühendislik İyileştirmeleri
1. **Token Tavanı Artırımı:**
   - `GeminiAiFactory.kt`: `VISION_PRESET_CONFIG`, `FAST_PRESET_CONFIG`, `DEEP_PRESET_CONFIG` ve `AiTaskType` çıktı token limiti 2048'den **4096** ve **8192**'ye çıkarıldı.
   - `AiRouterRepository.kt`: `AiModelPower.FLASH` (4096 token), `AiModelPower.PRO` (8192 token) olarak güncellendi.
   - `DeepSeekClient.kt`: Akış tamamlama tavanı 4096 token'a çıkarıldı.
   - `NoteDetailViewModel.kt`: Not özetleme token tavanı 4096 yapıldı.
2. **`ResponseStoppedException` Güvenli Yakalama:**
   - Akış sırasında token sınırına ulaşılsa dahi o ana kadar üretilen tüm çözümü ve açıklamayı çöpe atmak yerine koruyan `try-catch (rse: ResponseStoppedException)` bloğu eklendi. Böylece kullanıcı yarım kalan soruda bile üretilen tüm adımları görebilir hale geldi.

---

## 4. Odak ve Ambiyans Sesleri: Arkaplan Servis Mimarisi (`ZenAudioService`)

### 4.1. Kullanıcı İhtiyacı
- Öğrenci odak sesini (Yağmur, Orman, Dalgalar vb.) açıp başka bir ekrana geçtiğinde veya telefonu kilitlediğinde sesin "gaipten gelen bir ses" gibi kontrolsüz çalması engellenmeliydi.
- Bildirim panelinde ve kilit ekranında:
  - Hangi uygulamanın çaldığı (SharkMentor logosu ve adı),
  - Çalan sesin adı ve emojisi (örn. `🌧️ Yağmur`),
  - **Duraklat / Devam Et** butonu,
  - **Durdur** butonu görünür ve tıklanabilir olmalıydı.

### 4.2. Android 14+ Foreground Service Mimarisi
- `ZenAudioService.kt` sınıfı oluşturuldu:
  - Servis tipi: `mediaPlayback`
  - Bildirim Kanalı: `shark_zen_audio_channel` (Öncelik: Low/Düşük, sessiz bildirim)
  - Intent Aksiyonları: `ACTION_PLAY`, `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_STOP`, `ACTION_UPDATE`
- `AndroidManifest.xml` izinleri:
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />`
  - `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`

---

## 5. Odak Sesleri Hata Analizi ve Nihai Çözüm (Kritik RCA)

Uygulamanın cihaza kurulmasının ardından seslerin açılmaması sorunuyla karşılaşıldı. Yapılan Logcat incelemesi ve kod analizinde 4 temel kök neden tespit edildi:

### 5.1. Kök Neden 1: Çifte Intent Çakışması (`RemoteServiceException`)
- **Hata:** `android.app.RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground()`
- **Sebep:** `ZenAudioManager.playSound()` her yeni ses çağrıldığında önce `stopSound()` çağırıyor, o da servise `ACTION_STOP` gönderiyordu. Hemen 1 milisaniye sonra `ZenAudioService.start()` ile `startForegroundService` çağrılıyordu. Android OS, ilk `stopSelf()` emriyle servisi yok ederken ikinci `startForegroundService` çağrısı servisi ayakta bulamıyor ve 5 saniye içinde `startForeground` çağrılmadığı gerekçesiyle tüm uygulamayı kilitliyordu.
- **Çözüm:** `playSound()` fonksiyonunda ses değiştirilirken arkaplan servisini kapatıp açma döngüsü kaldırıldı. Yalnızca `MediaPlayer` kaynağı değiştirildi ve servis açık kalarak yeni ses bilgisiyle güncellendi.

### 5.2. Kök Neden 2: Erken Dosya Tanımlayıcı (AFD) Kapanması
- **Hata:** `createPlayer` fonksiyonunda `openRawResourceFd` kullanılıp `afd.close()` çağrısı `prepare()` öncesinde yapılmıştı.
- **Sebep:** Android 9 (API 28) ve altı sistemlerde dosya tanımlayıcı `prepare()` çalışırken açık kalmak zorundadır; kapatıldığında `Prepare failed.: status=0x1` hatası vererek ses çalınamıyordu.
- **Çözüm:** Karmaşık `openRawResourceFd` yerine doğrudan Android'in resmi yerel `MediaPlayer.create(context, resId)` metoduna geçildi. `MediaPlayer.create()` dosya tanıtıcısını kendisi açar, `prepare()` çağırır ve güvenle kapatır.

### 5.3. Kök Neden 3: VectorDrawable / Canvas Bitmap Uyumu
- **Hata:** Android 9'da `Notification.setSmallIcon` veya `setLargeIcon` işlemlerinde `R.mipmap.ic_launcher` veya XML vektör ikonları doğrudan bitmap olarak çözülemiyordu (`BitmapFactory.decodeResource` null dönüyordu).
- **Çözüm:** Vektör ikonlar bir Canvas (`Bitmap.createBitmap` + `Canvas(bitmap)`) üzerine dinamik olarak çizdirilerek bildirim paneline aktarıldı.

### 5.4. Kök Neden 4: Ses Seviyesi (Attenuation & Volume 1/15)
- **Durum:** Test edilen tabletin fiziksel medya ses seviyesi `1/15` düzeyindeydi ve kod tarafında `0.75f` volüm çarpanı vardı (toplamda duyulamayacak fısıltı seviyesi).
- **Çözüm:** Kod tarafında `setVolume(1.0f, 1.0f)` yapıldı ve tabletin medya ses seviyesi yükseltildi.

---

## 6. Doğrulama ve Canlı Test Sonuçları

- **Derleme:** Gradle `assembleDebug` 17 saniyede sıfır hata ile tamamlandı.
- **Cihaz Kurulumu:** Fiziksel Lenovo Tab M10 (`HA14CNQ2`, Android 9) üzerine `Streamed Install -> Success` ile yüklendi.
- **Canlı Medya Durumu (`dumpsys media.player`):**
  ```text
  Client: pid(30724), status(0), looping(true)
  NuPlayer: state(6), atEOS(0), looping(1)
  AudioOutput: stream type(3), left-right volume(1.000000, 1.000000)
  AudioTrack: sample rate(48000), channel count(2), routed to SPEAKER
  ```
- **Bildirim Paneli Durumu (`dumpsys notification`):**
  - Başlık: `🌧️ Yağmur`
  - Alt Başlık: `Odak sesi çalıyor • SharkMentor`
  - Butonlar: `[0] "Duraklat"`, `[1] "Durdur"`
  - İkon: Megalodon/Shark dinamik bitmap
  - Kilit ekranında ve bildirim çubuğunda canlı olarak çalışıyor.

---

## 7. Özet ve Sonraki Adımlar

SharkMentor artık:
1. Büyük ekranlarda ve tabletlerde dengeli ve şık bir yerleşime sahip.
2. Shark AI soru çözümlerinde ekranı tam doldurarak formül ve sembolleri kusursuz render ediyor.
3. Token aşımı nedeniyle soru çözümlerinin yarıda kalması engellendi.
4. Ambiyans odak sesleri arka planda, kilit ekranında ve bildirim paneli üzerinden kesintisiz, tam ses seviyesinde ve sonsuz döngüyle çalıyor.
