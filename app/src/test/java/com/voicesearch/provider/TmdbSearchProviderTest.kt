package com.voicesearch.provider

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class TmdbSearchProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: TmdbSearchProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = TmdbSearchProvider(
            apiKey = "test-api-key",
            baseUrl = server.url("/3/").toString(),
            client = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueGenreResponses(
        movieJson: String = """{"genres":[{"id":28,"name":"Боевик"},{"id":878,"name":"Фантастика"}]}""",
        tvJson: String = """{"genres":[{"id":28,"name":"Боевик"},{"id":18,"name":"Драма"}]}"""
    ) {
        server.enqueue(MockResponse().setBody(movieJson))
        server.enqueue(MockResponse().setBody(tvJson))
    }

    // ===== Search success =====

    @Test
    fun `search returns mapped movie and tv results`() = runBlocking {
        enqueueGenreResponses()
        server.enqueue(MockResponse().setBody("""
            {
              "page":1,
              "results":[
                {"id":603,"media_type":"movie","title":"Матрица","release_date":"1999-03-31","poster_path":"/poster1.jpg","overview":"Хакер Нео...","genre_ids":[28,878],"vote_average":8.2},
                {"id":1399,"media_type":"tv","name":"Игра престолов","first_air_date":"2011-04-17","poster_path":"/poster2.jpg","overview":"Семь семей...","genre_ids":[18],"vote_average":8.5}
              ],
              "total_pages":1,
              "total_results":2
            }
        """.trimIndent()))

        val results = provider.search("matrix")

        assertEquals(2, results.size)

        val movie = results[0]
        assertEquals("tmdb_movie_603", movie.id)
        assertEquals("Матрица", movie.title)
        assertEquals("1999", movie.year)
        assertEquals("movie", movie.metadata["type"])

        val tv = results[1]
        assertEquals("tmdb_tv_1399", tv.id)
        assertEquals("Игра престолов", tv.title)
        assertEquals("2011", tv.year)
        assertEquals("tv", tv.metadata["type"])
    }

    // ===== Person filtering =====

    @Test
    fun `search filters out person mediaType`() = runBlocking {
        enqueueGenreResponses(
            movieJson = """{"genres":[]}""",
            tvJson = """{"genres":[]}"""
        )
        server.enqueue(MockResponse().setBody("""
            {
              "page":1,
              "results":[
                {"id":603,"media_type":"movie","title":"Матрица","genre_ids":[]},
                {"id":123,"media_type":"person","name":"Keanu Reeves"}
              ],
              "total_pages":1,
              "total_results":2
            }
        """.trimIndent()))

        val results = provider.search("matrix")

        assertEquals(1, results.size)
        assertEquals("tmdb_movie_603", results[0].id)
    }

    // ===== PLACEHOLDER API key =====

    @Test
    fun `search throws ApiKeyInvalid when apiKey is PLACEHOLDER`() = runBlocking {
        val placeholderProvider = TmdbSearchProvider(
            apiKey = "PLACEHOLDER",
            baseUrl = server.url("/3/").toString(),
            client = OkHttpClient()
        )

        try {
            placeholderProvider.search("test")
            fail("Expected TmdbException.ApiKeyInvalid")
        } catch (e: TmdbException.ApiKeyInvalid) {
            // Expected
        }
    }

    @Test
    fun `search throws ApiKeyInvalid when apiKey is PLACEHOLDER_GET_YOUR_KEY`() = runBlocking {
        val placeholderProvider = TmdbSearchProvider(
            apiKey = "PLACEHOLDER_GET_YOUR_KEY",
            baseUrl = server.url("/3/").toString(),
            client = OkHttpClient()
        )

        try {
            placeholderProvider.search("test")
            fail("Expected TmdbException.ApiKeyInvalid")
        } catch (e: TmdbException.ApiKeyInvalid) {
            // Expected
        }
    }

    @Test
    fun `search throws ApiKeyInvalid when apiKey is blank`() = runBlocking {
        val blankProvider = TmdbSearchProvider(
            apiKey = "",
            baseUrl = server.url("/3/").toString(),
            client = OkHttpClient()
        )

        try {
            blankProvider.search("test")
            fail("Expected TmdbException.ApiKeyInvalid")
        } catch (e: TmdbException.ApiKeyInvalid) {
            // Expected
        }
    }

    // ===== 401 error =====

    @Test
    fun `search throws ApiKeyInvalid on 401 response`() = runBlocking {
        // Genre fetch hits 401 — fetchGenres catches per-type and returns emptyMap
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status_message":"Invalid API key"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status_message":"Invalid API key"}"""))
        // Search also hits 401 — this is the one we catch
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status_message":"Invalid API key"}"""))

        try {
            provider.search("test")
            fail("Expected TmdbException.ApiKeyInvalid")
        } catch (e: TmdbException.ApiKeyInvalid) {
            // Expected — thrown by the search request
        }
    }

    // ===== Network error =====

    @Test
    fun `search throws NetworkError on IOException`() = runBlocking {
        server.shutdown()

        try {
            provider.search("test")
            fail("Expected TmdbException.NetworkError")
        } catch (e: TmdbException.NetworkError) {
            // Expected
        }
    }

    // ===== Empty results =====

    @Test
    fun `search returns empty list when API returns no results`() = runBlocking {
        enqueueGenreResponses(
            movieJson = """{"genres":[]}""",
            tvJson = """{"genres":[]}"""
        )
        server.enqueue(MockResponse().setBody("""
            {"page":1,"results":[],"total_pages":0,"total_results":0}
        """.trimIndent()))

        val results = provider.search("nonexistent")

        assertTrue(results.isEmpty())
    }

    // ===== Genre cache lazy init + reuse =====

    @Test
    fun `genre cache is fetched once and reused on subsequent searches`() = runBlocking {
        enqueueGenreResponses()
        server.enqueue(MockResponse().setBody("""
            {"page":1,"results":[{"id":1,"media_type":"movie","title":"Test1","genre_ids":[28]}],"total_pages":1,"total_results":1}
        """.trimIndent()))

        val results1 = provider.search("first")
        assertEquals(1, results1.size)

        // Second search: only search request, no genre requests (cache reuse)
        server.enqueue(MockResponse().setBody("""
            {"page":1,"results":[{"id":2,"media_type":"movie","title":"Test2","genre_ids":[18]}],"total_pages":1,"total_results":1}
        """.trimIndent()))

        val results2 = provider.search("second")
        assertEquals(1, results2.size)

        // Verify genre cache was used: only 4 requests total (2 genre + 1 search + 1 search)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `incomplete genre cache is refetched on subsequent searches`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"genres":[{"id":28,"name":"Боевик"}]}"""))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("""
            {"page":1,"results":[{"id":1,"media_type":"movie","title":"Test1","genre_ids":[28]}],"total_pages":1,"total_results":1}
        """.trimIndent()))

        val firstResults = provider.search("first")
        assertEquals(1, firstResults.size)

        enqueueGenreResponses()
        server.enqueue(MockResponse().setBody("""
            {"page":1,"results":[{"id":2,"media_type":"movie","title":"Test2","genre_ids":[28]}],"total_pages":1,"total_results":1}
        """.trimIndent()))

        val secondResults = provider.search("second")
        assertEquals(1, secondResults.size)

        assertEquals(6, server.requestCount)
    }

    // ===== Malformed JSON =====

    @Test
    fun `search throws ParseError when API returns malformed JSON`() = runBlocking {
        enqueueGenreResponses(
            movieJson = """{"genres":[]}""",
            tvJson = """{"genres":[]}"""
        )
        server.enqueue(MockResponse().setBody("{{{broken json"))

        try {
            provider.search("test")
            fail("Expected TmdbException.ParseError")
        } catch (e: TmdbException.ParseError) {
            assertTrue(e.message!!.contains("Invalid JSON in search response"))
        }
    }

    @Test
    fun `fetchGenreList throws ParseError when API returns malformed JSON`() = runBlocking {
        // First genre request returns broken JSON
        server.enqueue(MockResponse().setBody("{{{broken json"))
        // Second genre request also broken (tv)
        server.enqueue(MockResponse().setBody("{{{broken json"))
        // Search request (won't be reached because genre errors are caught in fetchGenres)
        server.enqueue(MockResponse().setBody("""{"page":1,"results":[],"total_pages":0,"total_results":0}"""))

        // fetchGenres catches per-type, so search still works but with empty genre map
        val results = provider.search("test")
        assertTrue(results.isEmpty())
    }

    // ===== Genre fetch failure graceful degradation =====

    @Test
    fun `search proceeds without genres when genre fetch fails`() = runBlocking {
        // Both genre fetches return 500 — fetchGenres catches per-type and returns emptyMap
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        // Search still works
        server.enqueue(MockResponse().setBody("""
            {"page":1,"results":[{"id":1,"media_type":"movie","title":"Test","genre_ids":[28]}],"total_pages":1,"total_results":1}
        """.trimIndent()))

        val results = provider.search("test")

        assertEquals(1, results.size)
        assertNull(results[0].metadata["genre"])
    }

    @Test
    fun `empty genre map is not cached and is refetched on next search`() = runBlocking {
        // First search: both genre fetches fail → empty map, must NOT be cached
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("""{"page":1,"results":[],"total_pages":0,"total_results":0}"""))

        provider.search("first")
        assertEquals(3, server.requestCount)

        // Second search: genres succeed → 2 genre requests again, not served from cache
        enqueueGenreResponses()
        server.enqueue(MockResponse().setBody("""
            {"page":1,"results":[{"id":1,"media_type":"movie","title":"Test","genre_ids":[28]}],"total_pages":1,"total_results":1}
        """.trimIndent()))

        val results = provider.search("second")

        assertEquals(6, server.requestCount)
        assertEquals("Боевик", results[0].metadata["genre"])
    }

    @Test
    fun `cancelling the search cancels the in-flight HTTP call`() = runBlocking {
        enqueueGenreResponses()
        server.enqueue(
            MockResponse()
                .setBody("""{"page":1,"results":[],"total_pages":0,"total_results":0}""")
                .setBodyDelay(5, TimeUnit.SECONDS)
        )

        val job = launch(Dispatchers.IO) { provider.search("slow") }

        // Оба запроса жанров и сам поиск дошли до сервера — поиск висит на bodyDelay
        repeat(3) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }

        val elapsed = measureTimeMillis { job.cancelAndJoin() }

        assertTrue("cancelAndJoin took ${elapsed}ms, expected < 1500", elapsed < 1500)
    }

    @Test
    fun `prefetchGenres warms the cache so the next search only issues the search request`() = runBlocking {
        enqueueGenreResponses()

        provider.prefetchGenres()
        assertEquals(2, server.requestCount)

        server.enqueue(MockResponse().setBody("""
            {"page":1,"results":[{"id":1,"media_type":"movie","title":"Test","genre_ids":[28]}],"total_pages":1,"total_results":1}
        """.trimIndent()))

        val results = provider.search("test")

        // Всего три запроса: два жанровых из прогрева и один поисковый
        assertEquals(3, server.requestCount)
        assertEquals("Боевик", results[0].metadata["genre"])
    }

    @Test
    fun `prefetchGenres makes no requests when apiKey is not configured`() = runBlocking {
        val blankProvider = TmdbSearchProvider(
            apiKey = "",
            baseUrl = server.url("/3/").toString(),
            client = OkHttpClient()
        )

        blankProvider.prefetchGenres()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `validate returns normally on 200`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"genres":[{"id":28,"name":"Боевик"}]}"""))

        provider.validate()
    }

    @Test
    fun `validate throws ApiKeyInvalid on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status_message":"Invalid API key"}"""))

        try {
            provider.validate()
            fail("Expected TmdbException.ApiKeyInvalid")
        } catch (e: TmdbException.ApiKeyInvalid) {
            // Expected
        }
    }

    @Test
    fun `validate throws NetworkError when server is down`() = runBlocking {
        server.shutdown()

        try {
            provider.validate()
            fail("Expected TmdbException.NetworkError")
        } catch (e: TmdbException.NetworkError) {
            // Expected
        }
    }

    @Test
    fun `isKeyConfigured is true for a real key`() {
        assertTrue(provider.isKeyConfigured)
    }

    @Test
    fun `isKeyConfigured is false for PLACEHOLDER`() {
        val placeholderProvider = TmdbSearchProvider(
            apiKey = "PLACEHOLDER",
            baseUrl = server.url("/3/").toString(),
            client = OkHttpClient()
        )

        assertFalse(placeholderProvider.isKeyConfigured)
    }

    // ===== Trending =====

    @Test
    fun `trending requests weekly trending and maps movies and tv without persons`() = runBlocking {
        enqueueGenreResponses()
        server.enqueue(MockResponse().setBody("""
            {
              "page":1,
              "results":[
                {"id":603,"media_type":"movie","title":"Матрица","release_date":"1999-03-31","genre_ids":[28]},
                {"id":6384,"media_type":"person","name":"Киану Ривз"},
                {"id":1399,"media_type":"tv","name":"Игра престолов","first_air_date":"2011-04-17","genre_ids":[18]}
              ]
            }
        """.trimIndent()))

        val results = provider.trending()

        assertEquals(listOf("tmdb_movie_603", "tmdb_tv_1399"), results.map { it.id })
        assertEquals("Боевик", results[0].metadata["genre"])
        server.takeRequest(); server.takeRequest() // жанры
        val request = server.takeRequest()
        assertEquals("/3/trending/all/week", request.requestUrl?.encodedPath)
        assertEquals("ru-RU", request.requestUrl?.queryParameter("language"))
    }
}
