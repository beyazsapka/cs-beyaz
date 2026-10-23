package com.kendirepo

import android.util.Base64
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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DiziBox — dizibox.live
 *
 * Yabanci dizi sitesi. Oynatma zinciri:
 *   bolum sayfasi -> /player/king/king.php -> molystream embed
 *   -> CryptoJS AES ile sifreli yapilandirma -> m3u8
 */
class DiziBox : MainAPI() {
    override var mainUrl = "https://www.dizibox.live"
    override var name = "DiziBox"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    private val cfKiller by lazy { CloudflareKiller() }
    private val cfInterceptor by lazy { CloudflareInterceptor(cfKiller) }

    override val mainPage = mainPageOf(
        "$mainUrl/?bolum=son" to "Son Eklenen Bölümler",
        "$mainUrl/tum-bolumler/" to "Tüm Bölümler",
        "$mainUrl/?dizi=one-cikan" to "Dikkat Çeken Diziler",
    )

    private suspend fun getPage(
        url: String,
        referer: String? = null
    ): Document = app.get(
        url,
        headers = mapOf("Referer" to (referer ?: "$mainUrl/")),
        interceptor = cfInterceptor
    ).document

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val document = getPage(request.data)
        val items = when {
            request.data.contains("one-cikan") ->
                document.select("article.article-series-small-grid").mapNotNull { it.toSeriesResult() }
            else ->
                document.select("article.article-episode-card").mapNotNull { it.toEpisodeResult() }
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = false)
    }

    /** Ana sayfadaki son bolum kartlari; dizi sayfasina cozulur. */
    private fun Element.toEpisodeResult(): SearchResponse? {
        val anchor = selectFirst("a.episode-card-title") ?: selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val series = selectFirst("b.series-name")?.text()?.trim()
        val season = selectFirst("span.season")?.text()?.trim()
        val episode = selectFirst("b.episode")?.text()?.trim()
        val title = listOfNotNull(series, season, episode)
            .joinToString(" ").ifBlank { anchor.attr("title").trim() }
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun Element.toSeriesResult(): SearchResponse? {
        val anchor = selectFirst("a.figure") ?: selectFirst("a[href*=/diziler/]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = selectFirst("img")?.attr("alt")?.trim()
            ?: anchor.attr("title").removeSuffix("izle").trim()
                .ifBlank { href.substringAfterLast('/').replace('-', ' ') }
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))
        val rating = selectFirst("span.imdb")?.text()?.trim()?.toDoubleOrNull()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.score = rating?.let { Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Site aramasi anonim isteklerde zaman asimina ugruyor; arsiv listesini
        // indirip baslikta filtreliyoruz.
        val document = getPage("$mainUrl/diziler/")
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return emptyList()

        return document.select("a[href]").asSequence()
            .filter { it.attr("href").matches(Regex("""^https://www\.dizibox\.live/diziler/[^/]+/?$""")) }
            .mapNotNull { anchor ->
                val title = anchor.attr("title").removeSuffix("izle").trim()
                    .ifBlank { anchor.text().trim() }
                if (!title.lowercase().contains(needle)) return@mapNotNull null
                newTvSeriesSearchResponse(title, fixUrl(anchor.attr("href")), TvType.TvSeries)
            }
            .distinctBy { it.url }
            .take(60)
            .toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // Bolum sayfasindan gelindiyse once dizi sayfasini bul.
        if (!url.contains("/diziler/")) {
            val episodeDoc = getPage(url)
            val seriesUrl = episodeDoc.selectFirst("h1 a[href*=/diziler/]")?.attr("href")
                ?: episodeDoc.selectFirst("a[href*=/diziler/]")?.attr("href")
                ?: return null
            return load(fixUrl(seriesUrl))
        }

        val document = getPage(url)
        val title = document.selectFirst("h1 a")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("figure img")?.attr("src"))
        val plot = document.selectFirst("div.tv-story p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = document.selectFirst("a[href*=/yil/]")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("a[href*=/tur/]").map { it.text().trim() }
            .filter { it.isNotBlank() }
        val rating = document.selectFirst("span.label-imdb b")?.text()?.trim()?.toDoubleOrNull()
            ?: document.selectFirst("span.imdb")?.text()?.trim()?.toDoubleOrNull()
        val actors = document.select("a[href*=/oyuncu/]").map {
            com.lagradost.cloudstream3.Actor(it.text().trim())
        }.distinctBy { it.name }
        val trailer = document.selectFirst("div.tv-overview iframe")?.attr("src")
            ?.takeIf { it.contains("youtube") }

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        document.select("div#seasons-list a[href]").forEach { seasonAnchor ->
            val seasonUrl = fixUrlNull(seasonAnchor.attr("href")) ?: return@forEach
            val seasonNumber = Regex("""(\d+)\.\s*Sezon""")
                .find(seasonAnchor.text())?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val seasonDoc = runCatching { getPage(seasonUrl, referer = url) }.getOrNull() ?: return@forEach

            seasonDoc.select("article.grid-box").forEach { epElem ->
                val epAnchor = epElem.selectFirst("div.post-title a[href]") ?: return@forEach
                val epHref = fixUrlNull(epAnchor.attr("href")) ?: return@forEach
                val epTitle = epAnchor.text().trim()
                val season = Regex("""(\d+)\.?\s*Sezon""").find(epTitle)?.groupValues?.get(1)
                    ?.toIntOrNull() ?: seasonNumber
                val episode = Regex("""(\d+)\.?\s*Bölüm""").find(epTitle)?.groupValues?.get(1)
                    ?.toIntOrNull()

                episodes.add(newEpisode(epHref) {
                    this.name = epTitle.ifBlank { "S${season}B${episode ?: 0}" }
                    this.season = season
                    this.episode = episode
                    this.posterUrl = poster
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
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
        val document = getPage(data)
        var found = false

        val iframe = document.selectFirst("div#video-area iframe")?.attr("src")
        if (!iframe.isNullOrBlank()) {
            found = processPlayer(fixUrl(iframe), data, subtitleCallback, callback) || found
        }

        // Alternatif kaynaklar (/2/, /3/ ...)
        document.select("div.video-toolbar option[value]").forEach { option ->
            val altUrl = fixUrlNull(option.attr("value")) ?: return@forEach
            if (altUrl == data) return@forEach
            val altDoc = runCatching { getPage(altUrl, referer = data) }.getOrNull() ?: return@forEach
            val altIframe = altDoc.selectFirst("div#video-area iframe")?.attr("src") ?: return@forEach
            found = processPlayer(fixUrl(altIframe), altUrl, subtitleCallback, callback) || found
        }

        return found
    }

    private suspend fun processPlayer(
        playerUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            playerUrl.contains("/player/king/") -> processKing(playerUrl, pageUrl, subtitleCallback, callback)
            playerUrl.contains("/player/moly/") -> processMoly(playerUrl, pageUrl, subtitleCallback, callback)
            playerUrl.contains("/player/haydi") -> processHaydi(playerUrl, pageUrl, subtitleCallback, callback)
            playerUrl.contains("molystream.org/embed/") -> processEmbed(playerUrl, pageUrl, subtitleCallback, callback)
            else -> loadExtractor(playerUrl, pageUrl, subtitleCallback, callback)
        }
    }

    private suspend fun processKing(
        playerUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = playerUrl.replace("king.php?v=", "king.php?wmode=opaque&v=")
        val kingHtml = runCatching { app.get(url, referer = pageUrl, interceptor = cfInterceptor).text }
            .getOrNull() ?: return false
        val subIframe = Jsoup.parse(kingHtml).selectFirst("div#Player iframe")?.attr("src")
            ?: return false
        return processEmbed(fixUrl(subIframe), pageUrl, subtitleCallback, callback)
    }

    /** moly.php: unescape -> base64 -> HTML -> VidMoly embed. */
    private suspend fun processMoly(
        playerUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = playerUrl.replace("moly.php?h=", "moly.php?wmode=opaque&h=")
        val html = runCatching { app.get(url, referer = pageUrl, interceptor = cfInterceptor).text }
            .getOrNull() ?: return false
        val escaped = Regex("""unescape\("([^"]+)"\)""").find(html)?.groupValues?.get(1)
            ?: return false
        val inner = runCatching {
            val base64 = java.net.URLDecoder.decode(escaped, "UTF-8")
            String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return false

        val subIframe = Jsoup.parse(inner).selectFirst("div#Player iframe")?.attr("src")
            ?: return false
        return loadExtractor(fixUrl(subIframe), pageUrl, subtitleCallback, callback)
    }

    /** haydi.php?v=<base64 ok.ru adresi> */
    private suspend fun processHaydi(
        playerUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val value = Regex("""[?&]v=([^&]+)""").find(playerUrl)?.groupValues?.get(1) ?: return false
        val decoded = runCatching {
            String(Base64.decode(java.net.URLDecoder.decode(value, "UTF-8"), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.startsWith("http") } ?: return false
        return loadExtractor(decoded, pageUrl, subtitleCallback, callback)
    }

    private suspend fun processEmbed(
        embedUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(embedUrl, referer = pageUrl, interceptor = cfInterceptor)
        }.getOrNull() ?: return false
        val body = response.text.trim()

        // Bazi embed adresleri dogrudan HLS playlist donduruyor.
        if (body.startsWith("#EXTM3U")) {
            emitStream(embedUrl, embedUrl, callback)
            return true
        }

        val match = Regex("""AES\.decrypt\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""", RegexOption.DOT_MATCHES_ALL)
            .find(body) ?: return false
        val decrypted = cryptoJsDecrypt(match.groupValues[1], match.groupValues[2]) ?: return false
        val file = Regex("""file:\s*['"]([^'"]+)['"]""").find(decrypted)?.groupValues?.get(1)
            ?: return false

        if (file.contains(".m3u8") || file.contains("/embed/sheila/")) {
            emitStream(file, embedUrl, callback)
            return true
        }
        return loadExtractor(file, pageUrl, subtitleCallback, callback)
    }

    private suspend fun emitStream(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = if (referer.contains("molystream")) "https://dbx.molystream.org/" else referer
                this.quality = getQualityFromName("")
            }
        )
    }

    /** CryptoJS.AES.decrypt(cipher, passphrase) — OpenSSL "Salted__" bicimi. */
    private fun cryptoJsDecrypt(cipherBase64: String, passphrase: String): String? {
        return runCatching {
            val raw = Base64.decode(cipherBase64, Base64.DEFAULT)
            val salted = raw.size > 16 && String(raw, 0, 8, Charsets.ISO_8859_1) == "Salted__"
            val salt = if (salted) raw.copyOfRange(8, 16) else ByteArray(0)
            val data = if (salted) raw.copyOfRange(16, raw.size) else raw

            val material = ByteArrayOutputStream()
            var previous = ByteArray(0)
            while (material.size() < 48) {
                val md = MessageDigest.getInstance("MD5")
                md.update(previous)
                md.update(passphrase.toByteArray(Charsets.UTF_8))
                md.update(salt)
                previous = md.digest()
                material.write(previous)
            }
            val all = material.toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(all.copyOfRange(0, 32), "AES"),
                IvParameterSpec(all.copyOfRange(32, 48))
            )
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }
}
