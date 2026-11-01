package com.voicesearch.provider

import org.junit.Assert.*
import org.junit.Test

class TmdbDetailsMapperTest {

    private fun video(key: String, lang: String, type: String = "Trailer", site: String = "YouTube", official: Boolean = true) =
        TmdbVideo(key = key, site = site, type = type, language = lang, official = official)

    private fun movieCerts(vararg pairs: Pair<String, String>) = TmdbReleaseDates(
        pairs.map { (country, cert) -> TmdbCountryReleases(country, listOf(TmdbReleaseDate(cert))) }
    )

    // ===== Age rating =====

    @Test
    fun `russian movie certification wins over US`() {
        val d = TmdbMapper.mapDetails(TmdbDetailsResponse(releaseDates = movieCerts("US" to "R", "RU" to "16+")))
        assertEquals("16+", d.ageRating)
    }

    @Test
    fun `russian certification without plus gets one`() {
        val d = TmdbMapper.mapDetails(TmdbDetailsResponse(releaseDates = movieCerts("RU" to "12")))
        assertEquals("12+", d.ageRating)
    }

    @Test
    fun `blank russian certification falls back to mapped US`() {
        val d = TmdbMapper.mapDetails(TmdbDetailsResponse(releaseDates = movieCerts("RU" to "", "US" to "PG-13")))
        assertEquals("12+", d.ageRating)
    }

    @Test
    fun `tv content rating falls back to mapped US`() {
        val d = TmdbMapper.mapDetails(TmdbDetailsResponse(
            contentRatings = TmdbContentRatings(listOf(TmdbContentRating("US", "TV-MA")))
        ))
        assertEquals("18+", d.ageRating)
    }

    @Test
    fun `no usable certification gives null`() {
        val d = TmdbMapper.mapDetails(TmdbDetailsResponse(releaseDates = movieCerts("DE" to "16", "US" to "NR")))
        assertNull(d.ageRating)
    }

    // ===== Runtime =====

    @Test
    fun `movie runtime is taken as is`() {
        assertEquals(127, TmdbMapper.mapDetails(TmdbDetailsResponse(runtime = 127)).runtimeMinutes)
    }

    @Test
    fun `tv runtime falls back to last episode when episode_run_time is empty`() {
        val d = TmdbMapper.mapDetails(TmdbDetailsResponse(
            episodeRunTime = emptyList(),
            lastEpisode = TmdbEpisode(runtime = 45),
            numberOfSeasons = 3
        ))
        assertEquals(45, d.runtimeMinutes)
        assertEquals(3, d.seasons)
    }

    @Test
    fun `zero runtime is treated as unknown`() {
        assertNull(TmdbMapper.mapDetails(TmdbDetailsResponse(runtime = 0)).runtimeMinutes)
    }

    // ===== Trailer =====

    @Test
    fun `russian trailer wins over english`() {
        val d = TmdbMapper.mapDetails(TmdbDetailsResponse(videos = TmdbVideos(listOf(
            video("en1", "en"), video("ru1", "ru")
        ))))
        assertEquals("ru1", d.trailerKey)
    }

    @Test
    fun `no russian trailer gives null even if english exists`() {
        val d = TmdbMapper.mapDetails(TmdbDetailsResponse(videos = TmdbVideos(listOf(
            video("teaser", "ru", type = "Teaser"), video("en1", "en")
        ))))
        assertNull(d.trailerKey)
    }

    @Test
    fun `non numeric russian certification falls back to US`() {
        val d = TmdbMapper.mapDetails(TmdbDetailsResponse(releaseDates = movieCerts("RU" to "NR", "US" to "R")))
        assertEquals("16+", d.ageRating)
    }

    @Test
    fun `official trailer preferred within a language`() {
        val d = TmdbMapper.mapDetails(TmdbDetailsResponse(videos = TmdbVideos(listOf(
            video("fan", "ru", official = false), video("off", "ru", official = true)
        ))))
        assertEquals("off", d.trailerKey)
    }

    @Test
    fun `no youtube trailer gives null`() {
        val d = TmdbMapper.mapDetails(TmdbDetailsResponse(videos = TmdbVideos(listOf(
            video("v", "ru", site = "Vimeo"), video("c", "ru", type = "Clip"), video("de", "de")
        ))))
        assertNull(d.trailerKey)
    }
}
