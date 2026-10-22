package com.v210.wifivoice

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Routes the microphone / speaker correctly for a full-duplex call.
 *
 * The bug fixed here: with `MODE_IN_COMMUNICATION` + `isSpeakerphoneOn=false` +
 * `isBluetoothScoOn=true`, when NO Bluetooth headset is actually connected (or SCO
 * is still being established) Android sends playback to the tiny earpiece, which is
 * near-silent. That is why a phone could record its mic fine (the other side hears it)
 * but sound nothing locally on Android 11.
 *
 * We now detect whether a BT headset is really connected and route accordingly:
 *  - headset connected  -> enable Bluetooth SCO (mic + speaker on the headset)
 *  - no headset         -> turn the loudspeaker ON so playback is clearly audible
 * We also request audio focus so the call audio is not muted by other apps.
 */
class BluetoothAudioManager(context: Context) {

    private val context = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    fun start() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val useBluetooth = isBluetoothHeadsetConnected()
        audioManager.isBluetoothScoOn = useBluetooth
        audioManager.isSpeakerphoneOn = !useBluetooth
        if (useBluetooth) {
            audioManager.startBluetoothSco()
        } else {
            audioManager.stopBluetoothSco()
        }

        requestAudioFocus()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun stop() {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        abandonAudioFocus()
    }

    @SuppressLint("ServiceCast", "MissingPermission", "WrongConstant")
    private fun isBluetoothHeadsetConnected(): Boolean = try {
        val bt = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothAdapter
        val headset = bt.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        val a2dp = bt.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        headset || a2dp
    } catch (_: Exception) {
        false
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun abandonAudioFocus() {
        val request = focusRequest
        if (request != null) {
            audioManager.abandonAudioFocusRequest(request)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
