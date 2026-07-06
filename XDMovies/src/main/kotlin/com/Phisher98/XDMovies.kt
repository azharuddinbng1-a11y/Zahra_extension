package com.phisher98

import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicInteger

class XDMovies : MainAPI() {
    override var mainUrl = "https://top.xdmovies.wtf"
    override var name = "XD Movies"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    companion object {
        val headers = mapOf(
            "x-auth-token" to base64Decode("NzI5N3Nra2loa2Fqd25zZ2FrbGFrc2h1d2Q="),
            "x-requested-with" to "XMLHttpRequest"
        )

        private val gson = Gson()

        private const val CINEMETAURL = "https://cinemeta-live.strem.io"
        const val TMDBIMAGEBASEURL = "https://image.tmdb.org/t/p/original"
        const val TMDBAPI = "https://divine-darkness-fad4.phisher13.workers.dev"

        private val titleRegex = Regex("""S(\d{1,2})E(\d{1,3})""", RegexOption.IGNORE_CASE)
        private val seasonNumRegex1 = Regex("""season-(?:packs|episodes)-(\d+)""")
        private val seasonNumRegex2 = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)

        private fun extractTmdbId(url: String): Int? = url.substringAfterLast("-").toIntOrNull()

        private fun Element.safeText(selector: String) = this.selectFirst(selector)?.text().orEmpty()
        private fun Element.safeAttr(selector: String, attr: String) = this.selectFirst(selector)?.attr(attr).orEmpty()
    }

    override val mainPage = mainPageOf(
        "Homepage" to "HomePage",
        "category.php?ott=Netflix" to "Netflix",
        "category.php?ott=Amazon" to "Amazon Prime Video",
        "category.php?ott=DisneyPlus" to "Disney+",
        "category.php?ott=AppleTVPlus" to "Apple TV+",
        "category.php?ott=HBOMax" to "HBO Max",
        "category.php?ott=Hulu" to "Hulu",
        "category.php?ott=Zee5" to "Zee5",
        "category.php?ott=JioHotstar" to "Hotstar",
    )

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("Homepage")) {
            "$mainUrl/?page=$page"
        } else {
            "$mainUrl/${request.data}&page=$page"
        }

        val document = app.get(url, interceptor = CloudflareKiller()).document
        val home = document.select("div.movie-grid a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3")?.text() ?: return null
        val href = fixUrl(this.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val quality = this.selectFirst("div.quality-badge")?.ownText()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getSearchQuality(quality)
        }
    }

    private fun highestQuality(qualities: List<String>): String? {
        return qualities
            .mapNotNull { q ->
                q.filter { it.isDigit() }.toIntOrNull()?.let { res -> res to q }
            }
            .maxByOrNull { it.first }
            ?.second
    }

    private fun SearchData.SearchDataItem.toSearchResult(): SearchResponse {
        val isTv = type.equals("tv", ignoreCase = true) || type.equals("series", ignoreCase = true)
        val tvType = if (isTv) TvType.TvSeries else TvType.Movie
        val url = mainUrl + path
        val bestQuality = highestQuality(qualities)
        return newMovieSearchResponse(title, url, tvType) {
            this.posterUrl = TMDBIMAGEBASEURL + poster
            this.quality = getSearchQuality(bestQuality)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val searchData = app.get(
            "$mainUrl/php/search_api.php?query=$query&fuzzy=true",
            headers = headers
        ).parsedSafe<SearchData>() ?: return null
        val results = searchData.mapNotNull { it.toSearchResult() }
        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
    val document = app.get(url, interceptor = CloudflareKiller()).document

    // ✅ EXACT selectors from HTML
    val title = document.selectFirst("div.info h2")?.text().orEmpty()
    val poster = document.selectFirst("img.poster")?.attr("src")
        ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        ?: ""
    val backgroundPoster = document.selectFirst("#movie-header")
        ?.attr("style")?.substringAfter("url('")?.substringBefore("')")
        ?: poster
    val description = document.selectFirst("p.overview")?.text().orEmpty()
    val rating = Score.from10(
        document.select("p:contains(Rating:)").text()
            .removePrefix("Rating:").trim().substringBefore("/").trim()
    )
    val audios = document.selectFirst("span.neon-audio")?.text()
        ?.split(",")?.map { it.trim() } ?: emptyList()
    val genreText = document.select("p:contains(Genres:)").firstOrNull()
        ?.ownText()?.split(",")?.map { it.trim() } ?: emptyList()
    val tags = genreText + audios
    val firstAirDate = document.select("p:contains(First Air Date:)").text()
        .removePrefix("First Air Date:").trim()
    val year = firstAirDate.substringBefore("-").toIntOrNull()
    val source = document.selectFirst("span.neon-source")?.text().orEmpty()

    // TvType: URL se detect karo
    val contentType = url.substringAfter("$mainUrl/").substringBefore("/")
    val tvType = when {
        contentType.equals("anime", ignoreCase = true) -> TvType.Anime
        contentType.equals("tv", ignoreCase = true) ||
        contentType.equals("series", ignoreCase = true) -> TvType.TvSeries
        document.selectFirst("div.season-section") != null -> TvType.TvSeries
        else -> TvType.Movie
    }

    val tmdbTvTypeSlug = if (tvType == TvType.Movie) "movie" else "tv"
    val tvTypeSlugForCinemeta = if (tvType == TvType.Movie) "movie" else "series"
    val tmdbId = extractTmdbId(url) ?: 0

    val tmdbResText = runCatching {
        app.get("$TMDBAPI/$tmdbTvTypeSlug/$tmdbId/external_ids?api_key=1865f43a0549ca50d341dd9ab8b29f49").text
    }.getOrNull()
    val imdbId = gson.fromJson(tmdbResText, IMDB::class.java)?.imdbId

    val creditsJsonText = runCatching {
        app.get("$TMDBAPI/$tmdbTvTypeSlug/$tmdbId/credits?api_key=1865f43a0549ca50d341dd9ab8b29f49&language=en-US").text
    }.getOrNull()
    val actors = parseTmdbActors(creditsJsonText)

    val detailsJsonText = runCatching {
        app.get("$TMDBAPI/$tmdbTvTypeSlug/$tmdbId?api_key=1865f43a0549ca50d341dd9ab8b29f49&language=en-US").text
    }.getOrNull()
    val genres = detailsJsonText?.let { JSONObject(it) }
        ?.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name").takeIf { it!!.isNotBlank() }
            }
        } ?: emptyList()

    val tmdbPlot = detailsJsonText?.let {
        JSONObject(it).optString("overview").takeIf { s -> s.isNotBlank() }
    }
    val finalPlot = tmdbPlot ?: description

    val logoUrl = fetchTmdbLogoUrl(
        tmdbAPI = "https://api.themoviedb.org/3",
        apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
        type = tvType, tmdbId = tmdbId, appLangCode = "en"
    )

    val responseData = imdbId?.takeIf { it.isNotBlank() && it != "0" }?.let {
        val json = app.get("$CINEMETAURL/meta/$tvTypeSlugForCinemeta/$it.json").text
        if (json.startsWith("{")) gson.fromJson(json, ResponseData::class.java) else null
    }

    // ✅ Movie links
    val downloadLinks = document.select("div.download-item a, a.movie-download-btn, a.download-button")
        .mapNotNull { it.absUrl("href").takeIf { l -> l.isNotEmpty() } }
    val href = downloadLinks.toJson()

    if (tvType == TvType.TvSeries || tvType == TvType.Anime) {
        val episodes = mutableListOf<Episode>()

        // ✅ Season number: id="season-episodes-1" se extract karo
        document.select("div[id^=season-episodes-]").forEach { seasonDiv ->
            val seasonNum = seasonDiv.id()
    .removePrefix("season-episodes-")
    .toIntOrNull() ?: 1

            val tmdbSeasonRes: TMDBRes? = runCatching {
                gson.fromJson(
                    app.get("$TMDBAPI/tv/$tmdbId/season/$seasonNum?api_key=1865f43a0549ca50d341dd9ab8b29f49&language=en-US").text,
                    TMDBRes::class.java
                )
            }.getOrNull()

            // ✅ Episodes: div.episode-card se
            val episodeMap = mutableMapOf<Int, MutableList<String>>()

            seasonDiv.select("div.episode-card").forEach { card ->
                val titleText = card.selectFirst("div.episode-title")?.text().orEmpty()
                // S01E01 pattern se episode number nikalo
                val epNum = titleRegex.find(titleText)?.groupValues?.get(2)?.toIntOrNull()
                    ?: return@forEach
                val link = card.selectFirst("a.movie-download-btn")
                    ?.absUrl("href")?.takeIf { it.isNotEmpty() }
                    ?: return@forEach
                episodeMap.getOrPut(epNum) { mutableListOf() }.add(link)
            }

            for ((epNum, links) in episodeMap) {
                val tmdbEp = tmdbSeasonRes?.episodes?.find { it.episodeNumber == epNum }
                val info = responseData?.meta?.videos
                    ?.find { it.season == seasonNum && it.episode == epNum }
                episodes += newEpisode(links.toJson()) {
                    this.name = tmdbEp?.name ?: info?.name ?: "Episode $epNum"
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = tmdbEp?.stillPath?.let { TMDBIMAGEBASEURL + it }
                    this.description = tmdbEp?.overview ?: info?.overview
                    this.score = Score.from10(tmdbEp?.voteAverage)
                    addDate(tmdbEp?.airDate)
                }
            }
        }

        // ✅ Packs: div[id^=season-packs-] se
        document.select("div[id^=season-packs-]").forEach { packsDiv ->
            val seasonNum = packsDiv.id()
    .removePrefix("season-packs-")
    .toIntOrNull() ?: 1

            packsDiv.select("div.pack-card").forEachIndexed { idx, pack ->
                val link = pack.selectFirst("a.download-button")
                    ?.absUrl("href")?.takeIf { it.isNotEmpty() }
                    ?: return@forEachIndexed
                val packTitle = pack.selectFirst("h4.pack-title")?.text()
                    ?: "Season $seasonNum Pack ${idx + 1}"
                episodes += newEpisode(listOf(link).toJson()) {
                    this.name = packTitle
                    this.season = seasonNum
                    this.episode = -(idx + 1) // negative = pack
                    this.posterUrl = null
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backgroundPoster
            try { this.logoUrl = logoUrl } catch (_: Throwable) {}
            this.year = year
            this.plot = finalPlot
            this.tags = tags.ifEmpty { genres }
            this.score = rating
            this.contentRating = source
            this.actors = actors
            addImdbId(imdbId)
        }
    }

    return newMovieLoadResponse(title, url, TvType.Movie, href) {
        this.posterUrl = poster
        this.backgroundPosterUrl = backgroundPoster
        try { this.logoUrl = logoUrl } catch (_: Throwable) {}
        this.year = year
        this.plot = finalPlot
        this.tags = tags.ifEmpty { genres }
        this.score = rating
        this.contentRating = source
        this.actors = actors
        addImdbId(imdbId)
    }
}

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val links = runCatching {
            JSONArray(data).let { arr ->
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
            }
        }.getOrElse {
            listOf(data.trim()).filter { it.isNotEmpty() }
        }

        if (links.isEmpty()) return false

        val successCount = AtomicInteger(0)

        coroutineScope {
            links.map { link ->
                launch(Dispatchers.IO) {
                    runCatching {
                        val finalLink = if (link.contains("link.xdmovies.wtf")) {
                            bypassXD(link) ?: link
                        } else link
                        loadExtractor(finalLink, name, subtitleCallback, callback)
                        successCount.incrementAndGet()
                    }.onFailure {
                        Log.e("XDMovies", "Failed to load link: $link")
                    }
                }
            }.joinAll()
        }

        return successCount.get() > 0
    }
}
