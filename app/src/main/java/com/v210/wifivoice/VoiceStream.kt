package com.v210.wifivoice

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-duplex audio engine. Captures microphone audio (16kHz PCM, 20ms packets)
 * and reproduces received audio simultaneously using Android's built-in
 * AudioRecord / AudioTrack. No third party codec / library is used.
 */
class VoiceStream {

    private val running = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var onPacket: ((ByteArray) -> Unit)? = null

    private var captureThread: Thread? = null

    /** Buffer sufficient for a whole voice frame. */
    private var playbackBufferSize = 0

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onPacket: (ByteArray) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        this.onPacket = onPacket

        val minRec = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_IN,
            AudioConfig.ENCODING
        )
        val minPlay = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_OUT,
            AudioConfig.ENCODING
        )
        playbackBufferSize = minOf(minPlay, minPlay * 2).coerceAtLeast(AudioConfig.PACKET_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNEL_IN,
                AudioConfig.ENCODING,
                minRec
            )
        } catch (e: Exception) {
            // Fall back to MIC source if VOICE_COMMUNICATION fails.
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNEL_IN,
                AudioConfig.ENCODING,
                minRec
            )
        }

        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioConfig.ENCODING)
                .setSampleRate(AudioConfig.SAMPLE_RATE)
                .setChannelMask(AudioConfig.CHANNEL_OUT)
                .build(),
            playbackBufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        audioRecord?.startRecording()
        audioTrack?.play()

        captureThread = Thread { captureLoop() }.apply {
            name = "mic-capture"
            start()
        }
    }

    private fun captureLoop() {
        val record = audioRecord ?: return
        val consumer = onPacket ?: return

        // Accumulator so partial AudioRecord reads never split a frame.
        val packet = ByteArray(AudioConfig.PACKET_BYTES)
        val temp = ByteArray(AudioConfig.PACKET_BYTES)
        var filled = 0

        while (running.get()) {
            val n = record.read(temp, 0, AudioConfig.PACKET_BYTES)
            if (n <= 0) continue

            var offset = 0
            while (offset < n) {
                val chunk = minOf(n - offset, AudioConfig.PACKET_BYTES - filled)
                System.arraycopy(temp, offset, packet, filled, chunk)
                filled += chunk
                offset += chunk
                if (filled == AudioConfig.PACKET_BYTES) {
                    consumer(packet.copyOf())
                    filled = 0
                }
            }
        }
    }

    /** Called from the network receive thread. Thread-safe for AudioTrack.write. */
    @Synchronized
    fun play(frame: ByteArray) {
        val track = audioTrack ?: return
        if (running.get() && frame.size == AudioConfig.PACKET_BYTES) {
            track.write(frame, 0, frame.size)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        captureThread?.let { runCatching { it.join(300) } }
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioRecord = null
        audioTrack = null
    }
}
