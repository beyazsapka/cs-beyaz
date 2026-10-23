# Beyaz CloudStream Repo

[CloudStream](https://github.com/recloudstream/cloudstream) 3 için kişisel Türkçe film/dizi eklenti reposu.

Repo adresi:

```
https://raw.githubusercontent.com/beyazsapka/cs-beyaz/master/plugins.json
```

CloudStream > **Ayarlar > Eklentiler > Repo ekle** yolundan yukarıdaki adresi ekleyin.
`repo.json` dosyası da aynı repoyu gösterir:

```
https://raw.githubusercontent.com/beyazsapka/cs-beyaz/master/repo.json
```

### Tek tık / kısa bağlantı

Android'de tıklayınca CloudStream'in açılması için:

```
cloudstreamrepo://raw.githubusercontent.com/beyazsapka/cs-beyaz/master/repo.json
```

Ayrıca CloudStream, başında `http` olmayan ve yalnızca `a-z A-Z 0-9 ! _ -`
karakterlerinden oluşan girdileri **kısa kod** olarak çözer:

- `cutt.ly/<kod>` adresinin `repo.json`'a yönlendirmesi gerekir.
  Uygulamada **Depo ismi** alanını boş bırakıp **Depo URL'si** alanına sadece
  `<kod>` yazın (ör. `beyazcs` → `https://cutt.ly/beyazcs`).
- `!` ile başlayan kodlar `https://py.md/<kod>` üzerinden çözülür.

Kendi kısa kodunuzu oluşturmak için: cutt.ly'de hesap açıp
`https://raw.githubusercontent.com/beyazsapka/cs-beyaz/master/repo.json`
adresini kısaltın, özel takma ad olarak `beyazcs` gibi bir değer seçin.
Tarayıcıda `https://cutt.ly/beyazcs` açıldığında doğrudan JSON görünmelidir.

---

## Eklentiler

| Site | Tür | Durum | Not |
|---|---|---|---|
| [LookMovie2](LookMovie2) | Film + Dizi | ✅ Çalışıyor | m3u8 akışları ve altyazılar site API'sinden alınıyor. |
| [FilmMakinesi](FilmMakinesi) | Film + Dizi | ✅ Çalışıyor | CloseLoad / Rapidrame oynatıcıları çözülüyor. |
| [HDFilmCehennemi](HDFilmcehennemi) | Film + Dizi | ✅ Çalışıyor | Alternatif kaynaklar + `rplayer` çözücüsü. Site TR dışına kapalı olabilir. |
| [FullHDFilmizlesene](FullFilmIzlesene) | Film | ⚠️ Kısmi | Film bilgileri geliyor; kaynak `rapidvid` Cloudflare arkasında olduğu için bazı filmlerde link gelmeyebilir. |
| [YabanciDizi](YabanciDizi) | Film + Dizi | ⚠️ Beta | Arama ve bölüm listesi çalışıyor; oynatıcı AJAX'ı zaman aşımına uğrayabilir. |
| [FilmBoxIzle](FilmBoxIzle) | Film | ⚠️ Beta | hotstream AES yapılandırması çözülüyor; CDN otomatik istekleri bazen reddediyor. |
| [DiziBox](DiziBox) | Yabancı Dizi | ✅ Çalışıyor | Bölüm listesi, arama ve `king`/`moly`/`haydi` oynatıcı zinciri (CryptoJS AES + VidMoly/OK.ru). |
| [HDFilmIzle](HDFilmIzle) | Film + Dizi | ✅ Çalışıyor | CSRF korumalı arama, Vidrame oynatıcısı (XOR çözücü) ve dizi bölümleri. |

> "Çalışıyor" = site HTML/API'si ve oynatıcı zinciri test edildi, eklenti derleniyor.
> Uygulama içi son test cihazda yapılmalıdır; siteler sık sık yapı değiştirir.

### Neden bazı siteler yok?

Bu dört site **video kaynağı sunmadığı** için eklentiye çevrilemedi; kodları `arsiv/` altında duruyor:

| Site | Neden desteklenmiyor |
|---|---|
| **CineHD** (`cinehd.vc`) | TMDB tabanlı "nerede izlenir" rehberi. Kendi oynatıcısı/akışı yok; sadece platform bilgisi gösteriyor. |
| **StreamNaro** (`streamnaro.online`) | Kendi kaynağı yok; TMDB kataloğu + `vidsrc`/`vaplayer` embed'leri kullanıyor. Embed'ler DRM/anti-bot korumalı. |
| **TasteDive** (`tastedive.com`) | Film/dizi öneri API'si. Video akışı yok. |
| **iFlix** (`iflix.com`) | Servis kapandı; alan adı WeTV'ye yönleniyor (DRM korumalı). |

`arsiv/` klasörü `settings.gradle.kts` tarafından derlemeye alınmaz. Daha sonra üzerinde
çalışmak isterseniz klasörü kök dizine taşımanız yeterli.

---

## Yapı

| Dosya / klasör | Görevi |
|---|---|
| `LookMovie2/`, `FilmMakinesi/`, ... | Her site için ayrı eklenti modülü. |
| `Sablon/` | Yeni eklenti oluşturmak için şablon (CI'da derlenmez). |
| `arsiv/` | Video kaynağı olmayan sitelerin taslakları (derlenmez). |
| `repo.json` | Uygulamaya eklenen repo tanımı. |
| `build.gradle.kts` | Ortak derleme ayarları. |
| `.github/workflows/build.yml` | Her push'ta derleyip `builds` dalına `.cs3` + `plugins.json` yükler. |
| `yeni-eklenti.sh` | Şablondan yeni modül üretir. |

### Oynatıcı çözücüleri

- **`RapidRame.kt`** (FilmMakinesi ve HDFilmcehennemi): Türk sitelerinde yaygın olan
  CloseLoad/Rapidrame/rplayer oynatıcılarının video adresini üreten JS fonksiyonunun
  birebir Kotlin çevirisi. Base64 katmanı, harf kaydırma, permütasyon ve XOR akışını uygular.
- **FullHDFilmizlesene**: `scx` nesnesindeki ROT13 + base64 kodlu kaynak adreslerini çözer,
  ardından CloudStream'in hazır extractor'larına devreder.
- **FilmBoxIzle**: hotstream `bePlayer(token, data)` yapılandırmasını CryptoJS uyumlu
  AES-CBC (MD5/EVP_BytesToKey) ile çözer.

Tüm eklentiler yalnızca CloudStream'in kendi kütüphanesini ve Android/JDK sınıflarını kullanır;
ek bağımlılık yoktur.

---

## Yerelde derleme

```bash
# Android SDK yolu (bir kez)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew make                  # tüm eklentiler
./gradlew FilmMakinesi:make     # tek eklenti
```

Çıktı: `FilmMakinesi/build/FilmMakinesi.cs3`

Telefona doğrudan yüklemek için:

```bash
./gradlew FilmMakinesi:deployWithAdb
```

`deployWithAdb` için telefonda CloudStream'e "Tüm dosyalara erişim" izni gerekir:

```bash
adb shell appops set --uid com.lagradost.cloudstream3 MANAGE_EXTERNAL_STORAGE allow
```

Prerelease sürümde paket adı: `com.lagradost.cloudstream3.prerelease`

### Yayınlama (GitHub Actions'a gerek yok)

Repo, `master` dalındaki hazır dosyaları kullanır:

- `plugins/*.cs3` — derlenmiş eklentiler
- `plugins.json` — eklenti manifesti
- `repo.json` — CloudStream'in eklediği repo tanımı

`./gradlew make makePluginsJson` sonrası `.cs3` dosyalarını `plugins/` klasörüne
kopyalayıp `plugins.json` içindeki adresleri `master/plugins/...` olacak şekilde
güncellemek yeterlidir.

`.github/workflows/build.yml` ise (Actions etkinse) ayrıca `builds` dalına
derlenmiş paketleri yazar; repo için zorunlu değildir.

---

## Yeni site ekleme

```bash
./yeni-eklenti.sh SiteAdi https://siteadi.com
```

Ardından `SiteAdi/src/main/kotlin/com/kendirepo/SiteAdi.kt` içindeki seçicileri
sitenin HTML yapısına göre düzenleyin (tarayıcıda F12 > Elements).

Sürüm artırma kuralı: her yayında `SiteAdi/build.gradle.kts` içindeki `version`
tam sayısını 1 artırın; aksi halde uygulama güncellemeyi görmez.

---

## Yasal uyarı

Bu eklentiler yalnızca kişisel kullanım için, sitelerin herkese açık sayfalarındaki
verileri ayrıştırır. İçeriklerin telif hakları ilgili sahiplerine aittir. Kullanım
sorumluluğu kullanıcıya aittir.
