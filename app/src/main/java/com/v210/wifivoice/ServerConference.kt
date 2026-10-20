package com.v210.wifivoice

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Group-owner side of the call. Listens on [AudioConfig.SERVER_PORT], accepts
 * edges from any number of client phones, mixes all audio, and returns to each
 * participant a stream containing everyone except themselves. Also produces the
 * local playback mix for the group owner's own speaker / headset.
 */
class ServerConference(private val stream: VoiceStream) {

    private companion object {
        const val SOURCE_SELF = "local"
    }

    private val mixer = AudioMixer()
    private val clients = CopyOnWriteArrayList<ClientChannel>()
    private val running = AtomicBoolean(false)
    private var txCount = 0
    private var rxCount = 0

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var senderThread: Thread? = null
    private var clientCounter = 0

    fun start(onConnected: (Int) -> Unit, onError: (String) -> Unit, onDisconnected: () -> Unit) {
        if (!running.compareAndSet(false, true)) return

        stream.start { packet ->
            // The group owner's own microphone feeds the mixer as the "local" source.
            mixer.update(SOURCE_SELF, packet)
        }

        acceptThread = Thread {
            try {
                val ss = ServerSocket(AudioConfig.SERVER_PORT)
                serverSocket = ss
                while (running.get()) {
                    val socket = ss.accept()
                    val channel = ClientChannel(socket, "phone-${++clientCounter}")
                    clients.add(channel)
                    onConnected(clients.size + 1)
                    startReceiver(channel, onError, onDisconnected)
                }
            } catch (e: Exception) {
                if (running.get()) onError("Server socket: ${e.message}")
            }
        }.apply {
            name = "p2p-server"
            isDaemon = true
            start()
        }

        senderThread = Thread { senderLoop(onError) }.apply {
            name = "p2p-mixer"
            isDaemon = true
            start()
        }
    }

    private fun senderLoop(onError: (String) -> Unit) {
        while (running.get()) {
            val frameStart = System.nanoTime()

            // Play back what the other phones say.
            val localMix = mixer.mix(SOURCE_SELF)
            stream.play(localMix)

            // Send each phone the mix of everyone else.
            for (channel in clients) {
                val frame = mixer.mix(channel.id)
                try {
                    channel.send(frame)
                    if (++txCount % 50 == 0) {
                        Log.d("P2P", "GO sent to ${channel.id}: total=$txCount")
                    }
                } catch (e: IOException) {
                    clients.remove(channel)
                    channel.close()
                }
            }

            val elapsedNanos = System.nanoTime() - frameStart
            val sleepMs = (AudioConfig.PACKET_MS - elapsedNanos / 1_000_000.0).toLong()
            if (sleepMs > 0) {
                sleepQuietly(sleepMs)
            }
        }
    }

    private fun startReceiver(
        channel: ClientChannel,
        onError: (String) -> Unit,
        onDisconnected: () -> Unit
    ) {
        Thread {
            try {
                while (running.get() && channel.isOpen) {
                    val frame = channel.readFrame() ?: break
                    mixer.update(channel.id, frame)
                    if (++rxCount % 50 == 0) {
                        Log.d("P2P", "GO received ${rxCount} frames from ${channel.id}")
                    }
                }
            } catch (e: java.net.SocketException) {
                // Socket closed by hangUp()/app exit — normal termination.
            } catch (e: Exception) {
                if (running.get()) onError("连接中断: ${e.message}")
            } finally {
                clients.remove(channel)
                mixer.remove(channel.id)
                channel.close()
                // No peers left (peer hung up / network lost) -> end the call.
                if (clients.isEmpty()) onDisconnected()
            }
        }.apply {
            name = "p2p-${channel.id}-rx"
            isDaemon = true
            start()
        }
    }

    fun count(): Int = clients.size + 1

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun sleepQuietly(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private class ClientChannel(private val socket: Socket, val id: String) {
        private val output: OutputStream = socket.getOutputStream()
        private val input: InputStream = socket.getInputStream()
        @Volatile var isOpen: Boolean = true
            private set

        init {
            // Disable Nagle so 20ms audio packets go out immediately instead of
            // being coalesced by TCP (the usual cause of one-way / choppy audio
            // over Wi-Fi Direct).
            runCatching { socket.tcpNoDelay = true }
            runCatching { socket.keepAlive = true }
        }

        @Synchronized
        fun send(frame: ByteArray) {
            if (isOpen) {
                output.write(frame)
                output.flush()
            }
        }

        fun readFrame(): ByteArray? {
            if (!isOpen) return null
            val buf = ByteArray(AudioConfig.PACKET_BYTES)
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return null
                off += n
            }
            return buf
        }

        fun close() {
            isOpen = false
            runCatching { socket.close() }
        }
    }
}
