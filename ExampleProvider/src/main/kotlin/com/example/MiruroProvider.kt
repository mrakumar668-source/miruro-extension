package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element

class MiruroProvider : MainAPI() { 
    override var mainUrl = "https://miruro.ru" 
    override var name = "Miruro"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override var lang = "en"
    override val hasMainPage = true

    // This function runs when you use the search bar in Cloudstream
    override suspend fun search(query: String): List<SearchResponse> {
        // Send a network request to Miruro's search path
        val url = "$mainUrl/search?keyword=$query"
        val response = app.get(url).text
        
        // Jsoup is used here to parse the HTML layout of the site
        val document = org.jsoup.Jsoup.parse(response)
        
        // Look for the anime card items in the website's HTML (update the selector based on their actual HTML structure)
        return document.select("div.anime-card, div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".title, h3")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return AnimeSearchResponse(
            name = title,
            url = fixUrl(href),
            apiName = this@MiruroProvider.name,
            type = TvType.Anime,
            posterUrl = fixUrlNull(posterUrl),
            dubStatus = null,
            subStatus = null
        )
 
    }
}
