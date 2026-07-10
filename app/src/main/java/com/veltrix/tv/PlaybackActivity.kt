package com.veltrix.tv

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
 *  - The app NEVER kills itself: on any outage it keeps retrying the
 *    reconnection in place forever (capped backoff), so it can never drop the
 *    box to the launcher, and it resumes the instant the stream/internet returns
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
        private const val MAX_RETRY_DELAY_MS = 10000L
        private const val WATCHDOG_INTERVAL_MS = 10000L
        private const val SYNC_CHECK_INTERVAL_MS = 30000L
        private const val MAX_BUFFERING_TIME_MS = 60000L

        // Live re-sync: only trim latency when the buffered-ahead is genuinely
        // excessive, and always leave a healthy buffer behind. The old 3s
        // threshold + seek-to-1s fought the adaptive buffer growth and produced
        // a stop/jump-to-live cycle, worse on streams whose buffer had grown.
        private const val LIVE_RESYNC_THRESHOLD_MS = 45000L
        private const val RESYNC_KEEP_BUFFER_MS = 8000L

        // Stall detection: if the player claims to be playing but its position
        // stops advancing (e.g. the source stream changes audio/video format
        // mid-stream), auto reload playback instead of freezing.
        private const val STALL_CHECK_INTERVAL_MS = 4000L
        private const val STALL_TIMEOUT_MS = 12000L

        // Base buffer sizes (multiplied by the adaptive multiplier).
        private const val BASE_MIN_BUFFER_MS = 15000
        private const val BASE_MAX_BUFFER_MS = 30000
        private const val BASE_BUFFER_FOR_PLAYBACK_MS = 3000
        private const val BASE_BUFFER_AFTER_REBUFFER_MS = 5000
        private const val MAX_BUFFER_MULTIPLIER = 3
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

    // Stall detection state.
    private var lastKnownPosition = 0L
    private var lastAdvanceTime = 0L

    // Debug overlay (toggle with D-pad Down).
    private lateinit var debugText: TextView
    private var debugVisible = false
    private var bufferingEvents = 0
    private var restartEvents = 0
    private var audioUnderruns = 0
    private var droppedFrames = 0
    private var audioDecoderName = "-"
    private var videoDecoderName = "-"
    private var lastEvent = "-"

    private val retryRunnable = Runnable { fullRestartPlayback() }

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) { audioDecoderName = decoderName }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) { videoDecoderName = decoderName }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) { this@PlaybackActivity.droppedFrames += droppedFrames }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long
        ) { audioUnderruns++ }
    }

    private val debugRunnable = object : Runnable {
        override fun run() {
            if (debugVisible) {
                debugText.text = buildDebugText()
                handler.postDelayed(this, 1000L)
            }
        }
    }

    private val stallCheckRunnable = object : Runnable {
        override fun run() {
            checkForStall()
            handler.postDelayed(this, STALL_CHECK_INTERVAL_MS)
        }
    }

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

        // Safety net: if a background thread throws (e.g. the SRT/ExoPlayer
        // loader), recover the player IN PLACE. We never kill the process -
        // on this box a process kill drops out to the launcher and doesn't
        // come back, so staying alive and reconnecting is always better.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught on ${thread.name}: ${throwable.message}", throwable)
            try {
                handler.post {
                    try { fullRestartPlayback() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_playback)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        playerView = findViewById(R.id.player_view)
        statusText = findViewById(R.id.status_text)
        playerView.useController = false

        // Debug overlay - hidden until toggled with D-pad Down.
        debugText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
            visibility = View.GONE
        }
        addContentView(
            debugText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
        )

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
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Toggle the debug stats overlay.
                if (!isSettingsOpen) toggleDebugOverlay()
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

        // Force all audio to real PCM instead of passing Dolby/AC3 through to
        // the TV. Two layers:
        //  1) Prefer the bundled FFmpeg software decoder (EXTENSION_RENDERER_MODE
        //     _PREFER) - it decodes AC3/E-AC3/AAC/MP3 to PCM entirely in software,
        //     so the Amlogic hardware passthrough decoder is never used. This is
        //     what actually stops the TV from seeing Dolby.
        //  2) Give the audio sink PCM-only capabilities as a backstop so nothing
        //     can fall back to passthrough.
        // Decoder fallback also helps recover from a mid-stream codec change.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .build()
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
                addListener(playerListener)
                addAnalyticsListener(analyticsListener)
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
                        if (hasPlayed) { bufferingEvents++; lastEvent = "buffering" }
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
                lastKnownPosition = player?.currentPosition ?: 0L
                lastAdvanceTime = System.currentTimeMillis()
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
            isReconnecting = false
            return
        }

        retryAttempts++
        Log.i(TAG, "Reconnect attempt $retryAttempts (retrying indefinitely, delay=${currentRetryDelay}ms)")

        // IMPORTANT: never kill the app. On a long outage we keep retrying in
        // place forever - the foreground service + wake lock keep us alive, and
        // the moment the stream/internet returns the next retry just succeeds.
        // (The old "restart the whole app after 5 tries" is what made the box
        // drop to the launcher and never come back.)
        handler.postDelayed(retryRunnable, currentRetryDelay)
        currentRetryDelay = (currentRetryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun fullRestartPlayback() {
        handler.removeCallbacks(retryRunnable)
        isReconnecting = false
        currentRetryDelay = INITIAL_RETRY_DELAY_MS
        restartEvents++
        lastEvent = "reload"
        releasePlayer()
        initializePlayer()
    }

    private fun releasePlayer() {
        stopSyncChecking()
        stopWatchdog()
        bufferingStartTime = 0L
        player?.let {
            it.removeListener(playerListener)
            it.removeAnalyticsListener(analyticsListener)
            it.release()
        }
        player = null
    }

    // ---------------- Watchdog & sync ----------------

    private fun startWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        lastKnownPosition = 0L
        lastAdvanceTime = System.currentTimeMillis()
        handler.removeCallbacks(stallCheckRunnable)
        handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(stallCheckRunnable)
    }

    /**
     * Detects a "playing but frozen" stall - the case where the source stream
     * changes format mid-stream and the player wedges without reporting an
     * error or going idle. When the position stops advancing while we expect
     * playback, reload the stream automatically (same as a D-pad Up reset).
     */
    private fun checkForStall() {
        val p = player ?: return
        if (isSettingsOpen || isReconnecting) {
            lastAdvanceTime = System.currentTimeMillis()
            return
        }
        // Only meaningful when we actually want to play and have media ready.
        if (!p.playWhenReady || p.playbackState != Player.STATE_READY) {
            lastKnownPosition = p.currentPosition
            lastAdvanceTime = System.currentTimeMillis()
            return
        }

        val now = System.currentTimeMillis()
        val pos = p.currentPosition
        if (pos > lastKnownPosition + 100) {
            lastKnownPosition = pos
            lastAdvanceTime = now
        } else if (now - lastAdvanceTime > STALL_TIMEOUT_MS) {
            Log.w(TAG, "Playback stalled (position frozen at ${pos}ms) - auto reloading")
            lastAdvanceTime = now
            fullRestartPlayback()
        }
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
            val bufferedAhead = bufferedPosition - currentPosition
            // Only step toward live when latency is truly excessive, and keep a
            // healthy buffer so we don't immediately re-buffer. During normal
            // operation this never fires, so the buffer is left to do its job.
            if (bufferedAhead > LIVE_RESYNC_THRESHOLD_MS) {
                Log.w(TAG, "Latency ${bufferedAhead}ms too high, trimming toward live (keeping ${RESYNC_KEEP_BUFFER_MS}ms)")
                p.seekTo(bufferedPosition - RESYNC_KEEP_BUFFER_MS)
            }
        }
        if (kotlin.math.abs(p.playbackParameters.speed - 1.0f) > 0.01f) {
            p.setPlaybackSpeed(1.0f)
        }
    }

    // ---------------- Status UI (suppressed during playback) ----------------

    // ---------------- Debug overlay ----------------

    private fun toggleDebugOverlay() {
        debugVisible = !debugVisible
        if (debugVisible) {
            debugText.visibility = View.VISIBLE
            debugText.text = buildDebugText()
            handler.removeCallbacks(debugRunnable)
            handler.postDelayed(debugRunnable, 1000L)
        } else {
            debugText.visibility = View.GONE
            handler.removeCallbacks(debugRunnable)
        }
    }

    private fun buildDebugText(): String {
        val p = player
        val sb = StringBuilder()
        sb.append("VeltrixTV v5.4  (D-pad Down to hide)\n")

        if (p == null) {
            sb.append("player: null\n")
        } else {
            val stateStr = when (p.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?"
            }
            val pos = p.currentPosition
            val buffered = p.bufferedPosition
            val ahead = buffered - pos
            val minBuf = BASE_MIN_BUFFER_MS * bufferMultiplier
            val maxBuf = BASE_MAX_BUFFER_MS * bufferMultiplier

            sb.append("state: $stateStr  playing: ${p.isPlaying}  pWR: ${p.playWhenReady}\n")
            sb.append("pos: ${pos}ms  buffered: ${buffered}ms\n")
            sb.append("playback buffer (ahead of live-lag): ${ahead}ms\n")
            sb.append("buffer target: ${minBuf}-${maxBuf}ms  x$bufferMultiplier\n")
            sb.append("speed: ${p.playbackParameters.speed}\n")

            val af = p.audioFormat
            sb.append("audio in: ${af?.sampleMimeType ?: "-"} ")
            sb.append("${af?.sampleRate ?: 0}Hz ${af?.channelCount ?: 0}ch\n")
            sb.append("audio decoder: $audioDecoderName\n")

            val vf = p.videoFormat
            sb.append("video: ${vf?.width ?: 0}x${vf?.height ?: 0} ")
            sb.append("${vf?.sampleMimeType ?: "-"} ${vf?.frameRate ?: 0f}fps\n")
            sb.append("video decoder: $videoDecoderName\n")
            sb.append("dropped frames: $droppedFrames  audio underruns: $audioUnderruns\n")

            val err = p.playerError
            if (err != null) sb.append("player error: ${err.errorCodeName}\n")
        }

        sb.append("events: buffering=$bufferingEvents reload=$restartEvents  last=$lastEvent\n")
        sb.append("reconnect attempts: $retryAttempts\n")

        val srt = SrtDataSource.latestStats
        if (srt != null) {
            sb.append("--- SRT ---\n")
            sb.append("RTT: ${"%.1f".format(srt.rttMs)}ms  ")
            sb.append("bw: ${"%.2f".format(srt.mbpsBandwidth)}Mbps  ")
            sb.append("recv: ${"%.2f".format(srt.mbpsRecvRate)}Mbps\n")
            sb.append("pkt loss: ${srt.pktRcvLoss}  retrans: ${srt.pktRcvRetrans}  ")
            sb.append("drop: ${srt.pktRcvDrop}\n")
            sb.append("pkts recv total: ${srt.pktRecvTotal}\n")
        } else {
            sb.append("--- SRT --- (no data yet)\n")
        }
        return sb.toString()
    }

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
