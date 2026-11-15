package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
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
        mainUrl                                               to "Yeni Eklenen Filmler",
        "$mainUrl/yabancidiziizle-15/"                        to "Yeni Eklenen Diziler",
        "$mainUrl/category/tavsiye-filmler-702/"              to "Tavsiye Filmler",
        "$mainUrl/en-cok-begenilen-filmleri-izle-5/"          to "En Çok Beğenilenler",
        "$mainUrl/category/nette-ilk-filmler-4/"              to "Nette İlk",
        "$mainUrl/category/1080p-film-izle-702/"              to "1080p Filmler",
        "$mainUrl/category/altyazili-film-izle/"              to "Altyazılı Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}sayfa-$page/" else request.data
        val document = app.get(url).document

        val items = document.select("div.section-content a.poster, div.slider-container a.poster")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = document.selectFirst("a.next-page") != null
        )
    }

    // ======================== Quick-search (XHR JSON) ====================

    data class SearchResults(
        @JsonProperty("results") val results: List<String> = arrayListOf(),
    )

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/search?q=$query",
            headers = mapOf("X-Requested-With" to "fetch"),
        ).parsedSafe<SearchResults>() ?: return emptyList()

        return response.results.mapNotNull { resultHtml ->
            val doc = Jsoup.parse(resultHtml)
            val title = doc.selectFirst("h4.title")?.text() ?: return@mapNotNull null
            val href = fixUrlNull(doc.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(
                doc.selectFirst("img")?.attr("src")
                    ?: doc.selectFirst("img")?.attr("data-src")
            )?.replace("/thumb/", "/list/")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ======================== Detail / load ==============================

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.section-title")?.text()
            ?.substringBefore(" izle")?.trim() ?: return null
        val year = document.selectFirst("div.post-info-year-country a")?.text()
            ?.trim()?.toIntOrNull()
            ?: document.selectFirst("h1.section-title small")?.text()
                ?.removeSurrounding("(", ")")?.trim()?.toIntOrNull()
        val poster = fixUrlNull(
            document.select("aside.post-info-poster img").lastOrNull()
                ?.let { getBestImageUrl(it) }
        )
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val imdbScore = document.selectFirst("div.post-info-imdb-rating span")
            ?.text()?.substringBefore(" ")?.toDoubleOrNull()
        val tags = document.select("div.post-info-genres a").map { it.text() }
        val trailer = document.selectFirst("div.post-info-trailer button")
            ?.attr("data-modal")?.substringAfter("trailer/")
            ?.let { "https://www.youtube.com/embed/$it" }

        val tvType = if (document.select("div.seasons").isNotEmpty()) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull { epEl ->
                val epName = epEl.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref = fixUrlNull(epEl.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)
                    ?.groupValues?.get(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = Score.from10(imdbScore)
                this.tags = tags
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = Score.from10(imdbScore)
                this.tags = tags
                addTrailer(trailer)
            }
        }
    }

    // ======================== Load links / extractors ====================

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val doc = app.get(url, referer = "$mainUrl/").document
        val script = doc.select("script").find { it.data().contains("sources:") }?.data()
            ?: return

        val unpacked = getAndUnpack(script)
        val videoData = unpacked
            .substringAfter("file_link=\"")
            .substringBefore("\";")
        if (videoData.isBlank()) return

        val videoUrl = base64Decode(videoData)
        if (videoUrl.isBlank()) return

        callback.invoke(
            newExtractorLink(
                source,
                source,
                videoUrl,
                ExtractorLinkType.M3U8,
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )

        // Extract subtitles from the tracks JSON array
        val subData = script
            .substringAfter("tracks: [", "")
            .substringBefore("]", "")
        if (subData.isNotBlank()) {
            tryParseJson<List<SubSource>>("[$subData]")
                ?.filter { it.kind == "captions" }
                ?.forEach {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            it.label ?: "Unknown",
                            fixUrl(it.file ?: return@forEach)
                        )
                    )
                }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document

        document.select("div.alternative-links").map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            element.select("button.alternative-link").map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() +
                    " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                try {
                    val apiResp = app.get(
                        "$mainUrl/video/$videoID/",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "fetch",
                        ),
                        referer = data,
                    ).text

                    var iframe = Regex("""data-src=\\?"([^"]+)""")
                        .find(apiResp)?.groupValues?.get(1)
                        ?.replace("\\", "")
                        ?: return@forEach

                    // Convert rapidrame iframe URLs to local /playerr/ URLs
                    if (iframe.contains("?rapidrame_id=")) {
                        iframe = "$mainUrl/playerr/" +
                            iframe.substringAfter("?rapidrame_id=")
                    }

                    invokeLocalSource(source, iframe, subtitleCallback, callback)
                } catch (_: Exception) { }
            }
        }

        return true
    }

    // ======================== Helpers ====================================

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href").ifEmpty { return null }
        val title = this.selectFirst("strong.poster-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.let { getBestImageUrl(it) })
        val year = this.selectFirst("div.poster-meta span")?.text()?.toIntOrNull()
        val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
            this.year = year
        }
    }

    /**
     * Pick the best (highest resolution) image URL from an img element.
     * The site uses lazy-loading with data-srcset containing @2x variants.
     * Priority: data-srcset @2x > data-src > srcset @2x > src
     * Skips base64 placeholder images.
     */
    private fun getBestImageUrl(img: Element): String? {
        // Try to get the @2x variant from data-srcset first
        val dataSrcset = img.attr("data-srcset")
        if (dataSrcset.isNotBlank()) {
            val url2x = extractSrcsetUrl(dataSrcset, "2x")
            if (url2x != null) return fixUrl(url2x)
        }

        // Fall back to data-src
        val dataSrc = img.attr("data-src")
        if (dataSrc.isNotBlank() && !dataSrc.startsWith("data:")) return fixUrl(dataSrc)

        // Try srcset
        val srcset = img.attr("srcset")
        if (srcset.isNotBlank()) {
            val url2x = extractSrcsetUrl(srcset, "2x")
            if (url2x != null) return fixUrl(url2x)
        }

        // Fall back to src
        val src = img.attr("src")
        if (src.isNotBlank() && !src.startsWith("data:")) return fixUrl(src)

        return null
    }

    /** Extract a URL for a given density descriptor (e.g. "2x") from a srcset string. */
    private fun extractSrcsetUrl(srcset: String, descriptor: String): String? {
        return srcset.split(",")
            .map { it.trim() }
            .firstOrNull { it.endsWith(descriptor) }
            ?.substringBeforeLast(" ")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }
}
