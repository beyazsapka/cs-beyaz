package com.kendirepo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * HTML tabanlı bir film/dizi sitesi için şablon.
 *
 * Yeni site eklerken:
 *  1. mainUrl ve name'i değiştir.
 *  2. Tarayıcıda siteyi aç, F12 > Elements ile CSS seçicilerini bul,
 *     aşağıdaki "SEÇİCİ" yazan yerleri güncelle.
 *  3. loadLinks içinde video iframe'lerinin nasıl geldiğine bak.
 */
class FullFilmIzlesene : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullFilmIzlesene"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Ana sayfadaki satırlar: "url" to "başlık". url'deki %d sayfa numarasıyla değişir.
    override val mainPage = mainPageOf(
        "$mainUrl/filmler/page/%d" to "Filmler",
        "$mainUrl/diziler/page/%d" to "Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.format(page)).document
        val home = document.select("div.movie-item").mapNotNull { it.toSearchResult() } // SEÇİCİ
        return newHomePageResponse(request.name, home)
    }

    // Liste/arama sonuçlarındaki tek bir kartı SearchResponse'a çevirir.
    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2, .title")?.text()?.trim() ?: return null // SEÇİCİ
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val poster = imageUrl()
        val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie // SEÇİCİ

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    // Lazy-load kullanan sitelerde gerçek resim data-src'de olur.
    private fun Element.imageUrl(): String? {
        val img = selectFirst("img") ?: return null
        val src: String = img.attr("data-src").ifBlank { img.attr("src") }
        return fixUrlNull(src)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document // SEÇİCİ: sitenin arama adresi
        return document.select("div.movie-item").mapNotNull { it.toSearchResult() }
    }

    // Film/dizi detay sayfası.
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null // SEÇİCİ
        val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description = document.selectFirst("div.description")?.text()?.trim()
        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.genres a").map { it.text() }
        val actors = document.select("div.cast a").map { Actor(it.text()) }
        val trailer = document.selectFirst("iframe.trailer")?.attr("src")

        // Sayfada bölüm listesi varsa dizi say.
        val episodeElements = document.select("div.episodes a") // SEÇİCİ
        if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.map { ep ->
                // Örn. "1. Sezon 3. Bölüm" metninden sezon ve bölüm numarası çıkarılır.
                val text = ep.text()
                newEpisode(fixUrl(ep.attr("href"))) {
                    name = text
                    season = Regex("""(\d+)\.\s*Sezon""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    episode = Regex("""(\d+)\.\s*Bölüm""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                addActors(actors)
                addTrailer(trailer)
            }
        }

        // Film: data olarak sayfa url'si gönderilir, loadLinks bunu alır.
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    // data: film için sayfa url'si, dizi için bölüm url'si.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Çoğu site videoyu bir iframe ile başka bir oynatıcıdan (ör. vidmoly, filemoon) gösterir.
        // loadExtractor, CloudStream'in hazır extractor'larıyla bu oynatıcılardan video linkini çıkarır.
        document.select("iframe").forEach { iframe -> // SEÇİCİ
            val src = fixUrlNull(iframe.attr("data-src").ifBlank { iframe.attr("src") }) ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }

        // Site doğrudan .m3u8 veriyorsa:
        // callback(newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
        //     referer = mainUrl
        //     quality = Qualities.Unknown.value
        // })
        return true
    }
}
