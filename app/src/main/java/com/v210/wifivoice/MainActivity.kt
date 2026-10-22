package com.v210.wifivoice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.children

class MainActivity : AppCompatActivity(), WifiDirectController.Listener {

    private lateinit var txtStatus: TextView
    private lateinit var listPeers: ListView
    private lateinit var txtPeers: TextView
    private lateinit var txtSelected: TextView
    private lateinit var btnDiscover: Button
    private lateinit var btnConnect: Button
    private lateinit var btnCall: Button
    private lateinit var btnHangup: Button

    private lateinit var controller: WifiDirectController
    private lateinit var bluetooth: BluetoothAudioManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectTimeoutMs = 20_000L
    private var connectDeadline = 0L
    private var connecting = false

    /** Recurring ticker that shows a live countdown while waiting for connect. */
    private val connectTicker = object : Runnable {
        override fun run() {
            if (!connecting) return
            val remainMs = connectDeadline - SystemClock.elapsedRealtime()
            val remain = (remainMs + 999) / 1000
            val name = selectedDevice?.deviceName ?: "对方设备"
            if (remain <= 0) {
                resetToIdle("连接超时：$name 未确认。请让对方 App 也保持「发现」并点击连接后再试")
            } else {
                setStatus("正在连接 $name …（剩余 ${remain} 秒）")
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val peers = mutableListOf<WifiP2pDevice>()
    private var peerAdapter: ArrayAdapter<String>? = null
    private var selectedDevice: WifiP2pDevice? = null

    private lateinit var stream: VoiceStream
    private var server: ServerConference? = null
    private var client: ClientSession? = null
    private var inCall = false

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            // Android 13+ (API 33) uses NEARBY_WIFI_DEVICES for Wi-Fi Direct discovery.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()

    private val permissionRequestCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        listPeers = findViewById(R.id.listPeers)
        txtPeers = findViewById(R.id.txtPeers)
        txtSelected = findViewById(R.id.txtSelected)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnConnect = findViewById(R.id.btnConnect)
        btnCall = findViewById(R.id.btnCall)
        btnHangup = findViewById(R.id.btnHangup)

        controller = WifiDirectController(this)
        controller.setListener(this)
        bluetooth = BluetoothAudioManager(this)
        stream = VoiceStream()

        setStatus("请授权权限，然后点击「发现设备」")
        setupUi()
        requestPermissions()
    }

    @SuppressLint("MissingPermission")
    private fun setupUi() {
        peerAdapter = ArrayAdapter(this, R.layout.item_peer, mutableListOf())
        listPeers.adapter = peerAdapter

        listPeers.setOnItemClickListener { _, _, position, _ ->
            selectedDevice = peers.getOrNull(position)
            btnConnect.isEnabled = selectedDevice != null
            selectedDevice?.let {
                txtSelected.text = "已选中：${it.deviceName}  (${it.deviceAddress})"
            }
        }

        btnDiscover.setOnClickListener {
            if (!hasPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }
            controller.discoverPeers()
        }

        btnConnect.setOnClickListener {
            selectedDevice?.let { controller.connect(it) }
            btnConnect.isEnabled = false
            connecting = true
            scheduleConnectTimeout()
        }

        btnCall.setOnClickListener {
            startVoice()
        }

        btnHangup.setOnClickListener {
            hangUp()
        }

        btnConnect.isEnabled = false
    }

    private fun requestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequestCode)
        }
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun scheduleConnectTimeout() {
        connecting = true
        connectDeadline = SystemClock.elapsedRealtime() + connectTimeoutMs
        mainHandler.removeCallbacks(connectTicker)
        mainHandler.post(connectTicker)
    }

    private fun clearConnectTimeout() {
        mainHandler.removeCallbacks(connectTicker)
        connecting = false
    }

    private fun resetToIdle(message: String) {
        clearConnectTimeout()
        runCatching { controller.removeGroup() }
        selectedDevice = null
        txtSelected.text = "未选中设备"
        btnConnect.isEnabled = false
        btnDiscover.isEnabled = true
        setStatus(message)
    }

    override fun onResume() {
        super.onResume()
        controller.register()
    }

    override fun onPause() {
        super.onPause()
        controller.unregister()
    }

    override fun onDestroy() {
        hangUp()
        super.onDestroy()
    }

    // ---- WifiDirectController.Listener ----

    override fun onWifiStateChanged(enabled: Boolean) {
        if (!enabled) setStatus("WiFi Direct 未开启，请到系统设置打开")
    }

    override fun onPeersChanged(peers: List<WifiP2pDevice>) {
        this.peers.clear()
        this.peers.addAll(peers)
        peerAdapter?.clear()
        this.peers.forEach { peerAdapter?.add("${it.deviceName}  (${it.deviceAddress})") }
        peerAdapter?.notifyDataSetChanged()
        if (this.peers.isEmpty()) {
            setStatus("没有发现设备，请确保两台手机的 WiFi Direct 都开启了「发现」")
        } else {
            txtPeers.text = "已发现 ${this.peers.size} 台设备"
        }
    }

    override fun onConnectionState(state: String) {
        setStatus(state)
    }

    override fun onConnectFailed(message: String) {
        resetToIdle(message)
    }

    override fun onConnectionInfo(info: WifiP2pInfo?) {
        if (info == null) return
        // The CONNECTION_CHANGED broadcast fires when the group is torn down
        // (peer hung up / Wi-Fi dropped) — end the call on this side too.
        if (!info.groupFormed) {
            onPeerDisconnected("对方已挂断，通话结束")
            return
        }
        // The CONNECTION_CHANGED broadcast can arrive before the group is fully
        // formed (groupOwnerAddress is null then). Wait for the next broadcast.
        clearConnectTimeout()
        txtSelected.text = "已连接：${selectedDevice?.deviceName ?: "对方设备"}"
        bluetooth.start()
        txtPeers.text = if (info.isGroupOwner) "角色：主机 (Group Owner)" else "角色：成员 (Client)"
        if (info.isGroupOwner) {
            setStatus("小组已建立，你是主机，等待其他手机加入…")
            startServer()
            btnCall.isEnabled = true
        } else {
            val host = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
            setStatus("小组已建立，正在连接到主机 $host…")
            startClient(host)
            btnCall.isEnabled = true
        }
    }

    override fun onError(message: String) {
        setStatus(message)
    }

    // ---- Call start / stop ----

    private fun startVoice() {
        if (inCall) return
        inCall = true
        btnDiscover.isEnabled = false
        btnConnect.isEnabled = false
        btnCall.visibility = Button.GONE
        btnHangup.visibility = Button.VISIBLE
    }

    private fun startServer() {
        server?.stop()
        server = ServerConference(stream)
        server?.start(
            onConnected = { n -> runOnUiThread { setStatus("已有 $n 台设备在线，语音通话进行中") } },
            onError = { msg -> runOnUiThread { setStatus("主机异常: $msg") } },
            onDisconnected = { onPeerDisconnected("对方已挂断，通话结束") }
        )
        startVoice()
    }

    private fun startClient(host: String) {
        client = ClientSession(stream, host, AudioConfig.SERVER_PORT)
        client?.start(
            onConnected = { runOnUiThread { setStatus("已连接主机，语音通话进行中") } },
            onError = { msg ->
                runOnUiThread {
                    if (inCall) endCall("连接失败：$msg", true) else setStatus(msg)
                }
            },
            onDisconnected = { onPeerDisconnected("对方已挂断，通话结束") }
        )
        startVoice()
    }

    private fun hangUp() {
        endCall("已挂断", true)
    }

    /** Can be called from any thread; ends the call if one is active. */
    private fun onPeerDisconnected(message: String) {
        runOnUiThread {
            if (inCall || server != null || client != null) {
                endCall(message, removeGroup = true)
            }
        }
    }

    private fun endCall(message: String, removeGroup: Boolean) {
        if (!inCall && server == null && client == null) return
        clearConnectTimeout()
        client?.stop()
        server?.stop()
        client = null
        server = null
        stream.stop()
        bluetooth.stop()
        inCall = false
        selectedDevice = null
        txtSelected.text = "未选中设备"
        btnDiscover.isEnabled = true
        btnConnect.isEnabled = false
        btnHangup.visibility = Button.GONE
        btnCall.visibility = Button.VISIBLE
        if (removeGroup) controller.removeGroup()
        setStatus(message)
    }

    private fun setStatus(text: String) {
        runOnUiThread { txtStatus.text = text }
    }
}
