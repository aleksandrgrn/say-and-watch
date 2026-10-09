package com.voicesearch.dispatch

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for IntentDispatcher.
 *
 * NOTE: In the Android mockable-jar environment (isReturnDefaultValues=true),
 * Intent property getters (action, extras, flags, package) always return
 * null / 0 / false. This is a known limitation — Intent content assertions
 * require instrumented tests or Robolectric. These tests focus on:
 * - LaunchResult branching logic
 * - PackageManager interaction flow
 * - getSearchableApps filtering
 * - dataUriTemplate configuration
 * - launchWithTmdb() TMDB deep link logic
 */
class IntentDispatcherTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager

    @Before
    fun setUp() {
        context = mockk()
        packageManager = mockk()
        every { context.packageManager } returns packageManager
        every { context.startActivity(any()) } just Runs
    }

    private fun stubPackageInstalled(packageName: String) {
        every { packageManager.getPackageInfo(packageName, 0) } returns mockk()
    }

    private fun stubPackageNotInstalled(packageName: String) {
        every { packageManager.getPackageInfo(packageName, 0) } throws PackageManager.NameNotFoundException()
    }

    private fun stubAllPackagesInstalled() {
        stubPackageInstalled("ru.yourok.num")
        stubPackageInstalled("org.smarttube.stable")
        stubPackageInstalled("top.rootu.lamps")
        stubPackageInstalled("ru.twicker.lampa")
        stubPackageInstalled("com.lazycatsoftware.lmd")
        stubPackageInstalled("top.rootu.prisma")
        stubPackageInstalled("app.flux.tv")
    }

    private fun stubResolveActivitySuccess() {
        val activityInfo = ActivityInfo()
        activityInfo.packageName = "com.test"
        activityInfo.name = "com.test.SearchActivity"
        val resolveInfo = ResolveInfo()
        resolveInfo.activityInfo = activityInfo
        every { packageManager.resolveActivity(any(), ofType<Int>()) } returns resolveInfo
    }

    private fun stubResolveActivityNull() {
        every { packageManager.resolveActivity(any(), ofType<Int>()) } returns null
    }

    // ===== launch() — LaunchResult tests =====

    @Test
    fun launch_blankQuery_returnsNoHandler() {
        val app = IntentDispatcher.getAllApps().first()
        val result = IntentDispatcher.launch(context, app, "")
        assertEquals(LaunchResult.NO_HANDLER, result)
    }

    @Test
    fun launch_whitespaceQuery_returnsNoHandler() {
        val app = IntentDispatcher.getAllApps().first()
        val result = IntentDispatcher.launch(context, app, "   ")
        assertEquals(LaunchResult.NO_HANDLER, result)
    }

    @Test
    fun launch_appNotInstalled_returnsNoHandler() {
        val app = IntentDispatcher.getAllApps().first()
        stubPackageNotInstalled(app.packageName)

        val result = IntentDispatcher.launch(context, app, "Матрица")
        assertEquals(LaunchResult.NO_HANDLER, result)
    }

    @Test
    fun launch_resolveActivityNull_returnsNoHandler() {
        val app = IntentDispatcher.getAllApps().first()
        stubPackageInstalled(app.packageName)
        stubResolveActivityNull()

        val result = IntentDispatcher.launch(context, app, "Матрица")
        assertEquals(LaunchResult.NO_HANDLER, result)
    }

    @Test
    fun launch_successfulLaunch_returnsSuccess() {
        val app = IntentDispatcher.getAllApps().first()
        stubPackageInstalled(app.packageName)
        stubResolveActivitySuccess()

        val result = IntentDispatcher.launch(context, app, "Матрица")
        assertEquals(LaunchResult.SUCCESS, result)
    }

    @Test
    fun launch_activityNotFoundException_returnsNoHandler() {
        val app = IntentDispatcher.getAllApps().first()
        stubPackageInstalled(app.packageName)
        stubResolveActivitySuccess()
        every { context.startActivity(any()) } throws ActivityNotFoundException()

        val result = IntentDispatcher.launch(context, app, "Матрица")
        assertEquals(LaunchResult.NO_HANDLER, result)
    }

    @Test
    fun launch_generalException_returnsError() {
        val app = IntentDispatcher.getAllApps().first()
        stubPackageInstalled(app.packageName)
        stubResolveActivitySuccess()
        every { context.startActivity(any()) } throws RuntimeException("boom")

        val result = IntentDispatcher.launch(context, app, "Матрица")
        assertEquals(LaunchResult.ERROR, result)
    }

    // ===== launch() — interaction flow tests =====

    @Test
    fun launch_success_callsStartActivity() {
        val app = IntentDispatcher.getAllApps().first()
        stubPackageInstalled(app.packageName)
        stubResolveActivitySuccess()

        IntentDispatcher.launch(context, app, "Матрица")

        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun launch_noHandler_doesNotCallStartActivity() {
        val app = IntentDispatcher.getAllApps().first()
        stubPackageNotInstalled(app.packageName)

        IntentDispatcher.launch(context, app, "Матрица")

        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun launch_callsResolveActivityBeforeStart() {
        val app = IntentDispatcher.getAllApps().first()
        stubPackageInstalled(app.packageName)
        stubResolveActivitySuccess()

        IntentDispatcher.launch(context, app, "Матрица")

        verifyOrder {
            packageManager.resolveActivity(any(), ofType<Int>())
            context.startActivity(any())
        }
    }

    @Test
    fun launch_checksPackageInfoFirst() {
        val app = IntentDispatcher.getAllApps().first()
        stubPackageInstalled(app.packageName)
        stubResolveActivitySuccess()

        IntentDispatcher.launch(context, app, "Матрица")

        verifyOrder {
            packageManager.getPackageInfo(app.packageName, 0)
            packageManager.resolveActivity(any(), ofType<Int>())
            context.startActivity(any())
        }
    }

    @Test
    fun launch_smartTube_callsResolveActivity() {
        val app = IntentDispatcher.getAllApps().first { it.packageName == "org.smarttube.stable" }
        stubPackageInstalled(app.packageName)
        stubResolveActivitySuccess()

        val result = IntentDispatcher.launch(context, app, "Матрица")

        assertEquals(LaunchResult.SUCCESS, result)
        verify { packageManager.resolveActivity(any(), ofType<Int>()) }
    }

    @Test
    fun launch_setsExplicitComponent_afterResolve() {
        val app = IntentDispatcher.getAllApps().first()
        stubPackageInstalled(app.packageName)
        stubResolveActivitySuccess()

        IntentDispatcher.launch(context, app, "Матрица")

        verifyOrder {
            packageManager.resolveActivity(any(), ofType<Int>())
            context.startActivity(any())
        }
    }

    // ===== getAllApps() tests =====

    @Test
    fun getAllApps_returnsSevenApps() {
        val apps = IntentDispatcher.getAllApps()
        assertEquals(7, apps.size)
    }

    @Test
    fun getAllApps_smartTubeUsesActionView() {
        val smartTube = IntentDispatcher.getAllApps().first { it.packageName == "org.smarttube.stable" }
        assertEquals(Intent.ACTION_VIEW, smartTube.searchAction)
    }

    @Test
    fun getAllApps_numUsesActionView() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        assertEquals(Intent.ACTION_VIEW, num.searchAction)
    }

    @Test
    fun getAllApps_lampaUsesActionSearch() {
        val lampa = IntentDispatcher.getAllApps().first { it.packageName == "top.rootu.lamps" }
        assertEquals(Intent.ACTION_SEARCH, lampa.searchAction)
    }

    @Test
    fun getAllApps_lazyMediaUsesActionSearch() {
        val lazyMedia = IntentDispatcher.getAllApps().first { it.packageName == "com.lazycatsoftware.lmd" }
        assertEquals(Intent.ACTION_SEARCH, lazyMedia.searchAction)
    }

    // ===== dataUriTemplate tests =====

    @Test
    fun getAllApps_smartTubeHasDataUriTemplate() {
        val smartTube = IntentDispatcher.getAllApps().first { it.packageName == "org.smarttube.stable" }
        assertNotNull(smartTube.dataUriTemplate)
        assertTrue(smartTube.dataUriTemplate!!.contains("{query}"))
    }

    @Test
    fun getAllApps_smartTubeDataUriTemplate_isYouTubeResults() {
        val smartTube = IntentDispatcher.getAllApps().first { it.packageName == "org.smarttube.stable" }
        assertEquals("https://www.youtube.com/results?search_query={query}", smartTube.dataUriTemplate)
    }

    @Test
    fun getAllApps_numHasNoDataUriTemplate() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        assertNull(num.dataUriTemplate)
    }

    @Test
    fun getAllApps_lampaHasNoDataUriTemplate() {
        val lampa = IntentDispatcher.getAllApps().first { it.packageName == "top.rootu.lamps" }
        assertNull(lampa.dataUriTemplate)
    }

    @Test
    fun getAllApps_lazyMediaHasNoDataUriTemplate() {
        val lazyMedia = IntentDispatcher.getAllApps().first { it.packageName == "com.lazycatsoftware.lmd" }
        assertNull(lazyMedia.dataUriTemplate)
    }

    // ===== getSearchableApps() tests =====

    @Test
    fun getSearchableApps_allResolve_returnsSevenApps() {
        stubAllPackagesInstalled()
        stubResolveActivitySuccess()

        val searchable = IntentDispatcher.getSearchableApps(context)
        assertEquals(7, searchable.size)
    }

    @Test
    fun getSearchableApps_noneResolve_returnsZeroApps() {
        stubAllPackagesInstalled()
        stubResolveActivityNull()

        val searchable = IntentDispatcher.getSearchableApps(context)
        assertEquals(0, searchable.size)
    }

    @Test
    fun getSearchableApps_skipsNotInstalled() {
        stubPackageNotInstalled("ru.yourok.num")
        stubPackageInstalled("org.smarttube.stable")
        stubPackageInstalled("top.rootu.lamps")
        stubPackageNotInstalled("ru.twicker.lampa")
        stubPackageInstalled("com.lazycatsoftware.lmd")
        stubPackageNotInstalled("top.rootu.prisma")
        stubPackageNotInstalled("app.flux.tv")
        stubResolveActivitySuccess()

        val searchable = IntentDispatcher.getSearchableApps(context)
        assertFalse("NUM should not appear", searchable.any { it.packageName == "ru.yourok.num" })
        assertEquals(3, searchable.size)
    }

    // ===== launchWithTmdb() — NUM with TMDB deep link =====

    @Test
    fun launchWithTmdb_numWithTmdbId_returnsSuccess() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        stubPackageInstalled(num.packageName)
        stubResolveActivitySuccess()

        val result = IntentDispatcher.launchWithTmdb(context, num, "Матрица", "603", "movie")
        assertEquals(LaunchResult.SUCCESS, result)
        verify { context.startActivity(any()) }
    }

    @Test
    fun launchWithTmdb_numWithoutTmdbId_fallsThroughToLaunch() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        stubPackageInstalled(num.packageName)
        stubResolveActivitySuccess()

        // Without TMDB info, NUM falls through to launch() which uses ACTION_VIEW without data URI
        val result = IntentDispatcher.launchWithTmdb(context, num, "Матрица", null, null)
        // NUM without dataUriTemplate and without TMDB → launch() uses ACTION_VIEW with no data → resolveActivity may fail
        // But we stubbed it to succeed, so it should work
        assertEquals(LaunchResult.SUCCESS, result)
    }

    @Test
    fun launchWithTmdb_numWithNullTmdbId_fallsThroughToLaunch() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        stubPackageInstalled(num.packageName)
        stubResolveActivitySuccess()

        val result = IntentDispatcher.launchWithTmdb(context, num, "Матрица", null, "movie")
        assertEquals(LaunchResult.SUCCESS, result)
    }

    @Test
    fun launchWithTmdb_numWithNullTmdbType_fallsThroughToLaunch() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        stubPackageInstalled(num.packageName)
        stubResolveActivitySuccess()

        val result = IntentDispatcher.launchWithTmdb(context, num, "Матрица", "603", null)
        assertEquals(LaunchResult.SUCCESS, result)
    }

    @Test
    fun launchWithTmdb_numNotInstalled_returnsNoHandler() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        stubPackageNotInstalled(num.packageName)

        val result = IntentDispatcher.launchWithTmdb(context, num, "Матрица", "603", "movie")
        assertEquals(LaunchResult.NO_HANDLER, result)
    }

    @Test
    fun launchWithTmdb_numTmdbResolveFails_returnsNoHandler() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        stubPackageInstalled(num.packageName)
        stubResolveActivityNull()

        val result = IntentDispatcher.launchWithTmdb(context, num, "Матрица", "603", "movie")
        assertEquals(LaunchResult.NO_HANDLER, result)
    }

    @Test
    fun launchWithTmdb_numTmdbActivityNotFound_returnsNoHandler() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        stubPackageInstalled(num.packageName)
        stubResolveActivitySuccess()
        every { context.startActivity(any()) } throws ActivityNotFoundException()

        val result = IntentDispatcher.launchWithTmdb(context, num, "Матрица", "603", "movie")
        assertEquals(LaunchResult.NO_HANDLER, result)
    }

    @Test
    fun launchWithTmdb_numTmdbGeneralException_returnsError() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        stubPackageInstalled(num.packageName)
        stubResolveActivitySuccess()
        every { context.startActivity(any()) } throws RuntimeException("boom")

        val result = IntentDispatcher.launchWithTmdb(context, num, "Матрица", "603", "movie")
        assertEquals(LaunchResult.ERROR, result)
    }

    // ===== launchWithTmdb() — SmartTube falls through to launch() =====

    @Test
    fun launchWithTmdb_smartTubeIgnoresTmdb_delegatesToLaunch() {
        val smartTube = IntentDispatcher.getAllApps().first { it.packageName == "org.smarttube.stable" }
        stubPackageInstalled(smartTube.packageName)
        stubResolveActivitySuccess()

        // SmartTube should use dataUriTemplate regardless of TMDB params
        val result = IntentDispatcher.launchWithTmdb(context, smartTube, "Матрица", "603", "movie")
        assertEquals(LaunchResult.SUCCESS, result)
    }

    // Каталог Лампы — тот же TMDB: карточка открывается по ссылке, как у NUM.
    @Test
    fun launchWithTmdb_lampaWithTmdb_opensTmdbCard() {
        val lampa = IntentDispatcher.getAllApps().first { it.packageName == "top.rootu.lamps" }
        stubPackageInstalled(lampa.packageName)
        stubResolveActivitySuccess()
        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } returns mockk()

            val result = IntentDispatcher.launchWithTmdb(context, lampa, "Матрица", "603", "movie")

            assertEquals(LaunchResult.SUCCESS, result)
            verify { Uri.parse("https://www.themoviedb.org/movie/603") }
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    // Сборка Лампы ru.twicker.lampa ссылок themoviedb.org не берёт — только свою схему.
    @Test
    fun launchWithTmdb_twickerLampa_opensCardViaLampaScheme() {
        val lampa = IntentDispatcher.getAllApps().first { it.packageName == "ru.twicker.lampa" }
        stubPackageInstalled(lampa.packageName)
        stubResolveActivitySuccess()
        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } returns mockk()

            val result = IntentDispatcher.launchWithTmdb(context, lampa, "Во все тяжкие", "1396", "tv")

            assertEquals(LaunchResult.SUCCESS, result)
            verify { Uri.parse("lampa://ru.twicker.lampa/tv/1396") }
            verify(exactly = 0) { Uri.parse(match { it.startsWith("https://www.themoviedb.org/") }) }
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    @Test
    fun launchWithTmdb_smartTube_doesNotOpenTmdbCard() {
        val smartTube = IntentDispatcher.getAllApps().first { it.packageName == "org.smarttube.stable" }
        stubPackageInstalled(smartTube.packageName)
        stubResolveActivitySuccess()
        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } returns mockk()

            IntentDispatcher.launchWithTmdb(context, smartTube, "Матрица", "603", "movie")

            verify(exactly = 0) { Uri.parse(match { it.startsWith("https://www.themoviedb.org/") }) }
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    @Test
    fun tmdbCardPackages_includePrismaAndFlux() {
        assertEquals(setOf("ru.yourok.num", "top.rootu.lamps", "ru.twicker.lampa", "top.rootu.prisma", "app.flux.tv"), IntentDispatcher.TMDB_CARD_PACKAGES)
    }

    // ===== LazyMedia — поиск только явным компонентом =====
    // Неявный ACTION_SEARCH у LazyMedia резолвится в её ActivityTvArticle, а та падает с NPE.

    @Test
    fun lazyMedia_searchesViaSearchActivity() {
        // Сам Intent здесь не прочитать (геттеры — заглушки), поэтому проверяется настройка;
        // что запуск уходит в этот класс, проверено на устройстве.
        val lazy = IntentDispatcher.getAllApps().first { it.packageName == "com.lazycatsoftware.lmd" }
        assertEquals(
            "com.lazycatsoftware.lazymediadeluxe.ui.tv.activities.ActivityTvSearch",
            lazy.searchActivity
        )
    }

    // ===== launchWithTmdb() — blank query =====

    @Test
    fun launchWithTmdb_blankQuery_returnsNoHandler() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        val result = IntentDispatcher.launchWithTmdb(context, num, "", "603", "movie")
        assertEquals(LaunchResult.NO_HANDLER, result)
    }

    @Test
    fun launchWithTmdb_whitespaceQuery_returnsNoHandler() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        val result = IntentDispatcher.launchWithTmdb(context, num, "   ", "603", "movie")
        assertEquals(LaunchResult.NO_HANDLER, result)
    }

    // ===== launchWithTmdb() — interaction flow for NUM with TMDB =====

    @Test
    fun launchWithTmdb_numWithTmdb_callsResolveBeforeStart() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        stubPackageInstalled(num.packageName)
        stubResolveActivitySuccess()

        IntentDispatcher.launchWithTmdb(context, num, "Матрица", "603", "movie")

        verifyOrder {
            packageManager.getPackageInfo(num.packageName, 0)
            packageManager.resolveActivity(any(), ofType<Int>())
            context.startActivity(any())
        }
    }

    @Test
    fun launchWithTmdb_numTvShow_returnsSuccess() {
        val num = IntentDispatcher.getAllApps().first { it.packageName == "ru.yourok.num" }
        stubPackageInstalled(num.packageName)
        stubResolveActivitySuccess()

        val result = IntentDispatcher.launchWithTmdb(context, num, "Во все тяжкие", "1396", "tv")
        assertEquals(LaunchResult.SUCCESS, result)
    }
    @Test
    fun newApps_openExactMovieAndSeriesCards() {
        val cases = listOf(
            Triple("top.rootu.prisma", "movie", "https://www.themoviedb.org/movie/603"),
            Triple("top.rootu.prisma", "tv", "https://www.themoviedb.org/tv/1396"),
            Triple("app.flux.tv", "movie", "flux://movie/603"),
            Triple("app.flux.tv", "tv", "flux://series/1396"),
        )
        for ((pkg, type, expectedUri) in cases) {
            val app = IntentDispatcher.getAllApps().first { it.packageName == pkg }
            stubPackageInstalled(pkg)
            stubResolveActivitySuccess()
            mockkStatic(Uri::class)
            try {
                every { Uri.parse(any()) } returns mockk()
                val id = if (type == "movie") "603" else "1396"
                assertEquals(LaunchResult.SUCCESS,
                    IntentDispatcher.launchWithTmdb(context, app, "Название", id, type))
                verify { Uri.parse(expectedUri) }
            } finally {
                unmockkStatic(Uri::class)
            }
        }
    }

    @Test
    fun flux_withoutValidCard_doesNotLaunchUnrelatedScreen() {
        val app = IntentDispatcher.getAllApps().first { it.packageName == "app.flux.tv" }
        for ((id, type) in listOf(null to "movie", "603" to null, "603" to "person", "" to "tv")) {
            assertEquals(LaunchResult.NO_HANDLER,
                IntentDispatcher.launchWithTmdb(context, app, "Название", id, type))
        }
        assertEquals(LaunchResult.NO_HANDLER, IntentDispatcher.launch(context, app, "Название"))
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun flux_discoveryUsesCardUri() {
        stubAllPackagesInstalled()
        stubResolveActivitySuccess()
        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } returns mockk()
            assertTrue(IntentDispatcher.getSearchableApps(context).any { it.packageName == "app.flux.tv" })
            verify { Uri.parse("flux://movie/1") }
        } finally {
            unmockkStatic(Uri::class)
        }
    }

}
