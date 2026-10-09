package com.voicesearch.ui

import android.content.Context
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.graphics.Rect
import android.app.SearchManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.voicesearch.BuildConfig
import com.voicesearch.R
import com.voicesearch.dispatch.IntentDispatcher
import com.voicesearch.dispatch.LaunchResult
import com.voicesearch.data.ApiKeyStore
import com.voicesearch.data.HistoryStore
import com.voicesearch.databinding.ActivitySearchBinding
import com.voicesearch.provider.TmdbSearchProvider
import com.voicesearch.provider.TmdbException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VoiceSearch"
        private val VOICE_ACTIONS = listOf(Intent.ACTION_ASSIST, RecognizerIntent.ACTION_WEB_SEARCH)
    }

    private lateinit var binding: ActivitySearchBinding
    private lateinit var searchAdapter: SearchAdapter
    private lateinit var tmdbProvider: TmdbSearchProvider
    private val keyStore by lazy {
        ApiKeyStore(getSharedPreferences(ApiKeyStore.PREFS_NAME, MODE_PRIVATE))
    }
    private val historyStore by lazy {
        HistoryStore(getSharedPreferences(ApiKeyStore.PREFS_NAME, MODE_PRIVATE))
    }
    private var apiKeyDialogVisible = false
    private var onKeySaved: () -> Unit = {}
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var pendingVoiceStart = false
    private var searchJob: Job? = null
    private var resultsQuery: String? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withFixedCanvas())
    }

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            binding.voiceButton.isEnabled = true
            maybeStartVoiceSearch()
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                // Пользователь отказал без "Don't ask again" — краткий Toast
                Toast.makeText(this, R.string.voice_search_no_mic_permission, Toast.LENGTH_SHORT).show()
            } else {
                // "Don't ask again" — направить в App Settings
                showPermissionSettingsDialog()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Порядок источников ключа: введённый пользователем → вшитый в сборку → диалог
        applyKey(keyStore.get() ?: BuildConfig.TMDB_API_KEY)
        if (!tmdbProvider.isKeyConfigured) showApiKeyDialog()

        // SpeechRecognizer initialization + voice button setup
        // Try standard check first, then try explicit component names for TV devices
        // where AppsFilter may block SpeechRecognizer.isRecognitionAvailable()
        val speechAvailable = SpeechRecognizer.isRecognitionAvailable(this) || tryExplicitSpeechComponent()
        if (speechAvailable) {
            val component = resolveSpeechComponent()
            speechRecognizer = if (component != null) {
                SpeechRecognizer.createSpeechRecognizer(this, component)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
            speechRecognizer?.setRecognitionListener(voiceRecognitionListener)
            binding.voiceButton.isEnabled = true
            binding.voiceButton.contentDescription = getString(R.string.voice_search_button)
        } else {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            binding.voiceButton.isEnabled = false
            binding.voiceButton.contentDescription = getString(R.string.voice_error_not_available)
            Toast.makeText(this, R.string.voice_error_not_available, Toast.LENGTH_LONG).show()
        }
        binding.voiceButton.imageTintList = ContextCompat.getColorStateList(this, R.color.state_voice_mic_tint)
        binding.voiceButton.setOnClickListener { startVoiceOrRequestPermission() }
        binding.historyButton.setOnClickListener { showHistory() }

        // Search input
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        binding.searchInput.setOnKeyListener { input, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val direction = edgeFocusDirection(
                keyCode,
                binding.searchInput.selectionStart,
                binding.searchInput.selectionEnd,
                binding.searchInput.length(),
                !event.hasNoModifiers()
            ) ?: return@setOnKeyListener false
            input.focusSearch(direction)?.requestFocus() ?: false
        }

        // RecyclerView setup
        searchAdapter = SearchAdapter { result ->
            onResultClick(result)
        }
        binding.resultsRecyclerView.apply {
            layoutManager = GridLayoutManager(this@SearchActivity, 2, GridLayoutManager.HORIZONTAL, false)
            adapter = searchAdapter
            isFocusable = false
            addItemDecoration(GridSpaceItemDecoration((12 * resources.displayMetrics.density).toInt()))
        }

        // App buttons
        setupAppButtons()

        // Log launch source
        val fromVoiceIntent = intent?.action in VOICE_ACTIONS
        val preFilledQuery = intent?.getStringExtra(SearchManager.QUERY)
        Log.i(TAG, "SearchActivity launched, fromVoiceIntent=$fromVoiceIntent, action=${intent?.action}, preFilledQuery=$preFilledQuery")

        if (!preFilledQuery.isNullOrBlank()) {
            // ROM pre-filled the query (e.g. from system speech pre-processor) — skip voice input
            binding.searchInput.setText(preFilledQuery)
            performSearch()
        } else if (fromVoiceIntent && speechRecognizer != null) {
            pendingVoiceStart = true
        }
    }

    override fun onResume() {
        super.onResume()
        // lifecycle-aware auto-start (R4 fix)
        if (pendingVoiceStart && speechRecognizer != null) {
            pendingVoiceStart = false
            // Тот же путь, что у кнопки микрофона: без общей ветки запуск с пульта
            // при невыданном RECORD_AUDIO молча не делал ничего.
            startVoiceOrRequestPermission()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { setIntent(it) }
        val fromVoiceIntent = intent?.action in VOICE_ACTIONS
        val preFilledQuery = intent?.getStringExtra(SearchManager.QUERY)
        Log.i(TAG, "SearchActivity re-launched via onNewIntent, fromVoiceIntent=$fromVoiceIntent, action=${intent?.action}, preFilledQuery=$preFilledQuery")

        if (!preFilledQuery.isNullOrBlank()) {
            binding.searchInput.setText(preFilledQuery)
            performSearch()
        } else if (fromVoiceIntent) {
            pendingVoiceStart = true  // lifecycle-aware: реальный старт в onResume
        }
    }

    override fun onPause() {
        super.onPause()
        // Остановить распознавание если Activity уходит в background
        if (isListening) {
            stopListening()
        }
        pendingVoiceStart = false  // сбросить флаг — пользователь должен нажать кнопку сам
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ===== Voice recognition =====

    private val voiceRecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "SpeechRecognizer: ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "SpeechRecognizer: beginning of speech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Hook for future VU meter animation
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // No-op
        }

        override fun onEndOfSpeech() {
            Log.i(TAG, "SpeechRecognizer: end of speech")
            // Do NOT call setListeningState(false) here — onEndOfSpeech is informational.
            // A stale onEndOfSpeech from a prior stopListening()/cancel() can arrive
            // after a new session has started (setListeningState(true)), which would
            // incorrectly reset the UI state. The final state will be set by
            // onResults() or onError() for the current session.
            binding.searchInput.hint = getString(R.string.voice_processing_hint)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()
            if (!text.isNullOrBlank() && text != binding.searchInput.text.toString()) {
                binding.searchInput.setText(text)
                // НЕ вызываем performSearch() на partial results
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()
            setListeningState(false)
            binding.searchInput.hint = getString(R.string.search_hint)
            if (!text.isNullOrBlank()) {
                binding.searchInput.setText(text)
                performSearch()
            } else {
                Toast.makeText(this@SearchActivity, R.string.voice_error_no_match, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onError(errorCode: Int) {
            // Guard against stale ERROR_CLIENT from a prior cancel() — if we're
            // actively listening in a new session, that error belongs to the old one.
            if (errorCode == SpeechRecognizer.ERROR_CLIENT && isListening) {
                Log.d(TAG, "SpeechRecognizer: ignoring stale ERROR_CLIENT (new session active)")
                return
            }

            setListeningState(false)
            binding.searchInput.hint = getString(R.string.search_hint)
            Log.e(TAG, "SpeechRecognizer error: $errorCode (${errorCodeToString(errorCode)})")

            when (errorCode) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    speechRecognizer?.cancel()
                    Toast.makeText(this@SearchActivity, R.string.voice_error_speech_busy, Toast.LENGTH_SHORT).show()
                }
                SpeechRecognizer.ERROR_NO_MATCH ->
                    Toast.makeText(this@SearchActivity, R.string.voice_error_no_match, Toast.LENGTH_SHORT).show()
                SpeechRecognizer.ERROR_NETWORK ->
                    Toast.makeText(this@SearchActivity, R.string.voice_error_network, Toast.LENGTH_SHORT).show()
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    Toast.makeText(this@SearchActivity, R.string.voice_error_timeout, Toast.LENGTH_SHORT).show()
                SpeechRecognizer.ERROR_CLIENT ->
                    Toast.makeText(this@SearchActivity, R.string.voice_error_client, Toast.LENGTH_SHORT).show()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    Toast.makeText(this@SearchActivity, R.string.voice_error_permission, Toast.LENGTH_SHORT).show()
                else ->
                    Toast.makeText(this@SearchActivity, R.string.voice_error_client, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // Deprecated, no-op
        }

        private fun errorCodeToString(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            else -> "UNKNOWN($code)"
        }
    }

    private fun tryExplicitSpeechComponent(): Boolean {
        return resolveSpeechComponent() != null
    }

    private fun resolveSpeechComponent(): ComponentName? {
        val pm = packageManager
        // Known recognition service components on Android TV devices
        val candidates = listOf(
            ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
            ),
            ComponentName(
                "com.google.android.tts",
                "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
            )
        )
        for (component in candidates) {
            try {
                pm.getServiceInfo(component, 0)
                Log.i(TAG, "Found speech recognition service: ${component.flattenToString()}")
                return component
            } catch (_: Exception) {
                // Not found, try next
            }
        }
        return null
    }

    private fun maybeStartVoiceSearch() {
        if (isListening) {
            // Уже слушаем → остановить (toggle behaviour)
            stopListening()
            return
        }

        // R2 mitigation: cancel-before-start
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
            setListeningState(true)
            Log.i(TAG, "SpeechRecognizer: startListening (ru-RU, partial=true)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer", e)
            setListeningState(false)
        }
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        setListeningState(false)
        binding.searchInput.hint = getString(R.string.search_hint)
        Log.i(TAG, "SpeechRecognizer: stopped")
    }

    private fun setListeningState(listening: Boolean) {
        isListening = listening
        binding.voiceButton.isActivated = listening
        if (listening) {
            binding.searchInput.hint = getString(R.string.voice_listening_hint)
        }
        // hint сбрасывается вызывающим кодом при выходе из listening
    }

    /**
     * Единственная точка решения «слушать или просить разрешение».
     * Зовётся и кнопкой микрофона, и авто-стартом по интенту с пульта.
     */
    private fun startVoiceOrRequestPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            maybeStartVoiceSearch()
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Пользователь уже отказывал — показать rationale перед повторным запросом
            showMicRationaleDialog()
        } else {
            // Первый запрос или "Don't ask again" — запустить системный диалог
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showMicRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_rationale_title)
            .setMessage(R.string.permission_rationale_mic)
            .setPositiveButton(R.string.permission_allow) { _, _ ->
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(true)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_denied_permanent_title)
            .setMessage(R.string.permission_denied_permanent)
            .setPositiveButton(R.string.permission_go_to_settings) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open app settings", e)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(true)
            .show()
    }

    // ===== Search =====

    private fun applyKey(key: String) {
        tmdbProvider = TmdbSearchProvider(key)
        // Жанры не зависят от запроса — тянем их, пока пользователь диктует
        lifecycleScope.launch { tmdbProvider.prefetchGenres() }
    }

    /**
     * Один вход в диалог на оба пути: пустой ключ на старте и 401 при поиске.
     * [onSaved] зовётся только после подтверждения ключа сервером. Если диалог
     * уже открыт, второй не открывается, но последний [onSaved] запоминается:
     * запрос из интента, упавший на пустом ключе, повторится после ввода.
     */
    private fun showApiKeyDialog(onSaved: () -> Unit = {}) {
        onKeySaved = onSaved
        if (apiKeyDialogVisible) return
        apiKeyDialogVisible = true
        ApiKeyDialog.show(this) { key ->
            apiKeyDialogVisible = false
            keyStore.save(key)
            applyKey(key)
            onKeySaved()
        }
    }

    private fun performSearch() {
        val query = binding.searchInput.text.toString().trim()
        if (query.isBlank()) return

        // Cancel previous search
        searchJob?.cancel()
        resultsQuery = null

        // Show loading
        binding.searchProgressBar.visibility = android.view.View.VISIBLE
        binding.resultsRecyclerView.visibility = android.view.View.GONE
        binding.emptyStateText.visibility = android.view.View.GONE
        binding.providerLabel.visibility = android.view.View.GONE

        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Performing search: $query")
        }

        searchJob = lifecycleScope.launch {
            try {
                val results = tmdbProvider.search(query)
                searchAdapter.submitList(results) {
                    resultsQuery = query
                    if (results.isNotEmpty()) {
                        binding.resultsRecyclerView.post {
                            binding.resultsRecyclerView
                                .findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                        }
                    }
                }

                binding.searchProgressBar.visibility = android.view.View.GONE
                if (results.isEmpty()) {
                    binding.emptyStateText.setText(R.string.no_results)
                    binding.emptyStateText.visibility = android.view.View.VISIBLE
                    binding.resultsRecyclerView.visibility = android.view.View.GONE
                } else {
                    binding.resultsRecyclerView.visibility = android.view.View.VISIBLE
                    binding.emptyStateText.visibility = android.view.View.GONE
                }
            } catch (e: CancellationException) {
                // Не обновляем UI — Activity уничтожается
                throw e
            } catch (e: TmdbException.ApiKeyInvalid) {
                Log.e(TAG, "Search failed: API key", e)
                resultsQuery = null
                searchAdapter.submitList(emptyList())
                binding.searchProgressBar.visibility = android.view.View.GONE
                binding.emptyStateText.setText(R.string.error_api_key)
                binding.emptyStateText.visibility = android.view.View.VISIBLE
                binding.resultsRecyclerView.visibility = android.view.View.GONE
                binding.providerLabel.visibility = android.view.View.GONE
                // Ключ отклонён сервером — дать ввести новый и повторить запрос
                showApiKeyDialog { performSearch() }
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                resultsQuery = null
                searchAdapter.submitList(emptyList())
                binding.searchProgressBar.visibility = android.view.View.GONE
                binding.emptyStateText.setText(R.string.error_search_failed)
                binding.emptyStateText.visibility = android.view.View.VISIBLE
                binding.resultsRecyclerView.visibility = android.view.View.GONE
                binding.providerLabel.visibility = android.view.View.GONE
            }
        }
    }

    // ===== History =====

    private fun showHistory() {
        searchJob?.cancel()
        // История — не результат текущего запроса: NUM не должен брать из неё верхнюю карточку
        resultsQuery = null
        binding.searchProgressBar.visibility = android.view.View.GONE

        val history = historyStore.all()
        searchAdapter.submitList(history) {
            if (history.isNotEmpty()) {
                binding.resultsRecyclerView.post {
                    binding.resultsRecyclerView
                        .findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
        }
        binding.providerLabel.setText(R.string.history_button)
        binding.providerLabel.visibility = android.view.View.VISIBLE
        if (history.isEmpty()) {
            binding.emptyStateText.setText(R.string.history_empty)
            binding.emptyStateText.visibility = android.view.View.VISIBLE
            binding.resultsRecyclerView.visibility = android.view.View.GONE
        } else {
            binding.resultsRecyclerView.visibility = android.view.View.VISIBLE
            binding.emptyStateText.visibility = android.view.View.GONE
        }
    }

    // ===== Result click → DetailActivity =====

    private fun onResultClick(result: com.voicesearch.model.SearchResult) {
        historyStore.add(result)
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_TITLE, result.title)
            putExtra(DetailActivity.EXTRA_POSTER_URL, result.posterUrl)
            putExtra(DetailActivity.EXTRA_YEAR, result.year)
            putExtra(DetailActivity.EXTRA_OVERVIEW, result.overview)
            putExtra(DetailActivity.EXTRA_GENRE, result.metadata["genre"])
            putExtra(DetailActivity.EXTRA_DURATION, result.metadata["duration"])
            putExtra(DetailActivity.EXTRA_RATING, result.metadata["rating"])
            putExtra(DetailActivity.EXTRA_TYPE, result.metadata["type"])
            putExtra(DetailActivity.EXTRA_TMDB_ID, result.metadata["tmdbId"])
        }
        startActivity(intent)
    }

    // ===== App buttons =====

    private fun setupAppButtons() {
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
                    val query = binding.searchInput.text.toString().trim()
                    if (query.isBlank()) {
                        Toast.makeText(this, R.string.launch_empty_query, Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }

                    val result = if (packageName in IntentDispatcher.TMDB_CARD_PACKAGES) {
                        // Приложения с карточками TMDB: открываем верхний результат,
                        // а не запускаем поиск заново.
                        val top = searchAdapter.currentList.firstOrNull()
                        if (top == null || resultsQuery != query) {
                            Toast.makeText(this, R.string.launch_no_results, Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        IntentDispatcher.launchWithTmdb(
                            this, app, query,
                            top.metadata["tmdbId"],
                            top.metadata["type"]
                        )
                    } else {
                        IntentDispatcher.launch(this, app, query)
                    }
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

    // ===== Item decoration =====

    private class GridSpaceItemDecoration(private val spacePx: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: android.view.View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.right = spacePx
            // Щель делим поровну между рядами: карточки тянутся на высоту ряда,
            // и при отступе только у верхнего ряда он вышел бы ниже нижнего.
            if ((view.layoutParams as GridLayoutManager.LayoutParams).spanIndex == 0)
                outRect.bottom = spacePx / 2
            else
                outRect.top = spacePx / 2
        }
    }
}
