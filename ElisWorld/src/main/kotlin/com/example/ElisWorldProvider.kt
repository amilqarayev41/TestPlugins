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
        val document = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document
        val html = document.html()
        var foundLinks = false

        // 1. Check server buttons with data-url
        val serverButtons = document.select("button.server, button[data-url], .server[data-url]")
        for (button in serverButtons) {
            val rawUrl = button.attr("data-url").trim().replace("&amp;", "&")
            if (rawUrl.isBlank()) continue

            val serverName = button.selectFirst("span")?.text()?.trim() ?: "Server"
            val qualityText = button.selectFirst(".server-quality")?.text()?.trim() ?: ""
            val label = if (qualityText.isNotBlank()) "$serverName ($qualityText)" else serverName

            if (rawUrl.contains("123moviespremium.net")) {
                val qual = if (label.contains("1080") || label.contains("FHD", true)) {
                    Qualities.P1080.value
                } else {
                    Qualities.P720.value
                }
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - Direct MP4 ($label)",
                        url = rawUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to USER_AGENT
                        )
                        this.quality = qual
                    }
                )
                foundLinks = true
            } else if (rawUrl.contains("gomovies-sx.net")) {
                val ok = extractGoMovies(rawUrl, data, subtitleCallback, callback)
                if (ok) foundLinks = true
                try {
                    loadExtractor(rawUrl, data, subtitleCallback, callback)
                } catch (_: Exception) {}
            } else {
                try {
                    val ok = loadExtractor(rawUrl, data, subtitleCallback, callback)
                    if (ok) foundLinks = true
                } catch (_: Exception) {}
            }
        }

        // 2. Also check iframes in the document
        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (src.contains("acceptable.a-ads") || src.contains("google")) return@forEach
            try {
                val ok = loadExtractor(src, data, subtitleCallback, callback)
                if (ok) foundLinks = true
            } catch (_: Exception) {}
        }

        // 3. Fallback: search regex for any embedded player URLs in script/html
        Regex("""data-url=["'](https?://[^"']+)["']""").findAll(html).forEach { match ->
            val src = match.groupValues[1].replace("&amp;", "&")
            if (src.contains("123moviespremium.net") && !foundLinks) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - Direct MP4",
                        url = src,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to USER_AGENT
                        )
                        this.quality = Qualities.P720.value
                    }
                )
                foundLinks = true
            } else if (src.contains("gomovies-sx.net") || src.contains("vidlink.pro")) {
                try {
                    loadExtractor(src, data, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        return foundLinks
    }

    private suspend fun extractGoMovies(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val isTv = embedUrl.contains("/embed/tv/")
            val vsUrl = if (isTv) {
                val match = Regex("""/embed/tv/(\d+)/(\d+)/(\d+)""").find(embedUrl)
                if (match != null) {
                    val (tmdb, season, ep) = match.destructured
                    "https://gomovies-sx.net/vs_src.php?type=tv&id=$tmdb&season=$season&episode=$ep"
                } else null
            } else {
                val match = Regex("""/embed/movie/(\d+)""").find(embedUrl)
                if (match != null) {
                    val tmdb = match.groupValues[1]
                    "https://gomovies-sx.net/vs_src.php?type=movie&id=$tmdb"
                } else null
            }

            if (vsUrl != null) {
                val jsonText = app.get(
                    vsUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text
                val srcMatch = Regex(""""src"\s*:\s*"([^"]+)"""").find(jsonText)
                val srcUrl = srcMatch?.groupValues?.get(1)?.replace("\\/", "/")
                if (!srcUrl.isNullOrBlank()) {
                    return loadExtractor(srcUrl, embedUrl, subtitleCallback, callback)
                }
            }
            false
        } catch (_: Exception) {
            false
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
}
