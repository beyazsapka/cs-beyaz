package com.kendirepo

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * FullHDFilmizlesene — fullhdfilmizlesene.now
 *
 * Kaynak listesi sayfadaki "scx" JS nesnesinde ROT13 + base64 ile
 * saklaniyor. Cozulen adresler bilinen oynaticilara (rapidvid vb.) ait
 * oldugu icin CloudStream'in hazir extractor'larina devrediliyor.
 */
class FullFilmIzlesene : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullHDFilmizlesene"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)


    // Cloudflare dogrulamasi gelirse WebView ile cerez al.
    private val cfKiller by lazy { CloudflareKiller() }
    private val cfInterceptor by lazy { CloudflareInterceptor(cfKiller) }

    private suspend fun getPage(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ) = app.get(url, headers = headers, referer = referer, interceptor = cfInterceptor)

    // Bazi kaynaklar Cloudflare arkasinda; uygulama WebView cerezlerini kullanabilsin.
    override val usesWebView = true

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenen Filmler",
        "$mainUrl/en-cok-izlenen-filmler" to "En Cok Izlenenler",
        "$mainUrl/filmizle/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/filmizle/komedi-filmleri" to "Komedi",
        "$mainUrl/filmizle/korku-filmleri" to "Korku",
        "$mainUrl/filmizle/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "$mainUrl/filmizle/dram-filmleri" to "Dram",
        "$mainUrl/filmizle/yerli-filmler" to "Yerli Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val document = getPage(request.data, referer = "$mainUrl/").document
        val items = document.select("li.film").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.tt") ?: selectFirst("a[href*=/film/]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = selectFirst("span.film-title")?.text()?.trim()
            ?: anchor.text().removeSuffix("izle").trim().ifBlank { return null }
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))
        val year = selectFirst("span.film-yil")?.text()?.trim()?.toIntOrNull()
        val rating = selectFirst("span.imdb")?.text()?.trim()?.toDoubleOrNull()
        val tags = selectFirst("span.ktt")?.text()?.split(",")?.map { it.trim() }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            this.score = rating?.let { Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val document = getPage("$mainUrl/arama/$q", referer = "$mainUrl/").document
        return document.select("li.film").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getPage(url, referer = "$mainUrl/").document
        val html = document.html()

        val title = document.selectFirst("div.izle-titles h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim() ?: return null
        val original = document.selectFirst("div.izle-titles h2")?.text()?.trim()
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img.ic-afis")?.attr("data-src")
        )
        val plot = document.select("div.ozet-ic p").joinToString("\n") { it.text().trim() }
            .ifBlank { document.selectFirst("meta[name=description]")?.attr("content")?.trim() }
        val year = findInfo(document, "Yapım Yılı", "Yil", "Yıl")
            ?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: Regex("""datetime="(\d{4})""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst("span.film-yil")?.text()?.trim()?.toIntOrNull()
        val duration = findInfo(document, "Süre", "Sure")
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val tags = document.select("a[rel='category tag'], div.film-info a[href*=/filmizle/]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val actors = document.select("div.film-info li")
            .firstOrNull { it.selectFirst("span.dt")?.text()?.contains("Oyuncu") == true }
            ?.select("div.dd a")?.map { com.lagradost.cloudstream3.Actor(it.text().trim()) }
            ?: emptyList()
        val rating = document.selectFirst("span.imdb")?.text()?.trim()?.toDoubleOrNull()
            ?: Regex("""var starCntx = '([\d.]+)'""").find(html)?.groupValues?.get(1)?.toDoubleOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.duration = duration
            this.score = rating?.let { Score.from10(it) }
            this.plot = listOfNotNull(original, plot).joinToString("\n")
            addActors(actors)
        }
    }

    private fun findInfo(document: org.jsoup.nodes.Document, vararg labels: String): String? {
        for (li in document.select("div.film-info li")) {
            val label = li.selectFirst("span.dt")?.text()?.trim() ?: continue
            if (labels.any { label.contains(it, ignoreCase = true) }) {
                return li.selectFirst("div.dd")?.text()?.trim()
            }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getPage(data, referer = "$mainUrl/").document
        val html = document.html()

        val json = Regex("""var scx = (\{.*?});""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return false
        val groups = runCatching {
            jacksonObjectMapper().readValue(json, Map::class.java) as? Map<*, *>
        }.getOrNull() ?: return false

        var found = false
        for (value in groups.values) {
            val sx = (value as? Map<*, *>)?.get("sx") as? Map<*, *> ?: continue
            for (key in listOf("p", "t")) {
                val list = sx[key] as? List<*> ?: continue
                for (encoded in list) {
                    val sourceUrl = decodeSource(encoded?.toString() ?: continue) ?: continue
                    found = handleSource(sourceUrl, data, subtitleCallback, callback) || found
                }
            }
        }
        return found
    }

    private suspend fun handleSource(
        raw: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var url = raw.trim()
        if (url.startsWith("<")) {
            url = Jsoup.parse(url).selectFirst("iframe")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: return false
        }
        if (!url.startsWith("http")) return false

        // Bilinen oynatici alan adi degismisse extractor'in bekledigi ada cevir.
        url = url.replace("rapidvid.org", "rapidvid.net")

        if (url.contains(".m3u8") || url.contains("/master.txt")) {
            callback.invoke(
                com.lagradost.cloudstream3.utils.newExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = com.lagradost.cloudstream3.utils.getQualityFromName("")
                }
            )
            return true
        }

        return loadExtractor(url, referer, subtitleCallback, callback)
    }

    /** JS rtt(): her harfi alfabede 13 ileri/geri kaydirir (a-m ileri, n-z geri). */
    private fun rtt(input: String): String = buildString(input.length) {
        for (ch in input) {
            if (ch.isLetter()) {
                append((ch.code + if (ch.lowercaseChar() < 'n') 13 else -13).toChar())
            } else {
                append(ch)
            }
        }
    }

    private fun decodeSource(encoded: String): String? {
        return runCatching {
            val decoded = Base64.decode(rtt(encoded), Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    data class ScxGroup(
        @JsonProperty("sx") val sx: ScxSources? = null,
    )

    data class ScxSources(
        @JsonProperty("p") val parts: List<String>? = null,
        @JsonProperty("t") val trailers: List<String>? = null,
    )
}
