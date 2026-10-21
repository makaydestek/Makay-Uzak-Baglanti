# MAKAY Uzaktan Bağlantı

Windows bilgisayarını telefondan uzaktan yönetmek için tek yönlü uzaktan
bağlantı uygulaması. Bilgisayar ekranını telefonda görür, dokunarak kontrol
eder, dosya gönderir ve yazışır.

- **Paket adı:** `com.makay.remote`
- **Sürüm:** `VERSION.txt` (şu an `1.0.53`)
- **Gereksinim:** Android 8.0 (API 26) ve üzeri
- **Eşlenik masaüstü tarafı:** `Makay AIO` → *Uzak Bağlantı* (`apps/remote`)

İki uygulama **aynı iletişim protokolünü** kullanır: aynı oda adı, aynı şifre
doğrulaması, aynı mesaj tipleri. Oda her iki cihazdan da açılabilir (aşağıda
[Uyum](#windows-masaüstü-ile-uyum) tablosu).

---

## Özellikler

| Özellik | Durum |
|---|---|
| Bağlantı kurma (oda + şifre) | ✅ her iki cihazdan |
| Bilgisayar ekranını telefonda görme | ✅ |
| **Bilgisayarı telefondan dokunarak kontrol etme** | ✅ |
| Dosya gönderimi | ✅ iki yönde |
| Sohbet | ✅ |
| Pano (panodan kopyala/yapıştır) | ✅ iki yönde, alıcı onayı ister |
| Mikrofon durumu | ✅ |
| Hoparlör yönlendirme | ✅ |
| Telefonda ekran paylaşımı (yalnız izleme) | ✅ |
| Telefonu bilgisayardan kontrol etme | ❌ **bilinçli olarak kaldırıldı** |

---

## İndireceğiniz APK

Her GitHub Release'de **tek bir APK** vardır:

| APK | Ne işe yarar | Kurulum |
|---|---|---|
| `MakayRemote_vX.Y.Z.apk` | Uygulamanın tamamı: ekran paylaşımı, dosya, sohbet, pano, mikrofon/hoparlör **ve uzak kontrol.** | ✅ Diğer Makay uygulamalarıyla **aynı şekilde sorunsuz kurulur** |

**Neden tek APK?** Android, bir uygulamanın kendi penceresi dışında girdi
enjekte etmesine yalnızca erişilebilirlik servisiyle izin verir (`INJECT_EVENTS`
sistem imzası gerektirir ve uygulamalara hiç verilmez). Google ise
erişilebilirlik **bildiren** yandan yükleme yapılan uygulamaları "uyarı +
Yine de kur" düzeyinde değil, **kurulmasına kadar** engeller. Bu yüzden
uygulama bilerek **hiçbir erişilebilirlik servisi bildirmez** — diğer Makay
uygulamalarıyla birebir aynı kurulum deneyimi budur.

Bu kararın bedeli tek şeydir: **telefonu bilgisayardan yönetme özelliği
yoktur.** Uygulama tek yönde çalışır — *bilgisayar telefondan yönetilir.*

İndirme: [GitHub Releases](https://github.com/makaydestek/Makay-Uzak-Baglanti/releases)

---

## Hızlı başlangıç

Bağlantı **iki cihaz** üzerinden kurulur: biri odayı açar (ekranını paylaşan
taraf), diğeri odaya katılır.

### 1. Oda aç (ekranını paylaşacak taraf)

1. Uygulamayı aç → **Oda Aç**
2. Ekrana şu **davet metni** gelir:

   ```text
   MAKAY Uzaktan Bağlantı
   Kimlik: 0DH8 H0C0
   Şifre: 483920
   ```

3. Bu metni karşı tarafa gönderin (WhatsApp, e-posta, kopyala-yapıştır…)
   — **Paylaş** düğmesi metni panoya kopyalar.

**Davet metninin üç parçası:**

| Parça | Ne? |
|---|---|
| **Kimlik** | Oda kodu — 8 hane, `0-9` ve `A-Z` (halüsinasyonu önlemek için `I` ve `O` **yoktur**). Görüntülenirken `XXXX XXXX` şeklinde dörderli ayrılır. |
| **Şifre** | 6 haneli rastgele oturum şifresi (`100000`–`999999`). Her oda açılışında yeniden üretilir. |
| Oda kimliği | Şifre birleştirilip hesaplanır; şifreyi bilmeyen odaya **giremez**. |

> Kimlik ve şifre birlikte gönderilmezse bağlantı kurulamaz. Kod **8 hane** değilse
> uygulama "8 haneli partner kimliğini girin" uyarısı verir.

### 2. Odaya katıl (kontrol eden taraf)

1. Uygulamayı aç → **Odaya Katıl**
2. **Davet metnini yapıştırın** — kimlik ve şifre otomatik ayrıştırılır.
   Yalnızca kodu elle yazacaksanız:
   - **Kimlik:** `0DH8 H0C0` (boşluk/tire önemli değil)
   - **Şifre:** `483920`
3. **Bağlan**

Uygulama aşağıdakileri de kabul eder: `Bağlantı Kodu: …`, `Davet Kodu: …`
veya düzcil `0DH8H0C0` / `0DH8 H0C0`.

### 3. Onay

Oda sahibinin ekranına **"… bağlanmak istiyor"** isteği düşer →
**İzin Ver** ile bağlantı kurulur.

Bağlantı, eşleşme ağı (MQTT) üzerinden eşleşilir, ardından **doğrudan P2P**
(WebRTC) kurulur. Ağ tünelleri kurulamazsa otomatik olarak aktarma sunucusu
(STUN/TURN) denenir.

---

## Uzaktan kontrol (Android → Windows)

Odayı açan taraf ekranını paylaşır (genelde Windows); odaya **katılan** taraf
ekranı görür ve **ekrana dokunarak bilgisayarı yönetir.** Bu uygulamanın ana
destek senaryosudur ve **hiçbir özel izin gerektirmez.**

**Kontrol akışı:**

1. Telefonda **Kontrol İste** düğmesine dokunun
2. Kontrol **anında** etkinleşir — bilgisayar tarafı onay adımı beklemez
   (odayı açan taraf zaten izin vermiş sayılır)
3. Ekrana dokunun / sürükleyin
4. Bırakmak için **Kontrolü Bırak**

**Ne yapılır:**

| Telefon hareketi | Bilgisayarda |
|---|---|
| Dokunma | Sol tık |
| Sürükleme | Basılı sol tıkla sürükleme (pencere taşıma, alan seçimi) |

**Sınırlar:** Yalnızca fare; klavye girdisi yoktur, kaydırma (tekerlek) ve
sağ tık henüz yoktur.

> Telefon → bilgisayar yönünde ekran paylaşımını da tersine çevirebilirsiniz:
> telefon **Oda Aç** ile kendi ekranını paylaşır, bilgisayar yalnızca
> **izler.** (Telefonu bilgisayardan yönetme özelliği bilinçli olarak
> kaldırılmıştır — bkz. [İndireceğiniz APK](#indireceğiniz-apk).)

---

## Pano (panodan metin)

**Bilgisayardan telefona:** Windows'ta **Pano Gönder** → telefonda önizlemeli
onay penceresi çıkar; **Panoya Yaz** ile Android panosuna geçer,
**Reddet** ile atlanır.

**Telefondan bilgisayara:** Telefonda **Pano** düğmesi → kendi panonuzdaki metin
karşı tarafa gönderilir; Windows'ta aynı onay penceresi görünür.

---

## Mikrofon ve Hoparlör

Telefon tarafında **telefon görüşmesindeki gibi iki düğme** vardır:

| Düğme | Ne yapar |
|---|---|
| **Mikrofon** | Telefonun mikrofonunu açar/kapatır. İlk basışta Android izni (`RECORD_AUDIO`) bir kez istenir. **Varsayılan kapalıdır** — mikrofon izinsiz açılmaz. |
| **Hoparlör** | Sesin hangi çıkıştan verileceğini değiştirir. **Yalnızca düğmeye bastığınızda** yön değişir; dokunulmazsa mevcut davranış aynen korunur. |

Mikrofon açıldığında ses, WebRTC üzerinden karşı tarafa gider (donanım
**yankı bastırma + gürültü engelleme** açıktır). Her iki tarafın mikrofon
**durumu yayılır**, fakat kimse karşı tarafın mikrofonunu kapatamaz —
bu yalnızca bilgi gösterimidir (karşı tarafın düğmesi sadece kendi
cihazını etkiler).

---

## Dosya gönderimi ve sohbet

- **Dosya Gönder / Pano** düğmesi ile dosya seçin. Karşı taraf **İndir** ile
  onay verir, dosya `DownloadManager` ile İndirilenler'e yazılır.
- **Sınır:** 100 MB, Windows'ta çalıştırılabilir dosya türleri
  (`.exe .bat .cmd .ps1 .msi .scr .com .vbs .js .dll` …) **gönderilemez**.
- Sohbet mesajları bağlantının tamamı boyunca saklanır.

---

## Windows masaüstü ile uyum

İki taraf da aynı **tel (wire)** biçimini konuşur:

| Alan | Değer |
|---|---|
| Eşleşme ağı | `makay/aio/v1/<ODA>` (MQTT / WSS); davet metninde `Sinyal: <adres>` satiri **varsa** HTTP sinyal sunucusu (Cloudflare tüneli / LAN `api/join|poll|send`) kullanılır |
| Brokerler | `broker.emqx.io:8084`, `test.mosquitto.org:8081` (MQTT-5; Windows köprüsü MQTT 3.1.1 ile `broker.hivemq.com`'u da dener — Android'de MQTT-5 CONNACK vermediği için listede yok) |
| Zarf | `{v, pinHash, ts, from, t}` |
| Doğrulama | `hello` mesajında `sha256(sifre)` |
| Mesaj tipleri | `hello, text, sdp, ice, bye, ctrl-req/ok/deny/stop, ctrl, clip, mic, video-stop, file-meta, file-done, file-cancel, chunk` |
| Oda alfabesi | `0123456789ABCDEFGHJKLMNPQRSTUVWXYZ` |

Mesaj adları `WireEnvelope.kt` içinde sabittir ve birim testleri
(`WireEnvelopeTest`) bunları Windows `engine.js` karşılıklarıyla birebir
doğrular.

---

## Güvenlik

- Oda şifresi her açılışta `SecureRandom` ile üretilir.
- Bağlantı doğrulaması `sha256(şifre)` üzerinden yapılır; eşleşmeyen oturum
  kapatılır.
- Gelen **tüm** kontrol olayları `RemoteGuard` sınırından geçer:
  koordinatlar `0..1` (veya ≤ 16000 piksel), tekerlek `±40`, tuş adı
  1–32 karakter ve JSON ayracı içermez. Geçersiz olay **atılır**.
- Dosya adı/boyut/tür denetimi (`RemoteGuard.validateFileTransfer`).
- Pano içeriği her iki tarafta da **onay olmadan** yazılmaz.
- Release imzası: RSA 2048 / JKS, alias `makay-remote`.

> **Bilinen uyarı:** `v1.0.2` ve öncesi debug imzasıyla kurulmuş cihazlarda
> üretim anahtarına geçiş nedeniyle yerinde güncelleme çalışmaz — uygulamayı
> bir kez kaldırıp yeniden kurmak gerekir.

---

## Derleme ve test

```bat
:: birim testleri (102 test x debug/release varyantlari)
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:testReleaseUnitTest

:: APK (imza local.properties'teki RELEASE_* anahtarlarindan gelir)
gradlew.bat :app:assembleRelease
```

`assembleRelease` tek basina calistirildiginda `publishReleaseApk` gorevi APK'yi
uretip `APK\` klasorune yazar (ayrica `.apk.sha256` + R8 `mapping.txt`).
Uygulama **tek derleme tipidir** (`release`); hicbir ozel izin bildirmez.

Raporlar: `app\build\reports\tests\` ve `app\build\test-results\`.
CI her push'ta `.github/workflows/ci.yml` ile kosar.

**Kaynak kod yolları:** release derlemesinde kaynak yolları kısaltılır ve PNG
yeniden kodlanır; bu yüzden kaynakla hash **farklı** olabilir. Doğrulama
görsel/karşılaştırmalıdır.

---

## Yayınlama

Tek komut — **`APK-Hazirla.bat`**:

| Adım | Ne olur |
|---|---|
| 1 | Açık (commit'lenmemiş) değişiklikler commit edilir |
| 2 | `VERSION.txt` otomatik artar (`1.0.9` → `1.0.10`) |
| 3 | `gradlew assembleRelease` → `APK\…v1.0.11.apk`; `.apk.sha256` + `mapping.txt` ile birlikte |
| 4 | Sürüm commit'i oluşturulur ve `origin main`'e itilir |
| 5 | GitHub Release açılır, **3 asset** yüklenir (APK + SHA + mapping) |
| 6 | Eski release'ler + etiketleri silinir, yalnızca yenis kalır |

Yalnızca `VERSION.txt` commit'lenip push edilir; APK ve derleme çıktıları
`.gitignore` nedeniyle depoya girmez. **Keystore, `local.properties`, `*.jks`
ve APK hiçbir zaman commit edilmez.**

GitHub girişi bir kez: **`Gh-Giris.bat`** (`repo` + `workflow` kapsamı gerekir).

`versionCode`, `VERSION.txt`'den hesaplanır:
`major*1_000_000 + minor*1_000 + patch` → `1.0.10` = `1000010`.
Sürüm dizisi Makay ekosistemindeki gibidir: `1.0.10 → … → 1.0.99`, ondan sonra
`1.1.0` (betik `patch > 99` olduğunda `minor`'ı artırır).

---

## Uygulama içi güncelleme

Uygulama açılışta `api.github.com/repos/makaydestek/Makay-Uzak-Baglanti/releases/latest`
adresini denetler. Yeni sürüm varsa bildirim + diyalog gösterilir
(**Güncelle** / **Ertele**). **Sürüm notu boş bırakılır** — beş MAKAY
uygulamasının hepsinde aynı tercih.
