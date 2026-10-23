// Sürüm numarası tam sayı olmalı. Eklentide değişiklik yaptıkça artır,
// yoksa uygulama güncellemeyi görmez.
version = 1

cloudstream {
    description = "DiziBox - yabanci dizi izleme"
    language = "tr"

    /**
     * 0: Çalışmıyor, 1: Çalışıyor, 2: Yavaş, 3: Sadece beta
     */
    status = 1

    // Movie, TvSeries, Anime, AsianDrama, Documentary, Live ...
    tvTypes = listOf("TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=www.dizibox.live&sz=%size%"
}
