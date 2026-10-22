package com.kendirepo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * HDFilmCehennemi — hdfilmcehennemi.nl
 *
 * Film/dizi sayfalarindaki "alternative-link" butonlari /video/{id}/
 * endpoint'inden iframe donduruyor. Site kendi rplayer oynaticisini
 * kullaniyor; adres JS icinde sifreli, RapidRame ile cozuluyor.
 */
class HDFilmcehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)


    // Cloudflare dogrulamasi gelirse WebView ile cerez al.
    private val cfKiller by lazy { CloudflareKiller() }
    private val cfInterceptor by lazy { CloudflareInterceptor(cfKiller) }

    private suspend fun getPage(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ) = app.get(url, headers = headers, referer = referer, interceptor = cfInterceptor)

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenenler",
        "$mainUrl/category/film-izle-2/" to "Filmler",
        "$mainUrl/yabancidiziizle-5/" to "Yabanci Diziler",
        "$mainUrl/tur/aksiyon-filmleri-izleyin-8/" to "Aksiyon",
        "$mainUrl/tur/komedi-filmlerini-izleyin-2/" to "Komedi",
        "$mainUrl/tur/korku-filmlerini-izle-9/" to "Korku",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val document = getPage(request.data, referer = "$mainUrl/").document
        val items = document.select("a.poster").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("strong.poster-title")?.text()?.trim()
            ?: attr("title").trim().ifBlank { return null }
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))
        val year = selectFirst("div.poster-meta span")?.text()?.trim()?.toIntOrNull()
        val isSeries = href.contains("/dizi/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val response = getPage(
            "$mainUrl/search?q=$q",
            headers = mapOf("X-Requested-With" to "fetch"),
            referer = "$mainUrl/"
        ).parsedSafe<SearchResponseJson>() ?: return emptyList()

        return response.results.orEmpty().mapNotNull { html ->
            val doc = Jsoup.parse(html)
            val anchor = doc.selectFirst("a.search-result") ?: return@mapNotNull null
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val title = doc.selectFirst("h4.title")?.text()?.trim() ?: return@mapNotNull null
            val poster = fixUrlNull(doc.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(doc.selectFirst("img")?.attr("data-src"))
            val year = doc.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
            val rating = doc.selectFirst("span.imdb")?.text()?.trim()?.toDoubleOrNull()
            val isSeries = href.contains("/dizi/")

            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getPage(url, referer = "$mainUrl/").document
        val html = document.html()

        val title = cleanTitle(
            document.selectFirst("h1.section-title")?.ownText()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: return null
        )
        val poster = fixUrlNull(
            document.selectFirst("aside.post-info-poster img")?.attr("data-src")
                ?: document.selectFirst("aside.post-info-poster img")?.attr("src")
        )
        val plot = document.selectFirst("article.post-info-content p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""datePublished":"(\d{4})""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val rating = document.selectFirst("div.post-info-imdb-rating span")?.text()?.trim()
            ?.let { Regex("""([\d.]+)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val tags = document.select("div.post-info-genres a").map { it.text().trim() }
            .filter { it.isNotBlank() }
        val actors = document.select("div.post-info-cast a").mapNotNull {
            val actorName = it.selectFirst("strong")?.text()?.trim() ?: return@mapNotNull null
            com.lagradost.cloudstream3.Actor(actorName, fixUrlNull(it.selectFirst("img")?.attr("data-src")))
        }
        val duration = Regex(""""duration":"PT(\d+)M"""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val trailer = document.selectFirst("div.post-info-trailer button")
            ?.attr("data-modal")?.substringAfter("trailer/", "")?.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/embed/$it" }

        val episodeAnchors = document.select("a[href]").filter {
            it.attr("href").contains("/sezon-") && it.attr("href").contains("/bolum-")
        }
        val isSeries = document.select("div.seasons").isNotEmpty() || episodeAnchors.isNotEmpty()

        if (isSeries) {
            val episodes = episodeAnchors.mapNotNull { ep ->
                val href = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val match = Regex("""/sezon-(\d+)/bolum-(\d+)/""").find(href) ?: return@mapNotNull null
                val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val episode = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                newEpisode(href) {
                    this.name = ep.selectFirst("h4")?.text()?.trim() ?: "S${season}B${episode}"
                    this.season = season
                    this.episode = episode
                    this.posterUrl = poster
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = duration
            this.score = rating?.let { Score.from10(it) }
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getPage(data, referer = "$mainUrl/").document

        val iframeUrls = mutableListOf<String>()
        document.select("div.video-container iframe, iframe.rapidrame").forEach { iframe ->
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (src.startsWith("http")) iframeUrls.add(src)
        }

        // Alternatif kaynaklar: /video/{id}/ -> {"data":{"html":"<iframe ...>"}}
        document.select("button.alternative-link[data-video]").forEach { button ->
            val videoId = button.attr("data-video").trim()
            if (videoId.isBlank()) return@forEach
            val response = getPage(
                "$mainUrl/video/$videoId/",
                headers = mapOf("X-Requested-With" to "fetch"),
                referer = data
            ).parsedSafe<VideoApiResponse>()
            val iframeHtml = response?.data?.html ?: return@forEach
            val src = Jsoup.parse(iframeHtml).selectFirst("iframe")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            if (!src.isNullOrBlank()) iframeUrls.add(fixUrl(src))
        }

        var found = false
        for (rawUrl in iframeUrls.distinct()) {
            var url = rawUrl
            // rapidrame embed -> sitenin kendi rplayer sayfasi
            if (url.contains("rapidrame_id=")) {
                val id = url.substringAfter("rapidrame_id=").substringBefore("&").substringBefore("/")
                url = "$mainUrl/rplayer/$id/"
            }

            if (url.contains("/rplayer/")) {
                found = extractRplayer(url, data, subtitleCallback, callback) || found
            } else {
                found = loadExtractor(url, data, subtitleCallback, callback) || found
            }
        }
        return found
    }

    private suspend fun extractRplayer(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = getPage(url, referer = referer).text
        val script = Regex("""eval\(function\(p,a,c,k,e,d\).*""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value?.substringBefore("</script>") ?: return false
        val unpacked = getAndUnpack(script)
        val payload = Regex("""\w+\("([^"]+)"\.split\("\^"\)\)""").find(unpacked)?.groupValues?.get(1)
            ?: return false
        val stream = RapidRame.decrypt(payload, "^", null)?.takeIf { it.startsWith("http") }
            ?: return false

        val host = Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1) ?: return false
        parseTracks(unpacked, "https://$host", subtitleCallback)

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name Rapidrame",
                url = stream,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = getQualityFromName("")
            }
        )
        return true
    }

    private fun parseTracks(
        html: String,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val block = Regex("""tracks\s*:\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return
        val cleaned = block.replace("\\/", "/")
        Regex("""\{[^}]*}""").findAll(cleaned).forEach { match ->
            val obj = match.value
            val file = Regex(""""file"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
                ?.replace("\\/", "/") ?: return@forEach
            val label = Regex(""""label"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
                ?: "Unknown"
            val full = when {
                file.startsWith("http") -> file
                file.startsWith("//") -> "https:$file"
                else -> baseUrl.trimEnd('/') + "/" + file.trimStart('/')
            }
            subtitleCallback.invoke(SubtitleFile(label, full))
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*izle\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|\s*Hdfilmcehennemi.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    data class SearchResponseJson(
        @JsonProperty("results") val results: List<String>? = null,
    )

    data class VideoApiResponse(
        @JsonProperty("data") val data: VideoData? = null,
    )

    data class VideoData(
        @JsonProperty("html") val html: String? = null,
    )
}
