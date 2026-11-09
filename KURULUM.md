# Güvenlik Kamerası — Kurulum

## 1. APK'yı indir ve kur
1. https://github.com/nesseser/Kameramoly/actions → en üstteki yeşil "APK derle" → aşağıda **GuvenlikKamera-APK** → indir, zip'i aç.
2. `app-debug.apk`'yı telefona at (WhatsApp "Kendime mesaj" en kolayı), dokunup kur. Eski sürümün üstüne kurulur.

## 2. İlk açılış
1. Uygulamayı aç, PIN'i gir (varsayılan **6509**; uygulamada "PIN değiştir" ile 6+ hane yapman önerilir).
2. **Kamerayı başlat** → kamera ve bildirim izinlerine **İzin ver** (bildirim izni, cihaz onayı için gerekli).
3. Pil optimizasyonu sorusuna **İzin ver**. Xiaomi/Redmi'de: Ayarlar > Uygulamalar > Güvenlik Kamerası > Otomatik başlatma AÇ, Pil tasarrufu "Kısıtlama yok".

## 3. Bilgisayardan bağlan
1. Uygulamada yazan **https://192.168.x.x:8443** adresini aç.
2. "Bağlantınız gizli değil" çıkar → **Gelişmiş → Devam et** (sertifikayı telefon ürettiği için normal).
3. PIN'i gir → telefonda **"Yeni cihaz bağlanmak istiyor"** bildirimi çıkar → dokun, uygulama PIN'ini gir, **İzin ver**.
4. Bu bilgisayar artık onaylı; sonraki girişlerde sadece PIN yeter.

## Güvenlik
- Doğru PIN'i bilen biri bile telefondan onay vermeden giremez.
- Yalnızca ev ağından bağlanılabilir; internetten gelen bağlantılar reddedilir.
- Bağlantı şifreli (HTTPS); oturumlar bilgisayarın IP'sine ve cihazına bağlı.
- Uygulama kilitli: durdurmak, PIN'i görmek, cihaz onaylamak için PIN gerekir.
- Onaylı cihazları kaldırma, tüm oturumları kapatma, bağlantı geçmişi: uygulamada.

## Özellikler (panelde)
- Çözünürlük 480/720/1080p, kalite, gerçek kamera zoom'u, pozlama, düşük ışık modu, döndürme
- Görüntü iyileştirme: AMD FidelityFX CAS netleştirme (ekran kartında), gölge açma, parlaklık/kontrast
- Fare tekerleğiyle yakınlaştırma, sürükleyerek gezme, tam ekran
- Telegram hareket bildirimi (Chat ID otomatik bulunur), güvenlik uyarıları
- Diğer kameralar: başka telefonları ekle, hepsini tek ekranda izle
- Ana ekran widget'ı ve QR kod (uygulamada)
- Derin pil tasarrufu: kimse izlemezken kamera tamamen kapanır

Bu uygulama görüntü kaydetmez; yalnızca canlı izleme sağlar.
