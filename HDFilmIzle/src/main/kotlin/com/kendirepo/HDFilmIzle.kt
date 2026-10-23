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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * HDFilmizle — hdfilmizle.live
 *
 * Film ve dizi sayfalarinda `let parts = [...]` icindeki iframe'ler
 * kullaniliyor. Baskin kaynak Vidrame; video adresi XOR ile gizlenmis
 * bir byte dizisinden uretiliyor.
 */
class HDFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hdfilmizle.live"
    override var name = "HDFilmIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Son Eklenenler",
        "$mainUrl/en-cok-izlenen-filmler-hd-2/" to "En Çok İzlenenler",
        "$mainUrl/tur/aksiyon-2/" to "Aksiyon",
        "$mainUrl/tur/komedi-1/" to "Komedi",
        "$mainUrl/tur/korku-1/" to "Korku",
        "$mainUrl/tur/dram-1/" to "Dram",
        "$mainUrl/tur/yerli-film-izle-1/" to "Yerli Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val supportsPaging = request.data.contains("%d")
        if (page > 1 && !supportsPaging) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val url = if (page <= 1) request.data.replace("/page/%d/", "/") else request.data.format(page)
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.select("a.poster").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = supportsPaging && items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("h2.title")?.text()?.trim()
            ?: attr("title").trim().ifBlank { return null }
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))
        val year = selectFirst("div.poster-year")?.text()?.trim()?.toIntOrNull()
        val rating = selectFirst("span.poster-imdb")?.text()?.trim()?.toDoubleOrNull()
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
        val home = app.get("$mainUrl/", referer = "$mainUrl/").text
        val token = Regex("""<meta name="csrf-token" content="([^"]+)"""")
            .find(home)?.groupValues?.get(1) ?: return emptyList()

        val results = app.post(
            "$mainUrl/search/",
            data = mapOf("query" to query),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "X-CSRF-TOKEN" to token,
                "Referer" to "$mainUrl/"
            )
        ).parsedSafe<List<SearchItem>>() ?: return emptyList()

        return results.mapNotNull { item ->
            val slug = item.slug?.trim() ?: return@mapNotNull null
            val title = item.name?.trim() ?: return@mapNotNull null
            val isSeries = item.type?.contains("dizi", ignoreCase = true) == true ||
                (item.type == null && isSeriesSlug(slug))
            val href = if (isSeries) "$mainUrl/dizi/$slug/" else "$mainUrl/$slug/"
            val poster = fixUrlNull(item.thumbUrl)
            val rating = item.imdbRating?.toDoubleOrNull()

            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.year
                    this.score = rating?.let { Score.from10(it) }
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.year
                    this.score = rating?.let { Score.from10(it) }
                }
            }
        }
    }

    private suspend fun isSeriesSlug(slug: String): Boolean {
        val response = runCatching { app.get("$mainUrl/dizi/$slug/", referer = "$mainUrl/") }.getOrNull()
            ?: return false
        return response.code in 200..299 && response.text.contains("/sezon-")
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val h1 = document.selectFirst("h1") ?: return null

        val title = h1.ownText().trim().ifBlank { h1.text().trim() }
        val altName = h1.selectFirst("small.alt-name")?.text()?.trim()
        val year = h1.selectFirst("small")?.text()?.trim()?.toIntOrNull()
            ?: document.select("table small")
                .firstOrNull { it.text().contains("Yıl") }
                ?.parent()?.selectFirst("strong")?.text()?.trim()?.toIntOrNull()
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("article.text-white p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val tags = document.select("div.genres a").map { it.text().trim() }.filter { it.isNotBlank() }
        val duration = document.select("table small")
            .firstOrNull { it.text().contains("Süre") }
            ?.parent()?.selectFirst("strong")?.text()?.trim()
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        if (url.contains("/dizi/")) {
            val episodes = document.select("a[href]").filter {
                it.attr("href").contains("/sezon-") && it.attr("href").contains("/bolum-")
            }.mapNotNull { ep ->
                val href = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val match = Regex("""/sezon-(\d+)/bolum-(\d+)/""").find(href) ?: return@mapNotNull null
                val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val episode = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                newEpisode(href) {
                    this.name = ep.text().trim().ifBlank { "S${season}B${episode}" }
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
                this.plot = listOfNotNull(altName, plot).joinToString("\n")
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = listOfNotNull(altName, plot).joinToString("\n")
            this.year = year
            this.tags = tags
            this.duration = duration
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, referer = "$mainUrl/").text
        val parts = Regex("""let parts\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return false

        val iframes = Regex("""src=\\?["']([^"'\\]+)""").findAll(parts)
            .map { it.groupValues[1].replace("\\/", "/") }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()

        var found = false
        for (iframe in iframes) {
            found = if (iframe.contains("vidrame.")) {
                extractVidrame(iframe, data, subtitleCallback, callback) || found
            } else {
                loadExtractor(iframe, data, subtitleCallback, callback) || found
            }
        }
        return found
    }

    private suspend fun extractVidrame(
        iframeUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching { app.get(iframeUrl, referer = referer).text }.getOrNull() ?: return false
        val match = Regex("""\)\(\[([0-9,]{50,})],\s*"([0-9a-f]+)"\)""").find(html) ?: return false

        val bytes = match.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
        val key = match.groupValues[2]
        if (bytes.isEmpty() || key.isEmpty()) return false

        val url = buildString(bytes.size) {
            bytes.forEachIndexed { index, byte ->
                val keyCode = key[index % key.length].code
                append((byte xor keyCode xor ((index * 17 + 13) and 255)).toChar())
            }
        }
        if (!url.startsWith("http")) return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name Vidrame",
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = iframeUrl.substringBefore("/vr/") + "/"
                this.quality = getQualityFromName("")
            }
        )
        return true
    }

    data class SearchItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("imdb_rating") val imdbRating: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("type") val type: String? = null,
    )
}
