package com.voicesearch.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.voicesearch.BuildConfig
import com.voicesearch.R
import com.voicesearch.data.ApiKeyStore
import com.voicesearch.dispatch.IntentDispatcher
import com.voicesearch.dispatch.LaunchResult
import com.voicesearch.databinding.ActivityDetailBinding
import com.voicesearch.model.MediaDetails
import com.voicesearch.provider.TmdbSearchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_POSTER_URL = "extra_poster_url"
        const val EXTRA_YEAR = "extra_year"
        const val EXTRA_OVERVIEW = "extra_overview"
        const val EXTRA_GENRE = "extra_genre"
        const val EXTRA_DURATION = "extra_duration"
        const val EXTRA_RATING = "extra_rating"
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_TMDB_ID = "extra_tmdb_id"
        private const val TAG = "VoiceSearch"
    }

    private lateinit var binding: ActivityDetailBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withFixedCanvas())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Back button
        binding.backButton.setOnClickListener { finish() }

        // Populate details from intent
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val posterUrl = intent.getStringExtra(EXTRA_POSTER_URL)
        val year = intent.getStringExtra(EXTRA_YEAR)
        val overview = intent.getStringExtra(EXTRA_OVERVIEW)
        val genre = intent.getStringExtra(EXTRA_GENRE)
        val duration = intent.getStringExtra(EXTRA_DURATION)
        val rating = intent.getStringExtra(EXTRA_RATING)
        val type = intent.getStringExtra(EXTRA_TYPE)

        binding.detailTitle.text = title

        if (!year.isNullOrBlank()) {
            binding.detailYear.text = year
        } else {
            binding.detailYear.visibility = android.view.View.GONE
        }

        // Genre: show only if present
        if (!genre.isNullOrBlank()) {
            binding.detailGenre.text = genre
            binding.detailGenre.visibility = android.view.View.VISIBLE
        } else {
            binding.detailGenre.visibility = android.view.View.GONE
        }

        // Duration: show only if present
        if (!duration.isNullOrBlank()) {
            binding.detailDuration.text = duration
            binding.detailDuration.visibility = android.view.View.VISIBLE
        } else {
            binding.detailDuration.visibility = android.view.View.GONE
        }

        // Rating
        if (!rating.isNullOrBlank()) {
            binding.detailRating.visibility = android.view.View.VISIBLE
            binding.detailRating.text = "★ $rating"
        } else {
            binding.detailRating.visibility = android.view.View.GONE
        }

        // Type
        when (type) {
            "movie" -> {
                binding.detailType.visibility = android.view.View.VISIBLE
                binding.detailType.text = getString(R.string.type_movie)
            }
            "tv" -> {
                binding.detailType.visibility = android.view.View.VISIBLE
                binding.detailType.text = getString(R.string.type_tv)
            }
            else -> binding.detailType.visibility = android.view.View.GONE
        }

        // Описание без фокуса: с пульта оно только мешало ходить между «Назад» и кнопками
        if (!overview.isNullOrBlank()) {
            binding.detailOverview.text = overview
        }

        // Poster
        binding.detailPoster.load(posterUrl?.takeIf { it.isNotBlank() }) {
            crossfade(true)
            placeholder(R.drawable.bg_poster_placeholder)
            error(R.drawable.ic_no_poster)
            fallback(R.drawable.ic_no_poster)
        }

        // App buttons
        val tmdbId = intent.getStringExtra(EXTRA_TMDB_ID)
        setupAppButtons(title, tmdbId, type)
        loadDetails(title, tmdbId, type, year)

        // Фокус на первую активную кнопку приложения: экран открывают ради запуска
        // фильма, а не «Назад». Неактивные (неустановленные) кнопки пропускаем.
        val firstEnabledButton =
            listOf(binding.btnNum, binding.btnPrisma, binding.btnLampa, binding.btnFlux,
                binding.btnSmartTube, binding.btnLazyMedia)
                .firstOrNull { it.isEnabled }
        firstEnabledButton?.requestFocus()
        // Вниз/вправо с трейлера — на первую живую кнопку: жёсткий btnNum при не
        // установленном NUM замыкал цепочку «Назад → трейлер → NUM → Назад».
        val below = firstEnabledButton?.id ?: android.view.View.NO_ID
        binding.btnTrailer.nextFocusDownId = below
        binding.btnTrailer.nextFocusRightId = below
    }

    /**
     * Зовётся, когда ответ TMDB уже есть (или его не будет): до этого кнопка серая, чтобы
     * ранний клик не ушёл в поиск при существующем русском трейлере. Русский трейлер —
     * ролик в SmartTube, иначе поиск «название трейлер» в SmartTube; без SmartTube — серая.
     */
    private fun setupTrailer(title: String, videoKey: String?) {
        val smartTube = IntentDispatcher.getSearchableApps(this)
            .firstOrNull { it.packageName == IntentDispatcher.PKG_SMARTTUBE }
        when (val action = trailerAction(videoKey, title, smartTube != null)) {
            is TrailerAction.Open -> enableTrailer { openTrailer(action.videoKey) }
            TrailerAction.Search -> enableTrailer {
                val query = getString(R.string.trailer_search_query, title)
                handleLaunchResult(IntentDispatcher.launch(this, smartTube!!, query), smartTube.displayName)
            }
            TrailerAction.Off -> Unit
        }
    }

    private fun enableTrailer(onClick: () -> Unit) {
        binding.btnTrailer.isEnabled = true
        binding.btnTrailer.isFocusable = true
        binding.btnTrailer.setOnClickListener { onClick() }
    }

    private fun setupAppButtons(query: String, tmdbId: String?, tmdbType: String?) {
        val allApps = IntentDispatcher.getAllApps()
        val searchableApps = IntentDispatcher.getSearchableApps(this)
        val searchablePackages = searchableApps.map { it.packageName }.toSet()

        val buttonAppMap = mapOf(
            binding.btnNum to IntentDispatcher.PKG_NUM,
            binding.btnSmartTube to IntentDispatcher.PKG_SMARTTUBE,
            binding.btnLampa to (IntentDispatcher.LAMPA_PACKAGES.firstOrNull { it in searchablePackages }
                ?: IntentDispatcher.PKG_LAMPA),
            binding.btnLazyMedia to IntentDispatcher.PKG_LAZYMEDIA,
            binding.btnPrisma to IntentDispatcher.PKG_PRISMA,
            binding.btnFlux to IntentDispatcher.PKG_FLUX,
        )

        buttonAppMap.forEach { (button, packageName) ->
            val app = allApps.find { it.packageName == packageName }
            if (app != null && packageName in searchablePackages) {
                button.text = app.displayName
                button.isEnabled = true
                button.isFocusable = true
                button.setOnClickListener {
                    if (query.isBlank()) {
                        Toast.makeText(this, R.string.launch_empty_query, Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val result = IntentDispatcher.launchWithTmdb(this, app, query, tmdbId, tmdbType)
                    handleLaunchResult(result, app.displayName)
                }
            } else {
                button.text = app?.displayName ?: packageName
                button.isEnabled = false
                // Выключенная кнопка, оставаясь focusable, обрывает цепочку nextFocusRight:
                // фокус упирается в неё и дальше не идёт. Не-focusable Android перешагивает.
                button.isFocusable = false
            }
        }
    }

    private fun handleLaunchResult(result: LaunchResult, displayName: String) {
        when (result) {
            LaunchResult.SUCCESS -> { /* nothing */ }
            LaunchResult.NO_HANDLER ->
                Toast.makeText(this, getString(R.string.launch_no_handler, displayName), Toast.LENGTH_LONG).show()
            LaunchResult.ERROR ->
                Toast.makeText(this, getString(R.string.launch_error, displayName), Toast.LENGTH_LONG).show()
        }
    }

    // ===== Детали: возраст, длительность, трейлер =====

    /** Один запрос к TMDB. Ошибка — просто нет этих строк, трейлер ищется поиском. */
    private fun loadDetails(title: String, tmdbId: String?, type: String?, year: String?) {
        if (tmdbId.isNullOrBlank() || (type != "movie" && type != "tv")) {
            setupTrailer(title, null)
            return
        }
        val key = ApiKeyStore(getSharedPreferences(ApiKeyStore.PREFS_NAME, MODE_PRIVATE)).get()
            ?: BuildConfig.TMDB_API_KEY
        lifecycleScope.launch {
            val details = try {
                TmdbSearchProvider(key).details(type, tmdbId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Details failed", e)
                setupTrailer(title, null)
                return@launch
            }
            showDetails(details, type, year)
            setupTrailer(title, details.trailerKey)
        }
    }

    private fun showDetails(details: MediaDetails, type: String, year: String?) {
        val yearLine = listOfNotNull(year?.takeIf { it.isNotBlank() }, details.ageRating).joinToString(" · ")
        if (yearLine.isNotEmpty()) {
            binding.detailYear.text = yearLine
            binding.detailYear.visibility = android.view.View.VISIBLE
        }

        val runtime = details.runtimeMinutes?.let { formatMinutes(it) }
        val durationLine = if (type == "movie") {
            runtime?.let { getString(R.string.duration_movie, it) }
        } else {
            listOfNotNull(
                runtime?.let { getString(R.string.duration_episode, it) },
                details.seasons?.let { resources.getQuantityString(R.plurals.seasons, it, it) }
            ).joinToString(" · ").ifEmpty { null }
        }
        if (durationLine != null) {
            binding.detailDuration.text = durationLine
            binding.detailDuration.visibility = android.view.View.VISIBLE
        }
    }

    private fun formatMinutes(total: Int): String = when {
        total < 60 -> getString(R.string.duration_minutes, total)
        total % 60 == 0 -> getString(R.string.duration_hours, total / 60)
        else -> getString(R.string.duration_hours_minutes, total / 60, total % 60)
    }

    /** Ролик YouTube в SmartTube: кнопку включает только [setupTrailer], когда SmartTube есть. */
    private fun openTrailer(videoKey: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoKey"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setPackage(IntentDispatcher.PKG_SMARTTUBE)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app for trailer $videoKey", e)
            Toast.makeText(this, R.string.trailer_no_player, Toast.LENGTH_LONG).show()
        }
    }
}
