package com.voicesearch.provider

import com.squareup.moshi.Json

// DTO classes for TMDB API v3 JSON responses.
// Uses Moshi reflection (KotlinJsonAdapterFactory) — no @JsonClass needed.
// NOTE: Moshi does NOT auto-convert snake_case → camelCase.
// @Json(name=...) is required for all fields where JSON key != Kotlin property name.

data class TmdbMultiSearchResponse(
    val results: List<TmdbResultItem> = emptyList(),
    @Json(name = "total_results") val totalResults: Int = 0
)

data class TmdbResultItem(
    val id: Long = 0,
    @Json(name = "media_type") val mediaType: String = "",       // "movie" | "tv" | "person"
    // Movie-specific
    val title: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,  // "YYYY-MM-DD"
    // TV-specific
    val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null, // "YYYY-MM-DD"
    // Common
    @Json(name = "poster_path") val posterPath: String? = null,
    val overview: String? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null
)

data class TmdbGenreListResponse(
    val genres: List<TmdbGenre> = emptyList()
)

data class TmdbGenre(
    val id: Int = 0,
    val name: String = ""
)

// Детали фильма/сериала с append_to_response: videos + release_dates (фильм) / content_ratings (сериал)
data class TmdbDetailsResponse(
    val runtime: Int? = null,                                              // фильм
    @Json(name = "episode_run_time") val episodeRunTime: List<Int>? = null, // сериал, часто пустой
    @Json(name = "last_episode_to_air") val lastEpisode: TmdbEpisode? = null,
    @Json(name = "number_of_seasons") val numberOfSeasons: Int? = null,
    val videos: TmdbVideos? = null,
    @Json(name = "release_dates") val releaseDates: TmdbReleaseDates? = null,
    @Json(name = "content_ratings") val contentRatings: TmdbContentRatings? = null
)

data class TmdbEpisode(val runtime: Int? = null)

data class TmdbVideos(val results: List<TmdbVideo> = emptyList())

data class TmdbVideo(
    val key: String = "",
    val site: String = "",                                   // "YouTube"
    val type: String = "",                                   // "Trailer" | "Teaser" | ...
    @Json(name = "iso_639_1") val language: String? = null,  // "ru" | "en"
    val official: Boolean = false
)

data class TmdbReleaseDates(val results: List<TmdbCountryReleases> = emptyList())

data class TmdbCountryReleases(
    @Json(name = "iso_3166_1") val country: String = "",
    @Json(name = "release_dates") val releaseDates: List<TmdbReleaseDate> = emptyList()
)

data class TmdbReleaseDate(val certification: String = "")

data class TmdbContentRatings(val results: List<TmdbContentRating> = emptyList())

data class TmdbContentRating(
    @Json(name = "iso_3166_1") val country: String = "",
    val rating: String = ""
)
