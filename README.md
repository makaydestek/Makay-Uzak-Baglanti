# MAKAY Uzaktan Bağlantı

Windows bilgisayarı telefondan uzaktan yönetmek için iki yönlü uzaktan bağlantı
uygulaması. Bilgisayar ekranını telefonda görür, dokunarak kontrol eder,
dosya gönderir ve yazışır.

- **Paket adı:** `com.makay.remote`
- **Sürüm:** `VERSION.txt` (şu an `1.0.5`)
- **Gereksinim:** Android 8.0 (API 26) ve üzeri
- **Eşlenik masaüstü tarafı:** `Makay AIO` → *Uzak Bağlantı* (`apps/remote`)

İki uygulama **aynı iletişim protokolünü** kullanır: aynı oda adı, aynı şifre
doğrulaması, aynı mesaj tipleri. Yani Android ↔ Windows her iki yönde de
çalışır (aşağıda [Uyum](#windows-masaüstü-ile-uyum) tablosu).

---

## Özellikler

| Özellik | Android → Windows | Windows → Android |
|---|---|---|
| Ekran paylaşımı | ✅ | ✅ |
| Uzak kontrol (fare/klavye) | ✅ | ✅ *(`-kontrol` APK gerekir)* |
| Dosya gönderimi | ✅ | ✅ |
| Sohbet | ✅ | ✅ |
| Pano (panodan kopyala/yapıştır) | ✅ | ✅ *(onay ister)* |
| Mikrofon durumu | ✅ | ✅ |
| Hoparlör yönlendirme | ✅ | — |
| Bağlantı kurma (oda + şifre) | ✅ | ✅ |

---

## İndireceğiniz APK

Her GitHub Release'de **iki APK** vardır; ihtiyacınıza göre birini seçin:

| APK | Ne işe yarar | Kurulum |
|---|---|---|
| `MakayRemote_vX.Y.Z.apk` | **Temel.** Ekran paylaşımı, dosya, sohbet, pano, mikrofon/hoparlör **ve Android → Windows uzak kontrol.** Günlük kullanım için budur. | ✅ Diğer Makay uygulamalarıyla aynı şekilde kurulur |
| `MakayRemote_vX.Y.Z-kontrol.apk` | Temeldeki her şey **+ Windows → Android uzak kontrol** (bilgisayardan telefonu yönetme). | ⚠️ Erişilebilirlik servisi bildirdiği için yandan yüklemede engellenebilir |

**Neden ikisi?** Android, bir uygulamanın kendi penceresi dışında girdi
enjekte etmesine yalnızca erişilebilirlik servisiyle izin verir (`INJECT_EVENTS`
sistem imzası gerektirir, uygulamalara verilmez). Google ise erişilebilirlik
bildiren yandan yükleme yapılan uygulamaları **bilerek** engeller — aksi halde
her uygulama telefonu sessizce yönetebilirdi. Bu yüzden:

- **Telefon ekranını bilgisayardan yönetmeyecekseniz** temel APK yeterlidir ve
  sorunsuz kurulur.
- **Bu özellik gerekiyorsa** `-kontrol` APK'sını kurun. Google engeli çıkarsa
  telefonda **Ayarlar → Google → Play Protect → "Uygulamaları tara"**
  geçici kapatılıp kurulum yapılıp tekrar açılabilir. Bu, AnyDesk gibi uzaktan
  yönetim uygulamalarında da geçerlidir.

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

## Uzak kontrolü açma (Windows → Android)

> **Ön koşul:** Bu bölüm yalnızca **`-kontrol` APK'sına** aittir. Temel APK'da
> bu özellik pakette yoktur — bkz. [İndireceğiniz APK](#indireceğiniz-apk).

Windows'tan telefonu kontrol etmek için **bir kez** gerekir:

1. **Ayarlar → Erişilebilirlik → Erişilebilirlik hizmetleri**
2. **MAKAY Uzak Kontrol** → **Aç**

> Android, uygulama dışında girdi enjekte etmeye yalnızca bu yolla izin verir
> (sistem imzası gerektiren `INJECT_EVENTS` izni uygulamalara verilmez).
> Servis **kapalıysa** telefon tarafında sarı bir uyarı bandı çıkar ve
> **"Servisi Aç"** düğmesi ile doğrudan bu ekranına gidilir.
> Servis açılmadan da bağlantı kurulur, izin isteği onaylanır — yalnızca
> girdiler uygulanmaz.

**Kontrol akışı:**

1. Windows tarafı → **Kontrol İste**
2. Telefonda onay (Ayarlar → *"Kontrol izni zorunlu"* kapalıysa otomatik)
3. Windows tarafında **Kontrol aktif** rozeti belirir
4. Telefonda fare hareketleri Android dokunmasına dönüşür

**Ne yapılır:**

| Windows girdisi | Telefonda |
|---|---|
| Sol tık | Dokunma |
| Basılı tut (> 0,6 sn) veya sağ tık | Uzun basma (bağlam menüsü) |
| Sürükleme | Kaydırma |
| Tekerlek | Dikey kaydırma |
| `Esc` | Geri |
| `Home` / `Win` | Ana ekran |
| Harf, rakam, boşluk, `Enter` | Odaklı metin alanına yazma |
| `Backspace` / `Delete` | Karakter silme |

**Sınırlar:** Android imleç (hover) olmadığından basılı olmayan fare
hareketleri bir şeye dönüşmez. Yön tuşları, `Shift`/`Ctrl`/`Alt` ve `F`
tuşları Android'de karşılığı olmadığından yok sayılır.

Kontrolü bırakmak: Windows'ta **Kontrolü Kaldır**, telefonda ise
**Kontrol sonlandırıldı** bildirimi.

---

## Pano (panodan metin)

**Windows → Android:** Windows'ta **Pano Gönder** → telefonda önizlemeli
onay penceresi çıkar; **Panoya Yaz** ile Android panosuna geçer,
**Reddet** ile atlanır. (Windows tarafı da aynı şekilde onay ister.)

**Android → Windows:** Telefonda **Pano** düğmesi → kendi panonuzdaki metin
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
| Eşleşme ağı | `makay/aio/v1/<ODA>` (MQTT / WSS) |
| Brokerler | `broker.emqx.io:8084`, `broker.hivemq.com:8884`, `test.mosquitto.org:8081` |
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
:: birim testleri (121 test x debug/release/kontrol varyantlari)
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:testReleaseUnitTest

:: TEMEL APK (imza local.properties'teki RELEASE_* anahtarlarindan gelir)
gradlew.bat :app:assembleRelease

:: KONTROL APK (Windows -> Android uzak kontrol + AccessibilityService)
gradlew.bat :app:assembleKontrol
```

`assembleRelease` tek basina calistirildiginda `publishReleaseApk` gorevi **her iki
APK'yi de** uretip `APK\` klasörüne yazar. Kontrol varyanti `app/build.gradle.kts`
icindeki `kontrol` derleme tipidir; release yapisini miras alir ve tek fark
`src/kontrol/` kaynak kümesinin manifest'e birlestirilmesidir
(`AndroidManifest.xml` + erisilebilirlik servisi + kaynaklari).

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
| 3 | `gradlew assembleRelease` → **iki APK**: `APK\…v1.0.10.apk` (temel) + `…v1.0.10-kontrol.apk`; her ikisi için `.apk.sha256` + `mapping.txt` |
| 4 | Sürüm commit'i oluşturulur ve `origin main`'e itilir |
| 5 | GitHub Release açılır, **6 asset** yüklenir (2 APK + 2 SHA + 2 mapping) |
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
