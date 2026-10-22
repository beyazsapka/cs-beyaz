package com.kendirepo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * LookMovie2 — lookmovie2.to
 *
 * Site tamamen sunucu tarafinda render ediliyor; oynatma linkleri ise
 * /api/v1/security/movie-access ve /api/v1/security/episode-access
 * endpoint'lerinden JSON olarak geliyor.
 */
class LookMovie2 : MainAPI() {
    override var mainUrl = "https://www.lookmovie2.to"
    override var name = "LookMovie2"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d" to "Latest Movies",
        "$mainUrl/shows/page/%d" to "TV Episodes",
    )

    private val headers = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.format(page), referer = "$mainUrl/").document
        val items = document.select("div.movie-item-style-1").mapNotNull { it.toSearchResult() }
        // Dizi listesinde ayni dizi birden fazla bolumle cikabiliyor; tekilleştir.
        val home = if (request.data.contains("/shows/")) {
            items.distinctBy { it.url.substringBefore("?") }
        } else {
            items
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h6 a") ?: selectFirst("a[href*=/movies/view/]")
            ?: selectFirst("a[href*=/shows/view/]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = anchor.text().trim().ifBlank { return null }
        val poster = fixUrlNull(selectFirst("img[data-src]")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img[src]")?.attr("src"))
        val year = selectFirst("p.year")?.text()?.trim()?.toIntOrNull()
        val rating = selectFirst("p.rate span")?.text()?.trim()?.toDoubleOrNull()
        val isSeries = href.contains("/shows/")

        // Dizi kartlari bolum query'si ile gelir; dizi sayfasina indirge.
        val cleanHref = if (isSeries) href.substringBefore("?") else href

        return if (isSeries) {
            newTvSeriesSearchResponse(title, cleanHref, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, cleanHref, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val movies = app.get("$mainUrl/movies/search/?q=$q", referer = "$mainUrl/").document
            .select("div.movie-item-style-1").mapNotNull { it.toSearchResult() }
        val shows = app.get("$mainUrl/shows/search/?q=$q", referer = "$mainUrl/").document
            .select("div.movie-item-style-1").mapNotNull { it.toSearchResult() }
        return (movies + shows).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val html = document.html()

        if (url.contains("/shows/")) {
            val title = Regex("""title:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
                ?: document.selectFirst("h1.bd-hd")?.text()?.trim() ?: return null
            val year = Regex("""year:\s*'(\d{4})'""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            val poster = fixUrlNull(
                Regex("""poster_medium:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
                    ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            )
            val plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?: document.selectFirst("p.describe")?.text()?.trim()
            val idShow = Regex("""id_show:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
                ?: return null

            val entries = app.get("$mainUrl/api/v2/download/episode/list?id=$idShow", headers = headers)
                .parsedSafe<EpisodeListResponse>()?.list ?: return null

            val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
            for ((seasonKey, seasonMap) in entries) {
                val season = seasonKey.toIntOrNull() ?: continue
                for ((episodeKey, ep) in seasonMap) {
                    val episode = episodeKey.toIntOrNull() ?: continue
                    val idEpisode = ep.idEpisode ?: continue
                    episodes.add(
                        newEpisode("$mainUrl/shows/view/${url.substringAfter("/shows/view/").substringBefore("?")}?season=$season&episode=$episode&id_episode=$idEpisode") {
                            this.name = "S${season}E${episode}"
                            this.season = season
                            this.episode = episode
                            this.posterUrl = poster
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedWith(
                compareBy({ it.season }, { it.episode })
            )) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        val title = document.selectFirst("h1.bd-hd")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val year = Regex("""(\d{4})\s*$""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""year:\s*'(\d{4})'""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val tags = document.selectFirst("meta[name=keywords]")?.attr("content")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val rating = document.selectFirst("div.rate span")?.text()?.trim()?.toDoubleOrNull()
        val cleanTitle = title.replace(Regex("""\s*\d{4}\s*$"""), "").trim()

        return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = rating?.let { Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return if (data.contains("/shows/")) {
            loadEpisodeLinks(data, subtitleCallback, callback)
        } else {
            loadMovieLinks(data, subtitleCallback, callback)
        }
    }

    private suspend fun loadMovieLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.substringAfter("/movies/view/").substringBefore("/")
        val playUrl = "$mainUrl/movies/play/$slug"
        val html = app.get(playUrl, referer = "$mainUrl/").text
        val idMovie = Regex("""id_movie:\s*(\d+)""").find(html)?.groupValues?.get(1) ?: return false
        val hash = Regex("""hash:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return false
        val expires = Regex("""expires:\s*(\d+)""").find(html)?.groupValues?.get(1) ?: return false

        val access = app.get(
            "$mainUrl/api/v1/security/movie-access?id_movie=$idMovie&hash=$hash&expires=$expires",
            headers = headers,
            referer = playUrl
        ).parsedSafe<AccessResponse>() ?: return false

        emitStreams(access, subtitleCallback, callback)
        return true
    }

    private suspend fun loadEpisodeLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.substringAfter("/shows/view/").substringBefore("/").substringBefore("?")
        val idEpisode = Regex("""id_episode=(\d+)""").find(data)?.groupValues?.get(1) ?: return false
        val season = Regex("""season=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()
        val episode = Regex("""episode=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()

        val playUrl = "$mainUrl/shows/play/$slug" +
            if (season != null && episode != null) "#S$season-E$episode-$idEpisode" else ""
        val html = app.get(playUrl, referer = "$mainUrl/").text
        val hash = Regex("""hash:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return false
        val expires = Regex("""expires:\s*(\d+)""").find(html)?.groupValues?.get(1) ?: return false

        val access = app.get(
            "$mainUrl/api/v1/security/episode-access?id_episode=$idEpisode&hash=$hash&expires=$expires",
            headers = headers,
            referer = playUrl
        ).parsedSafe<AccessResponse>() ?: return false

        emitStreams(access, subtitleCallback, callback)
        return true
    }

    private suspend fun emitStreams(
        access: AccessResponse,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        access.streams?.forEach { (quality, streamUrl) ->
            if (streamUrl.isNullOrBlank()) return@forEach
            val label = if (quality.all { it.isDigit() }) "${quality}p" else quality
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} $label",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(label)
                }
            )
        }

        access.subtitles?.forEach { subtitle ->
            val file = subtitle.file ?: return@forEach
            val lang = subtitle.language ?: "Unknown"
            subtitleCallback.invoke(SubtitleFile(lang, fixUrl(file)))
        }
    }

    // ---------------------------------------------------------------- models
    data class EpisodeListResponse(
        @JsonProperty("list") val list: Map<String, Map<String, EpisodeListItem>>? = null,
    )

    data class EpisodeListItem(
        @JsonProperty("id_episode") val idEpisode: String? = null,
    )

    data class AccessResponse(
        @JsonProperty("streams") val streams: Map<String, String?>? = null,
        @JsonProperty("subtitles") val subtitles: List<Subtitle>? = null,
    )

    data class Subtitle(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("file") val file: String? = null,
    )
}
