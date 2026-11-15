package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class ElisWorldProvider : MainAPI() {
    override var mainUrl = "https://elisworld.tv"
    override var name = "ElisWorld"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.Movie)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val TMDB_KEY = "68e094699525b18a70bab2f86b1fa706"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/cartoons/?paged=" to "Cartoons & Animated Series",
        "$mainUrl/cartoons/?network=adult-swim&paged=" to "Adult Swim & Adult Animation",
        "$mainUrl/cartoons/?network=cartoon-network&paged=" to "Cartoon Network",
        "$mainUrl/cartoons/?network=nickelodeon&paged=" to "Nickelodeon",
        "$mainUrl/cartoons/?network=disney-channel&paged=" to "Disney Channel",
        "$mainUrl/cartoons/?network=netflix&paged=" to "Netflix Animation",
        "$mainUrl/movies/?paged=" to "Animated Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.endsWith("paged=")) {
            "${request.data}$page"
        } else {
            request.data
        }

        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val items = document.select("a.archive-item, a.show-card, a.ew-cw-card, a.ew-mv-card, a:has(.ew-cw-title)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.selectFirst("a[href*='paged=${page + 1}']") != null || items.size >= 24

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().replace(" ", "+")
        val url = "$mainUrl/?s=$cleanQuery"
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        return document.select("a.archive-item, a.show-card, a.ew-cw-card, a.ew-mv-card, a:has(.ew-cw-title)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val html = document.html()

        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" |")?.trim()
            ?: document.title().substringBefore(" |").trim()
        val title = rawTitle.cleanTitle()

        val posterUrl = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
                ?: document.selectFirst("div.show-thumb img, div.ew-cw-thumb img, img.wp-post-image")?.let { img ->
                    img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                        ?: img.attr("data-src").takeIf { it.isNotBlank() }
                }
        )

        val plot = (
            document.selectFirst("meta[name=description]")?.attr("content")
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
                ?: document.selectFirst("div.desc, p.summary, div.overview")?.text()
        )?.cleanTitle()

        val isMovie = url.contains("/movies/")

        if (isMovie) {
            val year = Regex("""\b(19\d\d|20\d\d)\b""").find(title + " " + (plot ?: ""))?.value?.toIntOrNull()
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        }

        // Cartoon / Animated Series
        val tmdbId = Regex("""const\s+id\s*=\s*["'](\d+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-id=["'](\d+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]idd?=(\d+)""").find(html)?.groupValues?.get(1)

        val showSlug = Regex("""const\s+showSlug\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: url.removeSuffix("/").substringAfterLast("/")

        val seasonOptions = document.select("#season-select option, select.season-select option, select[id*='season'] option")
        val seasons = if (seasonOptions.isNotEmpty()) {
            seasonOptions.mapNotNull { it.attr("value").trim().toIntOrNull() }.filter { it > 0 }
        } else {
            listOf(1)
        }

        val episodesList = arrayListOf<Episode>()

        if (!tmdbId.isNullOrBlank()) {
            for (season in seasons) {
                try {
                    val seasonApiUrl = "$mainUrl/wp-json/ew/v1/season?id=$tmdbId&season=$season"
                    val jsonStr = app.get(seasonApiUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
                    val seasonData = tryParseJson<SeasonResponse>(jsonStr)
                    seasonData?.episodes?.forEach { ep ->
                        val epNum = ep.episodeNumber ?: return@forEach
                        val epName = ep.name?.cleanTitle()?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                        val epUrl = "$mainUrl/watch/$showSlug/s${season}e${epNum}/"
                        val epThumb = ep.stillPath?.let { path ->
                            if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w500$path"
                        }
                        episodesList.add(
                            newEpisode(epUrl) {
                                this.name = epName
                                this.season = season
                                this.episode = epNum
                                this.posterUrl = epThumb
                                this.description = ep.overview?.cleanTitle()
                            }
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        // Fallback if no episodes were loaded via API
        if (episodesList.isEmpty()) {
            val watchLinks = document.select("a[href*='/watch/']")
            if (watchLinks.isNotEmpty()) {
                watchLinks.forEachIndexed { idx, a ->
                    val href = fixUrlNull(a.attr("href")) ?: return@forEachIndexed
                    val name = a.text().cleanTitle().takeIf { it.isNotBlank() } ?: "Episode ${idx + 1}"
                    episodesList.add(
                        newEpisode(href) {
                            this.name = name
                            this.season = 1
                            this.episode = idx + 1
                        }
                    )
                }
            } else {
                episodesList.add(
                    newEpisode("$mainUrl/watch/$showSlug/s1e1/") {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodesList) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("User-Agent" to USER_AGENT), timeout = 20L).document
        val html = document.html()
        var foundLinks = false

        // Collect all server URLs from buttons + raw HTML
        val serverUrls = (
            document.select("button.server[data-url], button[data-url], .server[data-url]")
                .map { it.attr("data-url") } +
                Regex("""data-url=["'](https?://[^"']+)["']""").findAll(html).map { it.groupValues[1] }
            )
            .map { it.trim().replace("&amp;", "&") }
            .filter { it.startsWith("http") }
            .distinct()

        // 1. Direct MP4 CDN stream (SD 360p) - instant, downloadable
        serverUrls.filter { it.contains("123moviespremium.net") }.forEach { mp4 ->
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name - Direct MP4 (SD 360p)",
                    url = mp4,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to USER_AGENT
                    )
                    this.quality = Qualities.P360.value
                }
            )
            foundLinks = true
        }

        // 2. Resolve TMDB id / season / episode (window.ewEpisode or server URLs)
        val tmdbId = Regex("""tmdb_id["']?\s*:\s*["']?(\d+)""").find(html)?.groupValues?.get(1)
            ?: serverUrls.firstNotNullOfOrNull { Regex("""/(?:tv|movie)/(\d+)""").find(it)?.groupValues?.get(1) }
        val tvMatch = serverUrls.firstNotNullOfOrNull { Regex("""/tv/\d+/(\d+)/(\d+)""").find(it) }
        val season = tvMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""season["']?\s*:\s*["']?(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""/s(\d+)e\d+/?""").find(data)?.groupValues?.get(1)?.toIntOrNull()
        val episode = tvMatch?.groupValues?.get(2)?.toIntOrNull()
            ?: Regex("""episode["']?\s*:\s*["']?(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""/s\d+e(\d+)/?""").find(data)?.groupValues?.get(1)?.toIntOrNull()
        val isMovie = data.contains("/movies/") || (season == null && episode == null)

        if (tmdbId != null) {
            // 3. VidLink API (DASH stream + captions) - fast API call, no WebView hang
            try {
                val vl = extractVidlinkApi(tmdbId, isMovie, season, episode)
                val stream = vl?.stream
                if (stream != null) {
                    val playlist = stream.playlist
                    if (!playlist.isNullOrBlank()) {
                        val linkHeaders = mutableMapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://vidlink.pro/",
                            "Origin" to "https://vidlink.pro"
                        )
                        stream.playlistHeaders?.forEach { (k, v) -> linkHeaders[k] = v }
                        val linkType = when {
                            stream.type.equals("dash", true) || playlist.contains(".mpd") -> ExtractorLinkType.DASH
                            playlist.contains(".m3u8") -> ExtractorLinkType.M3U8
                            else -> ExtractorLinkType.VIDEO
                        }
                        val heightStr = Regex("""_(\d{3,4})_h26[45]_""").find(playlist)?.groupValues?.get(1)
                        val qualityVal = if (heightStr != null) getQualityFromName(heightStr) else Qualities.Unknown.value
                        val label = if (heightStr != null) "$name - VidLink (${heightStr}p)" else "$name - VidLink"

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = label,
                                url = playlist,
                                type = linkType
                            ) {
                                this.referer = "https://vidlink.pro/"
                                this.headers = linkHeaders
                                this.quality = qualityVal
                            }
                        )
                        foundLinks = true
                    }
                    stream.captions?.forEach { cap ->
                        val url = cap.url ?: return@forEach
                        val lang = cap.language?.takeIf { it.isNotBlank() } ?: "Unknown"
                        subtitleCallback.invoke(SubtitleFile(lang, url))
                    }
                }
            } catch (_: Exception) {}

            // 4. OpenSubtitles (English, Turkish, and 15+ languages in SRT format)
            try {
                loadOpenSubtitles(tmdbId, isMovie, season, episode, subtitleCallback)
            } catch (_: Exception) {}
        }

        // 5. Fallback for any other standard third-party embeds (excluding gomovies/vidlink which lack built-in extractors)
        serverUrls.filter {
            !it.contains("123moviespremium.net") &&
                !it.contains("gomovies-sx.net") &&
                !it.contains("vidlink.pro")
        }.forEach { url ->
            try {
                if (loadExtractor(url, data, subtitleCallback, callback)) {
                    foundLinks = true
                }
            } catch (_: Exception) {}
        }

        return foundLinks
    }

    private suspend fun extractVidlinkApi(
        tmdbId: String,
        isMovie: Boolean,
        season: Int?,
        episode: Int?
    ): VidlinkResponse? {
        val tokenJson = app.get(
            "https://enc-dec.app/api/enc-vidlink?text=$tmdbId",
            headers = mapOf("User-Agent" to USER_AGENT),
            timeout = 15L
        ).text
        val token = tryParseJson<EncDecResponse>(tokenJson)?.result ?: return null
        val apiUrl = if (isMovie) {
            "https://vidlink.pro/api/b/movie/$token?multiLang=0"
        } else {
            "https://vidlink.pro/api/b/tv/$token/${season ?: 1}/${episode ?: 1}?multiLang=0"
        }
        val json = app.get(
            apiUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://vidlink.pro/",
                "X-Playback-Environment" to "webkit"
            ),
            timeout = 20L
        ).text
        return tryParseJson<VidlinkResponse>(json)
    }

    private suspend fun getImdbId(tmdbId: String, isMovie: Boolean): String? {
        try {
            val url = if (isMovie) {
                "https://data.vidsrc.sh/api.php?type=movie&tmdb=$tmdbId"
            } else {
                "https://data.vidsrc.sh/api.php?type=tv&tmdb=$tmdbId&season=1&episode=1"
            }
            val text = app.get(url, headers = mapOf("User-Agent" to USER_AGENT), timeout = 10L).text
            Regex(""""imdb_id"\s*:\s*"(tt\d+)"""").find(text)?.groupValues?.get(1)?.let { return it }
        } catch (_: Exception) {}
        try {
            val type = if (isMovie) "movie" else "tv"
            val text = app.get(
                "https://api.themoviedb.org/3/$type/$tmdbId/external_ids?api_key=$TMDB_KEY",
                timeout = 10L
            ).text
            Regex(""""imdb_id"\s*:\s*"(tt\d+)"""").find(text)?.groupValues?.get(1)?.let { return it }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun loadOpenSubtitles(
        tmdbId: String,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val imdb = getImdbId(tmdbId, isMovie)?.removePrefix("tt") ?: return
        val url = if (isMovie) {
            "https://rest.opensubtitles.org/search/imdbid-$imdb"
        } else {
            "https://rest.opensubtitles.org/search/episode-${episode ?: 1}/imdbid-$imdb/season-${season ?: 1}"
        }
        val text = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "TemporaryUserAgent",
                "X-User-Agent" to "TemporaryUserAgent"
            ),
            timeout = 15L
        ).text
        val subs = tryParseJson<List<OpenSubtitleItem>>(text) ?: return
        subs.filter { it.subFormat.equals("srt", true) && !it.subDownloadLink.isNullOrBlank() }
            .groupBy { it.languageName ?: it.iso639 ?: "Unknown" }
            .forEach { (lang, list) ->
                val best = list.sortedWith(
                    compareBy<OpenSubtitleItem> { it.subHearingImpaired == "1" }
                        .thenByDescending { it.subDownloadsCnt?.toIntOrNull() ?: 0 }
                ).first()
                val link = best.subDownloadLink!!.removeSuffix(".gz")
                subtitleCallback.invoke(SubtitleFile(lang, link))
            }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/cartoons/") && !href.contains("/movies/")) return null
        if (href.contains("?")) return null

        val rawTitle = this.selectFirst(".ew-cw-title, .show-title, .title")?.text()?.trim()
            ?: this.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val title = rawTitle.cleanTitle()

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
            }
        )

        val tvType = if (href.contains("/movies/")) TvType.Movie else TvType.Cartoon

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun String.cleanTitle(): String {
        return this.replace("&#8217;", "'")
            .replace("&#8216;", "'")
            .replace("&#038;", "&")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&quot;", "\"")
            .trim()
    }

    data class SeasonResponse(
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null
    )

    data class EpisodeItem(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null
    )

    data class EncDecResponse(
        @JsonProperty("result") val result: String? = null
    )

    data class VidlinkResponse(
        @JsonProperty("stream") val stream: VidlinkStream? = null
    )

    data class VidlinkStream(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("playlist") val playlist: String? = null,
        @JsonProperty("playlistHeaders") val playlistHeaders: Map<String, String>? = null,
        @JsonProperty("captions") val captions: List<VidlinkCaption>? = null
    )

    data class VidlinkCaption(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class OpenSubtitleItem(
        @JsonProperty("ISO639") val iso639: String? = null,
        @JsonProperty("LanguageName") val languageName: String? = null,
        @JsonProperty("SubFormat") val subFormat: String? = null,
        @JsonProperty("SubDownloadLink") val subDownloadLink: String? = null,
        @JsonProperty("SubHearingImpaired") val subHearingImpaired: String? = null,
        @JsonProperty("SubDownloadsCnt") val subDownloadsCnt: String? = null
    )
}
