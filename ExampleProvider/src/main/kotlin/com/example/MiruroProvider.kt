package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class MiruroProvider : MainAPI() {
    override var mainUrl = "https://miruro.ru"
    override var name = "Miruro"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    // ==================== SEARCH ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        val doc = Jsoup.parse(app.get(url).text)
        return doc.select("div.anime-card, div.flw-item, div.item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".title, h3, .film-title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val poster = this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src")

        return AnimeSearchResponse(
            name = title.trim(),
            url = fixUrl(href),
            apiName = this@MiruroProvider.name,
            type = TvType.Anime,
            posterUrl = fixUrlNull(poster),
            dubStatus = null,
            subStatus = null
        )
    }

    // ==================== ANIME DETAILS & EPISODES ====================
    override suspend fun load(url: String): LoadResponse? {
        val doc = Jsoup.parse(app.get(url).text)

        val title = doc.selectFirst("h1.entry-title, h1.film-name, h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("img.anime-poster, img.poster, img.attachment-post-thumbnail")
            ?.attr("data-src")
            ?: doc.selectFirst("img.anime-poster, img.poster")?.attr("src")
        val description = doc.selectFirst("div.description, div.synopsis, div.film-description, p.desc")?.text()?.trim()
        val year = doc.selectFirst("span.year, span.release, div.info span:contains(Year)")?.text()?.trim()
        val status = doc.selectFirst("span.status, div.info span:contains(Status)")?.text()?.trim()
        val genres = doc.select("div.genres a, a.genre, span.genre").map { it.text().trim() }

        // Episodes – adjust selector to match the actual episode list container
        val episodes = doc.select("ul#episode_related li a, div.episodes a, ul.episodes-list li a, div.eplister a")
            .mapNotNull { ep ->
                val epHref = ep.attr("href")
                val epName = ep.selectFirst(".name, .dub")?.text() ?: ep.text()
                val epNum = ep.selectFirst(".num, .episode-number")?.text()?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(ep.attr("data-number") ?: ep.text())?.value?.toIntOrNull()
                    ?: 1
                Episode(
                    name = epName.ifBlank { "Episode $epNum" },
                    episode = epNum,
                    season = 1,
                    data = fixUrl(epHref)
                )
            }

        return AnimeLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.Anime,
            posterUrl = fixUrlNull(poster),
            plot = description,
            year = year?.toIntOrNull(),
            status = status,
            genres = genres,
            episodes = episodes.distinctBy { it.data }
        )
    }

    // ==================== VIDEO STREAM EXTRACTION ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitlesCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(data).text
        val doc = Jsoup.parse(page)

        // 1) Look for a direct <video> source
        val videoSrc = doc.selectFirst("video source, video")
            ?.attr("src")?.takeIf { it.isNotBlank() }
        if (videoSrc != null) {
            callback(ExtractorLink(name, name, videoSrc, mainUrl, Qualities.P1080.value, videoSrc.endsWith(".m3u8")))
            return true
        }

        // 2) Look for an iframe
        val iframe = doc.selectFirst("iframe#iframe-embed, iframe.player-iframe, iframe")
        if (iframe != null) {
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                val iframeFull = fixUrl(iframeSrc)
                val iframePage = app.get(iframeFull).text
                extractFromIframe(iframeFull, iframePage, callback, subtitlesCallback)
                return true
            }
        }

        // 3) Fallback: search for player JavaScript variables
        val jsVars = Regex("""(?:sources|file|url)\s*[:=]\s*["']([^"']+)["']""").findAll(page)
        jsVars.forEach { match ->
            val link = match.groupValues[1]
            val type = if (link.contains(".m3u8")) ".m3u8" else ".mp4"
            callback(ExtractorLink(name, name, link, mainUrl, Qualities.P720.value, type == ".m3u8"))
        }
        return true
    }

    private fun extractFromIframe(
        iframeUrl: String,
        page: String,
        callback: (ExtractorLink) -> Unit,
        subtitlesCallback: (SubtitleFile) -> Unit
    ) {
        val doc = Jsoup.parse(page)

        // Inside iframe: try direct video source
        val videoSrc = doc.selectFirst("video source, video")?.attr("src")?.takeIf { it.isNotBlank() }
        if (videoSrc != null) {
            callback(ExtractorLink(name, name, videoSrc, iframeUrl, Qualities.P1080.value, videoSrc.endsWith(".m3u8")))
            return
        }

        // Search for JSON sources (common in many players)
        val jsonSources = Regex("""sources\s*:\s*(\[[^\]]+\])""").find(page)?.groupValues?.get(1)
        if (jsonSources != null) {
            // Extract all "file" entries from the JSON array
            Regex(""""file"\s*:\s*"([^"]+)"""").findAll(jsonSources).forEach { match ->
                val url = match.groupValues[1].replace("\\/", "/")
                val quality = Regex(""""label"\s*:\s*"([^"]+)"""").find(jsonSources)?.groupValues?.get(1) ?: "HD"
                val type = if (url.endsWith(".m3u8")) ".m3u8" else ".mp4"
                callback(ExtractorLink(name, "$name - $quality", url, iframeUrl, getQuality(quality), type == ".m3u8"))
            }
            return
        }

        // Fallback regex
        Regex("""file\s*:\s*["']([^"']+)["']""").findAll(page).forEach { match ->
            val link = match.groupValues[1]
            callback(ExtractorLink(name, name, link, iframeUrl, Qualities.P720.value, link.endsWith(".m3u8")))
        }
    }

    private fun getQuality(label: String): Int = when {
        label.contains("1080", true) -> Qualities.P1080.value
        label.contains("720", true) -> Qualities.P720.value
        label.contains("480", true) -> Qualities.P480.value
        label.contains("360", true) -> Qualities.P360.value
        else -> Qualities.P720.value
    }

    // ==================== HELPERS ====================
    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return if (url.startsWith("/")) mainUrl + url else "$mainUrl/$url"
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.let { 
            fixUrl(it) }
    }
                                    }
