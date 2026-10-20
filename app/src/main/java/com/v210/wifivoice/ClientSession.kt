package com.v210.wifivoice

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client (non group-owner) side of the call. Connects to the group owner over
 * TCP, streams its microphone audio up, and plays back the mixed stream the
 * group owner sends back. Capture and playback run on separate threads so the
 * link works full duplex.
 */
class ClientSession(private val stream: VoiceStream, private val host: String, private val port: Int) {

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var receiveThread: Thread? = null
    private var txCount = 0
    private var rxCount = 0

    fun start(onConnected: () -> Unit, onError: (String) -> Unit, onDisconnected: () -> Unit) {
        if (!running.compareAndSet(false, true)) return

        Thread {
            var sock: Socket? = null
            try {
                sock = connectWithRetry(host, port)
                socket = sock
                // Disable Nagle for low-latency full-duplex audio.
                runCatching { sock.tcpNoDelay = true }
                runCatching { sock.keepAlive = true }
                onConnected()

                val output = sock.getOutputStream().buffered()
                val input = sock.getInputStream().buffered()

                // Send our own microphone while we simultaneously receive/play.
                stream.start { frame ->
                    try {
                        if (running.get()) {
                            output.write(frame)
                            output.flush()
                            if (++txCount % 50 == 0) {
                                Log.d("P2P", "Client sent $txCount frames")
                            }
                        }
                    } catch (_: java.io.IOException) {
                        // Socket was closed during hangUp — ignore.
                    }
                }

                receiveThread = Thread {
                    val buf = ByteArray(AudioConfig.PACKET_BYTES)
                    var off = 0
                    while (running.get()) {
                        try {
                            val n = input.read(buf, off, buf.size - off)
                            if (n < 0) break
                            off += n
                            if (off == buf.size) {
                                stream.play(buf)
                                if (++rxCount % 50 == 0) {
                                    Log.d("P2P", "Client received $rxCount frames")
                                }
                                off = 0
                            }
                        } catch (e: Exception) {
                            break
                        }
                    }
                    onDisconnected()
                }.apply {
                    name = "p2p-client-rx"
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                if (running.get()) onError("Connect: ${e.message}")
                stop()
            }
        }.apply {
            name = "p2p-client"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
    }

    /**
     * The group owner may not have bound its ServerSocket the moment the client
     * connects, so retry a few times instead of giving up with "connect refused".
     */
    private fun connectWithRetry(host: String, port: Int): Socket {
        var last: Exception? = null
        for (attempt in 0 until 6) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 3000)
                return s
            } catch (e: Exception) {
                last = e
                if (attempt >= 5) break
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        throw last ?: IOException("connect failed: $host:$port")
    }
}
