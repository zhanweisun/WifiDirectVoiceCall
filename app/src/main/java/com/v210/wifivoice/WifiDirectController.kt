package com.v210.wifivoice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.annotation.RequiresPermission


/**
 * Thin wrapper over the Android Wi-Fi Direct (P2P) framework. Handles device
 * discovery, peer connection, group formation and the associated broadcasts
 * without any third party library.
 */
class WifiDirectController(private val context: Context) : BroadcastReceiver() {

    interface Listener {
        fun onPeersChanged(peers: List<WifiP2pDevice>)
        fun onConnectionInfo(info: WifiP2pInfo?)
        fun onConnectionState(state: String)
        fun onConnectFailed(message: String)
        fun onWifiStateChanged(enabled: Boolean)
        fun onError(message: String)
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, Looper.getMainLooper(), null)
    private var listener: Listener? = null

    fun setListener(l: Listener) {
        listener = l
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(this, filter)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(this) }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                listener?.onWifiStateChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                requestPeers()
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                requestConnectionInfo()
            }
        }
    }
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                listener?.onConnectionState("正在发现周围设备…")
            }

            override fun onFailure(reason: Int) {
                val hint = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "本机不支持 WiFi Direct"
                    WifiP2pManager.BUSY -> "系统忙，请稍后重试"
                    else -> "请检查：手机位置信息已开启、已授权「附近的设备」权限、WiFi 已开启"
                }
                listener?.onConnectionState("发现失败 (code $reason)：$hint")
            }
        })
    }


    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private fun requestPeers() {
        manager.requestPeers(channel) { peers -> listener?.onPeersChanged(peers.deviceList.toList()) }
    }
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun connect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        listener?.onConnectionState("正在连接到 ${device.deviceName} …")
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                listener?.onConnectionState("连接请求已发出，等待对方确认…")
            }

            override fun onFailure(reason: Int) {
                listener?.onConnectFailed("连接失败 (code $reason)")
            }
        })
    }

    private fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel) { info -> listener?.onConnectionInfo(info) }
    }

    fun removeGroup() {
        manager.removeGroup(channel, null)
    }
}
