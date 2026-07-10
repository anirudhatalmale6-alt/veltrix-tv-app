package com.veltrix.tv

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Player screen rebuilt on the architecture of the original stable StreamPlayer
 * (v3.2) build the client confirmed was reliable:
 *  - SRT playback via ExoPlayer + srtdroid custom data source
 *  - Foreground service + wake lock keep the process alive through outages
 *  - Reliable self-restart via AlarmManager (NOT Runtime.exit, which raced the
 *    relaunch and dumped the box to the launcher)
 *  - Simple main-thread player release, no force-close/background juggling
 *
 * Improvements added on top, as requested:
 *  1. No on-screen status text during playback
 *  2. Adaptive buffer that grows when network interruptions are detected
 *  3. D-pad Up = instant clean restart of playback
 */
class PlaybackActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VeltrixTV"
        private const val PREFS_NAME = "veltrix_prefs"
        private const val KEY_STREAM_URL = "stream_url"
        private const val KEY_PIN = "pin_code"
        private const val DEFAULT_PIN = "000000"

        private const val INITIAL_RETRY_DELAY_MS = 2000L
        private const val MAX_RETRY_DELAY_MS = 30000L
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val WATCHDOG_INTERVAL_MS = 10000L
        private const val SYNC_CHECK_INTERVAL_MS = 30000L
        private const val MAX_BUFFERING_TIME_MS = 60000L
        private const val MAX_DRIFT_THRESHOLD_MS = 3000L

        // Base buffer sizes (multiplied by the adaptive multiplier).
        private const val BASE_MIN_BUFFER_MS = 15000
        private const val BASE_MAX_BUFFER_MS = 30000
        private const val BASE_BUFFER_FOR_PLAYBACK_MS = 3000
        private const val BASE_BUFFER_AFTER_REBUFFER_MS = 5000
        private const val MAX_BUFFER_MULTIPLIER = 4
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var statusText: TextView
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var isSettingsOpen = false
    private var isReconnecting = false
    private var retryAttempts = 0
    private var currentRetryDelay = INITIAL_RETRY_DELAY_MS
    private var bufferingStartTime = 0L

    // Adaptive buffer: grows when interruptions are detected, applied on the
    // next player (re)initialization.
    private var bufferMultiplier = 1
    private var hasPlayed = false

    private val retryRunnable = Runnable { fullRestartPlayback() }

    private val syncCheckRunnable = object : Runnable {
        override fun run() {
            checkAndCorrectSync()
            handler.postDelayed(this, SYNC_CHECK_INTERVAL_MS)
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkPlayerHealth()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { handleFilePickerResult(it) } }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { handleFilePickerResult(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safety net: if anything unexpected crashes, relaunch reliably via the
        // AlarmManager path instead of dropping out to another launcher.
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception, restarting: ${throwable.message}", throwable)
            try { restartApp() } catch (_: Exception) {}
        }

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_playback)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        playerView = findViewById(R.id.player_view)
        statusText = findViewById(R.id.status_text)
        playerView.useController = false

        PlaybackService.start(this)
    }

    override fun onStart() {
        super.onStart()
        if (player == null && !isSettingsOpen) {
            initializePlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        if (player == null && !isSettingsOpen) {
            initializePlayer()
        }
    }

    override fun onPause() {
        super.onPause()
        // Keep playing in the background for TV appliance behaviour.
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        PlaybackService.stop(this)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_BACK -> {
                if (!isSettingsOpen) showPinDialog()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // Manual reset shortcut: instant clean restart of playback.
                if (!isSettingsOpen) {
                    retryAttempts = 0
                    bufferMultiplier = 1
                    fullRestartPlayback()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- Player lifecycle ----------------

    private fun initializePlayer() {
        if (player != null) return

        val minBuf = BASE_MIN_BUFFER_MS * bufferMultiplier
        val maxBuf = BASE_MAX_BUFFER_MS * bufferMultiplier
        val playbackBuf = BASE_BUFFER_FOR_PLAYBACK_MS * bufferMultiplier
        val rebufferBuf = BASE_BUFFER_AFTER_REBUFFER_MS * bufferMultiplier

        Log.i(TAG, "Init player, buffer x$bufferMultiplier (min=$minBuf max=$maxBuf)")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, playbackBuf, rebufferBuf)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(10000, true)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
                addListener(playerListener)
            }
        player = exo
        playerView.player = exo
        playerView.useController = false

        startWatchdog()
        startPlayback()
    }

    private fun startPlayback() {
        val url = prefs.getString(KEY_STREAM_URL, "") ?: ""
        if (url.isBlank()) {
            showConfigPrompt("No stream configured. Press MENU or BACK to open settings.")
            return
        }
        hideStatus()
        Log.i(TAG, "Starting playback: $url")
        val mediaSource = buildMediaSource(url)
        player?.apply {
            setMediaSource(mediaSource)
            prepare()
        }
    }

    private fun buildMediaSource(url: String): MediaSource {
        val uri = Uri.parse(url)
        val scheme = (uri.scheme ?: "").lowercase()
        val isM3u8 = url.contains(".m3u8", ignoreCase = true)

        return when {
            scheme == "srt" -> {
                val extractorsFactory = DefaultExtractorsFactory()
                    .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
                    .setTsExtractorTimestampSearchBytes(282000)
                ProgressiveMediaSource.Factory(SrtDataSource.Factory(), extractorsFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            scheme == "rtmp" || scheme == "rtmps" -> {
                ProgressiveMediaSource.Factory(RtmpDataSource.Factory())
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            scheme == "hls" || isM3u8 -> {
                val http = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000)
                    .setAllowCrossProtocolRedirects(true)
                HlsMediaSource.Factory(http)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            else -> {
                val http = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000)
                    .setAllowCrossProtocolRedirects(true)
                val dsf = DefaultDataSource.Factory(this, http)
                ProgressiveMediaSource.Factory(dsf)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> {
                    if (!isReconnecting && player != null) {
                        Log.w(TAG, "Player IDLE unexpectedly - reconnecting")
                        scheduleReconnect()
                    }
                }
                Player.STATE_BUFFERING -> {
                    if (bufferingStartTime == 0L) {
                        bufferingStartTime = System.currentTimeMillis()
                    }
                    // A real underrun (already played, not a planned reconnect)
                    // means the network is rough: grow the buffer for next init.
                    if (hasPlayed && !isReconnecting && bufferMultiplier < MAX_BUFFER_MULTIPLIER) {
                        bufferMultiplier++
                        Log.i(TAG, "Interruption detected, buffer grown to x$bufferMultiplier")
                    }
                }
                Player.STATE_READY -> {
                    bufferingStartTime = 0L
                    hideStatus()
                }
                Player.STATE_ENDED -> {
                    Log.w(TAG, "Stream ended - reconnecting")
                    bufferingStartTime = 0L
                    scheduleReconnect()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                hasPlayed = true
                isReconnecting = false
                retryAttempts = 0
                currentRetryDelay = INITIAL_RETRY_DELAY_MS
                hideStatus()
                startSyncChecking()
            } else {
                stopSyncChecking()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: ${error.message}", error)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (isReconnecting) return
        isReconnecting = true

        val url = prefs.getString(KEY_STREAM_URL, "") ?: ""
        if (url.isBlank()) {
            showConfigPrompt("No stream configured. Press MENU or BACK to open settings.")
            return
        }

        retryAttempts++
        Log.i(TAG, "Retry attempt $retryAttempts of $MAX_RETRY_ATTEMPTS")

        if (retryAttempts >= MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Max retries reached - restarting app (AlarmManager)")
            handler.postDelayed({ restartApp() }, 2000)
            return
        }

        handler.postDelayed(retryRunnable, currentRetryDelay)
        currentRetryDelay = (currentRetryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun fullRestartPlayback() {
        handler.removeCallbacks(retryRunnable)
        isReconnecting = false
        currentRetryDelay = INITIAL_RETRY_DELAY_MS
        releasePlayer()
        initializePlayer()
    }

    private fun releasePlayer() {
        stopSyncChecking()
        stopWatchdog()
        bufferingStartTime = 0L
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
    }

    /**
     * Reliable self-restart. The relaunch is scheduled with AlarmManager BEFORE
     * the process is killed, so the OS brings the app back even on TV boxes.
     * This is the key fix for the "exits to launcher and never resumes" bug -
     * the old code used Runtime.exit() which raced the relaunch.
     */
    private fun restartApp() {
        Log.i(TAG, "Performing full app restart via AlarmManager")
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 500, pending)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule restart: ${e.message}", e)
        }
        releasePlayer()
        PlaybackService.stop(this)
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    // ---------------- Watchdog & sync ----------------

    private fun startWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
    }

    private fun checkPlayerHealth() {
        val p = player ?: return

        if (bufferingStartTime > 0) {
            val bufferingDuration = System.currentTimeMillis() - bufferingStartTime
            if (bufferingDuration > MAX_BUFFERING_TIME_MS) {
                Log.w(TAG, "Watchdog: stuck buffering ${bufferingDuration}ms, full restart")
                bufferingStartTime = 0L
                handler.removeCallbacks(retryRunnable)
                isReconnecting = false
                if (bufferMultiplier < MAX_BUFFER_MULTIPLIER) bufferMultiplier++
                handler.post { fullRestartPlayback() }
                return
            }
        }

        if (!isReconnecting && p.playbackState == Player.STATE_IDLE) {
            val url = prefs.getString(KEY_STREAM_URL, "") ?: ""
            if (url.isNotBlank()) {
                Log.w(TAG, "Watchdog: player stuck IDLE, reconnecting")
                scheduleReconnect()
            }
        }

        if (!isReconnecting && p.playerError != null) {
            Log.w(TAG, "Watchdog: unhandled player error, reconnecting")
            scheduleReconnect()
        }
    }

    private fun startSyncChecking() {
        handler.removeCallbacks(syncCheckRunnable)
        handler.postDelayed(syncCheckRunnable, SYNC_CHECK_INTERVAL_MS)
    }

    private fun stopSyncChecking() {
        handler.removeCallbacks(syncCheckRunnable)
    }

    /**
     * Keeps audio/video in sync by seeking toward the live edge when drift
     * builds up (no speed manipulation) - the approach the client preferred.
     */
    private fun checkAndCorrectSync() {
        val p = player ?: return
        if (!p.isPlaying) return

        val currentPosition = p.currentPosition
        val bufferedPosition = p.bufferedPosition
        if (bufferedPosition > 0 && currentPosition > 0) {
            val behindLiveEdge = bufferedPosition - currentPosition
            if (behindLiveEdge > MAX_DRIFT_THRESHOLD_MS) {
                Log.w(TAG, "Drift ${behindLiveEdge}ms behind live edge, catching up")
                p.seekTo(bufferedPosition - 1000)
            }
        }
        if (kotlin.math.abs(p.playbackParameters.speed - 1.0f) > 0.01f) {
            p.setPlaybackSpeed(1.0f)
        }
    }

    // ---------------- Status UI (suppressed during playback) ----------------

    private fun showConfigPrompt(message: String) {
        runOnUiThread {
            statusText.text = message
            statusText.visibility = View.VISIBLE
        }
    }

    private fun hideStatus() {
        runOnUiThread { statusText.visibility = View.GONE }
    }

    // ---------------- Settings / PIN dialogs ----------------

    private fun showPinDialog() {
        isSettingsOpen = true
        stopWatchdog()
        stopSyncChecking()

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter 6-digit PIN"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
        }

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Enter PIN")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val enteredPin = input.text.toString()
                val savedPin = prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
                if (enteredPin == savedPin) {
                    showSettingsDialog()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                    resumeAfterSettings()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> resumeAfterSettings() }
            .setOnCancelListener { resumeAfterSettings() }
            .show()
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val currentUrl = prefs.getString(KEY_STREAM_URL, "") ?: ""

        val urlLabel = TextView(this).apply {
            text = "Stream URL:"
            setTextColor(0xFFCCCCCC.toInt())
        }
        val urlInput = EditText(this).apply {
            setText(currentUrl)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            hint = "srt://host:port  or  http://...  or  rtmp://..."
            isSingleLine = true
        }
        val usbButton = Button(this).apply {
            text = "Load from USB (file picker)"
            setOnClickListener {
                try {
                    openDocumentLauncher.launch(arrayOf("text/plain", "*/*"))
                } catch (e: Exception) {
                    try {
                        filePickerLauncher.launch("text/plain")
                    } catch (e2: Exception) {
                        Toast.makeText(this@PlaybackActivity,
                            "No file picker available. Install X-plore or similar.",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        val changePinButton = Button(this).apply {
            text = "Change PIN"
            setOnClickListener { showChangePinDialog() }
        }

        layout.addView(urlLabel)
        layout.addView(urlInput)
        layout.addView(usbButton)
        layout.addView(changePinButton)

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Settings")
            .setView(layout)
            .setPositiveButton("Save & Play") { _, _ ->
                val newUrl = urlInput.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    prefs.edit().putString(KEY_STREAM_URL, newUrl).apply()
                    retryAttempts = 0
                    bufferMultiplier = 1
                    hasPlayed = false
                    resumeAfterSettings(restart = true)
                } else {
                    Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show()
                    resumeAfterSettings()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> resumeAfterSettings() }
            .setOnCancelListener { resumeAfterSettings() }
            .show()
    }

    private fun resumeAfterSettings(restart: Boolean = false) {
        isSettingsOpen = false
        if (restart) {
            fullRestartPlayback()
        } else {
            startWatchdog()
            if (player != null) startSyncChecking()
        }
    }

    private fun showChangePinDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val currentPinInput = EditText(this).apply {
            hint = "Current PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
        }
        val newPinInput = EditText(this).apply {
            hint = "New 6-digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
        }
        layout.addView(currentPinInput)
        layout.addView(newPinInput)

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Change PIN")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val currentPin = currentPinInput.text.toString()
                val newPin = newPinInput.text.toString()
                val savedPin = prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
                when {
                    currentPin != savedPin ->
                        Toast.makeText(this, "Current PIN is incorrect", Toast.LENGTH_SHORT).show()
                    newPin.length != 6 ->
                        Toast.makeText(this, "PIN must be 6 digits", Toast.LENGTH_SHORT).show()
                    else -> {
                        prefs.edit().putString(KEY_PIN, newPin).apply()
                        Toast.makeText(this, "PIN changed successfully", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleFilePickerResult(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val reader = BufferedReader(InputStreamReader(inputStream))
                val url = reader.readLine()?.trim() ?: ""
                reader.close()
                inputStream.close()
                if (url.isNotEmpty()) {
                    prefs.edit().putString(KEY_STREAM_URL, url).apply()
                    Toast.makeText(this, "Stream URL loaded: $url", Toast.LENGTH_LONG).show()
                    retryAttempts = 0
                    bufferMultiplier = 1
                    hasPlayed = false
                    resumeAfterSettings(restart = true)
                } else {
                    Toast.makeText(this, "File is empty", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file: ${e.message}")
            try {
                val path = uri.path ?: ""
                val file = File(path)
                if (file.exists()) {
                    val url = file.readLines().firstOrNull()?.trim() ?: ""
                    if (url.isNotEmpty()) {
                        prefs.edit().putString(KEY_STREAM_URL, url).apply()
                        Toast.makeText(this, "Stream URL loaded: $url", Toast.LENGTH_LONG).show()
                        retryAttempts = 0
                        bufferMultiplier = 1
                        hasPlayed = false
                        resumeAfterSettings(restart = true)
                        return
                    }
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback read failed: ${e2.message}")
            }
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
