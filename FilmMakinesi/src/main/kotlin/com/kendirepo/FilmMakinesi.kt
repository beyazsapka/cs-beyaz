package com.kendirepo

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
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
import org.jsoup.nodes.Element

/**
 * FilmMakinesi — filmmakinesi.to
 *
 * Oynatma kaynaklari "CloseLoad" ve "Rapidrame" oynaticilarindan geliyor.
 * Bu oynaticilar video adresini JS icinde sifreli olarak tutuyor; RapidRame
 * nesnesi o sifreyi cozer.
 */
class FilmMakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
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
        "$mainUrl/filmler-1/sayfa/%d/" to "Filmler",
        "$mainUrl/yabanci-dizi-izle-2/sayfa/%d/" to "Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Sayfa 1 adresi /sayfa/1/ degil, dogrudan liste adresi.
        val url = if (page <= 1) request.data.replace("/sayfa/%d/", "/") else request.data.format(page)
        val document = getPage(url, referer = "$mainUrl/").document
        val items = document.select("a.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("div.title")?.text()?.trim()
            ?: attr("data-title").trim().ifBlank { return null }
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))
            ?: fixUrlNull(selectFirst("img")?.attr("data-src"))
        val year = selectFirst("div.info span")?.text()?.trim()?.toIntOrNull()
        val rating = selectFirst("div.rating")?.text()?.trim()?.toDoubleOrNull()
        val isSeries = href.contains("/dizi/")

        return if (isSeries) {
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

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val document = getPage("$mainUrl/arama/?s=$q", referer = "$mainUrl/").document
        return document.select("a.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getPage(url, referer = "$mainUrl/").document
        val html = document.html()

        val rawTitle = document.selectFirst("h1.title")?.ownText()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = document.selectFirst("h1.title span.date a")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val rating = document.selectFirst("div.info div.imdb b")?.text()?.trim()?.toDoubleOrNull()
        val tags = document.select("div.info div.type a").map { it.text().trim() }.filter { it.isNotBlank() }
        val duration = document.selectFirst("div.info div.time")?.text()
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        // Dizi sayfasi: bolum listesi dogrudan HTML icinde.
        if (url.contains("/dizi/") && !url.contains("/sezon-")) {
            val episodes = document.select("a.item-ep").mapNotNull { ep ->
                val href = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val match = Regex("""/sezon-(\d+)/bolum-(\d+)/""").find(href) ?: return@mapNotNull null
                val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val episode = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                newEpisode(href) {
                    this.name = "S${season}B${episode}"
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
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = duration
            this.score = rating?.let { Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getPage(data, referer = "$mainUrl/").document
        val sources = document.select("a[data-video_url]")
            .mapNotNull { it.attr("data-video_url").takeIf { url -> url.startsWith("http") } }
            .filterNot { it.contains("youtube.com") || it.contains("youtu.be") }
            .distinct()

        var found = false
        for (source in sources) {
            val host = Regex("""^https?://([^/]+)""").find(source)?.groupValues?.get(1) ?: continue
            if (host.contains("closeload")) {
                found = extractCloseLoad(source, data, subtitleCallback, callback) || found
            } else if (host.contains("rapid")) {
                found = extractRapid(source, data, subtitleCallback, callback) || found
            } else {
                found = loadExtractor(source, data, subtitleCallback, callback) || found
            }
        }
        return found
    }

    private suspend fun extractCloseLoad(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = getPage(url, referer = referer).text
        val payload = Regex("""\w+\("([^"]+)"\.split\("\^"\)\)""").find(html)?.groupValues?.get(1)
            ?: return false
        val host = Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1)
        val stream = RapidRame.decrypt(payload, "^", host)?.takeIf { it.startsWith("http") }
            ?: return false

        parseTracks(html, "https://$host", subtitleCallback)
        emit(stream, "CloseLoad", url, callback)
        return true
    }

    private suspend fun extractRapid(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = getPage(url, referer = referer).text
        val script = Regex("""eval\(function\(p,a,c,k,e,d\).*""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value?.substringBefore("</script>") ?: return false
        val unpacked = getAndUnpack(script)
        val payload = Regex("""\w+\("([^"]+)"\.split\("~"\)\)""").find(unpacked)?.groupValues?.get(1)
            ?: return false
        val host = Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1)
        val stream = RapidRame.decrypt(payload, "~", host)?.takeIf { it.startsWith("http") }
            ?: return false

        parseTracks(unpacked, "https://$host", subtitleCallback)
        emit(stream, "Rapidrame", url, callback)
        return true
    }

    private suspend fun emit(
        stream: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name $label",
                url = stream,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = if (referer.startsWith("http")) referer else "$mainUrl/"
                this.quality = getQualityFromName("")
            }
        )
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
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .replace(Regex("""\s+\d{4}\s+Filmi.*$"""), "")
            .trim()
    }
}
