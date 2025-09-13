package com.streamflixreborn.streamflix.providers

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AniWorldDatabase
import com.streamflixreborn.streamflix.database.dao.TvShowDao
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.SerienStreamProvider.SerienStreamService
import com.streamflixreborn.streamflix.utils.AniWorldUpdateTvShowWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object AniWorldProvider : Provider {


    private const val URL = "https://aniworld.to/"
    override val baseUrl = URL

    override val name = "AniWorld"
    override val logo = "$URL/public/img/facebook.jpg"
    override val language = "de"

    private val service = Service.build()

    private var tvShowDao: TvShowDao? = null
    private var isWorkerScheduled = false
    private lateinit var appContext: Context

    private var preloadJob: Job? = null

    private val cacheLock = Any()

    fun initialize(context: Context) {
        if (AniWorldProvider.tvShowDao == null) {
            AniWorldProvider.tvShowDao = AniWorldDatabase.getInstance(context).tvShowDao()

            this.appContext = context.applicationContext

        }
        if (!AniWorldProvider.isWorkerScheduled) {
            scheduleUpdateWorker(context)
            AniWorldProvider.isWorkerScheduled = true
        }
    }


    private fun scheduleUpdateWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<AniWorldUpdateTvShowWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "AniWorldUpdateTvShowWorker",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun getDao(): TvShowDao {
        return tvShowDao ?: throw IllegalStateException("AniWorldProvider not initialized")
    }

    override suspend fun getHome(): List<Category> {
        preloadSeriesAlphabet()
        val document = service.getHome()

        val categories = mutableListOf<Category>()

        categories.add(
            Category(
                name = "Beliebt bei AniWorld",
                list = document.select("div.container > div:nth-child(7) > div.previews div.coverListItem")
                    .map {
                        TvShow(
                            id = it.selectFirst("a")
                                ?.attr("href")?.substringAfter("/anime/stream/")
                                ?: "",
                            title = it.selectFirst("a h3")
                                ?.text()
                                ?: "",
                            poster = it.selectFirst("img")
                                ?.attr("data-src")?.let { src -> URL + src },
                        )
                    }
            )
        )

        categories.add(
            Category(
                name = "Neue Animes",
                list = document.select("div.container > div:nth-child(11) > div.previews div.coverListItem")
                    .map {
                        TvShow(
                            id = it.selectFirst("a")
                                ?.attr("href")?.substringAfter("/anime/stream/")
                                ?: "",
                            title = it.selectFirst("a h3")
                                ?.text()
                                ?: "",
                            poster = it.selectFirst("img")
                                ?.attr("data-src")?.let { src -> URL + src },
                        )
                    }
            )
        )

        categories.add(
            Category(
                name = "Derzeit beliebte Animes",
                list = document.select("div.container > div:nth-child(16) > div.previews div.coverListItem")
                    .map {
                        TvShow(
                            id = it.selectFirst("a")
                                ?.attr("href")?.substringAfter("/anime/stream/")
                                ?: "",
                            title = it.selectFirst("a h3")
                                ?.text()
                                ?: "",
                            poster = it.selectFirst("img")
                                ?.attr("data-src")?.let { src -> URL + src },
                        )
                    }
            )
        )

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val document = service.getGenres()

            val genres = document.select("#seriesContainer h3").map {
                Genre(
                    id = it.text().lowercase(Locale.getDefault()),
                    name = it.text(),
                )
            }

            return genres
        }

        val lowerQuery = query.trim().lowercase(Locale.getDefault())
        val limit = chunkSize
        val offset = (page - 1) * chunkSize
        val results = getDao().searchTvShows(lowerQuery, limit, offset)
        return results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        throw Exception("Keine Filme verfügbar")
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val fromIndex = (page - 1) * chunkSize
        val toIndex = page * chunkSize

        if (!isSeriesCacheLoaded) {
            var cachedShows = emptyList<TvShow>()
            try {
                cachedShows = getDao().getAll().first()
            } catch (exception: Exception) {
                // ignore for now
            }
            if (cachedShows.isNotEmpty()) {
                synchronized(cacheLock) {
                    seriesCache.clear()
                    seriesCache.addAll(cachedShows)
                    isSeriesCacheLoaded = true
                }
            } else {
                preloadSeriesAlphabet()
            }
        }
        CoroutineScope(Dispatchers.IO).launch { preloadSeriesAlphabet() }
        synchronized(cacheLock) {
            if (fromIndex >= seriesCache.size) return emptyList()
            val actualToIndex = minOf(toIndex, seriesCache.size)
            return seriesCache.subList(fromIndex, actualToIndex).toList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        throw Exception("Keine Filme verfügbar")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val document = service.getAnime(id)

        val tvShow = TvShow(
            id = id,
            title = document.selectFirst("h1 > span")
                ?.text()
                ?: "",
            overview = document.selectFirst("p.seri_des")
                ?.attr("data-full-description"),
            released = document.selectFirst("div.series-title > small > span:nth-child(1)")
                ?.text()
                ?: "",
            trailer = document.selectFirst("div[itemprop='trailer'] a")
                ?.attr("href"),
            poster = document.selectFirst("div.seriesCoverBox img")
                ?.attr("data-src")?.let { URL + it },
            banner = document.selectFirst("#series > section > div.backdrop")
                ?.attr("style")
                ?.replace("background-image: url(/", "")?.replace(")", "")
                ?.let { URL + it },


            seasons = document.select("#stream > ul:nth-child(1) > li")
                .filter { it -> it.select("a").size > 0 }
                .map {
                    Season(
                        id = it.selectFirst("a")
                            ?.attr("href")?.substringAfter("/anime/stream/")
                            ?: "",
                        number = it.selectFirst("a")
                            ?.text()?.toIntOrNull()
                            ?: 0,
                        title = it.selectFirst("a")
                            ?.attr("title"),
                    )
                },
            genres = document.select(".genres li").map {
                Genre(
                    id = it.selectFirst("a")
                        ?.text()?.lowercase(Locale.getDefault())
                        ?: "",
                    name = it.selectFirst("a")
                        ?.text()
                        ?: "",
                )
            },
            directors = document.select(".cast li[itemprop='director']").map {
                People(
                    id = it.selectFirst("a")
                        ?.attr("href")?.substringAfter("/animes/")
                        ?: "",
                    name = it.selectFirst("span")
                        ?.text()
                        ?: ""
                )
            },
            cast = document.select(".cast li[itemprop='actor']").map {
                People(
                    id = it.selectFirst("a")
                        ?.attr("href")?.substringAfter("/animes/")
                        ?: "",
                    name = it.selectFirst("span")
                        ?.text()
                        ?: "",
                )
            },
        )

        return tvShow
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvShowId, season) = seasonId.split("/")

        val document = service.getSeason(tvShowId, season)

        val episodes = document.select("tbody tr").map {
            Episode(
                id = it.selectFirst("a")
                    ?.attr("href")?.substringAfter("/anime/stream/")
                    ?: "",
                number = it.selectFirst("meta")
                    ?.attr("content")?.toIntOrNull()
                    ?: 0,
                title = it.selectFirst("strong")
                    ?.text(),
            )
        }

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        if (page > 1) return Genre(id, "")

        val document = service.getGenre(id, page)

        val genre = Genre(
            id = id,
            name = document.selectFirst("h1")
                ?.text()?.substringBefore(" Animes")
                ?: "",

            shows = document.select(".seriesListContainer > div").map {
                TvShow(
                    id = it.selectFirst("a")
                        ?.attr("href")?.substringAfter("/anime/stream/")
                        ?: "",
                    title = it.selectFirst("h3")
                        ?.text()
                        ?: "",
                    poster = it.selectFirst("img")
                        ?.attr("data-src")?.let { src -> URL + src },
                )
            }
        )

        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id, "")

        val document = service.getPeople(id)

        val people = People(
            id = id,
            name = document.selectFirst("h1 strong")
                ?.text()
                ?: "",

            filmography = document.select(".seriesListContainer > div").map {
                TvShow(
                    id = it.selectFirst("a")
                        ?.attr("href")?.substringAfter("/anime/stream/")
                        ?: "",
                    title = it.selectFirst("h3")
                        ?.text() ?: "",
                    poster = it.selectFirst("img")
                        ?.attr("data-src")?.let { src -> URL + src },
                )
            }
        )

        return people
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val (tvShowId, seasonId, episodeId) = id.split("/")

        val document = service.getEpisode(tvShowId, seasonId, episodeId)

        val servers = document.select("div.hosterSiteVideo > ul > li").mapNotNull {
            val redirectUrl = it.selectFirst("a")
                ?.attr("href")?.let { href -> URL + href }
                ?: return@mapNotNull null

            val name = it.selectFirst("h4")
                ?.text()?.let { name ->
                    name + when (it.attr("data-lang-key")) {
                        "1" -> " - DUB"
                        "2" -> " - SUB English"
                        "3" -> " - SUB"
                        else -> ""
                    }
                }
                ?: ""

            Video.Server(
                id = name,
                name = name,
                src = redirectUrl,
            )
        }

        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val response = service.getRedirectLink(server.src)
            .let { response -> response.raw() as okhttp3.Response }
        val videoUrl = response.request.url

        val link = when (server.name) {
            "VOE" -> "https://voe.sx${videoUrl.encodedPath}"
            else -> videoUrl.toString()
        }

        return Extractor.extract(link)
    }


    private val seriesCache = mutableListOf<TvShow>()
    private const val chunkSize = 25
    private var isSeriesCacheLoaded = false

    private suspend fun preloadSeriesAlphabet() {
        val document = service.getAnimesAlphabet()
        val elements = document.select(".genre > ul > li")

        val loadedShows = elements.map {
            TvShow(
                id = it.selectFirst("a[data-alternative-title]")
                    ?.attr("href")?.substringAfter("/anime/stream/")
                    ?: "",
                title = it.selectFirst("a[data-alternative-title]")
                    ?.text()
                    ?: "",
                overview = "",
            )
        }
        val dao = getDao()
        val existingIds = dao.getAllIds()
        val newShows = loadedShows.filter { it.id !in existingIds }

        if (newShows.isNotEmpty()) {
            dao.insertAll(newShows)
        }
        val allShows = dao.getAll().first()
        synchronized(cacheLock) {
            seriesCache.clear()
            seriesCache.addAll(allShows)
            isSeriesCacheLoaded = true
        }

        scheduleUpdateWorker(appContext)
    }

    fun invalidateCache() {
        synchronized(cacheLock) {
            seriesCache.clear()
            isSeriesCacheLoaded = false
        }
    }

    fun getSeriesChunk(pageIndex: Int): List<TvShow> {
        val fromIndex = pageIndex * chunkSize
        if (fromIndex >= seriesCache.size) return emptyList()
        val toIndex = minOf(fromIndex + chunkSize, seriesCache.size)
        return seriesCache.subList(fromIndex, toIndex)
    }

    fun getTotalPages(): Int {
        return (seriesCache.size + chunkSize - 1) / chunkSize
    }


    private interface Service {
        companion object {
            private const val DNS_QUERY_URL = "https://1.1.1.1/dns-query"

            private fun getOkHttpClient(): OkHttpClient {
                val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)
                val clientBuilder = OkHttpClient.Builder()
                    .cache(appCache)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                val client = clientBuilder.build()

                val dns = DnsOverHttps.Builder()
                    .client(client)
                    .url(DNS_QUERY_URL.toHttpUrl())
                    .build()
                return clientBuilder
                    .dns(dns)
                    .build()
            }

            private fun getUnsafeOkHttpClient(): OkHttpClient {
                try {
                    val trustAllCerts = arrayOf<TrustManager>(
                        object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                        }
                    )
                    val sslContext = SSLContext.getInstance("SSL")
                    sslContext.init(null, trustAllCerts, SecureRandom())
                    val sslSocketFactory = sslContext.socketFactory

                    val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)
                    val clientBuilder = OkHttpClient.Builder()
                        .cache(appCache)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                        .hostnameVerifier { _, _ -> true }

                    val client = clientBuilder.build()

                    val dns = DnsOverHttps.Builder()
                        .client(client)
                        .url(DNS_QUERY_URL.toHttpUrl())
                        .build()

                    return clientBuilder
                        .dns(dns)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build()
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }

            fun build(): Service {
                val client = getOkHttpClient()
                val retrofit = Retrofit.Builder()
                    .baseUrl(URL)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                return retrofit.create(Service::class.java)
            }

            fun buildUnsafe(): Service {
                val client = getUnsafeOkHttpClient()
                val retrofit = Retrofit.Builder()
                    .baseUrl(URL)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                return retrofit.create(Service::class.java)
            }
        }

        @GET(".")
        suspend fun getHome(): Document

        @POST("https://aniworld.to/ajax/search")
        @FormUrlEncoded
        suspend fun search(@Field("keyword") query: String): List<SearchItem>

        @GET("animes-genres")
        suspend fun getGenres(): Document

        @GET("animes-alphabet")
        suspend fun getAnimesAlphabet(): Document

        @GET("anime/stream/{id}")
        suspend fun getAnime(@Path("id") id: String): Document

        @GET("anime/stream/{tvShowId}/{seasonId}")
        suspend fun getSeason(
            @Path("tvShowId") tvShowId: String,
            @Path("seasonId") seasonId: String,
        ): Document

        @GET("genre/{id}/{page}")
        suspend fun getGenre(
            @Path("id") id: String,
            @Path("page") page: Int,
        ): Document

        @GET("animes/{id}")
        suspend fun getPeople(@Path("id", encoded = true) id: String): Document

        @GET("anime/stream/{tvShowId}/{seasonId}/{episodeId}")
        suspend fun getEpisode(
            @Path("tvShowId") tvShowId: String,
            @Path("seasonId") seasonId: String,
            @Path("episodeId") episodeId: String,
        ): Document

        @GET
        @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        suspend fun getRedirectLink(@Url url: String): Response<ResponseBody>


        data class SearchItem(
            val title: String,
            val description: String,
            val link: String,
        )
    }
}
