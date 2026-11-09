# Güvenlik Kamerası — Kurulum

Eski bir Android telefonu, bilgisayardan izlenen bir güvenlik kamerasına çevirir.
Telefon kamerayı ekran kapalıyken de çalıştırır; bilgisayara hiçbir şey kurman gerekmez,
sadece tarayıcı yeterli. İkisi de aynı Wi-Fi ağında olmalı.

## 1) APK'yı derle (tek seferlik)

Bu projeyi burada derleyemedim çünkü ortamda Android SDK ve internet yok.
Kendi bilgisayarında şöyle derliyorsun:

**Android Studio ile (en kolay):**
1. Android Studio'yu kur (içinde Android SDK gelir).
2. `File > Open` ile bu klasörü aç. İlk açılışta gerekli paketleri internetten indirir.
3. Üstten telefonu USB ile bağla veya `Build > Build APK(s)`.
4. Çıkan `app/build/outputs/apk/release/app-release.apk` dosyasını telefona at ve kur.

**Komut satırıyla:**
```
./gradlew assembleRelease        # Linux/Mac
gradlew.bat assembleRelease      # Windows
```
(Android SDK kurulu ve `ANDROID_HOME` ayarlı olmalı.)

APK debug anahtarıyla imzalı; kendi telefonuna kurman için yeterli.
"Bilinmeyen kaynaklar / bu kaynağa izin ver" uyarısına izin ver.

### Bilgisayara hiçbir şey kurmadan (GitHub ile) — alternatif

Android Studio kurmak istemezsen, derlemeyi GitHub'ın sunucusunda yaptırabilirsin:

1. github.com'da ücretsiz hesap aç, **New repository** ile yeni (private) bir depo oluştur.
2. Depoda **Add file > Upload files** ile bu klasörün TÜM içeriğini yükle
   (`.github` klasörü dahil — gizli klasör, yüklerken onu da sürükle). "Commit changes" de.
3. Üstteki **Actions** sekmesine geç. "APK derle" iş akışı otomatik başlar
   (başlamazsa seç, sağdan **Run workflow**).
4. Yeşil tik gelince o çalışmayı aç, en altta **Artifacts > GuvenlikKamera-APK**'yı indir.
   İçinden `app-debug.apk` çıkar — telefona kuracağın dosya budur.

> `.github/workflows/build.yml` dosyasının yüklendiğinden emin ol; derlemeyi tetikleyen odur.

## 2) Telefonda

1. Uygulamayı aç, kamera ve bildirim izni ver.
2. "Pil optimizasyonunu kapat"a bas (yoksa sistem arka planda kapatabilir).
   Xiaomi/Samsung/Huawei'de ayrıca "Otomatik başlatma"yı da aç.
3. "Kamerayı başlat"a bas.
4. Ekranda çıkan adresi not et: `http://<telefon-ip>:8080`
5. Artık ekranı kilitleyebilir / uygulamadan çıkabilirsin. Kamera çalışmaya devam eder.
   Durdurmak için bildirimdeki **Durdur**.

> Üstteki kamera bildirimi ve yeşil nokta Android tarafından zorunludur, kaldırılamaz.

## 3) Bilgisayarda

1. Bilgisayar telefonla **aynı Wi-Fi**'de olsun.
2. Tarayıcıda `http://<telefon-ip>:8080` adresini aç.
3. PIN gir (varsayılan **6509**, uygulamadan değiştirebilirsin).
4. Canlı görüntü, hareket hassasiyeti, ön/arka kamera ve fener panelde. (Kayıt yok, sadece canlı izleme.)

## Güvenlik

- Panel PIN ister; doğru PIN sonrası tarayıcıya 6 saat geçerli bir oturum anahtarı (çerez) verilir.
- 5 yanlış denemeden sonra o cihaz artan süreyle **geçici olarak** kilitlenir (hiçbir şey silinmez).
- Yayın yalnızca yerel ağda (Wi-Fi) erişilebilir; internete açık değildir.
- Daha güçlü istiyorsan PIN'i 6+ haneli yap.

## Kayıt

- Bu uygulama **görüntü kaydetmez**. Telefona hiçbir fotoğraf veya video yazılmaz.
- Panelde yalnızca canlı görüntü ve anlık "hareket var/yok" göstergesi vardır.

## Notlar / olası düzeltmeler

- Kod Android Studio'da açılınca birkaç küçük sürüm uyarısı çıkarsa bana söyle, düzeltirim.
- Telefonu şarjda tut. Mümkünse şarj sınırlama (ör. %80) ayarını aç.
