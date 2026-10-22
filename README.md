# Kendi Repom: CloudStream Eklentileri

Kişisel [CloudStream](https://github.com/recloudstream/cloudstream) eklenti reposu.

## Uygulamaya ekleme

CloudStream > Ayarlar > Eklentiler > Repo ekle:

```
https://raw.githubusercontent.com/kullaniciadi/cs-repo/main/repo.json
```

## İlk kurulum (bir kere)

1. GitHub'da **public** bir repo aç (ör. `cs-repo`).
2. Şu dosyalardaki `kullaniciadi/cs-repo` yazan yerleri kendi bilgilerinle değiştir:
   - `build.gradle.kts`: `setRepo(...)` ve `authors`
   - `repo.json`: `pluginLists`
   - bu README
3. Kodu `main` dalına gönder:
   ```bash
   git init -b main
   git add .
   git commit -m "İlk sürüm"
   git remote add origin https://github.com/kullaniciadi/cs-repo.git
   git push -u origin main
   ```
4. GitHub Actions eklentileri derler ve `builds` dalına `.cs3` dosyalarını + `plugins.json`'u koyar.
   Repo > Settings > Actions > General > *Workflow permissions* ayarının **Read and write** olduğundan emin ol.

## Yeni site eklemek

```bash
./yeni-eklenti.sh SiteAdi https://siteadi.com
```

Bu komut `Sablon` klasörünü kopyalar. Sonra `SiteAdi/src/main/kotlin/com/kendirepo/SiteAdi.kt` dosyasında
`// SEÇİCİ` yorumu olan yerleri sitenin HTML yapısına göre düzenle (tarayıcıda F12 > Elements).

Eklentiyi her güncellediğinde `SiteAdi/build.gradle.kts` içindeki `version` değerini 1 artır.
Artırmazsan uygulama güncellemeyi görmez.

## Yerelde derleme ve test

```bash
./gradlew SiteAdi:make             # SiteAdi/build/SiteAdi.cs3 oluşur
./gradlew SiteAdi:deployWithAdb    # USB ile bağlı telefona direkt yükler
```

`deployWithAdb` için telefonda CloudStream'e "Tüm dosyalara erişim" izni gerekir:

```bash
adb shell appops set --uid com.lagradost.cloudstream3 MANAGE_EXTERNAL_STORAGE allow
```

(prerelease sürüm için paket adı: `com.lagradost.cloudstream3.prerelease`)

## Yapı

| Dosya | Görevi |
|---|---|
| `Sablon/` | Yeni eklentiler için şablon. GitHub'da yayınlanmaz. |
| `repo.json` | Uygulamaya eklenen repo adresi bunu gösterir. |
| `build.gradle.kts` | Tüm eklentilerin ortak derleme ayarları. |
| `.github/workflows/build.yml` | Her push'ta derleyip `builds` dalına yükler. |
| `yeni-eklenti.sh` | Yeni site eklentisi oluşturur. |
