// Surum numarasi tam sayi olmali. Eklentide degisiklik yaptikca artir,
// yoksa uygulama guncellemeyi gormez.
version = 2

cloudstream {
    authors     = listOf("beyazsapka")
    description = "FilmBoxIzle - Turkce dublajli film izleme"
    language    = "tr"

    /**
     * 0: Calismiyor, 1: Calisiyor, 2: Yavas, 3: Sadece beta
     */
    status = 3

    // Movie, TvSeries, Anime, AsianDrama, Documentary, Live ...
    tvTypes = listOf("Movie")

    iconUrl = "https://www.google.com/s2/favicons?domain=filmboxizle.net&sz=%size%"
}
