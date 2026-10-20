package com.v210.wifivoice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Mixes the most recent PCM frame from every source. The group owner uses this
 * to build, for each recipient, a stream containing everyone except themselves,
 * which gives natural full-duplex conferencing without an echo of your own voice.
 */
class AudioMixer {
    private val frames = ConcurrentHashMap<String, ByteArray>()

    fun update(source: String, frame: ByteArray) {
        if (frame.size == AudioConfig.PACKET_BYTES) {
            frames[source] = frame.copyOf()
        }
    }

    fun remove(source: String) {
        frames.remove(source)
    }

    fun hasAny(): Boolean = frames.isNotEmpty()

    /**
     * Returns a frame that is the sum of every source except [exclude].
     * If no source is present, returns a frame of silence.
     */
    fun mix(exclude: String): ByteArray {
        val out = ShortArray(AudioConfig.PACKET_BYTES / 2)
        for ((id, frame) in frames) {
            if (id == exclude) continue
            val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in out.indices) {
                val mixed = out[i] + buf.get(i)
                val clamped = if (mixed > 32767) 32767 else if (mixed < -32768) -32768 else mixed
                out[i] = clamped.toShort()
            }
        }
        val result = ByteBuffer.allocate(AudioConfig.PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (s in out) result.putShort(s)
        return result.array()
    }
}
