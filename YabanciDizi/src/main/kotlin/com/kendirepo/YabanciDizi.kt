package com.kendirepo

import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * YabanciDizi — yabancidizi.news
 *
 * Arama ve oynatma istekleri POST /ajax/service ve POST /search
 * endpoint'lerine gidiyor. Bolum sayfasindaki #not-loaded alanindaki
 * e_id/v_lang degerleri oynatici iframe'ini almak icin kullaniliyor.
 */
class YabanciDizi : MainAPI() {
    override var mainUrl = "https://yabancidizi.news"
    override var name = "YabanciDizi"
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
        mainUrl to "Son Eklenenler",
        "$mainUrl/dizi-izle" to "Diziler",
        "$mainUrl/film-izle" to "Filmler",
    )

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val document = getPage(request.data, referer = "$mainUrl/").document
        val items = document.select("div.poster").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = selectFirst("h2")?.text()?.trim()
            ?: anchor.attr("title").removeSuffix("izle").trim().ifBlank { return null }
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))
        val isSeries = href.contains("/dizi/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val response = app.post(
            "$mainUrl/search?qr=$q",
            headers = ajaxHeaders,
            referer = "$mainUrl/",
            interceptor = cfInterceptor
        ).parsedSafe<SearchJson>() ?: return emptyList()

        return response.data?.result.orEmpty().mapNotNull { item ->
            val link = item.link?.trim() ?: return@mapNotNull null
            val title = item.name?.trim() ?: return@mapNotNull null
            val isSeries = item.type == "0" || link.contains("dizi")
            val href = fixUrl(if (isSeries) "/dizi/$link" else "/film/$link")
            val poster = fixUrlNull(item.image?.let { image ->
                if (image.startsWith("http")) image else "/uploads/series/$image"
            })

            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getPage(url, referer = "$mainUrl/").document
        val html = document.html()

        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val title = rawTitle.replace(Regex("""\s*izle\b.*$""", RegexOption.IGNORE_CASE), "").trim()
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = Regex("""(\d{4})""").find(
            document.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        )?.groupValues?.get(1)?.toIntOrNull()

        if (url.contains("/dizi/")) {
            val episodes = document.select("a[href]").filter {
                it.attr("href").contains("/sezon-") && it.attr("href").contains("/bolum-")
            }.mapNotNull { ep ->
                val href = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val match = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(href) ?: return@mapNotNull null
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
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getPage(data, referer = "$mainUrl/").document
        val html = document.html()

        // 1) Sayfada dogrudan player container varsa: get_stream
        val playerContainer = document.selectFirst("#player_container[data-e_id]")
        if (playerContainer != null) {
            val eId = playerContainer.attr("data-e_id")
            val lang = playerContainer.attr("data-v_lang").ifBlank { "en" }
            val streamJson = app.post(
                "$mainUrl/ajax/service",
                data = mapOf("e_id" to eId, "v_lang" to lang, "type" to "get_stream"),
                headers = ajaxHeaders,
                referer = data,
                interceptor = cfInterceptor
            ).parsedSafe<StreamJson>()
            if (emitSources(streamJson?.api?.src?.sources, data, subtitleCallback, callback)) return true
        }

        // 2) #not-loaded: get_whatwehave -> api_iframe
        val notLoaded = document.selectFirst("#not-loaded[data-whatwehave]")
            ?: document.selectFirst("[data-whatwehave]")
        if (notLoaded != null) {
            val eId = notLoaded.attr("data-whatwehave")
            val lang = notLoaded.attr("data-lang").ifBlank { "en" }
            val what = app.post(
                "$mainUrl/ajax/service",
                data = mapOf("e_id" to eId, "v_lang" to lang, "type" to "get_whatwehave"),
                headers = ajaxHeaders,
                referer = data,
                interceptor = cfInterceptor
            ).parsedSafe<WhatWeHaveJson>()
            val iframe = what?.apiIframe
            if (!iframe.isNullOrBlank()) {
                return loadExtractor(iframe, data, subtitleCallback, callback)
            }
        }

        // 3) Son care: sayfadaki iframe'ler
        var found = false
        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (src.startsWith("http")) {
                found = loadExtractor(src, data, subtitleCallback, callback) || found
            }
        }
        return found
    }

    private suspend fun emitSources(
        sources: List<SourceItem>?,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (sources.isNullOrEmpty()) return false
        var found = false
        for (source in sources) {
            val url = source.file?.trim()
            if (url.isNullOrBlank() || !url.startsWith("http")) continue
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(source.label ?: "")
                }
            )
            found = true
        }
        return found
    }

    data class SearchJson(
        @JsonProperty("data") val data: SearchData? = null,
    )

    data class SearchData(
        @JsonProperty("result") val result: List<SearchItem>? = null,
    )

    data class SearchItem(
        @JsonProperty("s_link") val link: String? = null,
        @JsonProperty("s_name") val name: String? = null,
        @JsonProperty("s_image") val image: String? = null,
        @JsonProperty("s_type") val type: String? = null,
    )

    data class WhatWeHaveJson(
        @JsonProperty("api_iframe") val apiIframe: String? = null,
        @JsonProperty("attr") val attr: String? = null,
    )

    data class StreamJson(
        @JsonProperty("api") val api: StreamApi? = null,
    )

    data class StreamApi(
        @JsonProperty("src") val src: StreamSrc? = null,
    )

    data class StreamSrc(
        @JsonProperty("sources") val sources: List<SourceItem>? = null,
    )

    data class SourceItem(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )
}
