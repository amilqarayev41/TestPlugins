package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.Interceptor
import org.jsoup.nodes.Element
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class DizillaProvider : MainAPI() {
    override var mainUrl = "https://dizilla.la"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Cartoon)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/yabanci-dizi-izle?page=" to "Yabancı Diziler",
        "$mainUrl/imdb-top-100" to "Popüler Diziler (Top 100)",
        "$mainUrl/anime-izle?page=" to "Animeler",
        "$mainUrl/turkce-dublaj-dizi-izle" to "Türkçe Dublaj Diziler",
        "$mainUrl/kdrama-izle" to "Kore Dizileri",
        "$mainUrl/yerli-dizi-izle" to "Yerli Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.endsWith("page=")) {
            "${request.data}$page"
        } else {
            request.data
        }

        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val items = document.select("a.pcard, a[href*='/dizi/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.selectFirst("a[href*='page=${page + 1}']") != null || items.size >= 24

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ara?q=${query.trim().replace(" ", "+")}"
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        return document.select("a.pcard, a[href*='/dizi/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val html = document.html()

        // Extract series metadata
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.title().substringBefore(" izle").trim()

        val posterUrl = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("span.pimg img, img.poster")?.attr("src")
        )

        val backgroundUrl = fixUrlNull(
            document.selectFirst("img[src*='/backdrop/']")?.attr("src")
        )

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("p.summary, div.desc")?.text()

        // Rating
        val ratingMatch = Regex("""★\s*<!--\s*-->\s*([0-9.]+)""").find(html)
            ?: Regex("""([0-9.]+)\s*/\s*10""").find(html)
            ?: Regex("""★\s*([0-9.]+)""").find(html)
        val score = ratingMatch?.groupValues?.get(1)?.toDoubleOrNull()

        // Year
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(html)?.value?.toIntOrNull()

        // Genres
        val genres = document.select("a[href*='/kategori/'], a[href*='/tur/']").map { it.text().trim() }

        // Next.js RSC payload contains the complete episodes array
        val fullRscText = extractFullRscText(html)
        val episodesList = arrayListOf<Episode>()

        val episodesMatch = Regex(""""episodes"\s*:\s*(\[.*?\])\s*,\s*"seasons"""").find(fullRscText)
        if (episodesMatch != null) {
            val episodesJson = episodesMatch.groupValues[1]
            val node = tryParseJson<List<Map<String, Any?>>>(episodesJson)
            if (node != null) {
                for (ep in node) {
                    val s = (ep["s"] as? Number)?.toInt() ?: 1
                    val n = (ep["n"] as? Number)?.toInt() ?: 1
                    val epTitle = ep["title"] as? String
                    val epSummary = ep["summary"] as? String
                    val epStill = ep["still"] as? String
                    val epUrl = (ep["url"] as? String) ?: (ep["slug"] as? String)?.let { "/$it" }
                    if (!epUrl.isNullOrBlank()) {
                        episodesList.add(
                            newEpisode(fixUrl(epUrl)) {
                                this.name = epTitle
                                this.season = s
                                this.episode = n
                                this.description = epSummary
                                this.posterUrl = fixUrlNull(epStill)
                            }
                        )
                    }
                }
            }
        }

        // Fallback: If RSC parsing didn't find episodes, search links
        if (episodesList.isEmpty()) {
            document.select("a[href*='-bolum']").forEach { link ->
                val epHref = fixUrl(link.attr("href"))
                val epName = link.text().trim()
                val seasonMatch = Regex("""(\d+)\.\s*[Ss]ezon""").find(epName)
                val episodeMatch = Regex("""(\d+)\.\s*[Bb]ölüm""").find(epName)
                val s = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val n = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                episodesList.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.season = s
                        this.episode = n
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backgroundUrl
            this.plot = plot
            this.year = year
            this.score = score
            this.tags = genres
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
        val fullRscText = extractFullRscText(html)

        // Find sources array in RSC payload
        val sourcesMatch = Regex(""""sources"\s*:\s*(\[.*?\])\s*,\s*"hosts"""").find(fullRscText)
        val sourcesList = arrayListOf<DizillaSource>()

        if (sourcesMatch != null) {
            val sourcesJson = sourcesMatch.groupValues[1]
            tryParseJson<List<DizillaSource>>(sourcesJson)?.let {
                sourcesList.addAll(it)
            }
        }

        // Fallback: extract iframe urls matching pichive or other video hosts
        if (sourcesList.isEmpty()) {
            Regex("""(?:https?:)?//[^\s"'<>]*(?:pichive|hotlinger|playru|filese)[^\s"'<>]*iframe\.php\?v=[a-zA-Z0-9]+""")
                .findAll(html)
                .forEach { match ->
                    val src = fixUrl(match.value.replace("\\", ""))
                    sourcesList.add(DizillaSource(name = "PUB", langLabel = "Video", src = src))
                }
        }

        var foundLinks = false

        for (source in sourcesList) {
            val src = source.src ?: continue
            val label = listOfNotNull(source.name, source.langLabel).joinToString(" - ")

            if (src.contains("pichive")) {
                val ok = extractPichive(src, data, label, subtitleCallback, callback)
                if (ok) foundLinks = true
            } else {
                val ok = loadExtractor(src, data, subtitleCallback, callback)
                if (ok) foundLinks = true
            }
        }

        return foundLinks
    }

    private suspend fun extractPichive(
        iframeUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val iframeHtml = app.get(
                iframeUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            ).text

            // 1. Extract NP_GATE function from iframe
            val gateFuncMatch = Regex("""window\.NP_GATE\s*=\s*(function\([^)]+\)\{.*?\});""").find(iframeHtml)
            val gateFunc = gateFuncMatch?.groupValues?.get(1)

            // 2. Extract NP_CONFIG
            val configMatch = Regex("""window\.NP_CONFIG\s*=\s*(\{.*?\});\s*(?:window\.|</script>)""").find(iframeHtml)
                ?: return false
            val config = tryParseJson<NpConfig>(configMatch.groupValues[1]) ?: return false

            val gateN = config.gate?.n ?: return false
            val gateB = config.gate.b ?: 12

            // 3. Solve gate proof of work
            val pVal = if (gateFunc != null) {
                solveDynamicGate(gateFunc, gateN)
            } else {
                solveFallbackGate(gateN, gateB)
            } ?: return false

            // 4. Construct and emit master HLS playlist
            val rawMaster = config.sources?.firstOrNull()?.master ?: return false
            val masterUrl = "$rawMaster&p=$pVal"

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "$name - $label",
                    url = masterUrl,
                    referer = "https://four.pichive.online/",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf(
                        "Referer" to "https://four.pichive.online/",
                        "Origin" to "https://four.pichive.online",
                        "User-Agent" to USER_AGENT
                    )
                )
            )

            // 5. Emit subtitles with token appended
            config.subtitles?.forEach { sub ->
                val subUrl = sub.url ?: return@forEach
                val subLabel = sub.label ?: sub.lang ?: "Altyazı"
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = subLabel,
                        url = "$subUrl&p=$pVal"
                    )
                )
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Dynamically parses and executes the server-generated window.NP_GATE PoW algorithm.
     * Extracts:
     * - Secret string from String.fromCharCode(...)
     * - Parameter and variable names
     * - Condition mask: (hashFn(expr)[wordIdx] (op) val) === 0
     * Solves PoW via hardware-accelerated SHA-256.
     */
    private fun solveDynamicGate(gateFunc: String, gateN: String): String? {
        return try {
            val charCodesMatch = Regex("""String\.fromCharCode\(([^)]+)\)""").find(gateFunc) ?: return null
            val secretStr = charCodesMatch.groupValues[1].split(",")
                .mapNotNull { it.trim().toIntOrNull()?.toChar() }
                .joinToString("")

            val funcParamsMatch = Regex("""function\(([^)]+)\)""").find(gateFunc) ?: return null
            val funcParams = funcParamsMatch.groupValues[1].split(",").map { it.trim() }
            val gateNParam = funcParams.getOrNull(0) ?: "_xt"

            val secretVar = Regex("""var\s+(\w+)\s*=\s*String\.fromCharCode""").find(gateFunc)?.groupValues?.get(1) ?: "_lfor"

            val condMatch = Regex("""\((\w+)\(([^)]+)\)\[(\d+)\]\s*(&|>>>)\s*(\d+)\)\s*===?\s*0""").find(gateFunc) ?: return null
            val hashArg = condMatch.groupValues[2]
            val wordIdx = condMatch.groupValues[3].toIntOrNull() ?: 3
            val op = condMatch.groupValues[4]
            val checkVal = condMatch.groupValues[5].toLongOrNull() ?: 20L

            val parts = hashArg.split("+").map { it.trim() }
            val md = MessageDigest.getInstance("SHA-256")

            for (i in 0 until 5_000_000) {
                val iStr = i.toString()
                val sb = StringBuilder()
                for (p in parts) {
                    when {
                        p == gateNParam -> sb.append(gateN)
                        p == secretVar -> sb.append(secretStr)
                        (p.startsWith("'") && p.endsWith("'")) || (p.startsWith("\"") && p.endsWith("\"")) -> {
                            sb.append(p.substring(1, p.length - 1))
                        }
                        else -> sb.append(iStr)
                    }
                }

                md.reset()
                val digest = md.digest(sb.toString().toByteArray(Charsets.UTF_8))
                val word = ((digest[wordIdx * 4].toLong() and 0xFFL) shl 24) or
                           ((digest[wordIdx * 4 + 1].toLong() and 0xFFL) shl 16) or
                           ((digest[wordIdx * 4 + 2].toLong() and 0xFFL) shl 8) or
                           (digest[wordIdx * 4 + 3].toLong() and 0xFFL)

                val passes = if (op == "&") {
                    (word and checkVal) == 0L
                } else {
                    (word ushr checkVal.toInt()) == 0L
                }

                if (passes) {
                    return iStr
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fallback gate solver from np.js when window.NP_GATE is omitted.
     * Evaluates sha256("$gateN:$n")[0] ushr (32 - b) == 0.
     */
    private fun solveFallbackGate(gateN: String, gateB: Int): String? {
        val md = MessageDigest.getInstance("SHA-256")
        val shift = 32 - gateB
        for (i in 0 until 5_000_000) {
            val iStr = i.toString()
            md.reset()
            val digest = md.digest("$gateN:$iStr".toByteArray(Charsets.UTF_8))
            val word0 = ((digest[0].toLong() and 0xFFL) shl 24) or
                        ((digest[1].toLong() and 0xFFL) shl 16) or
                        ((digest[2].toLong() and 0xFFL) shl 8) or
                        (digest[3].toLong() and 0xFFL)

            if ((word0 ushr shift) == 0L) {
                return iStr
            }
        }
        return null
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            val url = request.url.toString()

            if (url.contains("pichive") || url.contains(".com/f/") || url.contains("hotlinger")) {
                builder.header("Referer", "https://four.pichive.online/")
                builder.header("Origin", "https://four.pichive.online")
                builder.header("User-Agent", USER_AGENT)
            } else {
                val referer = if (extractorLink.referer.isNotBlank()) extractorLink.referer else "$mainUrl/"
                builder.header("Referer", referer)
                builder.header("User-Agent", USER_AGENT)
            }

            extractorLink.headers.forEach { (key, value) ->
                builder.header(key, value)
            }

            chain.proceed(builder.build())
        }
    }

    // ======================== Helpers ====================================

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href").ifEmpty { return null }
        if (!href.contains("/dizi/")) return null

        val title = this.selectFirst("span.mvk-t")?.text()?.trim()
            ?: this.selectFirst("span.pimg img")?.attr("alt")?.trim()
            ?: this.attr("aria-label").substringBefore(",").trim().ifEmpty { return null }

        val poster = fixUrlNull(
            this.selectFirst("span.pimg img")?.attr("src")
                ?: this.selectFirst("img")?.attr("src")
        )

        val yearText = this.selectFirst("span.mvk-m")?.text() ?: this.attr("aria-label")
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(yearText)?.value?.toIntOrNull()

        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private fun extractFullRscText(html: String): String {
        val nextFRegex = Regex("""self\.__next_f\.push\(\[(?:1|0),\s*"(.*?[^\\])"]\)""")
        val sb = StringBuilder()
        for (match in nextFRegex.findAll(html)) {
            sb.append(unescapeNextF(match.groupValues[1]))
        }
        return sb.toString()
    }

    private fun unescapeNextF(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        val len = input.length
        while (i < len) {
            val c = input[i]
            if (c == '\\' && i + 1 < len) {
                when (val next = input[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000c'); i += 2 }
                    'u' -> {
                        if (i + 6 <= len) {
                            val hex = input.substring(i + 2, i + 6)
                            try {
                                sb.append(hex.toInt(16).toChar())
                                i += 6
                            } catch (_: Exception) {
                                sb.append(c)
                                i++
                            }
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // ======================== Data classes ===============================

    data class DizillaSource(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("langLabel") val langLabel: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("origin") val origin: String? = null,
        @JsonProperty("trailer") val trailer: Boolean? = false,
    )

    data class NpConfig(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("gate") val gate: NpGate? = null,
        @JsonProperty("sources") val sources: List<NpSource>? = null,
        @JsonProperty("subtitles") val subtitles: List<NpSubtitle>? = null,
    )

    data class NpGate(
        @JsonProperty("n") val n: String? = null,
        @JsonProperty("b") val b: Int? = null,
    )

    data class NpSource(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("master") val master: String? = null,
    )

    data class NpSubtitle(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("url") val url: String? = null,
    )
}
