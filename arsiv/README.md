# Arşiv

Bu klasördeki modüller **derlemeye alınmaz** ve repoya yayınlanmaz.
Sebep: bu siteler video akışı sunmuyor (rehber / öneri / DRM'li servis).

| Modül | Site | Neden |
|---|---|---|
| `CineHD` | cinehd.vc | TMDB tabanlı "nerede izlenir" rehberi; kendi oynatıcısı yok. |
| `StreamNaro` | streamnaro.online | Kendi kaynağı yok; vidsrc/vaplayer embed'leri kullanıyor (DRM/anti-bot). |
| `TasteDive` | tastedive.com | Film/dizi öneri API'si; video yok. |
| `IFlix` | iflix.com | Servis kapandı, alan adı WeTV'ye (DRM) yönleniyor. |

Üzerinde çalışmak için klasörü repo köküne taşıyın; `settings.gradle.kts`
kök dizindeki her modülü otomatik olarak derlemeye dahil eder.
