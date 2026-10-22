package com.kendirepo

import android.util.Base64
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * FilmBoxIzle — filmboxizle.net
 *
 * WordPress tabanli. Oynatma iframe'i hotstream.club uzerinden geliyor;
 * hotstream "bePlayer" yapilandirmasini AES (CryptoJS passphrase) ile
 * sifreliyor. Anahtar, iframe HTML'indeki bePlayer(token, data) cagrisinda.
 */
class FilmBoxIzle : MainAPI() {
    override var mainUrl = "https://filmboxizle.net"
    override var name = "FilmBoxIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenenler",
        "$mainUrl/dizi/aksiyon-filmleri/" to "Aksiyon",
        "$mainUrl/dizi/komedi-filmleri/" to "Komedi",
        "$mainUrl/dizi/korku-filmleri/" to "Korku",
        "$mainUrl/dizi/bilim-kurgu-filmleri/" to "Bilim Kurgu",
        "$mainUrl/dizi/dram-filmleri/" to "Dram",
        "$mainUrl/dizi/yerli-filmler/" to "Yerli Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val document = app.get(request.data, referer = "$mainUrl/").document
        val items = document.select("div.movie-box").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.film-ismi a") ?: selectFirst("div.poster a") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = anchor.text().trim().ifBlank { return null }
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))
        val year = selectFirst("div.film-yil")?.text()?.trim()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$q", referer = "$mainUrl/").document
        return document.select("div.movie-box").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document

        val title = document.selectFirst("h1.title-border")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim() ?: return null
        val original = document.selectFirst("div.bolum-ismi")?.text()?.trim()
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("div#film-aciklama")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = document.selectFirst("a[href*=/yil/]")?.text()?.trim()?.toIntOrNull()
        val actors = document.select("a[href*=/oyuncu/]").map {
            com.lagradost.cloudstream3.Actor(it.text().trim())
        }.distinctBy { it.name }
        val tags = document.select("a[href*=/dizi/]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val duration = document.select("div.list-item")
            .firstOrNull { it.text().contains("Süre") }
            ?.text()?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = duration
            this.plot = listOfNotNull(original, plot).joinToString("\n")
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        val iframes = document.select("iframe").mapNotNull { iframe ->
            val src = iframe.attr("data-litespeed-src").ifBlank { iframe.attr("data-src") }
                .ifBlank { iframe.attr("src") }
            src.takeIf { it.startsWith("http") }
        }.filterNot { it.contains("facebook.com") || it.contains("youtube.com") }
            .distinct()

        var found = false
        for (iframe in iframes) {
            if (iframe.contains("hotstream.")) {
                found = extractHotStream(iframe, data, subtitleCallback, callback) || found
            } else {
                found = com.lagradost.cloudstream3.utils.loadExtractor(
                    iframe, data, subtitleCallback, callback
                ) || found
            }
        }
        return found
    }

    private suspend fun extractHotStream(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(embedUrl, referer = referer).text
        val match = Regex("""bePlayer\(\s*'([^']+)'\s*,\s*'((?:[^'\\]|\\.)*)'""")
            .find(html) ?: return false
        val token = match.groupValues[1]
        val payload = match.groupValues[2].replace("\\'", "'")

        val configJson = decryptCryptoJs(payload, token) ?: return false
        val config = runCatching {
            jacksonObjectMapper().readValue(configJson, Map::class.java) as Map<*, *>
        }.getOrNull() ?: return false

        val videoLocation = config["video_location"] as? String ?: return false
        val location = fixUrl(videoLocation)

        // video_location genelde HLS playlist'in kendisi ya da ona yonlendiren bir adres.
        val response = app.get(location, referer = videoLocationReferer(embedUrl, referer))
        val body = response.text.trim()
        val stream = when {
            body.startsWith("#EXTM3U") -> location
            body.startsWith("http") -> body.substringBefore("\n").trim()
            else -> location
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name HotStream",
                url = stream,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = videoLocationReferer(embedUrl, referer)
                this.quality = getQualityFromName("")
            }
        )

        // Altyazi listesi varsa (file alanlari) ekle.
        val subtitles = config["strSubtitles"] as? List<*>
        subtitles?.forEach { item ->
            val map = item as? Map<*, *> ?: return@forEach
            val file = map["file"] as? String ?: return@forEach
            val label = map["label"] as? String ?: "Unknown"
            subtitleCallback.invoke(SubtitleFile(label, fixUrl(file)))
        }
        return true
    }

    private fun videoLocationReferer(embedUrl: String, fallback: String): String {
        return if (embedUrl.contains("hotstream.")) "$mainUrl/" else fallback
    }

    /** CryptoJS.AES.decrypt(payload, passphrase) — OpenSSL/EVP_BytesToKey (MD5) uyumlu. */
    private fun decryptCryptoJs(payload: String, passphrase: String): String? {
        return runCatching {
            val map = jacksonObjectMapper().readValue(payload, Map::class.java) as Map<*, *>
            val cipherText = Base64.decode(map["ct"] as String, Base64.DEFAULT)
            val salt = hexToBytes(map["s"] as String)

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
            val key = all.copyOfRange(0, 32)
            val iv = all.copyOfRange(32, 48)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
