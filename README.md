# Sade Ekran – minimal Google TV launcher

Siyah zemin, saat, tarih, uygulama simgeleri. Reklam yok, öneri yok, internet izni yok.

## Kullanım
- Uygulamaya OK ile gir.
- Uygulamaya **OK'a uzun bas**: Gizle/Göster ya da Uygulama bilgisi (kaldır/devre dışı).
- En alttaki "Gizlenenleri göster" ile gizlediklerini geri getir.

## APK üretme
**A) Android Studio:** klasörü aç → Build → Build APK(s). Çıktı: `app/build/outputs/apk/debug/app-debug.apk`
**B) GitHub:** klasörü yeni bir repoya yükle → Actions → "Build APK" → artifact'ı indir.

## TV'ye kurma
1. TV: Ayarlar → Sistem → Hakkında → "Derleme"ye 7 kez bas → Geliştirici seçenekleri → USB/ağ hata ayıklama aç.
2. `adb connect TV_IP:5555` → `adb install app-debug.apk`

## Ana ekran yapma
Önce Home tuşuna bas; "Sade Ekran" seçimi çıkarsa **Her zaman** de.
Çıkmazsa (çoğu Google TV'de çıkmaz), orijinal launcher'ı devre dışı bırak:

    adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
    adb shell pm disable-user --user 0 com.google.android.tvlauncher

Geri almak için aynı komutlarda `disable-user` yerine `enable` yaz:

    adb shell pm enable com.google.android.apps.tv.launcherx
    adb shell pm enable com.google.android.tvlauncher
