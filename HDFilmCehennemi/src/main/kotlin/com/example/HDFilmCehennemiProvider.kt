package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class HDFilmCehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ======================== Main-page sections ========================

    override val mainPage = mainPageOf(
        "$mainUrl/category/tavsiye-filmler-702/"   to "Tavsiye Filmler",
        "$mainUrl/category/nette-ilk-filmler-4/"   to "Nette İlk",
        "$mainUrl/category/1080p-film-izle-702/"   to "1080p Filmler",
        "$mainUrl/category/altyazili-film-izle/"   to "Altyazılı Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}sayfa-$page/" else request.data
        val document = app.get(url).document

        val items = document.select("a.poster").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = document.selectFirst("a.next-page") != null
        )
    }

    // ======================== Quick-search (XHR JSON) ====================

    data class SearchJSON(
        @JsonProperty("query") val query: String? = null,
        @JsonProperty("results") val results: List<String>? = null,
    )

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val raw = app.get(
            "$mainUrl/search?q=$query",
            headers = mapOf(
                "X-Requested-With" to "fetch",
                "Content-Type"     to "application/json",
            ),
        ).text

        val json = parseJson<SearchJSON>(raw)
        val doc = org.jsoup.Jsoup.parse(json.results?.joinToString("") ?: return emptyList())

        return doc.select("a.search-result").mapNotNull { el ->
            val href  = el.attr("href").ifEmpty { return@mapNotNull null }
            val title = el.selectFirst("h4.title")?.text() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { fixImageUrl(it) }
            val year   = el.selectFirst("span.year")?.text()?.toIntOrNull()
            val type   = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = quickSearch(query)

    // ======================== Detail / load ==============================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1.section-title")?.text() ?: ""
        val title = rawTitle
            .replace(Regex("\\s*izle\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        val year = document.selectFirst("h1.section-title small")?.text()
            ?.removeSurrounding("(", ")")?.trim()?.toIntOrNull()
        val poster = document.selectFirst("div.post-info-poster img")
            ?.let { fixImageUrl(it) }
        val description = document.selectFirst("article.post-info-content p")?.text()
        val rating = document.selectFirst("div.post-info-imdb-rating span")
            ?.text()?.substringBefore(" ")?.toDoubleOrNull()
            ?.let { (it * 10).toInt() }
        val tags = document.select("div.post-info-meta a[href*=category]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val trailer = document.selectFirst("button[data-modal^=trailer/]")
            ?.attr("data-modal")?.substringAfter("trailer/")
            ?.let { "https://www.youtube.com/watch?v=$it" }

        // ---- Determine if series or movie ----
        val isSeries = url.contains("/dizi/") ||
            document.selectFirst("div.seasons") != null

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("button.section-tab[data-tab]").forEach { seasonBtn ->
                val seasonNum = seasonBtn.text()
                    .replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                val tabId = seasonBtn.attr("data-tab")

                // Episodes are inside div.tab-content[data-tab=<id>]
                val container = document.selectFirst(
                    "div.tab-content[data-tab=$tabId]"
                ) ?: document.selectFirst(
                    "div.seasons div[data-tab=$tabId]"
                ) ?: document  // fallback

                container.select("a[href*=bolum]").forEachIndexed { idx, epEl ->
                    val epHref = epEl.attr("href").ifEmpty { return@forEachIndexed }
                    val epTitle = epEl.selectFirst("h4.mini-poster-title")?.text()?.trim()
                    val epNum = epTitle?.let {
                        Regex("(\\d+)\\.\\s*Bölüm").find(it)?.groupValues?.get(1)?.toIntOrNull()
                    } ?: (idx + 1)
                    episodes.add(
                        newEpisode(fixUrl(epHref)) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }

            // Fallback: if no season tabs, grab all bolum links directly
            if (episodes.isEmpty()) {
                document.select("a[href*=bolum]").forEachIndexed { idx, epEl ->
                    val epHref = epEl.attr("href").ifEmpty { return@forEachIndexed }
                    val epTitle = epEl.selectFirst("h4.mini-poster-title")?.text()?.trim()
                    episodes.add(
                        newEpisode(fixUrl(epHref)) {
                            this.name = epTitle
                            this.season = 1
                            this.episode = idx + 1
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = rating
                this.tags = tags
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = rating
                this.tags = tags
                addTrailer(trailer)
            }
        }
    }

    // ======================== Load links / extractors ====================

    data class VideoJSON(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: VideoData? = null,
    )

    data class VideoData(
        @JsonProperty("html") val html: String? = null,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document

        // 1) Collect all video IDs from alternative-link buttons
        val videoIds = document.select("button.alternative-link[data-video]")
            .mapNotNull { it.attr("data-video").ifEmpty { null } }

        // 2) Also grab the iframe data-src that is already on the page
        val directIframe = document.selectFirst(
            "div.video-container iframe, div[aria-label=Player] iframe"
        )?.let { it.attr("data-src").ifEmpty { it.attr("src") } }

        val iframeUrls = mutableSetOf<String>()
        if (!directIframe.isNullOrBlank()) iframeUrls.add(directIframe)

        // 3) For each video ID, call the /video/<id>/ endpoint
        for (id in videoIds) {
            try {
                val resp = app.get(
                    "$mainUrl/video/$id/",
                    headers = mapOf(
                        "X-Requested-With" to "fetch",
                        "Content-Type"     to "application/json",
                        "Referer"          to data,
                    ),
                ).text

                val json = parseJson<VideoJSON>(resp)
                val html = json.data?.html ?: continue
                val iframeSrc = Regex("""(?:src|data-src)\s*=\s*["']([^"']+)["']""")
                    .find(html)?.groupValues?.get(1) ?: continue
                iframeUrls.add(iframeSrc)
            } catch (_: Exception) { }
        }

        // 4) Resolve each iframe URL
        for (iframeUrl in iframeUrls) {
            val fullUrl = fixUrl(iframeUrl)
            try {
                // Some iframes are rapidrame/rplayer wrappers that contain
                // the real player (JWPlayer with direct sources).
                if (fullUrl.contains("/rplayer/") || fullUrl.contains("hdfilmcehennemi")) {
                    val playerDoc = app.get(
                        fullUrl,
                        referer = data,
                    ).document

                    // Look for nested iframes pointing to known extractors
                    playerDoc.select("iframe").forEach { nestedIframe ->
                        val nestedSrc = nestedIframe.attr("src").ifEmpty {
                            nestedIframe.attr("data-src")
                        }
                        if (nestedSrc.isNotBlank()) {
                            loadExtractor(fixUrl(nestedSrc), data, subtitleCallback, callback)
                        }
                    }

                    // Also look for JWPlayer sources in inline scripts
                    val pageText = playerDoc.html()
                    Regex(""""?file"?\s*:\s*"([^"]+\.m3u8[^"]*)"""")
                        .findAll(pageText).forEach { match ->
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    name,
                                    match.groupValues[1],
                                    ExtractorLinkType.M3U8,
                                ) {
                                    this.referer = fullUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }

                    Regex(""""?file"?\s*:\s*"([^"]+\.mp4[^"]*)"""")
                        .findAll(pageText).forEach { match ->
                            val label = Regex(""""?label"?\s*:\s*"(\d+)p?"""")
                                .find(pageText)?.groupValues?.get(1)?.toIntOrNull()
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    name,
                                    match.groupValues[1],
                                    ExtractorLinkType.VIDEO,
                                ) {
                                    this.referer = fullUrl
                                    this.quality = label ?: Qualities.Unknown.value
                                }
                            )
                        }
                } else {
                    // External extractor (vidmoly, filemoon, closeload, etc.)
                    loadExtractor(fullUrl, data, subtitleCallback, callback)
                }
            } catch (_: Exception) { }
        }

        return iframeUrls.isNotEmpty()
    }

    // ======================== Helpers ====================================

    private fun Element.toSearchResult(): SearchResponse? {
        val href   = this.attr("href").ifEmpty { return null }
        val title  = this.selectFirst("strong.poster-title")?.text()?.trim() ?: return null
        val poster = this.selectFirst("img")?.let { fixImageUrl(it) }
        val year   = this.selectFirst("div.poster-meta span")?.text()?.toIntOrNull()
        val type   = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
            this.year = year
        }
    }

    /** Pick the best image URL from src / data-src / srcset. */
    private fun fixImageUrl(img: Element): String? {
        val url = img.attr("data-src").ifBlank {
            img.attr("src").ifBlank { null }
        } ?: return null
        // Skip placeholder base64 images
        if (url.startsWith("data:")) return null
        return fixUrl(url)
    }
}
