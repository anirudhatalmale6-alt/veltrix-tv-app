package com.veltrix.tv

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class PlaybackActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VeltrixTV"
        private const val PREFS_NAME = "veltrix_prefs"
        private const val KEY_STREAM_URL = "stream_url"
        private const val KEY_PIN = "pin_code"
        private const val DEFAULT_PIN = "000000"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_BEFORE_RESTART = 5
        private const val SYNC_CHECK_INTERVAL_MS = 30000L
        private const val MAX_DRIFT_MS = 3000L
        private const val WATCHDOG_INTERVAL_MS = 15000L
        private const val MAX_BUFFERING_TIME_MS = 60000L
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var statusText: TextView
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var isSettingsOpen = false
    private var consecutiveReconnectFailures = 0
    private var bufferingStartTime = 0L
    private var isBuffering = false
    private var currentAdaptiveBufferMultiplier = 1

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFilePickerResult(it) }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleFilePickerResult(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        playerView = findViewById(R.id.player_view)
        statusText = findViewById(R.id.status_text)

        playerView.useController = false

        val streamUrl = prefs.getString(KEY_STREAM_URL, "") ?: ""
        if (streamUrl.isNotEmpty()) {
            startPlayback()
        } else {
            showStatus("No stream configured. Press MENU to open settings.")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO -> {
                if (!isSettingsOpen) {
                    showPinDialog()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (!isSettingsOpen) {
                    showPinDialog()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showPinDialog() {
        isSettingsOpen = true
        stopWatchdog()
        stopSyncChecker()

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
                    isSettingsOpen = false
                    startWatchdog()
                    startSyncChecker()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                isSettingsOpen = false
                startWatchdog()
                startSyncChecker()
            }
            .setOnCancelListener {
                isSettingsOpen = false
                startWatchdog()
                startSyncChecker()
            }
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
            hint = "srt://host:port or rtmp://... or http://..."
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
            setOnClickListener {
                showChangePinDialog()
            }
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
                    consecutiveReconnectFailures = 0
                    currentAdaptiveBufferMultiplier = 1
                    isSettingsOpen = false
                    fullRestartPlayback()
                } else {
                    Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show()
                    isSettingsOpen = false
                }
                startWatchdog()
                startSyncChecker()
            }
            .setNegativeButton("Cancel") { _, _ ->
                isSettingsOpen = false
                startWatchdog()
                startSyncChecker()
            }
            .setOnCancelListener {
                isSettingsOpen = false
                startWatchdog()
                startSyncChecker()
            }
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
                    isSettingsOpen = false
                    fullRestartPlayback()
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
                        isSettingsOpen = false
                        fullRestartPlayback()
                        return
                    }
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback read failed: ${e2.message}")
            }
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
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

                if (currentPin != savedPin) {
                    Toast.makeText(this, "Current PIN is incorrect", Toast.LENGTH_SHORT).show()
                } else if (newPin.length != 6) {
                    Toast.makeText(this, "PIN must be 6 digits", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString(KEY_PIN, newPin).apply()
                    Toast.makeText(this, "PIN changed successfully", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startPlayback() {
        val streamUrl = prefs.getString(KEY_STREAM_URL, "") ?: ""
        if (streamUrl.isEmpty()) {
            showStatus("No stream URL configured")
            return
        }

        showStatus("Connecting...")
        releasePlayer()
        initializePlayer(streamUrl)
    }

    private fun initializePlayer(streamUrl: String) {
        val bufferMs = 10000 * currentAdaptiveBufferMultiplier
        val maxBufferMs = 30000 * currentAdaptiveBufferMultiplier
        val playbackBufferMs = 2500 * currentAdaptiveBufferMultiplier

        Log.d(TAG, "Initializing player with adaptive buffer: min=${bufferMs}ms, max=${maxBufferMs}ms, playback=${playbackBufferMs}ms (multiplier=$currentAdaptiveBufferMultiplier)")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferMs,
                maxBufferMs,
                playbackBufferMs,
                5000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                playerView.player = this
                playWhenReady = true

                val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl)

                if (streamUrl.contains("srt://", ignoreCase = true)) {
                    mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
                }

                if (isLikelyLiveStream(streamUrl)) {
                    mediaItemBuilder.setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setMaxPlaybackSpeed(1.04f)
                            .setMinPlaybackSpeed(0.96f)
                            .setMaxOffsetMs(8000L * currentAdaptiveBufferMultiplier)
                            .setMinOffsetMs(3000L)
                            .setTargetOffsetMs(5000L)
                            .build()
                    )
                }

                setMediaItem(mediaItemBuilder.build())
                addListener(playerListener)
                prepare()
            }

        startWatchdog()
        startSyncChecker()
    }

    private fun isLikelyLiveStream(url: String): Boolean {
        return url.contains("srt://", ignoreCase = true) ||
                url.contains("rtmp://", ignoreCase = true) ||
                url.contains(".m3u8", ignoreCase = true)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (!isBuffering) {
                        isBuffering = true
                        bufferingStartTime = System.currentTimeMillis()
                    }
                    showStatus("Buffering...")
                }
                Player.STATE_READY -> {
                    isBuffering = false
                    bufferingStartTime = 0
                    consecutiveReconnectFailures = 0
                    hideStatus()
                }
                Player.STATE_ENDED -> {
                    showStatus("Stream ended. Reconnecting...")
                    scheduleReconnect()
                }
                Player.STATE_IDLE -> {
                    // No action
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                consecutiveReconnectFailures = 0
                isBuffering = false
                bufferingStartTime = 0
                hideStatus()
                player?.let {
                    if (it.playbackParameters.speed != 1.0f) {
                        it.playbackParameters = PlaybackParameters(1.0f)
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}")
            showStatus("Connection error. Reconnecting...")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (isSettingsOpen) return

        consecutiveReconnectFailures++
        Log.d(TAG, "Reconnect attempt $consecutiveReconnectFailures/$MAX_RECONNECT_BEFORE_RESTART")

        if (consecutiveReconnectFailures >= MAX_RECONNECT_BEFORE_RESTART) {
            Log.w(TAG, "Max reconnect attempts reached, restarting app")
            showStatus("Restarting app...")
            handler.postDelayed({ restartApp() }, 1000)
            return
        }

        handler.postDelayed({
            if (!isSettingsOpen) {
                showStatus("Reconnecting (attempt $consecutiveReconnectFailures)...")
                fullRestartPlayback()
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun fullRestartPlayback() {
        releasePlayer()
        handler.postDelayed({
            val streamUrl = prefs.getString(KEY_STREAM_URL, "") ?: ""
            if (streamUrl.isNotEmpty()) {
                initializePlayer(streamUrl)
            }
        }, 500)
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    // --- Sync checker: prevents audio/video drift on long sessions ---

    private val syncCheckRunnable = object : Runnable {
        override fun run() {
            if (!isSettingsOpen) {
                checkPlaybackSync()
            }
            handler.postDelayed(this, SYNC_CHECK_INTERVAL_MS)
        }
    }

    private fun startSyncChecker() {
        handler.removeCallbacks(syncCheckRunnable)
        handler.postDelayed(syncCheckRunnable, SYNC_CHECK_INTERVAL_MS)
    }

    private fun stopSyncChecker() {
        handler.removeCallbacks(syncCheckRunnable)
    }

    private fun checkPlaybackSync() {
        val p = player ?: return
        if (!p.isPlaying) return
        if (isSettingsOpen) return

        if (p.isCurrentMediaItemLive) {
            val liveOffset = p.currentLiveOffset
            if (liveOffset > MAX_DRIFT_MS) {
                Log.d(TAG, "Live drift detected: ${liveOffset}ms, seeking to live edge")
                showStatus("Syncing...")
                p.seekToDefaultPosition()
                handler.postDelayed({ hideStatus() }, 2000)
            }
        }

        if (p.playbackParameters.speed != 1.0f) {
            Log.d(TAG, "Speed drift: ${p.playbackParameters.speed}, resetting to 1.0")
            p.playbackParameters = PlaybackParameters(1.0f)
        }
    }

    // --- Watchdog: detects stuck buffering and triggers recovery ---

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isSettingsOpen) {
                checkPlayerHealth()
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun startWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
    }

    private fun checkPlayerHealth() {
        if (isSettingsOpen) return
        val p = player ?: return

        if (isBuffering && bufferingStartTime > 0) {
            val bufferingDuration = System.currentTimeMillis() - bufferingStartTime
            Log.d(TAG, "Buffering for ${bufferingDuration}ms (adaptive multiplier=$currentAdaptiveBufferMultiplier)")

            if (bufferingDuration > MAX_BUFFERING_TIME_MS) {
                Log.w(TAG, "Stuck buffering, increasing buffer and restarting")
                showStatus("Adjusting buffer...")

                if (currentAdaptiveBufferMultiplier < 4) {
                    currentAdaptiveBufferMultiplier++
                }
                isBuffering = false
                bufferingStartTime = 0
                fullRestartPlayback()
            }
        }

        if (!isNetworkAvailable() && p.playbackState != Player.STATE_IDLE) {
            Log.w(TAG, "Network lost, will retry when available")
            showStatus("No network. Waiting...")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // --- UI helpers ---

    private fun showStatus(message: String) {
        runOnUiThread {
            statusText.text = message
            statusText.visibility = View.VISIBLE
        }
    }

    private fun hideStatus() {
        runOnUiThread {
            statusText.visibility = View.GONE
        }
    }

    private fun releasePlayer() {
        stopWatchdog()
        stopSyncChecker()
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
    }

    override fun onResume() {
        super.onResume()
        if (!isSettingsOpen && player == null) {
            startPlayback()
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't release on pause - keep playing in background for TV
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
    }
}
