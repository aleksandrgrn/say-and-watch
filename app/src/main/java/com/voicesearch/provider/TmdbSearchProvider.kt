package com.voicesearch.provider

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.voicesearch.model.MediaDetails
import com.voicesearch.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TmdbSearchProvider(
    private val apiKey: String,
    private val language: String = "ru-RU",
    private val baseUrl: String = "https://api.themoviedb.org/3/",
    private val client: OkHttpClient = com.voicesearch.VoiceSearchApp.httpClient
) : SearchProvider {

    override val id = "tmdb"
    override val displayName = "TMDB"

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val searchResponseAdapter = moshi.adapter(TmdbMultiSearchResponse::class.java)
    private val genreListAdapter = moshi.adapter(TmdbGenreListResponse::class.java)
    private val detailsAdapter = moshi.adapter(TmdbDetailsResponse::class.java)

    private val genreMutex = Mutex()
    private var genreCache: Map<Int, String>? = null

    val isKeyConfigured: Boolean
        get() = apiKey.isNotBlank() &&
            apiKey != "PLACEHOLDER" &&
            apiKey != "PLACEHOLDER_GET_YOUR_KEY"

    override suspend fun search(query: String): List<SearchResult> {
        return withContext(Dispatchers.Default) {
            if (!isKeyConfigured) {
                Log.w(TAG, "TMDB API key is not configured — skipping search")
                throw TmdbException.ApiKeyInvalid()
            }

            val cache = ensureGenreCache()

            val response = fetchResults(
                "search/multi",
                "query" to query,
                "page" to "1",
                "include_adult" to "false"
            )
            val results = response.results
                .mapNotNull { item -> TmdbMapper.mapResultItem(item, cache) }

            Log.d(TAG, "TMDB search '$query' → ${results.size} results (API total: ${response.totalResults})")
            results
        }
    }

    /** Фильмы и сериалы в тренде за неделю — лента на пустом экране. Бросает то же, что и поиск. */
    suspend fun trending(): List<SearchResult> {
        return withContext(Dispatchers.Default) {
            if (!isKeyConfigured) throw TmdbException.ApiKeyInvalid()
            val cache = ensureGenreCache()
            fetchResults("trending/all/week")
                .results
                .mapNotNull { item -> TmdbMapper.mapResultItem(item, cache) }
        }
    }

    /**
     * Возрастной рейтинг, длительность и трейлер одним запросом — для карточки фильма.
     * [type] — "movie" | "tv". Бросает то же, что и поиск.
     */
    suspend fun details(type: String, id: String): MediaDetails {
        return withContext(Dispatchers.Default) {
            if (!isKeyConfigured) throw TmdbException.ApiKeyInvalid()
            val ratings = if (type == "tv") "content_ratings" else "release_dates"
            val json = executeGet(buildUrl(
                "$type/$id",
                "append_to_response" to "videos,$ratings",
                "include_video_language" to "ru"
            ))
            val response = try {
                detailsAdapter.fromJson(json) ?: throw TmdbException.ParseError("Null details response")
            } catch (e: com.squareup.moshi.JsonDataException) {
                throw TmdbException.ParseError("Invalid JSON in details response: ${e.message}")
            } catch (e: java.io.IOException) {
                throw TmdbException.ParseError("Invalid JSON in details response: ${e.message}")
            }
            TmdbMapper.mapDetails(response)
        }
    }

    /**
     * Прогревает кэш жанров заранее, чтобы первый поиск не ждал два лишних запроса.
     * Безопасен для вызова из UI: не бросает и молча ничего не делает без ключа.
     */
    suspend fun prefetchGenres() {
        withContext(Dispatchers.Default) {
            if (!isKeyConfigured) return@withContext
            try {
                ensureGenreCache()
            } catch (e: Exception) {
                Log.w(TAG, "Genre prefetch failed", e)
            }
        }
    }

    /**
     * Проверяет ключ одним дешёвым запросом. Ничего не возвращает:
     * успех — вернулась без исключения, иначе бросает то же, что и поиск.
     * Кэш жанров намеренно не заполняет — провайдер тут одноразовый.
     */
    suspend fun validate() {
        withContext(Dispatchers.Default) {
            fetchGenreList("movie")
        }
    }

    private suspend fun ensureGenreCache(): Map<Int, String> {
        genreCache?.let { return it }
        return genreMutex.withLock {
            genreCache ?: fetchGenres().let { (genres, isComplete) ->
                if (isComplete && genres.isNotEmpty()) genreCache = genres
                genres
            }
        }
    }

    private suspend fun fetchGenres(): Pair<Map<Int, String>, Boolean> {
        val combined = mutableMapOf<Int, String>()
        var isComplete = true
        try {
            fetchGenreList("movie").forEach { combined[it.id] = it.name }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch movie genre list", e)
            isComplete = false
        }
        try {
            fetchGenreList("tv").forEach { combined[it.id] = it.name }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch TV genre list", e)
            isComplete = false
        }
        if (combined.isNotEmpty()) {
            Log.d(TAG, "Genre cache initialized: ${combined.size} genres")
        }
        return Pair(combined, isComplete)
    }

    private suspend fun fetchGenreList(type: String): List<TmdbGenre> {
        val baseHttpUrl = baseUrl.toHttpUrl()
        val url = baseHttpUrl.newBuilder()
            .addPathSegments("genre/$type/list")
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("language", language)
            .build()

        val json = executeGet(url)
        val response = try {
            genreListAdapter.fromJson(json)
                ?: throw TmdbException.ParseError("Null genre list response")
        } catch (e: com.squareup.moshi.JsonDataException) {
            throw TmdbException.ParseError("Invalid JSON in genre list response: ${e.message}")
        } catch (e: java.io.IOException) {
            throw TmdbException.ParseError("Invalid JSON in genre list response: ${e.message}")
        }
        return response.genres
    }

    private fun buildUrl(path: String, vararg params: Pair<String, String>): okhttp3.HttpUrl =
        baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(path)
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("language", language)
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()

    private suspend fun fetchResults(
        path: String,
        vararg params: Pair<String, String>
    ): TmdbMultiSearchResponse {
        val json = executeGet(buildUrl(path, *params))
        return try {
            searchResponseAdapter.fromJson(json)
                ?: throw TmdbException.ParseError("Null search response")
        } catch (e: com.squareup.moshi.JsonDataException) {
            throw TmdbException.ParseError("Invalid JSON in search response: ${e.message}")
        } catch (e: java.io.IOException) {
            throw TmdbException.ParseError("Invalid JSON in search response: ${e.message}")
        }
    }

    private suspend fun executeGet(url: okhttp3.HttpUrl): String {
        val request = Request.Builder().url(url).build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive) return
                    cont.resumeWithException(TmdbException.NetworkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                val code = it.code
                                throw when (code) {
                                    401 -> TmdbException.ApiKeyInvalid()
                                    in 400..499 -> TmdbException.HttpError(code, "Client error")
                                    in 500..599 -> TmdbException.HttpError(code, "Server error")
                                    else -> TmdbException.HttpError(code, "Unexpected HTTP error")
                                }
                            }
                            val body = it.body?.string()
                                ?: throw TmdbException.ParseError("Empty response body")
                            cont.resume(body)
                        }
                    } catch (e: IOException) {
                        if (!cont.isActive) return
                        cont.resumeWithException(TmdbException.NetworkError(e))
                    } catch (e: TmdbException) {
                        if (!cont.isActive) return
                        cont.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        private const val TAG = "VoiceSearch"
    }
}

sealed class TmdbException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ApiKeyInvalid : TmdbException("TMDB API key is invalid or missing")
    class NetworkError(cause: IOException) : TmdbException("Network error", cause)
    class ParseError(description: String) : TmdbException("Parse error: $description")
    class HttpError(val code: Int, description: String) : TmdbException("HTTP $code: $description")
}
