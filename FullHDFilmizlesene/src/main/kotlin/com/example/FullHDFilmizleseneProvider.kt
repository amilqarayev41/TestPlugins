package com.example

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActorNames
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.Interceptor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FullHDFilmizleseneProvider : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullHDFilmizlesene"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val mapper = jacksonObjectMapper()
    }

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-filmler" to "Yeni Filmler",
        "$mainUrl/trend-filmler" to "Trend Filmler",
        "$mainUrl/populer-filmler" to "Popüler Filmler",
        "$mainUrl/en-cok-izlenen-filmler" to "En Çok İzlenenler",
        "$mainUrl/filmizle/imdb-puani-yuksek-filmler" to "IMDB Puanı Yüksek",
        "$mainUrl/filmizle/turkce-dublaj-filmler-1" to "Türkçe Dublaj Filmler",
        "$mainUrl/filmizle/turkce-altyazili-filmler-1" to "Türkçe Altyazılı Filmler",
        "$mainUrl/filmizle/dual-filmler-1" to "Dual (Dublaj & Altyazı)",
        "$mainUrl/filmizle/4k-filmler" to "4K Filmler",
        "$mainUrl/filmizle/yerli-filmler" to "Yerli Filmler",
        "$mainUrl/filmizle/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/filmizle/animasyon-filmleri-izle" to "Animasyon",
        "$mainUrl/filmizle/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "$mainUrl/filmizle/dram-filmler-izle" to "Dram",
        "$mainUrl/filmizle/fantastik-filmler" to "Fantastik",
        "$mainUrl/filmizle/gerilim-filmleri" to "Gerilim",
        "$mainUrl/filmizle/komedi-filmleri" to "Komedi",
        "$mainUrl/filmizle/korku-filmleri" to "Korku",
        "$mainUrl/filmizle/macera-filmleri-izle-1" to "Macera",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}/$page" else request.data
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        val items = document.select("ul.list li.film, li.film")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.selectFirst("div.sayfalama a.secili + a.sayfala") != null

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = hasNext,
        )
    }

    // ======================== Search & QuickSearch ========================

    private data class AutocompleteItem(
        @JsonProperty("baslik") val baslik: String? = null,
        @JsonProperty("altbaslik") val altbaslik: String? = null,
        @JsonProperty("dizilink") val dizilink: String? = null,
        @JsonProperty("vidresim") val vidresim: String? = null,
        @JsonProperty("imdb") val imdb: String? = null,
        @JsonProperty("yil") val yil: String? = null,
        @JsonProperty("tarz") val tarz: String? = null,
        @JsonProperty("partmod") val partmod: String? = null,
        @JsonProperty("prefix") val prefix: String? = null,
    )

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val jsonText = app.get(
            "$mainUrl/autocomplete/q.php?q=$encoded&callback=",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "X-Requested-With" to "XMLHttpRequest",
            ),
        ).text

        val items = tryParseJson<List<AutocompleteItem>>(jsonText) ?: return emptyList()
        return items.mapNotNull { item ->
            val title = item.baslik?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val slug = item.dizilink?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val prefix = item.prefix?.trim()?.ifEmpty { "film" } ?: "film"
            val href = "$mainUrl/$prefix/$slug/"
            val poster = fixUrlNull(item.vidresim)
            val year = item.yil?.trim()?.toIntOrNull()
            val score = item.imdb?.trim()?.toDoubleOrNull()

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val searchQuery = trimmed.replace(Regex("\\s+"), "+")
        val document = app.get(
            "$mainUrl/arama/$searchQuery",
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/"),
        ).document

        val results = document.select("ul.list li.film, li.film")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return results.ifEmpty { quickSearch(trimmed) }
    }

    // ======================== Detail / load ===============================

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        val title = document.selectFirst("h1 a, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" izle")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.afis img")?.let { getBestImageUrl(it) }
        )

        val plot = document.select("div.film-ozeti div.ozet-ic p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.matches(Regex("""^\d{4}\s+Film\s+izle$""", RegexOption.IGNORE_CASE)) }
            .joinToString("\n\n")
            .ifBlank { document.selectFirst("meta[name=description]")?.attr("content")?.trim() }

        val infoMap = document.select("div.film-info ul li").associate { li ->
            val key = li.selectFirst("span.dt")?.text()?.trim().orEmpty()
            key to li.selectFirst("div.dd")
        }

        val year = infoMap["Yapım"]?.selectFirst("a")?.text()
            ?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.let { Regex("""\((\d{4})\)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val tags = infoMap["Tür"]?.select("a")
            ?.map { it.text().replace(Regex("""\s+Filmleri?$""", RegexOption.IGNORE_CASE), "").trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val actors = infoMap["Oyuncular"]?.select("a")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val imdbScore = document.selectFirst("div.imdb-ic span")?.text()?.trim()?.toDoubleOrNull()
            ?: Regex(""""ratingValue"\s*:\s*([0-9.]+)""").find(document.html())
                ?.groupValues?.get(1)?.toDoubleOrNull()

        val trailer = document.selectFirst("div.frg[data-code]")?.attr("data-code")
            ?.takeIf { it.isNotBlank() }
            ?.let { decodeBase64Utf8(it) }
            ?.let { decoded ->
                Regex("""(?:data-src|src)=["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)
            }
            ?.let { fixUrl(it) }

        val duration = infoMap["Süre"]?.text()?.trim()

        val recommendations = document.select("div.benzerlist li.film")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = Score.from10(imdbScore)
            this.tags = tags
            if (actors.isNotEmpty()) addActorNames(actors)
            if (!duration.isNullOrBlank()) addDuration(duration)
            addTrailer(trailer)
            this.recommendations = recommendations
        }
    }

    // ======================== Load links / RapidVid =======================

    private data class RapidVidTrack(
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )

    private data class RapidVidConfig(
        @JsonProperty("cm") val cm: String? = null,
        @JsonProperty("tm") val tm: String? = null,
        @JsonProperty("tr") val tr: Int? = null,
        @JsonProperty("ct") val ct: List<RapidVidTrack>? = null,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(
            data,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/"),
        ).text

        val scxJson = Regex("""var\s+scx\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return false

        val rootNode = try {
            mapper.readTree(scxJson)
        } catch (_: Exception) {
            return false
        }

        val processedEmbeds = mutableSetOf<String>()

        rootNode.fields().forEach { (sourceKey, sourceNode) ->
            val rawTitle = sourceNode.path("tt").asText("")
            val sourceTitle = decodeBase64Utf8(rawTitle).ifBlank { sourceKey }
            val sxNode = sourceNode.path("sx")

            val embedTargets = mutableListOf<Pair<String, String>>()
            collectDecodedTargets(sxNode.path("t"), sourceTitle, isPartList = false, out = embedTargets)
            collectDecodedTargets(sxNode.path("p"), sourceTitle, isPartList = true, out = embedTargets)

            for ((label, rawEmbedUrl) in embedTargets) {
                var embedUrl = fixUrl(rawEmbedUrl.trim())
                if (embedUrl.isBlank()) continue

                if (sourceTitle.contains("turbo", ignoreCase = true) &&
                    (embedUrl.contains("/movie/") || embedUrl.contains("/watch/"))
                ) {
                    val videoId = embedUrl.split(Regex("(movie|watch)")).lastOrNull()
                        ?.trimStart('/')?.substringBefore('/')
                    if (!videoId.isNullOrBlank()) {
                        embedUrl = "https://turbo.imgz.me/play/$videoId"
                    }
                }

                if (!processedEmbeds.add(embedUrl)) continue

                if (embedUrl.contains("rapidvid.org", ignoreCase = true)) {
                    extractRapidVid(label, embedUrl, subtitleCallback, callback)
                } else {
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private fun collectDecodedTargets(
        node: JsonNode,
        sourceTitle: String,
        isPartList: Boolean,
        out: MutableList<Pair<String, String>>,
    ) {
        if (node.isArray) {
            node.forEachIndexed { index, item ->
                val decoded = decodeScxValue(item.asText(""))
                if (decoded.isNotBlank()) {
                    val label = if (isPartList) "$sourceTitle Part ${index + 1}" else sourceTitle
                    out.add(label to decoded)
                }
            }
        } else if (node.isObject) {
            node.fields().forEach { (key, value) ->
                val decoded = decodeScxValue(value.asText(""))
                if (decoded.isNotBlank()) {
                    val suffix = when (key.lowercase()) {
                        "tr" -> "TR Dublaj"
                        "en" -> "TR Altyazı"
                        else -> if (isPartList) "Part $key" else key
                    }
                    out.add("$sourceTitle ($suffix)" to decoded)
                }
            }
        }
    }

    private suspend fun extractRapidVid(
        sourceLabel: String,
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val embedHtml = try {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/",
                ),
            ).text
        } catch (_: Exception) {
            return
        }

        // Format 1: /vx/ uses window._p8 = '...' containing encrypted JSON config
        val p8Encoded = Regex("""window\._p8\s*=\s*['"]([^'"]+)['"]""").find(embedHtml)?.groupValues?.get(1)
        if (!p8Encoded.isNullOrBlank()) {
            val configJson = decodeAv(p8Encoded)
            val config = tryParseJson<RapidVidConfig>(configJson)
            if (config != null) {
                val primaryUrl = config.cm?.takeIf { it.isNotBlank() } ?: config.tm?.takeIf { it.isNotBlank() }
                if (!primaryUrl.isNullOrBlank()) {
                    emitM3u8Link(sourceLabel, primaryUrl, callback)
                }
                emitSubtitles(config.ct, subtitleCallback)
                return
            }
        }

        // Format 2: /vod/ uses av('...') in jwSetup.sources and JSON array in jwSetup.tracks
        val avEncoded = Regex("""av\(\s*['"]([^'"]+)['"]\s*\)""").find(embedHtml)?.groupValues?.get(1)
        if (!avEncoded.isNullOrBlank()) {
            val streamUrl = decodeAv(avEncoded)
            if (streamUrl.isNotBlank()) {
                emitM3u8Link(sourceLabel, streamUrl, callback)
            }
        }

        val tracksJson = Regex("""jwSetup\.tracks\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
            .find(embedHtml)?.groupValues?.get(1)
        if (!tracksJson.isNullOrBlank()) {
            val tracks = tryParseJson<List<RapidVidTrack>>(tracksJson)
            emitSubtitles(tracks, subtitleCallback)
        }
    }

    private suspend fun emitM3u8Link(
        sourceLabel: String,
        streamUrl: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback.invoke(
            newExtractorLink(
                "$name - $sourceLabel",
                "$name - $sourceLabel",
                streamUrl,
                ExtractorLinkType.M3U8,
            ) {
                this.referer = "https://rapidvid.org/"
                this.headers = mapOf(
                    "Referer" to "https://rapidvid.org/",
                    "Origin" to "https://rapidvid.org",
                    "User-Agent" to USER_AGENT,
                )
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun emitSubtitles(
        tracks: List<RapidVidTrack>?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        tracks.orEmpty()
            .filter { !it.file.isNullOrBlank() && (it.kind == null || it.kind == "captions" || it.kind == "subtitles") }
            .forEach { track ->
                val label = track.label?.trim()?.ifBlank { "Türkçe" } ?: "Türkçe"
                subtitleCallback.invoke(
                    SubtitleFile(
                        label,
                        fixUrl(track.file!!),
                    )
                )
            }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            val referer = extractorLink.referer.ifBlank { "https://rapidvid.org/" }
            builder.header("Referer", referer)
            builder.header("User-Agent", USER_AGENT)
            extractorLink.headers.forEach { (key, value) ->
                builder.header(key, value)
            }
            chain.proceed(builder.build())
        }
    }

    // ======================== Decoders & Helpers ==========================

    /** Site's rtt(s) + atob(...) decoder: ROT13 on ASCII letters followed by Base64 decode. */
    private fun decodeScxValue(encoded: String): String {
        if (encoded.isBlank()) return ""
        val rot13 = buildString(encoded.length) {
            for (ch in encoded) {
                when (ch) {
                    in 'a'..'m', in 'A'..'M' -> append((ch.code + 13).toChar())
                    in 'n'..'z', in 'N'..'Z' -> append((ch.code - 13).toChar())
                    else -> append(ch)
                }
            }
        }
        return decodeBase64Utf8(rot13)
    }

    /** RapidVid's av(s) decoder: reverse -> Base64 -> subtract ("K9L"[i % 3] % 5 + 1) -> Base64. */
    private fun decodeAv(encoded: String): String {
        return try {
            val reversed = encoded.reversed()
            val decodedBytes = Base64.decode(padBase64(reversed), Base64.DEFAULT)
            val firstPass = String(decodedBytes, Charsets.ISO_8859_1)
            val salt = "K9L"
            val unshifted = buildString(firstPass.length) {
                for (i in firstPass.indices) {
                    val shift = (salt[i % 3].code % 5) + 1
                    append((firstPass[i].code - shift).toChar())
                }
            }
            String(Base64.decode(padBase64(unshifted), Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun decodeBase64Utf8(input: String): String {
        return try {
            String(Base64.decode(padBase64(input.trim()), Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun padBase64(value: String): String {
        val rem = value.length % 4
        return if (rem == 0) value else value + "=".repeat(4 - rem)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.tt") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = this.selectFirst("span.film-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: link.text().substringBefore(" izle").trim().takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            this.selectFirst("picture img")?.let { getBestImageUrl(it) }
                ?: this.selectFirst("picture source")?.attr("srcset")
                    ?.let { extractHighestDensityUrl(it) }
        )

        val year = this.selectFirst("span.film-yil")?.text()?.trim()?.toIntOrNull()
        val score = this.selectFirst("span.imdb")?.text()?.trim()?.toDoubleOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            this.score = Score.from10(score)
        }
    }

    private fun getBestImageUrl(img: Element): String? {
        val dataSrcset = img.attr("data-srcset")
        if (dataSrcset.isNotBlank()) {
            extractHighestDensityUrl(dataSrcset)?.let { return fixUrl(it) }
        }
        val dataSrc = img.attr("data-src")
        if (dataSrc.isNotBlank() && !dataSrc.startsWith("data:")) {
            return fixUrl(dataSrc)
        }
        val srcset = img.attr("srcset")
        if (srcset.isNotBlank()) {
            extractHighestDensityUrl(srcset)?.let { return fixUrl(it) }
        }
        val src = img.attr("src")
        if (src.isNotBlank() && !src.startsWith("data:")) {
            return fixUrl(src)
        }
        return null
    }

    private fun extractHighestDensityUrl(srcset: String): String? {
        return srcset.split(",")
            .map { it.trim() }
            .lastOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.substringBeforeLast(" ")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }
}
