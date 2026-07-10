package com.veltrix.tv

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import java.io.IOException

/**
 * Custom ExoPlayer DataSource that reads an MPEG-TS stream from an SRT socket.
 *
 * This is a faithful re-implementation of the SRT reader used by the original
 * stable StreamPlayer build: a plain blocking recv() that throws IOException on
 * error and returns -1 (EOF) on an empty read. No force-close, no background
 * threads. Recovery on stream loss is handled by SRT's peer-idle timeout, which
 * makes recv() fail so ExoPlayer reports the error and the activity reconnects.
 */
class SrtDataSource : BaseDataSource(true) {

    companion object {
        private const val TAG = "SrtDataSource"
        private const val SRT_PAYLOAD_SIZE = 1316
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val RCVBUF_SIZE = 8388608
        // If the SRT peer goes silent for this long the connection is declared
        // dead and recv() fails - this is what lets the reader thread unblock
        // and the player release/reconnect cleanly on a real outage.
        private const val PEER_IDLE_TIMEOUT_MS = 5000

        // Sample SRT stats roughly twice a second (~380 reads/s at typical
        // bitrates) from the reader thread, so the UI can read a snapshot
        // without ever touching the socket concurrently with recv().
        private const val STATS_SAMPLE_EVERY_READS = 180

        /** Latest SRT statistics snapshot, updated on the reader thread. */
        @Volatile
        var latestStats: SrtStatsSnapshot? = null
            private set
    }

    /** Immutable snapshot of the SRT socket's live statistics. */
    data class SrtStatsSnapshot(
        val rttMs: Double,
        val mbpsBandwidth: Double,
        val mbpsRecvRate: Double,
        val pktRcvLoss: Int,
        val pktRcvRetrans: Int,
        val pktRcvDrop: Int,
        val pktRecvTotal: Long
    )

    private var socket: SrtSocket? = null
    private var currentUri: Uri? = null
    private var pendingData: ByteArray? = null
    private var pendingOffset: Int = 0
    private var readCount: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        currentUri = uri

        val host = uri.host ?: throw IOException("SRT URL has no host: $uri")
        val port = if (uri.port > 0) uri.port else throw IOException("SRT URL has no port: $uri")

        Log.i(TAG, "Opening SRT connection to $host:$port")

        try {
            val sock = SrtSocket()
            sock.setSockFlag(SockOpt.CONNTIMEO, CONNECT_TIMEOUT_MS)
            sock.setSockFlag(SockOpt.RCVBUF, RCVBUF_SIZE)
            sock.setSockFlag(SockOpt.PAYLOADSIZE, SRT_PAYLOAD_SIZE)
            sock.setSockFlag(SockOpt.PEERIDLETIMEO, PEER_IDLE_TIMEOUT_MS)

            applyUrlParams(sock, uri)

            sock.connect(host, port)
            socket = sock
        } catch (e: Exception) {
            Log.e(TAG, "SRT connect failed: ${e.message}", e)
            closeQuietly()
            throw IOException("SRT connect failed: ${e.message}", e)
        }

        transferStarted(dataSpec)
        // Live stream of unknown length.
        return C.LENGTH_UNSET.toLong()
    }

    private fun applyUrlParams(sock: SrtSocket, uri: Uri) {
        try {
            for (name in uri.queryParameterNames) {
                val value = uri.getQueryParameter(name) ?: continue
                when (name.lowercase()) {
                    "passphrase" -> sock.setSockFlag(SockOpt.PASSPHRASE, value)
                    "pbkeylen" -> value.toIntOrNull()?.let { sock.setSockFlag(SockOpt.PBKEYLEN, it) }
                    "streamid" -> sock.setSockFlag(SockOpt.STREAMID, value)
                    "latency" -> value.toIntOrNull()?.let { sock.setSockFlag(SockOpt.LATENCY, it) }
                    "rcvlatency" -> value.toIntOrNull()?.let { sock.setSockFlag(SockOpt.RCVLATENCY, it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error applying SRT URL params: ${e.message}")
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        val sock = socket ?: throw IOException("SRT socket is not connected")

        try {
            // Drain any leftover from the previous recv first.
            val pending = pendingData
            if (pending != null && pendingOffset < pending.size) {
                val remaining = pending.size - pendingOffset
                val toCopy = minOf(remaining, length)
                System.arraycopy(pending, pendingOffset, buffer, offset, toCopy)
                pendingOffset += toCopy
                if (pendingOffset >= pending.size) {
                    pendingData = null
                    pendingOffset = 0
                }
                bytesTransferred(toCopy)
                return toCopy
            }

            val received = sock.recv(SRT_PAYLOAD_SIZE)
            if (received.isEmpty()) {
                Log.w(TAG, "SRT recv returned empty - stream may have ended")
                return C.RESULT_END_OF_INPUT
            }

            // Sample SRT stats periodically on this (reader) thread only.
            if (++readCount >= STATS_SAMPLE_EVERY_READS) {
                readCount = 0
                sampleStats(sock)
            }

            val toCopy = minOf(received.size, length)
            System.arraycopy(received, 0, buffer, offset, toCopy)
            if (toCopy < received.size) {
                pendingData = received
                pendingOffset = toCopy
            }
            bytesTransferred(toCopy)
            return toCopy
        } catch (e: Exception) {
            Log.e(TAG, "SRT read error: ${e.message}", e)
            throw IOException("SRT read failed: ${e.message}", e)
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        Log.i(TAG, "Closing SRT connection")
        pendingData = null
        pendingOffset = 0
        closeQuietly()
        currentUri = null
        transferEnded()
    }

    private fun sampleStats(sock: SrtSocket) {
        try {
            val s = sock.bistats(false, true)
            latestStats = SrtStatsSnapshot(
                rttMs = s.msRTT,
                mbpsBandwidth = s.mbpsBandwidth,
                mbpsRecvRate = s.mbpsRecvRate,
                pktRcvLoss = s.pktRcvLoss,
                pktRcvRetrans = s.pktRcvRetrans,
                pktRcvDrop = s.pktRcvDrop,
                pktRecvTotal = s.pktRecvTotal
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error sampling SRT stats: ${e.message}")
        }
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing SRT socket: ${e.message}")
        }
        socket = null
        latestStats = null
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SrtDataSource()
    }
}
