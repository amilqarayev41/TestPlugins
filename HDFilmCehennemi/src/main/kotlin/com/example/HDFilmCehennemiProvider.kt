package com.example

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
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

    /**
     * Decrypt Variant 1: Operation and hash keys are embedded inside the chunks array
     * at deterministic positions (pos2 = 8 + s % 5, pos1 = s % 7).
     */
    private fun decryptSourceV1(chunks: MutableList<String>): String? {
        if (chunks.size < 3) return null

        val s = chunks.size - 2
        val pos2 = 8 + (s % 5)
        val pos1 = s % 7

        if (pos2 >= chunks.size) return null
        val operationKey = chunks.removeAt(pos2)

        if (pos1 >= chunks.size) return null
        val hashKey = chunks.removeAt(pos1)

        var d = chunks.joinToString("")

        var bigA = 0
        var t = 0
        for ((index, ch) in hashKey.withIndex()) {
            val k = ch.code
            bigA = (bigA * 53 + k) % 251
            t = (t + ((k shl 2) xor index)) and 255
        }

        val startKey = (bigA * 3 + t) % 256
        val increment = (t % 11) + 5
        var shuffleSeed = ((t * 251 + bigA) % 65519) + 1

        for (z in operationKey.reversed()) {
            when (z) {
                '7' -> {
                    try {
                        var padded = d
                        while (padded.length % 4 != 0) padded += "="
                        d = String(Base64.decode(padded, Base64.DEFAULT), Charsets.ISO_8859_1)
                    } catch (_: Exception) {
                        return null
                    }
                }
                '3' -> {
                    d = d.reversed()
                }
                else -> {
                    val shiftAmount = (26 - ((z.code - 96) % 26)) % 26
                    if (shiftAmount != 0) {
                        d = caesarShiftV1(d, shiftAmount)
                    }
                }
            }
        }

        val len = d.length
        if (len < 2) return null
        val indices = IntArray(len)
        for (i in len - 1 downTo 1) {
            shuffleSeed = (shuffleSeed * 89 + 53) % 65519
            indices[i] = shuffleSeed % (i + 1)
        }
        val chars = d.toCharArray()
        for (i in 1 until len) {
            val j = indices[i]
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        d = String(chars)

        var key = startKey
        val result = StringBuilder(len)
        for (ch in d) {
            val k = ch.code
            key = (key * 7 + increment) % 256
            result.append((k xor key).toChar())
            key = (key + k) % 256
        }

        return result.toString()
    }

    private fun caesarShiftV1(input: String, shift: Int): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when {
                ch in 'a'..'z' -> sb.append(((ch - 'a' + shift) % 26 + 'a'.code).toChar())
                ch in 'A'..'Z' -> sb.append(((ch - 'A' + shift) % 26 + 'A'.code).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Decrypt Variant 2: Keys are local constants inside the unpacked function.
     * Uses constants 31, 65521, 65537 with letter offset 64.
     */
    private fun decryptSourceV2(chunks: List<String>, hashKey: String, opKey: String): String? {
        if (chunks.isEmpty() || hashKey.isBlank() || opKey.isBlank()) return null

        var d = chunks.joinToString("")

        var gcose = 0
        var yk1y = 0
        for ((index, ch) in hashKey.withIndex()) {
            val code = ch.code
            gcose = (gcose * 31 + code) % 251
            yk1y = (yk1y xor (code + index)) and 255
        }

        val yz1 = (gcose + yk1y) % 256
        val fus = (gcose % 13) + 3
        var f546r = ((gcose * 256 + yk1y) % 65521) + 1

        for (z in opKey.reversed()) {
            when (z) {
                'b' -> {
                    try {
                        var padded = d
                        while (padded.length % 4 != 0) padded += "="
                        d = String(Base64.decode(padded, Base64.DEFAULT), Charsets.ISO_8859_1)
                    } catch (_: Exception) {
                        return null
                    }
                }
                'v' -> {
                    d = d.reversed()
                }
                else -> {
                    val shift = (26 - ((z.code - 64) % 26)) % 26
                    if (shift != 0) {
                        d = caesarShiftV2(d, shift)
                    }
                }
            }
        }

        val len = d.length
        if (len < 2) return null
        val indices = IntArray(len)
        for (i in len - 1 downTo 1) {
            f546r = (f546r * 75 + 74) % 65537
            indices[i] = f546r % (i + 1)
        }

        val chars = d.toCharArray()
        for (i in 1 until len) {
            val j = indices[i]
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        d = String(chars)

        var bupw4 = yz1
        val sb = StringBuilder(len)
        for (ch in d) {
            val code = ch.code
            bupw4 = (bupw4 + fus) % 256
            sb.append((code xor bupw4).toChar())
            bupw4 = (bupw4 + code) % 256
        }

        return sb.toString()
    }

    private fun caesarShiftV2(input: String, shift: Int): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in 'A'..'Z' -> sb.append(((ch - 'A' + shift) % 26 + 'A'.code).toChar())
                in 'a'..'z' -> sb.append(((ch - 'a' + shift) % 26 + 'a'.code).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unpackPackedJs(script: String): String {
        return try {
            val match = Regex("""\}\s*\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""").find(script)
                ?: return getAndUnpack(script)
            val payload = match.groupValues[1].replace("\\'", "'")
            val radix = match.groupValues[2].toIntOrNull() ?: 36
            val count = match.groupValues[3].toIntOrNull() ?: 0
            val symtab = match.groupValues[4].split("|")

            fun unbase(str: String): Int {
                val alphabet62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                var ret = 0
                val reversed = str.reversed()
                for ((i, ch) in reversed.withIndex()) {
                    val idx = alphabet62.indexOf(ch)
                    if (idx < 0 || idx >= radix) return -1
                    var multiplier = 1
                    for (k in 0 until i) multiplier *= radix
                    ret += multiplier * idx
                }
                return ret
            }

            val decoded = Regex("""\b[a-zA-Z0-9_]+\b""").replace(payload) { wordMatch ->
                val word = wordMatch.value
                val idx = unbase(word)
                if (idx in symtab.indices && symtab[idx].isNotBlank()) {
                    symtab[idx]
                } else {
                    word
                }
            }
            decoded
        } catch (_: Exception) {
            getAndUnpack(script)
        }
    }

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val pageText = try {
            app.get(url, referer = "$mainUrl/").text
        } catch (_: Exception) {
            return
        }
        var videoUrl: String? = null

        // 1. Try split format with dynamic delimiter: return func("chunk1...chunkN".split("delim"))
        val splitMatch = Regex("""return\s+([a-zA-Z0-9_$]+)\s*\(\s*"([^"]+)"\s*\.split\("([^"]+)"\)\s*\)""")
            .find(pageText)
        if (splitMatch != null) {
            val rawData = splitMatch.groupValues[2]
            val delim = splitMatch.groupValues[3]
            val chunks = rawData.split(delim).toMutableList()
            videoUrl = decryptSourceV1(chunks)
        }

        // 2. Try array format in pageText: return func(["chunk1", "chunk2", ...])
        // Uses bracket match so any escaped characters (like \/ or \) are fully captured
        if (videoUrl.isNullOrBlank() || !videoUrl.contains(".m3u8")) {
            val arrMatch = Regex("""return\s+([a-zA-Z0-9_$]+)\s*\(\s*(\[[^\]]+\])\s*\)""")
                .find(pageText)
            if (arrMatch != null) {
                val rawArr = arrMatch.groupValues[2]
                val chunks = rawArr.removeSurrounding("[", "]")
                    .split(",")
                    .map {
                        it.trim()
                            .trim('"', '\'')
                            .replace("\\/", "/")
                            .replace("\\\\", "\\")
                    }
                    .filter { it.isNotBlank() }

                // Try V1 first
                videoUrl = decryptSourceV1(chunks.toMutableList())

                // If V1 didn't yield a valid m3u8, try V2 by extracting key pairs from unpacked scripts
                if (videoUrl.isNullOrBlank() || !videoUrl.contains(".m3u8")) {
                    val scriptTags = Jsoup.parse(pageText).select("script").map { it.data() }
                    for (script in scriptTags) {
                        if (!script.contains("eval(function")) continue
                        val unpacked = unpackPackedJs(script)
                        val keyMatches = Regex("""var\s+\w+\s*=\s*"([A-Za-z0-9+/=]{15,40})"\s*;\s*var\s+\w+\s*=\s*"([A-Za-z0-9+/=]{2,10})"\s*;""")
                            .findAll(unpacked)
                        for (km in keyMatches) {
                            val hashKey = km.groupValues[1]
                            val opKey = km.groupValues[2]
                            val candidate = decryptSourceV2(chunks, hashKey, opKey)
                            if (!candidate.isNullOrBlank() && candidate.contains(".m3u8")) {
                                videoUrl = candidate
                                break
                            }
                        }
                        if (!videoUrl.isNullOrBlank() && videoUrl.contains(".m3u8")) break
                    }
                }
            }
        }

        // 3. Scan unpacked scripts for embedded call: var x = func(["chunk1", ...])
        if (videoUrl.isNullOrBlank() || !videoUrl.contains(".m3u8")) {
            val scriptTags = Jsoup.parse(pageText).select("script").map { it.data() }
            for (script in scriptTags) {
                if (!script.contains("eval(function")) continue
                val unpacked = unpackPackedJs(script)
                val callMatch = Regex("""var\s+\w+\s*=\s*([a-zA-Z0-9_$]+)\s*\(\s*(\[[^\]]+\])\s*\)""")
                    .find(unpacked)
                if (callMatch != null) {
                    val rawArr = callMatch.groupValues[2]
                    val chunks = rawArr.removeSurrounding("[", "]")
                        .split(",")
                        .map {
                            it.trim()
                                .trim('"', '\'')
                                .replace("\\/", "/")
                                .replace("\\\\", "\\")
                        }
                        .filter { it.isNotBlank() }

                    val keyMatches = Regex("""var\s+\w+\s*=\s*"([A-Za-z0-9+/=]{15,40})"\s*;\s*var\s+\w+\s*=\s*"([A-Za-z0-9+/=]{2,10})"\s*;""")
                        .findAll(unpacked)
                    for (km in keyMatches) {
                        val hashKey = km.groupValues[1]
                        val opKey = km.groupValues[2]
                        val candidate = decryptSourceV2(chunks, hashKey, opKey)
                        if (!candidate.isNullOrBlank() && candidate.contains(".m3u8")) {
                            videoUrl = candidate
                            break
                        }
                    }
                    if (!videoUrl.isNullOrBlank() && videoUrl.contains(".m3u8")) break
                }
            }
        }

        // 4. Fallback: try legacy getAndUnpack + file_link
        if (videoUrl.isNullOrBlank() || !videoUrl.contains(".m3u8")) {
            val scriptData = Jsoup.parse(pageText).select("script")
                .find { it.data().contains("sources:") }?.data()
            if (scriptData != null) {
                val unpacked = unpackPackedJs(scriptData)
                val fileLink = unpacked
                    .substringAfter("file_link=\"", "")
                    .substringBefore("\";", "")
                if (fileLink.isNotBlank()) {
                    videoUrl = base64Decode(fileLink)
                }
            }
        }

        // 5. Fallback: direct m3u8/mp4 URL in page
        if (videoUrl.isNullOrBlank() || !videoUrl.contains(".m3u8")) {
            videoUrl = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                .find(pageText)?.groupValues?.get(1)
        }

        if (!videoUrl.isNullOrBlank() && (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
            val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(
                    source,
                    source,
                    videoUrl,
                    linkType,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // ---- Extract subtitles ----
        val subData = pageText
            .substringAfter("tracks:", "")
            .substringAfter("[", "")
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
        val processedPlayerIds = mutableSetOf<String>()

        fun normalizePlayerUrl(rawUrl: String): String {
            var url = fixUrl(rawUrl)
            if (url.contains("?rapidrame_id=")) {
                val id = url.substringAfter("?rapidrame_id=").substringBefore("&").trimEnd('/')
                url = "$mainUrl/rplayer/$id/"
            } else if (url.contains("/playerr/")) {
                val id = url.substringAfter("/playerr/").substringBefore("?").trimEnd('/')
                url = "$mainUrl/rplayer/$id/"
            }
            return url
        }

        fun extractPlayerId(url: String): String {
            return url.trimEnd('/').substringAfterLast('/')
        }

        // ---- Method 1: Direct iframe from page ----
        val directIframe = document.selectFirst("div.video-container iframe")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        if (!directIframe.isNullOrBlank()) {
            val playerUrl = normalizePlayerUrl(directIframe)
            val pid = extractPlayerId(playerUrl)
            if (pid.isNotBlank()) processedPlayerIds.add(pid)
            try {
                invokeLocalSource("HDFilmCehennemi", playerUrl, subtitleCallback, callback)
            } catch (_: Exception) { }
        }

        // ---- Method 2: Alternative links (Dual, Altyazılı, Dublaj) ----
        val altButtons = document.select("div.alternative-links").flatMap { element ->
            val langCode = element.attr("data-lang").uppercase()
            element.select("button.alternative-link").map { button ->
                val source = button.text().replace("(HDrip Xbet)", "").trim() + " $langCode"
                val videoId = button.attr("data-video")
                source to videoId
            }
        }

        for ((source, videoID) in altButtons) {
            try {
                val apiResp = app.get(
                    "$mainUrl/video/$videoID/",
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "X-Requested-With" to "fetch",
                    ),
                    referer = data,
                ).text

                val rawIframe = Regex("""data-src=\\?"([^"]+)""")
                    .find(apiResp)?.groupValues?.get(1)
                    ?.replace("\\", "")
                    ?: continue

                val playerUrl = normalizePlayerUrl(rawIframe)
                val pid = extractPlayerId(playerUrl)
                if (pid.isNotBlank() && !processedPlayerIds.add(pid)) {
                    continue
                }

                invokeLocalSource(source, playerUrl, subtitleCallback, callback)
            } catch (_: Exception) { }
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
