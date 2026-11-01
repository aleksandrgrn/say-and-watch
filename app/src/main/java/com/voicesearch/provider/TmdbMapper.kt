package com.voicesearch.provider

import com.voicesearch.model.MediaDetails
import com.voicesearch.model.SearchResult
import java.util.Locale

object TmdbMapper {
    private const val POSTER_BASE_URL = "https://image.tmdb.org/t/p/w500"
    private const val FALLBACK_TITLE = "Без названия"

    fun mapResultItem(item: TmdbResultItem, genreCache: Map<Int, String>): SearchResult? {
        return when (item.mediaType) {
            "movie" -> mapMovie(item, genreCache)
            "tv" -> mapTv(item, genreCache)
            else -> null  // person and unknown types filtered out
        }
    }

    private fun mapMovie(item: TmdbResultItem, genreCache: Map<Int, String>): SearchResult {
        val year = item.releaseDate?.takeIf { it.length >= 4 }?.substring(0, 4)
        val genreNames = mapGenreNames(item.genreIds, genreCache)
        return SearchResult(
            id = "tmdb_movie_${item.id}",
            title = item.title ?: item.originalTitle ?: FALLBACK_TITLE,
            posterUrl = item.posterPath?.let { "$POSTER_BASE_URL$it" },
            year = year,
            overview = item.overview,
            metadata = buildMap {
                put("type", "movie")
                put("tmdbId", item.id.toString())
                if (genreNames != null) put("genre", genreNames)
                item.voteAverage?.let { put("rating", String.format(Locale.US, "%.1f", it)) }
            }
        )
    }

    private fun mapTv(item: TmdbResultItem, genreCache: Map<Int, String>): SearchResult {
        val year = item.firstAirDate?.takeIf { it.length >= 4 }?.substring(0, 4)
        val genreNames = mapGenreNames(item.genreIds, genreCache)
        return SearchResult(
            id = "tmdb_tv_${item.id}",
            title = item.name ?: item.originalName ?: FALLBACK_TITLE,
            posterUrl = item.posterPath?.let { "$POSTER_BASE_URL$it" },
            year = year,
            overview = item.overview,
            metadata = buildMap {
                put("type", "tv")
                put("tmdbId", item.id.toString())
                if (genreNames != null) put("genre", genreNames)
                item.voteAverage?.let { put("rating", String.format(Locale.US, "%.1f", it)) }
            }
        )
    }

    private fun mapGenreNames(genreIds: List<Int>?, genreCache: Map<Int, String>): String? {
        if (genreIds.isNullOrEmpty()) return null
        val names = genreIds.mapNotNull { genreCache[it] }
        return names.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    fun mapDetails(details: TmdbDetailsResponse): MediaDetails = MediaDetails(
        ageRating = ageRating(details),
        runtimeMinutes = (details.runtime
            ?: details.episodeRunTime?.firstOrNull { it > 0 }
            ?: details.lastEpisode?.runtime)?.takeIf { it > 0 },
        seasons = details.numberOfSeasons?.takeIf { it > 0 },
        trailerKey = trailerKey(details.videos?.results.orEmpty())
    )

    // ponytail: перевод американских рейтингов в российские шкалы — приблизительный, по
    // ближайшей возрастной ступени. Нужен потому, что RU у TMDB заполнен не у всех;
    // если начнёт врать на заметных фильмах — показывать US как есть ("PG-13").
    private val US_TO_RU = mapOf(
        "G" to "0+", "PG" to "6+", "PG-13" to "12+", "R" to "16+", "NC-17" to "18+",
        "TV-Y" to "0+", "TV-G" to "0+", "TV-Y7" to "6+", "TV-PG" to "12+",
        "TV-14" to "16+", "TV-MA" to "18+"
    )

    private fun ageRating(details: TmdbDetailsResponse): String? {
        fun certs(country: String): List<String> =
            details.releaseDates?.results.orEmpty()
                .filter { it.country == country }
                .flatMap { it.releaseDates }
                .map { it.certification.trim() } +
            details.contentRatings?.results.orEmpty()
                .filter { it.country == country }
                .map { it.rating.trim() }

        // Только «16» или «16+»: прочие подписи (NR и т.п.) пропускаем — дальше US.
        certs("RU").firstNotNullOfOrNull { Regex("""(\d{1,2})\+?""").matchEntire(it) }?.let {
            return "${it.groupValues[1]}+"
        }
        return certs("US").firstNotNullOfOrNull { US_TO_RU[it] }
    }

    private fun trailerKey(videos: List<TmdbVideo>): String? {
        // Только русский: без него карточка ищет «название трейлер» в SmartTube.
        return videos
            .filter { it.site == "YouTube" && it.type == "Trailer" && it.key.isNotBlank() && it.language == "ru" }
            .maxByOrNull { it.official }
            ?.key
    }
}
