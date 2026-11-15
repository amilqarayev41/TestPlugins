package com.example

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import okhttp3.Interceptor

class CizgiMaxProvider : MainAPI() {
    override var mainUrl = "https://cizgimax.online"
    override var name = "CizgiMax"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie
    )

    // ======================== Main-page sections ========================

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-eklenenler/" to "Yeni Eklenenler",
        "$mainUrl/arsiv/?sort=populer&donem=daily" to "Trendler",
        "$mainUrl/diziler/cizgi-film/" to "Çizgi Filmler",
        "$mainUrl/diziler/anime/" to "Animeler",
        "$mainUrl/diziler/dizi/" to "Diziler",
        "$mainUrl/film/" to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val document = app.get(url).document
        val items = document.select("div.film-item, div.top-list-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    // ======================== Search =====================================

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ara/?q=$query"
        val document = app.get(url).document
        return document.select("div.film-item").mapNotNull { it.toSearchResult() }
    }

    // ======================== Detail / load ==============================

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1 a.anime-title-link, h1.anime-title, h1")?.text()
            ?.substringBefore(" izle")?.trim() ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.anime-poster img")?.attr("src")
                ?: document.selectFirst("img.anime-poster")?.attr("src")
        )

        val description = document.selectFirst("p.anime-desc, div.anime-desc-wrap p")?.text()?.trim()

        val year = document.select("li:contains(Yayın Yılı) strong").firstOrNull()?.text()
            ?.trim()?.toIntOrNull()

        val rating = document.selectFirst("span.meta-score-val")?.text()
            ?.trim()?.replace(",", ".")?.toDoubleOrNull()

        val tags = document.select("li.meta-genres a, .genre-links a").map { it.text().trim() }

        val category = document.select("li:contains(Kategori) strong").firstOrNull()?.text()?.lowercase() ?: ""
        val tvType = when {
            category.contains("çizgi") -> TvType.Cartoon
            category.contains("anime") -> TvType.Anime
            category.contains("dizi") -> TvType.TvSeries
            category.contains("film") || url.contains("/film/") -> TvType.Movie
            else -> TvType.TvSeries
        }

        val episodes = mutableListOf<Episode>()
        val seasonPanes = document.select("div[data-season-pane]")

        if (seasonPanes.isNotEmpty()) {
            seasonPanes.forEach { pane ->
                val seasonNum = pane.attr("data-season-pane").toIntOrNull() ?: 1
                pane.select("a.ep-num-btn").forEach { btn ->
                    val epHref = fixUrlNull(btn.attr("href")) ?: return@forEach
                    val epNum = btn.selectFirst(".ep-num-label")?.text()?.trim()?.toIntOrNull()
                    val epTitle = btn.attr("title").takeIf { it.isNotBlank() } ?: "Bölüm $epNum"
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }
        } else {
            document.select("a.ep-num-btn").forEach { btn ->
                val epHref = fixUrlNull(btn.attr("href")) ?: return@forEach
                val epNum = btn.selectFirst(".ep-num-label")?.text()?.trim()?.toIntOrNull()
                val epTitle = btn.attr("title").takeIf { it.isNotBlank() } ?: "Bölüm $epNum"
                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.season = 1
                        this.episode = epNum
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = Score.from10(rating)
                this.tags = tags
            }
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.score = Score.from10(rating)
            this.tags = tags
        }
    }

    // ======================== Video / stream extraction ==================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val html = document.html()

        val serverJson = extractServersJson(html)
        val serverList = if (!serverJson.isNullOrBlank()) {
            tryParseJson<List<ServerItem>>(serverJson)
        } else {
            null
        }

        var foundAny = false

        if (!serverList.isNullOrEmpty()) {
            for (server in serverList) {
                // Skip disabled / copyright notices
                if (server.notice.equals("copyright", ignoreCase = true)) continue

                val rawLabel = server.label ?: "Sunucu"
                val langPrefix = when (server.lang) {
                    "dub" -> "Dublaj"
                    "sub" -> "Altyazı"
                    else -> ""
                }
                val sourceName = if (langPrefix.isNotEmpty() && !rawLabel.contains(langPrefix, ignoreCase = true)) {
                    "$langPrefix - $rawLabel"
                } else {
                    rawLabel
                }

                // 1. ResolveUrl flow (Tau-video / animecix)
                if (!server.resolveUrl.isNullOrBlank()) {
                    try {
                        val resolveFull = fixUrl(server.resolveUrl)
                        val npRes = app.get(resolveFull, referer = data).parsedSafe<NpResolveResponse>()
                        if (npRes?.id != null) {
                            val tauRes = app.get("https://tau-video.xyz/api/video/${npRes.id}").parsedSafe<TauVideoResponse>()
                            tauRes?.urls?.forEach { tauUrl ->
                                val vUrl = tauUrl.url ?: return@forEach
                                val quality = getQualityFromName(tauUrl.label)
                                val labelStr = tauUrl.label ?: "HD"
                                callback.invoke(
                                    newExtractorLink(
                                        sourceName,
                                        "$sourceName ($labelStr)",
                                        vUrl,
                                        ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://tau-video.xyz/"
                                        this.headers = mapOf(
                                            "Referer" to "https://tau-video.xyz/",
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                        )
                                        this.quality = quality
                                    }
                                )
                                foundAny = true
                            }

                            // Subtitles from animecix
                            if (tauRes?.titleId != null) {
                                try {
                                    val subUrl = "https://animecix.tv/secure/episode-videos?titleId=${tauRes.titleId}&season=${tauRes.seasonNumber ?: 1}&episode=${tauRes.episodeNumber ?: 1}"
                                    val animecixRes = app.get(subUrl).parsedSafe<List<AnimecixVideoItem>>()
                                    val targetEmbed = "/embed/${npRes.id}"
                                    animecixRes?.firstOrNull { it.url?.contains(targetEmbed) == true }?.captions?.forEach { cap ->
                                        val cUrl = cap.url ?: return@forEach
                                        val cLang = cap.name ?: cap.lang ?: "Türkçe"
                                        subtitleCallback.invoke(SubtitleFile(cLang, cUrl))
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                }

                // 2. Direct native streamUrl flow (HLS / mrc / native)
                if (!server.streamUrl.isNullOrBlank()) {
                    try {
                        val fullStreamUrl = fixUrl(server.streamUrl)
                        val quality = getQualityFromName(server.label)
                        callback.invoke(
                            newExtractorLink(
                                sourceName,
                                sourceName,
                                fullStreamUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = data
                                this.headers = mapOf(
                                    "Referer" to data,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                )
                                this.quality = quality
                            }
                        )
                        foundAny = true

                        // Check metaUrl for subtitles
                        if (!server.metaUrl.isNullOrBlank()) {
                            try {
                                val metaFull = fixUrl(server.metaUrl)
                                val metaResp = app.get(metaFull, referer = data).parsedSafe<MetaResponse>()
                                metaResp?.subs?.forEach { sub ->
                                    val sUrl = fixUrlNull(sub.url) ?: return@forEach
                                    val sLang = sub.label ?: sub.lang ?: "Türkçe"
                                    subtitleCallback.invoke(SubtitleFile(sLang, sUrl))
                                }
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }

                // 3. Src iframe flow (/oynat/?t=...)
                if (!server.src.isNullOrBlank()) {
                    try {
                        val fullSrc = fixUrl(server.src)
                        val resp = app.get(fullSrc, referer = data)
                        val finalUrl = resp.url
                        val body = resp.text

                        // Check if redirected to an external host (Google Drive, Vidmoly, etc.)
                        if (finalUrl != fullSrc) {
                            if (finalUrl.contains("drive.google.com")) {
                                if (extractGoogleDrive(finalUrl, sourceName, callback)) {
                                    foundAny = true
                                }
                            } else if (loadExtractor(finalUrl, referer = data, subtitleCallback, callback)) {
                                foundAny = true
                            }
                        }

                        // Direct m3u8 in response body
                        val cleanBody = body.replace("\\/", "/")
                        val m3u8Matches = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(cleanBody)
                        for (match in m3u8Matches) {
                            val streamUrl = match.value.replace("&amp;", "&")
                            callback.invoke(
                                newExtractorLink(
                                    sourceName,
                                    sourceName,
                                    streamUrl,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = finalUrl
                                    this.headers = mapOf(
                                        "Referer" to finalUrl,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                    )
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            foundAny = true
                        }

                        // Direct mp4 in response body
                        val mp4Matches = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(cleanBody)
                        for (match in mp4Matches) {
                            val streamUrl = match.value.replace("&amp;", "&")
                            callback.invoke(
                                newExtractorLink(
                                    sourceName,
                                    sourceName,
                                    streamUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = finalUrl
                                    this.headers = mapOf(
                                        "Referer" to finalUrl,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                    )
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            foundAny = true
                        }

                        // Nested iframes
                        val iframes = Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(cleanBody)
                        for (iframe in iframes) {
                            val iUrl = fixUrl(iframe.groupValues[1].replace("&amp;", "&"))
                            if (iUrl.contains("drive.google.com")) {
                                if (extractGoogleDrive(iUrl, sourceName, callback)) {
                                    foundAny = true
                                }
                            } else if (loadExtractor(iUrl, referer = finalUrl, subtitleCallback, callback)) {
                                foundAny = true
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Fallback: check if page contains any iframes directly
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
            if (src.isNotBlank()) {
                try {
                    val fullUrl = fixUrl(src)
                    if (fullUrl.contains("drive.google.com")) {
                        if (extractGoogleDrive(fullUrl, "Google Drive", callback)) {
                            foundAny = true
                        }
                    } else if (loadExtractor(fullUrl, referer = data, subtitleCallback, callback)) {
                        foundAny = true
                    }
                } catch (_: Exception) {}
            }
        }

        return foundAny
    }

    private suspend fun extractGoogleDrive(
        url: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileId = Regex("""(?:drive\.google\.com/(?:file/d/|open\?id=)|/download\?id=)([a-zA-Z0-9_-]+)""")
            .find(url)?.groupValues?.get(1) ?: return false

        return try {
            val checkUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download"
            val html = app.get(
                checkUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            ).text
            val uuid = Regex("""name=["']uuid["']\s*value=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            val directUrl = if (!uuid.isNullOrBlank()) {
                "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t&uuid=$uuid"
            } else {
                "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            }
            callback.invoke(
                newExtractorLink(
                    sourceName,
                    "$sourceName (Drive)",
                    directUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://drive.google.com/"
                    this.headers = mapOf(
                        "Referer" to "https://drive.google.com/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                    this.quality = Qualities.P1080.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val builder = request.newBuilder()
            builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            if (url.contains("google.com") || url.contains("googleusercontent.com")) {
                builder.header("Referer", "https://drive.google.com/")
            } else if (url.contains("okcdn.ru") || url.contains("dzen.ru")) {
                builder.header("Referer", "https://dzen.ru/")
            } else {
                val referer = if (extractorLink.referer.isNotBlank()) extractorLink.referer else "$mainUrl/"
                builder.header("Referer", referer)
            }
            extractorLink.headers.forEach { (key, value) ->
                builder.header(key, value)
            }
            chain.proceed(builder.build())
        }
    }

    // ======================== Helpers ====================================

    private fun extractServersJson(html: String): String? {
        val match = Regex("""var\s+servers\s*=\s*JSON\.parse\(atob\(["']([^"']+)["']\)\)""").find(html)
            ?: Regex("""JSON\.parse\(atob\(["']([^"']+)["']\)\)""").find(html)

        val b64 = match?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
        return try {
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("a.film-name, a.top-name, a.phc-title")
        val title = titleEl?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val href = fixUrlNull(
            selectFirst("a.poster")?.attr("href")
                ?: titleEl?.attr("href")
                ?: selectFirst("a")?.attr("href")
        ) ?: return null

        val posterUrl = fixUrlNull(
            selectFirst("img")?.attr("src")
                ?: selectFirst("img")?.attr("data-src")
        )

        val tag = selectFirst("span.corner-tag")?.text()?.lowercase() ?: ""
        val type = when {
            tag.contains("çizgi") -> TvType.Cartoon
            tag.contains("anime") -> TvType.Anime
            tag.contains("dizi") -> TvType.TvSeries
            tag.contains("film") || href.contains("/film/") -> TvType.Movie
            else -> TvType.TvSeries
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun getQualityFromName(label: String?): Int {
        return when {
            label == null -> Qualities.Unknown.value
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // ======================== Models =====================================

    data class ServerItem(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("streamUrl") val streamUrl: String? = null,
        @JsonProperty("metaUrl") val metaUrl: String? = null,
        @JsonProperty("resolveUrl") val resolveUrl: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("embedId") val embedId: Any? = null,
        @JsonProperty("notice") val notice: String? = null
    )

    data class MetaResponse(
        @JsonProperty("hls") val hls: String? = null,
        @JsonProperty("subs") val subs: List<MetaSub>? = null
    )

    data class MetaSub(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class NpResolveResponse(
        @JsonProperty("id") val id: String? = null
    )

    data class TauVideoResponse(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("title_id") val titleId: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("urls") val urls: List<TauUrl>? = null
    )

    data class TauUrl(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class AnimecixVideoItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("captions") val captions: List<AnimecixCaption>? = null
    )

    data class AnimecixCaption(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("url") val url: String? = null
    )
}
