package com.v210.wifivoice

import android.media.AudioFormat

object AudioConfig {
    const val SAMPLE_RATE = 16000
    const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** 20ms audio packet size in bytes (16kHz * 16bit * mono). */
    const val PACKET_MS = 20
    const val PACKET_BYTES = SAMPLE_RATE * 2 * PACKET_MS / 1000

    /** TCP port used on the Wi-Fi Direct group owner. */
    const val SERVER_PORT = 5000
}
